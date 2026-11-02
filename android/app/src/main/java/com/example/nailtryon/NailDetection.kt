package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.*

internal data class NailPoint(val x: Float, val y: Float)
internal data class NailBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Coordinates refer to the EXIF-corrected input bitmap, never the camera preview or model padding. */
internal data class NailRegion(
    val confidence: Float,
    val box: NailBox,
    val mask: ByteArray,
    val contour: List<NailPoint>,
    val center: NailPoint,
    // Undirected major axis: not a claim about cuticle/tip or finger identity.
    val axisRadians: Float,
    val orientedCorners: List<NailPoint>,
    val touchesImageEdge: Boolean,
)

internal data class NailDetection(val width: Int, val height: Int, val regions: List<NailRegion>) {
    val readyForAlignment get() = regions.size == 5 && regions.all { it.confidence >= .6f && !it.touchesImageEdge }
}

/** On-device instance segmentation. No fixed-position/ellipse fallback, no polish compositing. */
internal class NailDetector(model: ByteArray) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        environment.createSession(model, options)
    }
    private val inputName = session.inputNames.single()
    private val shape = (session.inputInfo.getValue(inputName).info as TensorInfo).shape
    private val inputH = shape[2].toInt()
    private val inputW = shape[3].toInt()
    init { require(shape.contentEquals(longArrayOf(1, 3, inputH.toLong(), inputW.toLong())) && inputH > 0 && inputW > 0) }

    @Synchronized fun detect(photo: Bitmap): NailDetection {
        require(!photo.isRecycled && photo.width >= 32 && photo.height >= 32)
        val transform = NailLetterbox(photo.width, photo.height, inputW, inputH)
        val input = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(inputW * inputH)
        try {
            val canvas = Canvas(input)
            canvas.drawColor(Color.rgb(114, 114, 114))
            canvas.drawBitmap(photo, null, Rect(transform.left, transform.top, transform.left + transform.scaledW, transform.top + transform.scaledH), Paint(Paint.FILTER_BITMAP_FLAG))
            input.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        } finally { input.recycle() }
        val values = FloatArray(pixels.size * 3)
        pixels.forEachIndexed { i, pixel ->
            values[i] = Color.red(pixel) / 255f
            values[i + pixels.size] = Color.green(pixel) / 255f
            values[i + 2 * pixels.size] = Color.blue(pixel) / 255f
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val predictions = (outputs[0].value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val prototypes = (outputs[1].value as Array<Array<Array<FloatArray>>>)[0]
                val regions = NailPostprocess.decode(predictions, prototypes, transform)
                NailDetection(photo.width, photo.height, regions)
            }
        }
    }
    override fun close() = session.close()
}

internal class NailLetterbox(val width: Int, val height: Int, val inputW: Int, val inputH: Int) {
    private val gain = min(inputW.toFloat() / width, inputH.toFloat() / height)
    val scaledW = (width * gain).roundToInt().coerceAtLeast(1)
    val scaledH = (height * gain).roundToInt().coerceAtLeast(1)
    val left = (inputW - scaledW) / 2
    val top = (inputH - scaledH) / 2
    val gainX = scaledW.toFloat() / width
    val gainY = scaledH.toFloat() / height
    fun sourceX(x: Float) = (x - left) / gainX
    fun sourceY(y: Float) = (y - top) / gainY
}

