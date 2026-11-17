package com.example.nailtryon

import android.content.Context
import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Guides Seedream with clear nail masks and any available anatomical finger labels. */
internal data class GeneratedTryOn(val bitmap: Bitmap, val onDevice: Boolean = false)

internal enum class TryOnMode { AUTOMATIC, LOCAL, CLOUD }

internal object SeedreamFusion {
    suspend fun generate(
        context: Context,
        hand: Bitmap,
        kit: Bitmap,
        style: NailStyle,
        client: SeedreamTryOnClient,
        customDirectory: String? = null,
        mode: TryOnMode = TryOnMode.AUTOMATIC,
    ): GeneratedTryOn {
        val check=inspectAlbumHand(context,hand)
        require(check.accepted){"请换一张指甲清晰可见的手部照片"}
        val nails = checkNotNull(check.nails)
        suspend fun localPreview() = withContext(Dispatchers.Default) {
            GeneratedTryOn(LocalNailTryOn.render(hand, nails, style) {
                NailKitStore.loadPieces(context, style, customDirectory)
            }, onDevice = true)
        }
        if (mode == TryOnMode.LOCAL) return localPreview()
        val guide = withContext(Dispatchers.Default) { guide(hand, nails) }
        try {
            val generated = client.generate(hand, kit, style, guide)
            try {
                return GeneratedTryOn(withContext(Dispatchers.Default) { normalizeResult(hand, generated) })
            } catch (error: IllegalArgumentException) {
                generated.recycle()
                throw TryOnCloudException("试戴云服务返回的图片构图不匹配", error)
            }
        } catch (error: TryOnCloudException) {
            android.util.Log.w("NailTryOn", error.message.orEmpty())
            if (mode == TryOnMode.CLOUD) throw error
            return localPreview()
        } finally {guide.recycle()}
    }

    fun guide(hand:Bitmap,nails:NailDetection):Bitmap {
        require(nails.width==hand.width && nails.height==hand.height && nails.readyForAlignment)
        return hand.copy(Bitmap.Config.ARGB_8888,true).also { image ->
            val canvas=Canvas(image)
            val size=(minOf(hand.width,hand.height)*.025f).coerceAtLeast(12f)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN
                strokeWidth = size * .12f
                style = Paint.Style.STROKE
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
            nails.usableRegions.forEach { nail ->
                val path = Path()
                nail.contour.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                canvas.drawPath(path, outline)
                val label = nail.finger?.let { (it.ordinal + 1).toString() } ?: return@forEach
                val x = nail.box.left.toFloat()
                val y = (nail.box.top - size * .3f).coerceAtLeast(size)
                labelPaint.color = Color.BLACK
                canvas.drawRect(x, y - size, x + size, y + size * .15f, labelPaint)
                labelPaint.color = Color.CYAN
                canvas.drawText(label, x + size * .15f, y, labelPaint)
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
