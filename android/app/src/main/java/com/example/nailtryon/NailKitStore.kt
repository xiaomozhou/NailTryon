package com.example.nailtryon

import android.content.Context
import android.graphics.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

internal object NailKitStore {
    fun reference(source:Bitmap,regions:List<NailRegion>):Bitmap {
        require(regions.size==5)
        val upright=regions.map{region ->
            val crop=nailCutout(source,region)
            var angle=region.axisRadians
            if(sin(angle)>0)angle-=(Math.PI).toFloat()
            val rotated=Bitmap.createBitmap(crop,0,0,crop.width,crop.height,Matrix().apply{postRotate(-90f-Math.toDegrees(angle.toDouble()).toFloat())},true)
            if(rotated!==crop)crop.recycle()
            rotated
        }
        try {
            val scale=min(1f,320f/upright.maxOf{it.height})
            val width=upright.sumOf{(it.width*scale).roundToInt().coerceAtLeast(1)}+24*6
            val height=(upright.maxOf{it.height}*scale).roundToInt()+48
            return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { result ->
                val canvas=Canvas(result);canvas.drawColor(Color.WHITE);var x=24f
                upright.forEach { image ->
                    val w=image.width*scale;val h=image.height*scale
                    canvas.drawBitmap(image,null,RectF(x,24f,x+w,24f+h),Paint(Paint.FILTER_BITMAP_FLAG));x+=w+24
                }
            }
        }finally{upright.forEach{it.recycle()}}
    }

    fun save(context:Context,source:Bitmap,regions:List<NailRegion>,selection:String):File {
        require(regions.size==5)
        require(regions.indices.all{i->(i+1..4).all{j->NailPostprocess.iou(regions[i].box,regions[j].box)<.45f}})
        val root=File(context.cacheDir,"prepared-nail-kits").apply{check(isDirectory||mkdirs())}
        val directory=File(root,UUID.randomUUID().toString()).apply{check(mkdir())}
        fun png(file:File,image:Bitmap){file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
        try {
            png(File(directory,"source.png"),source)
            val sourceHash=MessageDigest.getInstance("SHA-256").digest(File(directory,"source.png").readBytes()).joinToString(""){"%02x".format(it)}
            val pieces=regions.mapIndexed { i,region ->
                val finger=NailFinger.entries[i];val name="${finger.name}.png"
                val crop=nailCutout(source,region);try{png(File(directory,name),crop)}finally{crop.recycle()}
                val b=region.box;val pad=(max(b.width,b.height)*.18f).roundToInt()
                val l=max(0,b.left-pad);val t=max(0,b.top-pad);val r=min(source.width,b.right+pad);val bottom=min(source.height,b.bottom+pad)
                val contextImage=Bitmap.createBitmap(source,l,t,r-l,bottom-t)
                try{png(File(directory,"${finger.name}-context.png"),contextImage)}finally{if(contextImage!==source)contextImage.recycle()}
                buildJsonObject {
                    put("finger",finger.name);put("file",name);put("contextFile","${finger.name}-context.png")
                    putJsonArray("sourceBox"){listOf(b.left,b.top,b.right,b.bottom).forEach{add(it)}}
                    putJsonArray("contextBox"){listOf(l,t,r,bottom).forEach{add(it)}}
                    put("axisRadians",region.axisRadians);put("confidence",region.confidence)
                    putJsonArray("contour"){region.contour.forEach{p->add(buildJsonArray{add(p.x);add(p.y)})}}
                }
            }
            val reference=reference(source,regions);try{png(File(directory,"reference.png"),reference)}finally{reference.recycle()}
            File(directory,"manifest.json").writeText(buildJsonObject{
                put("version",1);put("sourceSha256",sourceHash);put("width",source.width);put("height",source.height)
                put("selection",selection);put("orientation","major-axis-up-assumption; not a cuticle/tip guarantee")
                put("pieces",JsonArray(pieces))
            }.toString())
            return directory
        }catch(error:Exception){directory.listFiles()?.forEach{it.delete()};directory.delete();throw error}
    }
    fun loadPieces(context: Context, style: NailStyle, customDirectory: String?): Map<NailFinger, Bitmap> {
        fun open(name: String): java.io.InputStream =
            if (style.id == "custom") File(checkNotNull(customDirectory), name).inputStream()
            else context.assets.open("nail-kits/${style.drawableName}/$name")

        val manifest = open("manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val pieces = mutableMapOf<NailFinger, Bitmap>()
        try {
            for (piece in manifest.getValue("pieces").jsonArray) {
                val fields = piece.jsonObject
                val finger = NailFinger.valueOf(fields.getValue("finger").jsonPrimitive.content)
                check(finger !in pieces)
                val bitmap = open("${finger.name}.png").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    pieces[finger] = LocalNailTryOn.upright(bitmap, fields.getValue("axisRadians").jsonPrimitive.float)
                } finally {
                    if (pieces[finger] !== bitmap) bitmap.recycle()
                }
            }
            check(pieces.size == NailFinger.entries.size)
            return pieces
        } catch (error: Exception) {
            pieces.values.forEach { it.recycle() }
            throw error
        }
    }

    fun catalogReference(context:Context,drawable:String):Bitmap = context.assets.open("nail-kits/$drawable/reference.png").use{BitmapFactory.decodeStream(it)?:error("Invalid prepared catalog reference")}
}