internal object NailPostprocess {
    private data class Candidate(val index: Int, val score: Float, val box: NailBox)
    fun decode(pred: Array<FloatArray>, proto: Array<Array<FloatArray>>, t: NailLetterbox): List<NailRegion> {
        require(proto.isNotEmpty() && pred.size == 5 + proto.size) { "Expected one-class nail segmentation model" }
        val count = pred[0].size
        require(pred.all { it.size == count })
        val candidates = (0 until count).mapNotNull { i ->
            val score = pred[4][i]
            val cx = pred[0][i]; val cy = pred[1][i]; val w = pred[2][i]; val h = pred[3][i]
            if (!score.isFinite() || score < .5f || !cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return@mapNotNull null
            val box = NailBox(floor(t.sourceX(cx-w/2)).toInt().coerceIn(0,t.width), floor(t.sourceY(cy-h/2)).toInt().coerceIn(0,t.height),
                ceil(t.sourceX(cx+w/2)).toInt().coerceIn(0,t.width), ceil(t.sourceY(cy+h/2)).toInt().coerceIn(0,t.height))
            if (box.width < 3 || box.height < 3 || box.width.toLong()*box.height > t.width.toLong()*t.height/4) null else Candidate(i,score,box)
        }.sortedByDescending { it.score }.take(100)
        val kept = mutableListOf<Candidate>()
        candidates.forEach { candidate -> if (kept.none { iou(it.box,candidate.box) > .45f }) kept.add(candidate) }
        // Preserve extra detections; a second hand/false positive must not silently become "five nails".
        return kept.take(20).mapNotNull { candidate ->
            val mh=proto[0].size; val mw=proto[0][0].size
            val probabilities=FloatArray(mh*mw)
            for(y in 0 until mh) for(x in 0 until mw) {
                var logit=0f
                for(c in proto.indices) logit += pred[5+c][candidate.index]*proto[c][y][x]
                probabilities[y*mw+x] = (1.0/(1.0+exp(-logit.toDouble()))).toFloat()
            }
            val b=candidate.box
            val mask=ByteArray(b.width*b.height)
            for(y in 0 until b.height) for(x in 0 until b.width) {
                val px=((b.left+x+.5f)*t.gainX+t.left)/t.inputW*mw-.5f
                val py=((b.top+y+.5f)*t.gainY+t.top)/t.inputH*mh-.5f
                if(sample(probabilities,mw,mh,px,py) >= .5f) mask[y*b.width+x]=(-1).toByte()
            }
            NailMaskGeometry.region(mask,b,candidate.score,t.width,t.height)
        }.sortedBy { it.center.x }
    }
    internal fun sample(data:FloatArray,w:Int,h:Int,x:Float,y:Float):Float {
        val xx=x.coerceIn(0f,(w-1).toFloat()); val yy=y.coerceIn(0f,(h-1).toFloat())
        val x0=floor(xx).toInt(); val y0=floor(yy).toInt(); val x1=min(x0+1,w-1); val y1=min(y0+1,h-1)
        val dx=xx-x0; val dy=yy-y0
        return (data[y0*w+x0]*(1-dx)+data[y0*w+x1]*dx)*(1-dy)+(data[y1*w+x0]*(1-dx)+data[y1*w+x1]*dx)*dy
    }
    internal fun iou(a:NailBox,b:NailBox):Float {
        val intersect=max(0,min(a.right,b.right)-max(a.left,b.left))*max(0,min(a.bottom,b.bottom)-max(a.top,b.top))
        return intersect.toFloat()/(a.width*a.height+b.width*b.height-intersect).coerceAtLeast(1)
    }
}

internal object NailMaskGeometry {
    fun region(raw:ByteArray,box:NailBox,confidence:Float,imageW:Int,imageH:Int):NailRegion? {
        require(raw.size==box.width*box.height)
        val w=box.width; val h=box.height
        // One nail = one connected region. Remove speckles instead of stretching an ellipse over skin.
        val visited=BooleanArray(raw.size); val queue=IntArray(raw.size); var largest=IntArray(0)
        for(seed in raw.indices) if(raw[seed].toInt()!=0 && !visited[seed]) {
            var head=0; var tail=1; queue[0]=seed; visited[seed]=true
            while(head<tail) {
                val p=queue[head++]; val x=p%w; val y=p/w
                for(dy in -1..1) for(dx in -1..1) {
                    val xx=x+dx; val yy=y+dy
                    if(xx in 0 until w && yy in 0 until h) { val n=yy*w+xx
                        if(!visited[n] && raw[n].toInt()!=0) { visited[n]=true; queue[tail++]=n }
                    }
                }
            }
            if(tail>largest.size) largest=queue.copyOf(tail)
        }
        if(largest.size<max(16,(imageW.toLong()*imageH*.00002).toInt())) return null
        val mask=ByteArray(raw.size); largest.forEach { mask[it]=(-1).toByte() }
        val cx=largest.sumOf { (it%w+.5).toDouble() }/largest.size
        val cy=largest.sumOf { (it/w+.5).toDouble() }/largest.size
        var xx=0.0; var xy=0.0; var yy=0.0
        largest.forEach { val dx=it%w+.5-cx; val dy=it/w+.5-cy; xx+=dx*dx; xy+=dx*dy; yy+=dy*dy }
        val angle=(atan2(2*xy,xx-yy)/2).toFloat(); val ux=cos(angle); val uy=sin(angle)
        val contour=boundary(mask,w,h).map { NailPoint(it.x+box.left,it.y+box.top) }
        if(contour.size<4) return null
        var minU=Float.POSITIVE_INFINITY; var maxU=Float.NEGATIVE_INFINITY
        var minV=Float.POSITIVE_INFINITY; var maxV=Float.NEGATIVE_INFINITY
        contour.forEach { p -> val dx=p.x-box.left-cx.toFloat(); val dy=p.y-box.top-cy.toFloat()
            val u=dx*ux+dy*uy; val v=-dx*uy+dy*ux
            minU=min(minU,u); maxU=max(maxU,u); minV=min(minV,v); maxV=max(maxV,v)
        }
        val center=NailPoint(cx.toFloat()+box.left,cy.toFloat()+box.top)
        val corners=listOf(minU to minV,maxU to minV,maxU to maxV,minU to maxV).map { (u,v) -> NailPoint(center.x+u*ux-v*uy,center.y+u*uy+v*ux) }
        val clipped=largest.any { val x=it%w+box.left; val y=it/w+box.top; x<=1||y<=1||x>=imageW-2||y>=imageH-2 }
        return NailRegion(confidence,box,mask,contour,center,angle,corners,clipped)
    }
    /** Trace pixel edges in order; do not sort boundary pixels by angle (breaks concave nails). */
    internal fun boundary(mask:ByteArray,w:Int,h:Int):List<NailPoint> {
        val stride=w+1
        val edges=mutableMapOf<Int,MutableList<Int>>()
        fun edge(x:Int,y:Int,xx:Int,yy:Int) { edges.getOrPut(y*stride+x){mutableListOf()}.add(yy*stride+xx) }
        fun filled(x:Int,y:Int)=x in 0 until w && y in 0 until h && mask[y*w+x].toInt()!=0
        for(y in 0 until h) for(x in 0 until w) if(filled(x,y)) {
            if(!filled(x,y-1)) edge(x,y,x+1,y)
            if(!filled(x+1,y)) edge(x+1,y,x+1,y+1)
            if(!filled(x,y+1)) edge(x+1,y+1,x,y+1)
            if(!filled(x-1,y)) edge(x,y+1,x,y)
        }
        var best=listOf<NailPoint>(); var bestArea=0.0
        while(edges.isNotEmpty()) {
            val start=edges.keys.first(); var current=start; val loop=mutableListOf<NailPoint>()
            do {
                loop.add(NailPoint((current%stride).toFloat(),(current/stride).toFloat()))
                val next=edges[current]?:break
                val key=current; current=next.removeAt(next.lastIndex); if(next.isEmpty()) edges.remove(key)
            } while(current!=start)
            if(current==start && loop.size>=4) {
                var area=0.0
                loop.indices.forEach { i -> val a=loop[i]; val b=loop[(i+1)%loop.size]; area+=a.x*b.y-a.y*b.x }
                if(abs(area)>bestArea) { bestArea=abs(area); best=loop }
            }
        }
        // Exact collinear reduction retains every turn at the segmentation boundary.
        return best.filterIndexed { i,p ->
            val a=best[(i+best.size-1)%best.size]; val b=best[(i+1)%best.size]
            (p.x-a.x)*(b.y-p.y)!=(p.y-a.y)*(b.x-p.x)
        }
    }
}
