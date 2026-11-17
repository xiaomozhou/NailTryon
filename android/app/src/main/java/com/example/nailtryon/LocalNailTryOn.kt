package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Transfers the selected kit's textures into detected nail masks at the original photo resolution. */
internal object LocalNailTryOn {
    fun render(hand: Bitmap, nails: NailDetection, style: NailStyle, loadKit: () -> Map<NailFinger, Bitmap>): Bitmap {
        require(nails.width == hand.width && nails.height == hand.height && nails.readyForAlignment)
        val kit = loadKit()
        val output = hand.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val regions = nails.usableRegions
            val handCenter = regions.map { it.center }.let { points ->
                NailPoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
            }
            for (nail in regions) {
                val finger = nail.finger ?: estimateFinger(nail, regions)
                transfer(hand, output, nail, kit.getValue(finger), handCenter, style.id == "solid_lemon")
            }
            return output
        } catch (error: Exception) {
            output.recycle()
            throw error
        } finally {
            kit.values.forEach { it.recycle() }
        }
    }

    private fun estimateFinger(nail: NailRegion, regions: List<NailRegion>): NailFinger {
        val width = nailWidth(nail)
        val largest = regions.maxOf(::nailWidth)
        return when {
            regions.size >= 3 && width <= largest * .62f -> NailFinger.LITTLE
            regions.size >= 4 && width >= largest * .99f -> NailFinger.THUMB
            else -> NailFinger.MIDDLE
        }
    }

    private fun nailWidth(nail: NailRegion): Float =
        hypot(nail.orientedCorners[0].x - nail.orientedCorners[3].x,
            nail.orientedCorners[0].y - nail.orientedCorners[3].y)

    private fun transfer(
        hand: Bitmap,
        output: Bitmap,
        nail: NailRegion,
        texture: Bitmap,
        center: NailPoint,
        lemonYellow: Boolean,
    ) {
        val box = nail.box
        var tipX = cos(nail.axisRadians)
        var tipY = sin(nail.axisRadians)
        val tip = nail.tipDirection
        val outwardX = tip?.x ?: (nail.center.x - center.x)
        val outwardY = tip?.y ?: (nail.center.y - center.y)
        val alignment = tipX * outwardX + tipY * outwardY
        if (alignment < 0 || (alignment == 0f && tipY > 0)) {
            tipX = -tipX
            tipY = -tipY
        }

        val corners = nail.orientedCorners
        val halfLength = hypot(corners[0].x - corners[1].x, corners[0].y - corners[1].y) / 2
        val halfWidth = nailWidth(nail) / 2
        if (halfLength < 1f || halfWidth < 1f) return
        val length = (2 * halfLength).roundToInt().coerceAtLeast(2)
        val left = IntArray(length) { Int.MAX_VALUE }
        val right = IntArray(length) { Int.MIN_VALUE }
        val original = IntArray(box.width * box.height)
        hand.getPixels(original, 0, box.width, box.left, box.top, box.width, box.height)
        val rows = IntArray(original.size) { -1 }
        val columns = IntArray(original.size)
        var lightTotal = 0L
        var count = 0
        for (i in original.indices) {
            if (nail.mask[i].toInt() == 0) continue
            val dx = box.left + i % box.width + .5f - nail.center.x
            val dy = box.top + i / box.width + .5f - nail.center.y
            val row = (halfLength - (dx * tipX + dy * tipY)).roundToInt().coerceIn(0, length - 1)
            val column = (dx * -tipY + dy * tipX).roundToInt()
            rows[i] = row
            columns[i] = column
            left[row] = minOf(left[row], column)
            right[row] = maxOf(right[row], column)
            lightTotal += luminance(original[i])
            count++
        }
        if (count == 0) return

        val texWidth = texture.width
        val texHeight = texture.height
        val pixels = IntArray(texWidth * texHeight)
        texture.getPixels(pixels, 0, texWidth, 0, 0, texWidth, texHeight)
        val textureRows = (0 until texHeight).mapNotNull { row ->
            val start = row * texWidth
            pixels.copyOfRange(start, start + texWidth).filter { pixel ->
                pixel ushr 24 >= 192 && (!lemonYellow || isYellow(pixel))
            }.takeIf { it.isNotEmpty() }?.toIntArray()
        }
        require(textureRows.isNotEmpty()) { "甲片素材不可用" }

        val meanLight = lightTotal.toFloat() / count
        val blended = IntArray(original.size)
        val distance = distanceInsideMask(nail)
        val feather = (halfWidth * .18f).coerceIn(2f, 6f)
        for (i in original.indices) {
            val row = rows[i]
            if (row < 0 || distance[i] <= 1f) continue
            val textureRow = textureRows[(row.toFloat() / (length - 1) * (textureRows.size - 1)).roundToInt()]
            val u = (columns[i] - left[row]).toFloat() / (right[row] - left[row]).coerceAtLeast(1)
            val sample = textureRow[(u * (textureRow.size - 1)).roundToInt()]
            val shade = (1f + (luminance(original[i]) - meanLight) / 280f).coerceIn(.72f, 1.25f)
            val coverage = ((distance[i] - 1f) / feather).coerceIn(0f, 1f)
            val opacity = (245 * coverage * coverage * (3f - 2f * coverage)).roundToInt()
            blended[i] = (opacity shl 24) or (shadeChannel(sample, 16, shade) shl 16) or
                (shadeChannel(sample, 8, shade) shl 8) or shadeChannel(sample, 0, shade)
        }
        val patch = Bitmap.createBitmap(blended, box.width, box.height, Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(output).drawBitmap(patch, box.left.toFloat(), box.top.toFloat(), null)
        } finally {
            patch.recycle()
        }
    }

    /** Approximate distance to the nearest unpaintable pixel, including beyond the crop. */
    private fun distanceInsideMask(nail: NailRegion): FloatArray {
        val width = nail.box.width
        val height = nail.box.height
        val distance = FloatArray(nail.mask.size) { i ->
            if (nail.mask[i].toInt() == 0) 0f else {
                val x = i % width
                val y = i / width
                minOf(x + 1, y + 1, width - x, height - y).toFloat()
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x > 0) distance[i] = minOf(distance[i], distance[i - 1] + 1f)
            if (y > 0) {
                distance[i] = minOf(distance[i], distance[i - width] + 1f)
                if (x > 0) distance[i] = minOf(distance[i], distance[i - width - 1] + 1.414f)
                if (x + 1 < width) distance[i] = minOf(distance[i], distance[i - width + 1] + 1.414f)
            }
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            if (x + 1 < width) distance[i] = minOf(distance[i], distance[i + 1] + 1f)
            if (y + 1 < height) {
                distance[i] = minOf(distance[i], distance[i + width] + 1f)
                if (x > 0) distance[i] = minOf(distance[i], distance[i + width - 1] + 1.414f)
                if (x + 1 < width) distance[i] = minOf(distance[i], distance[i + width + 1] + 1.414f)
            }
        }
        return distance
    }

    // The lemon kit's little-finger cutout contains part of a gray product holder.
    private fun isYellow(pixel: Int): Boolean {
        val blue = pixel and 255
        return ((pixel ushr 16) and 255) - blue > 35 && ((pixel ushr 8) and 255) - blue > 35
    }

    private fun shadeChannel(pixel: Int, shift: Int, shade: Float) =
        (((pixel ushr shift) and 255) * shade).roundToInt().coerceIn(0, 255)

    private fun luminance(pixel: Int): Int =
        (((pixel ushr 16) and 255) * 54 + ((pixel ushr 8) and 255) * 183 + (pixel and 255) * 19) / 256

    fun upright(piece: Bitmap, axisRadians: Float): Bitmap {
        var angle = axisRadians
        if (sin(angle) > 0) angle -= Math.PI.toFloat()
        val rotation = Matrix().apply { postRotate(-90f - Math.toDegrees(angle.toDouble()).toFloat()) }
        return Bitmap.createBitmap(piece, 0, 0, piece.width, piece.height, rotation, true)
    }
}
