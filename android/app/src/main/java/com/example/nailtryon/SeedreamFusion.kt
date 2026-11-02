package com.example.nailtryon

import android.content.Context
import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Five masks are in anatomical thumb -> little order, established using the 21 hand landmarks. */
internal object SeedreamFusion {
    suspend fun generate(context:Context,hand:Bitmap,kit:Bitmap,style:NailStyle,client:SeedreamTryOnClient):Bitmap {
        val check=inspectAlbumHand(context,hand)
        require(check.accepted){"请重新拍摄清晰的左手手背，张开五指并露出全部指甲"}
        val guide=withContext(Dispatchers.Default){guide(hand,check.nails!!)}
        try {
            val generated=client.generate(hand,kit,style,guide) ?: error("试戴生成失败，请检查网络或稍后重试")
            return withContext(Dispatchers.Default){normalizeResult(hand,generated)}
        } finally {guide.recycle()}
    }

    fun guide(hand:Bitmap,nails:NailDetection):Bitmap {
        require(nails.width==hand.width && nails.height==hand.height && nails.readyForAlignment)
        return hand.copy(Bitmap.Config.ARGB_8888,true).also { image ->
            val canvas=Canvas(image)
            val size=(minOf(hand.width,hand.height)*.025f).coerceAtLeast(12f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.CYAN;strokeWidth=size*.12f;style=Paint.Style.STROKE}
            nails.regions.forEachIndexed { index,nail ->
                val path=Path();nail.contour.forEachIndexed{i,p->if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y)};path.close();canvas.drawPath(path,paint)
                paint.style=Paint.Style.FILL;paint.textSize=size;paint.color=Color.BLACK
                val label="${index+1}";val x=nail.box.left.toFloat();val y=(nail.box.top-size*.3f).coerceAtLeast(size)
                canvas.drawRect(x,y-size,x+size,y+size*.15f,paint)
                paint.color=Color.CYAN;canvas.drawText(label,x+size*.15f,y,paint);paint.style=Paint.Style.STROKE
            }
        }
    }

    // The provider may change resolution, but must not crop/change the source aspect ratio.
    fun normalizeResult(hand:Bitmap,generated:Bitmap):Bitmap {
        require(generated.width>=64 && generated.height>=64){"试戴结果图片无效，请重新生成"}
        val ratio=hand.width.toDouble()/hand.height
        require(abs((generated.width.toDouble()/generated.height)/ratio-1)<.025){"试戴结果构图发生变化，请重新生成"}
        return Bitmap.createScaledBitmap(generated,hand.width,hand.height,true)
    }
}
