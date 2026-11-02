package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.*

internal data class HandJoint(val x:Float,val y:Float,val z:Float=0f)
internal data class HandObservation(val points:List<HandJoint>,val presence:Float,val mirroredLeftProbability:Float)

/**
 * MediaPipe-compatible palm/landmark inference on unmirrored, EXIF-corrected photos.
 * Anchor and rotated ROI configuration follows yakhyo/mediapipe-hand-landmark-onnx
 * (Copyright 2026 Yakhyokhuja Valikhujaev, Apache-2.0); see bundled license/NOTICE.
 */
internal class HandLandmarkDetector(palmModel:ByteArray,landmarkModel:ByteArray):Closeable {
    private val env=OrtEnvironment.getEnvironment()
    private fun session(bytes:ByteArray)=OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2);env.createSession(bytes,options)
    }
    private val palm=session(palmModel)
    private val landmarks=try { session(landmarkModel) } catch(error:Exception) { palm.close();throw error }
    private val anchors=buildList {
        listOf(24 to 2,12 to 6).forEach { (cells,repeats) ->
            for(y in 0 until cells) for(x in 0 until cells) repeat(repeats) { add((x+.5f)/cells to (y+.5f)/cells) }
        }
    }
    @Synchronized fun detect(photo:Bitmap):List<HandObservation> {
        val w=photo.width;val h=photo.height
        val pixels=IntArray(w*h);photo.getPixels(pixels,0,w,0,0,w,h)
        val gain=192f/max(w,h);val padX=(192-w*gain)/2;val padY=(192-h*gain)/2
        val palmInput=sample(pixels,w,h,192) { x,y -> (x-padX)/gain to (y-padY)/gain }
        val palms=run(palm,palmInput,192) { outputs ->
            @Suppress("UNCHECKED_CAST") val boxes=(outputs[0].value as Array<Array<FloatArray>>)[0]
            @Suppress("UNCHECKED_CAST") val scores=(outputs[1].value as Array<Array<FloatArray>>)[0]
            require(boxes.size==anchors.size && scores.size==anchors.size)
            val candidates=boxes.indices.mapNotNull { i ->
                val score=(1.0/(1.0+exp(-scores[i][0].toDouble()))).toFloat()
                val r=boxes[i];val a=anchors[i]
                if(!score.isFinite() || score<.6f || r.size<10 || r.any{!it.isFinite()} || r[2]<=0 || r[3]<=0) null
                else floatArrayOf(score,r[0]/192+a.first,r[1]/192+a.second,r[2]/192,r[3]/192,
                    r[4]/192+a.first,r[5]/192+a.second,r[8]/192+a.first,r[9]/192+a.second)
            }.sortedByDescending { it[0] }.toMutableList()
            val merged=mutableListOf<FloatArray>()
            while(candidates.isNotEmpty() && merged.size<6) {
                val seed=candidates.first();val overlap=candidates.filter { palmIou(seed,it)>.3f }
                val row=FloatArray(9);val total=overlap.sumOf{it[0].toDouble()}.toFloat()
                row[0]=seed[0]
                for(j in 1..8) row[j]=overlap.sumOf{(it[j]*it[0]).toDouble()}.toFloat()/total
                candidates.removeAll(overlap.toSet());merged.add(row)
            }
            merged
        }
        return palms.mapNotNull { p ->
            val width=max(p[3],p[4])*192/gain
            val wrist=HandJoint((p[5]*192-padX)/gain,(p[6]*192-padY)/gain)
            val mcp=HandJoint((p[7]*192-padX)/gain,(p[8]*192-padY)/gain)
            val dx=mcp.x-wrist.x;val dy=mcp.y-wrist.y;val norm=hypot(dx,dy)
            if(norm<1 || width<1) return@mapNotNull null
            val cx=(p[1]*192-padX)/gain+.5f*width*dx/norm
            val cy=(p[2]*192-padY)/gain+.5f*width*dy/norm
            val angle=atan2(dy,dx)+(PI/2).toFloat();val c=cos(angle);val s=sin(angle)
            val scale=width*2.6f/224
            fun original(x:Float,y:Float):Pair<Float,Float> {
                val u=(x-112)*scale;val v=(y-112)*scale
                return (cx+c*u-s*v) to (cy+s*u+c*v)
            }
            val input=sample(pixels,w,h,224,::original)
            run(landmarks,input,224) { outputs ->
                @Suppress("UNCHECKED_CAST") val screen=(outputs[0].value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST") val confidence=(outputs[1].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST") val handedness=(outputs[2].value as Array<FloatArray>)[0][0]
                if(screen.size!=63 || confidence<.7f || !confidence.isFinite() || !handedness.isFinite() || screen.any{!it.isFinite()}) null
                else HandObservation((0..20).map { j ->
                    val xy=original(screen[j*3],screen[j*3+1]);HandJoint(xy.first,xy.second,screen[j*3+2]*scale)
                },confidence,handedness)
            }
        }
    }
    private fun <T> run(session:OrtSession,input:FloatArray,size:Int,read:(OrtSession.Result)->T):T =
        OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,3,size.toLong(),size.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use(read)
        }
    private fun sample(pixels:IntArray,w:Int,h:Int,size:Int,map:(Float,Float)->Pair<Float,Float>):FloatArray {
        val output=FloatArray(size*size*3)
        fun channel(x:Int,y:Int,shift:Int):Float = if(x in 0 until w && y in 0 until h) ((pixels[y*w+x] ushr shift) and 255).toFloat() else 0f
        for(y in 0 until size) for(x in 0 until size) {
            val p=map(x.toFloat(),y.toFloat());val xx=floor(p.first).toInt();val yy=floor(p.second).toInt()
            val fx=p.first-xx;val fy=p.second-yy
            listOf(16,8,0).forEachIndexed { c,shift ->
                output[c*size*size+y*size+x]=((channel(xx,yy,shift)*(1-fx)+channel(xx+1,yy,shift)*fx)*(1-fy)+
                    (channel(xx,yy+1,shift)*(1-fx)+channel(xx+1,yy+1,shift)*fx)*fy)/255f
            }
        }
        return output
    }
    private fun palmIou(a:FloatArray,b:FloatArray):Float {
        val intersect=max(0f,min(a[1]+a[3]/2,b[1]+b[3]/2)-max(a[1]-a[3]/2,b[1]-b[3]/2))*
            max(0f,min(a[2]+a[4]/2,b[2]+b[4]/2)-max(a[2]-a[4]/2,b[2]-b[4]/2))
        return intersect/(a[3]*a[4]+b[3]*b[4]-intersect).coerceAtLeast(1e-6f)
    }
    override fun close() { try { landmarks.close() } finally { palm.close() } }
}
