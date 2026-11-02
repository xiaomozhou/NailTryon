package com.example.nailtryon

import android.graphics.*
import androidx.core.graphics.PathParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeftHandGuideTest {
    @Test fun fiveSeparateFingerContoursHaveVisibleGapsAndLeftHandOrientation() {
        val paths = LeftHandGuide.fingers.values.map { PathParser.createPathFromPathData(it)!! }
        assertEquals(5, paths.size)
        paths.forEachIndexed { index, path ->
            val measure = PathMeasure(path, false)
            assertTrue(measure.isClosed)
            assertFalse(measure.nextContour())
            paths.drop(index + 1).forEach { other ->
                val intersection = Path(path)
                assertTrue(intersection.op(other, Path.Op.INTERSECT))
                assertTrue("Fingers must not overlap", intersection.isEmpty)
            }
        }
        val bounds = paths.map { RectF().also { r -> it.computeBounds(r, true) } }
        val expanded = paths.map { path -> Path().also {
            Paint().apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 16f }.getFillPath(path, it)
        } }
        expanded.zipWithNext().forEach { (left, right) ->
            val intersection = Path(left)
            intersection.op(right, Path.Op.INTERSECT)
            assertTrue("At least 16 units of visible finger separation", intersection.isEmpty)
        }
        assertTrue(bounds.last().centerX() > bounds[3].centerX())
        assertTrue(bounds[2].top < bounds[1].top)
    }

    @Test fun overlayFitsPortraitAndLandscapeWithoutFillingPhotoArea() {
        listOf(750 to 1000, 1000 to 750).forEach { (w, h) ->
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            LeftHandGuide.draw(Canvas(bitmap), w.toFloat(), h.toFloat())
            var marked = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
                    marked++
                    assertTrue(x > 0 && x < w - 1 && y > 0 && y < h - 1)
                }
            }
            assertTrue(marked > 1000)
            assertTrue("Preview remains visible", marked < w * h / 8)
            if (w == 750) {
                val file = File("build/reports/hand-guide-preview.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
