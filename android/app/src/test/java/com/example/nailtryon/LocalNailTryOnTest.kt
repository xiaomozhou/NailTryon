package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalNailTryOnTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun bothReportedStylesGenerateWithNoNetworkAndLeaveSkinAndBackgroundUnchanged() = runBlocking<Unit> {
        val hand = javaClass.getResourceAsStream("/nail-detection/hand-1.jpg").use { BitmapFactory.decodeStream(it)!! }
        val original = hand.copy(Bitmap.Config.ARGB_8888, false)
        val detection = inspectAlbumHand(context, hand).nails!!
        val blockedPort = ServerSocket(0).use { it.localPort }
        val client = SeedreamTryOnClient("http://127.0.0.1:$blockedPort/try-on")
        val first = NailCatalog.styles.first { it.id == "solid_lemon" }
        val second = NailCatalog.styles.first { it.id == "solid_cherry" }
        val count = hand.width * hand.height
        val source = IntArray(count).also { hand.getPixels(it, 0, hand.width, 0, 0, hand.width, hand.height) }
        val inMask = BooleanArray(count)
        for (nail in detection.usableRegions) {
            val b = nail.box
            for (i in nail.mask.indices) if (nail.mask[i].toInt() != 0) {
                inMask[(b.top + i / b.width) * hand.width + b.left + i % b.width] = true
            }
        }

        for (style in listOf(first, second)) {
            val generated = SeedreamFusion.generate(context, hand, NailKitStore.catalogReference(context, style.drawableName), style, client)
            assertTrue(generated.onDevice)
            assertEquals(hand.width, generated.bitmap.width)
            assertEquals(hand.height, generated.bitmap.height)
            val actual = IntArray(count).also { generated.bitmap.getPixels(it, 0, hand.width, 0, 0, hand.width, hand.height) }
            assertTrue(source.indices.all { inMask[it] || source[it] == actual[it] })
            for (nail in detection.usableRegions) {
                val pixel = generated.bitmap.getPixel(nail.center.x.toInt(), nail.center.y.toInt())
                assertTrue(Color.red(pixel) > Color.blue(pixel))
                if (style == first) assertTrue(Color.green(pixel) > 100)
                else assertTrue(Color.green(pixel) < 95)
            }
            if (style == first) {
                val changed = source.indices.filter { inMask[it] && source[it] != actual[it] }
                assertTrue(changed.size > 1000)
                val interiors = changed.filter { i ->
                    i > 6 * hand.width && i + 6 * hand.width < count &&
                        (1..6).all { offset ->
                            inMask[i - offset] && inMask[i + offset] &&
                                inMask[i - offset * hand.width] && inMask[i + offset * hand.width]
                        }
                }
                assertTrue(interiors.size > 1000)
                assertTrue("Interior must be yellow without gray fixture pixels", interiors.all {
                    Color.green(actual[it]) > Color.blue(actual[it]) + 15
                })
            }
            val report = File("build/reports/fusion-live/${style.id}-local.jpg")
            report.parentFile!!.mkdirs()
            report.outputStream().use { generated.bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        }
        assertTrue(original.sameAs(hand))
    }

    @Test fun customKitUsesItsOwnFingerTextureAndPartialDetection() {
        val hand = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val fixture = HandPolicyFixture.nails()
        val palette = listOf(Color.BLACK, Color.BLUE, Color.GREEN, Color.RED, Color.MAGENTA)
        fixture.regions.forEachIndexed { i, nail ->
            android.graphics.Canvas(hand).drawRect(nail.box.left.toFloat(), nail.box.top.toFloat(),
                nail.box.right.toFloat(), nail.box.bottom.toFloat(), android.graphics.Paint().apply { color = palette[i] })
        }
        val kit = NailKitStore.save(context, hand, fixture.regions, "test")
        val photo = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val ring = fixture.regions[3].copy(finger = NailFinger.RING, tipDirection = NailPoint(0f, -1f))
        val style = NailStyle("custom", "我的美甲", "自选美甲", "")
        val result = LocalNailTryOn.render(photo, fixture.copy(regions = listOf(ring)), style) {
            NailKitStore.loadPieces(context, style, kit.absolutePath)
        }
        val column = ring.center.x.toInt()
        val edge = result.getPixel(column, ring.box.top)
        val feather = result.getPixel(column, ring.box.top + 1)
        val interior = result.getPixel(column, ring.center.y.toInt())
        assertTrue(Color.red(interior) > 230)
        assertEquals(Color.LTGRAY, edge)
        assertTrue(Color.green(feather) < Color.green(edge) && Color.green(feather) > Color.green(interior))
        assertEquals(Color.LTGRAY, result.getPixel(fixture.regions[0].center.x.toInt(), fixture.regions[0].center.y.toInt()))
    }
}
