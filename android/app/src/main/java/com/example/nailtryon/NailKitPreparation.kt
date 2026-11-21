package com.example.nailtryon

import android.content.Context
import android.graphics.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

internal data class NailKitAnalysis(val rows:List<KitRow>,val selection:KitSelection)

internal suspend fun readKitLabels(photo:Bitmap):List<KitText> {
    val reader=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    try {
        return withTimeout(15000) { suspendCancellableCoroutine { continuation ->
            reader.process(InputImage.fromBitmap(photo,0)).addOnSuccessListener { text ->
                if(continuation.isActive)continuation.resume(text.textBlocks.flatMap{it.lines}.flatMap{it.elements}.mapNotNull { word ->
                    word.boundingBox?.let{KitText(word.text,NailPoint(it.exactCenterX(),it.exactCenterY()))}
                })
            }.addOnFailureListener { if(continuation.isActive)continuation.resumeWithException(it) }
        } }
    } finally { reader.close() }
}

private fun kitSegmenter(context:Context,photo:Bitmap):NailKitSegmenter {
    fun model(name:String)=context.assets.open("models/$name").use{it.readBytes()}
    return NailKitSegmenter(model("mobile_sam_image_encoder.onnx"),model("sam_mask_decoder_single.onnx"),photo)
}

internal suspend fun analyzeNailKit(context:Context,photo:Bitmap):NailKitAnalysis {
    var ocrSucceeded=true
    val text=try {readKitLabels(photo)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){ocrSucceeded=false;emptyList()}
    return withContext(Dispatchers.Default) {
        val candidates=mutableListOf<NailRegion>()
        kitSegmenter(context,photo).use { segmenter ->
            // Refine the sampling when narrow or tilted nails fall between the coarse grid points.
            for(divisions in listOf(10,20)) {
                for(y in 1 until divisions)for(x in 1 until divisions) {
                    ensureActive()
                    val point=NailPoint(photo.width*x/divisions.toFloat(),photo.height*y/divisions.toFloat())
                    val covered=candidates.any { region ->
                        val box=region.box;val px=point.x.toInt();val py=point.y.toInt()
                        px in box.left until box.right && py in box.top until box.bottom &&
                            region.mask[(py-box.top)*box.width+px-box.left].toInt()!=0
                    }
                    if(covered)continue
                    val region=segmenter.segment(point,null)?:continue
                    val c=region.orientedCorners
                    val long=hypot(c[1].x-c[0].x,c[1].y-c[0].y);val short=hypot(c[2].x-c[1].x,c[2].y-c[1].y)
                    val ratio=max(long,short)/min(long,short).coerceAtLeast(1f)
                    if(region.touchesImageEdge || ratio !in 1.1f..5f)continue
                    val old=candidates.indexOfFirst{NailPostprocess.iou(it.box,region.box)>.5f}
                    if(old<0)candidates.add(region)else if(candidates[old].confidence<region.confidence)candidates[old]=region
                }
                if(NailKitLayout.rows(candidates.sortedByDescending{it.confidence}.take(20)).isNotEmpty())break
            }
        }
        val rows=NailKitLayout.rows(candidates.sortedByDescending{it.confidence}.take(20)).map{KitRow(separateKitMasks(it.regions,photo.width,photo.height))}
        NailKitAnalysis(rows,NailKitLayout.choose(rows,text,ocrSucceeded))
    }
}

