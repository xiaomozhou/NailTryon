package com.example.nailtryon

import android.graphics.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HandLandmarkModelTest {
    private fun model(name:String)=File("src/main/assets/models/$name").readBytes()
    private fun detector()=HandLandmarkDetector(model("palm_detection_full_Nx3x192x192.onnx"),model("hand_landmark_full_Nx3x224x224.onnx"))
    private fun photo(path:String)=javaClass.getResourceAsStream(path).use{BitmapFactory.decodeStream(it)!!}
    @Test fun bundledHandModelsMatchPublishedChecksums() {
        listOf("palm_detection_full_Nx3x192x192.onnx" to "42cbac27cd02625c823a3cefe833f60f945f89163ec2bf681f598c6ea26ffe41",
            "hand_landmark_full_Nx3x224x224.onnx" to "0965b9c5babdddd961d406aa7af4868321e9ea0e1c30f1832d5f3be21f79023c").forEach{(name,hash)->
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(model(name)).joinToString(""){"%02x".format(it)})
        }
    }
    @Test fun realModelsRecognizeLeftDorsalOpenHandsAndMatchAllFiveNailMasks() {
        detector().use { pose -> NailDetector(model("nail-seg.onnx")).use { nails ->
            for(i in 1..2) {
                val source=photo("/nail-detection/hand-$i.jpg");val hands=pose.detect(source)
                println("hand-$i observations=$hands")
                assertEquals(1,hands.size)
                val result=AlbumHandPolicy.inspect(hands,source.width,source.height,nails.detect(source))
                assertEquals("hand-$i pose/masks must pass: ${result.problems}",emptyList<HandPhotoProblem>(),result.problems)
                assertTrue(result.accepted)
            }
        } }
    }
    @Test fun rotatedAndMirroredHandsPassWithRealNailMasks() {
        val source = photo("/nail-detection/hand-1.jpg")
        detector().use { pose -> NailDetector(model("nail-seg.onnx")).use { segmenter ->
            for (transform in listOf(Matrix().apply { setRotate(90f) }, Matrix().apply { setScale(-1f, 1f) })) {
                val image = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                val result = AlbumHandPolicy.inspect(pose.detect(image), image.width, image.height, segmenter.detect(image))
                assertTrue(result.accepted)
                assertEquals(NailFinger.entries, result.nails!!.regions.map { it.finger })
            }
        } }
    }

    @Test fun realModelsStillRejectBlankPhotoAndAcceptClearNailsWhenOneIsObscured() {
        val image = photo("/nail-detection/hand-1.jpg").copy(Bitmap.Config.ARGB_8888, true)
        Canvas(image).drawRect(520f, 185f, 595f, 280f, Paint().apply { color = Color.BLUE })
        val blank = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        detector().use { pose -> NailDetector(model("nail-seg.onnx")).use { segmenter ->
            val obscured = AlbumHandPolicy.inspect(pose.detect(image), image.width, image.height, segmenter.detect(image))
            assertTrue(obscured.accepted)
            assertTrue(obscured.nails!!.regions.size in 1..4)
            assertFalse(obscured.nails.regions.any { it.center.x in 520f..595f && it.center.y in 185f..280f })
            assertFalse(AlbumHandPolicy.inspect(pose.detect(blank), blank.width, blank.height, segmenter.detect(blank)).accepted)
        } }
    }
}
