package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Demo-safe local fusion fallback. The cloud endpoint can replace this implementation later. */
class FusionEngine {
    fun compose(hand: Bitmap, styleColor: Int): Bitmap {
        val output = hand.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = styleColor; alpha = 185 }
        val w = output.width.toFloat(); val h = output.height.toFloat()
        val nailWidth = w * .13f; val nailHeight = h * .10f; val gap = w * .025f
        val startX = (w - (5*nailWidth + 4*gap))/2f
        val y = h * .62f
        repeat(5) { i -> canvas.drawRoundRect(RectF(startX+i*(nailWidth+gap), y, startX+i*(nailWidth+gap)+nailWidth, y+nailHeight), nailWidth*.25f, nailWidth*.25f, paint) }
        return output
    }
}