internal suspend fun preparePointedNails(context:Context,photo:Bitmap,points:List<NailPoint>):List<NailRegion> = withContext(Dispatchers.Default) {
    require(points.size==5 && points.distinct().size==5)
    val first=points.first();val last=points.last();val span=hypot(last.x-first.x,last.y-first.y)
    require(span>min(photo.width,photo.height)*.2f){"请点选同一排的五片美甲"}
    val ux=(last.x-first.x)/span;val uy=(last.y-first.y)/span
    val width=span/4*1.25f;val length=span/4*3f
    kitSegmenter(context,photo).use { segmenter ->
        points.map { point ->
            ensureActive()
            val corners=listOf(-1f to -1f,-1f to 1f,1f to -1f,1f to 1f).map{(a,b)->
                NailPoint(point.x+a*width/2*ux-b*length/2*uy,point.y+a*width/2*uy+b*length/2*ux)
            }
            val box=NailBox(floor(corners.minOf{it.x}).toInt().coerceAtLeast(0),floor(corners.minOf{it.y}).toInt().coerceAtLeast(0),
                ceil(corners.maxOf{it.x}).toInt().coerceAtMost(photo.width),ceil(corners.maxOf{it.y}).toInt().coerceAtMost(photo.height))
            segmenter.segment(point,box,points.filter{it!=point})?:error("部分甲片未能完整提取，请重新点选或换图")
        }.also { regions -> require(regions.indices.all{i->(i+1 until regions.size).all{j->NailPostprocess.iou(regions[i].box,regions[j].box)<.45f}}){"请分别选择五片不同的美甲"} }
            .let{separateKitMasks(it,photo.width,photo.height,points)}
    }
}

internal fun separateKitMasks(regions:List<NailRegion>,width:Int,height:Int,seeds:List<NailPoint> = regions.map{it.center}):List<NailRegion> {
    require(regions.size==seeds.size)
    return regions.mapIndexed { index,region ->
        val b=region.box;val mask=region.mask.copyOf();val seed=seeds[index]
        for(y in b.top until b.bottom)for(x in b.left until b.right) {
            val pixel=(y-b.top)*b.width+x-b.left;if(mask[pixel].toInt()==0)continue
            val own=(x-seed.x)*(x-seed.x)+(y-seed.y)*(y-seed.y)
            regions.forEachIndexed{otherIndex,other->if(otherIndex!=index){
                val box=other.box
                if(x in box.left until box.right && y in box.top until box.bottom && other.mask[(y-box.top)*box.width+x-box.left].toInt()!=0){
                    val point=seeds[otherIndex];val distance=(x-point.x)*(x-point.x)+(y-point.y)*(y-point.y)
                    if(distance<own || (distance==own && otherIndex<index))mask[pixel]=0
                }
            }}
        }
        // White paint/glare can be segmented as holes; an opaque nail plate is not perforated.
        val outside=BooleanArray(mask.size);val queue=IntArray(mask.size);var head=0;var tail=0
        fun visit(x:Int,y:Int){if(x in 0 until b.width && y in 0 until b.height){val at=y*b.width+x
            if(mask[at].toInt()==0 && !outside[at]){outside[at]=true;queue[tail++]=at}
        }}
        for(x in 0 until b.width){visit(x,0);visit(x,b.height-1)}
        for(y in 0 until b.height){visit(0,y);visit(b.width-1,y)}
        while(head<tail){val at=queue[head++];val x=at%b.width;val y=at/b.width;visit(x-1,y);visit(x+1,y);visit(x,y-1);visit(x,y+1)}
        for(i in mask.indices)if(mask[i].toInt()==0 && !outside[i])mask[i]=(-1).toByte()
        NailMaskGeometry.region(mask,b,region.confidence,width,height)?:error("甲片区域重叠，无法确定五片独立美甲")
    }
}

/** The image's RGB is untouched; transparency only comes from the selected instance mask. */
internal fun nailCutout(source:Bitmap,region:NailRegion):Bitmap {
    val b=region.box
    require(b.left>=0 && b.top>=0 && b.right<=source.width && b.bottom<=source.height && region.mask.size==b.width*b.height)
    val pixels=IntArray(b.width*b.height);source.getPixels(pixels,0,b.width,b.left,b.top,b.width,b.height)
    pixels.indices.forEach{ i ->pixels[i]=if(region.mask[i].toInt()==0)0 else pixels[i] or (0xff shl 24)}
    return Bitmap.createBitmap(pixels,b.width,b.height,Bitmap.Config.ARGB_8888)
}
