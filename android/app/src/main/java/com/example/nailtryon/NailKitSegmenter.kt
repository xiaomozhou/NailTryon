package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.*

/** Promptable object segmentation for detached press-on nails, not the hand-nail detector. */
internal class NailKitSegmenter(encoderBytes:ByteArray,decoderBytes:ByteArray,private val source:Bitmap):Closeable {
    private val env=OrtEnvironment.getEnvironment()
    private fun open(bytes:ByteArray)=OrtSession.SessionOptions().use{it.setIntraOpNumThreads(2);env.createSession(bytes,it)}
    private val encoder=open(encoderBytes)
    private val decoder=try{open(decoderBytes)}catch(error:Exception){encoder.close();throw error}
    private val scale=1024f/max(source.width,source.height)
    private val sw=(source.width*scale).roundToInt()
    private val sh=(source.height*scale).roundToInt()
    private val encoded=try {
        val image=Bitmap.createScaledBitmap(source,sw,sh,true)
        val pixels=IntArray(sw*sh);image.getPixels(pixels,0,sw,0,0,sw,sh)
        if(image!==source)image.recycle()
        val values=FloatArray(pixels.size*3)
        pixels.forEachIndexed{i,p->values[i*3]=Color.red(p).toFloat();values[i*3+1]=Color.green(p).toFloat();values[i*3+2]=Color.blue(p).toFloat()}
        OnnxTensor.createTensor(env,FloatBuffer.wrap(values),longArrayOf(sh.toLong(),sw.toLong(),3)).use { input ->
            encoder.run(mapOf("input_image" to input))
        }
    }catch(error:Exception){decoder.close();encoder.close();throw error}
    @Synchronized fun segment(point:NailPoint,box:NailBox?,negatives:List<NailPoint> = emptyList()):NailRegion? {
        require(point.x in 0f..(source.width-1).toFloat() && point.y in 0f..(source.height-1).toFloat())
        val points=mutableListOf(point);val labels=mutableListOf(1f)
        if(box!=null){points.add(NailPoint(box.left.toFloat(),box.top.toFloat()));points.add(NailPoint(box.right.toFloat(),box.bottom.toFloat()));labels.addAll(listOf(2f,3f))}
        negatives.forEach{points.add(it);labels.add(0f)}
        if(box==null && negatives.isEmpty()){points.add(NailPoint(0f,0f));labels.add(-1f)}
        val coordinates=points.flatMap{listOf(it.x*sw/source.width,it.y*sh/source.height)}.toFloatArray()
        val inputs=linkedMapOf<String,OnnxTensor>()
        fun tensor(name:String,data:FloatArray,vararg dims:Long){inputs[name]=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),dims)}
        try {
            tensor("point_coords",coordinates,1,points.size.toLong(),2)
            tensor("point_labels",labels.toFloatArray(),1,points.size.toLong())
            tensor("mask_input",FloatArray(256*256),1,1,256,256)
            tensor("has_mask_input",floatArrayOf(0f),1)
            tensor("orig_im_size",floatArrayOf(source.height.toFloat(),source.width.toFloat()),2)
            val all=inputs.toMutableMap();all["image_embeddings"]=encoded[0] as OnnxTensor
            return decoder.run(all).use { result ->
                val logits=(result[0] as OnnxTensor).floatBuffer
                val score=(result[1] as OnnxTensor).floatBuffer.get(0)
                if(!score.isFinite() || score<.7f)return@use null
                var left=source.width;var top=source.height;var right=0;var bottom=0;var area=0
                for(y in 0 until source.height)for(x in 0 until source.width)if(logits.get(y*source.width+x)>0f){
                    left=min(left,x);top=min(top,y);right=max(right,x+1);bottom=max(bottom,y+1);area++
                }
                if(area<source.width.toLong()*source.height*.001 || area>source.width.toLong()*source.height*.10 || left>=right || top>=bottom)return@use null
                if(logits.get(point.y.toInt()*source.width+point.x.toInt())<=0f)return@use null
                val b=NailBox(left,top,right,bottom)
                val mask=ByteArray(b.width*b.height)
                for(y in top until bottom)for(x in left until right)if(logits.get(y*source.width+x)>0f)mask[(y-top)*b.width+x-left]=(-1).toByte()
                NailMaskGeometry.region(mask,b,score,source.width,source.height)
            }
        }finally{inputs.values.forEach{it.close()}}
    }
    override fun close(){try{encoded.close()}finally{try{decoder.close()}finally{encoder.close()}}}
}
