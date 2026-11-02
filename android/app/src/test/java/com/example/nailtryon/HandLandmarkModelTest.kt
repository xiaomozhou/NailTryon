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
    @Test fun rotatedLeftHandPassesButMirroredRightHandIsRejected() {
        val source=photo("/nail-detection/hand-1.jpg")
        detector().use { detector ->
            val rotated=Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{setRotate(90f)},true)
            val observed=detector.detect(rotated)
            assertEquals(1,observed.size)
            assertTrue("Rotation must not change handedness: $observed",AlbumHandPolicy.poseProblems(observed,rotated.width,rotated.height).isEmpty())
            val right=Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{setScale(-1f,1f)},true)
            val mirrored=detector.detect(right)
            assertEquals(1,mirrored.size)
            assertTrue(AlbumHandPolicy.poseProblems(mirrored,right.width,right.height).contains(HandPhotoProblem.LEFT_REQUIRED))
        }
    }
    @Test fun realModelsRejectNonOpenGesturesMultipleHandsAndBlankPhoto() {
        detector().use { detector ->
            for(name in listOf("victory","thumb_up","woman_hands")) {
                val image=photo("/hand-pose/$name.jpg");val hands=detector.detect(image)
                val issues=AlbumHandPolicy.poseProblems(hands,image.width,image.height)
                println("$name count=${hands.size} issues=$issues")
                assertFalse("Unsupported pose must not pass: $name",issues.isEmpty())
            }
            val blank=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
            assertTrue(detector.detect(blank).isEmpty())
        }
    }
    @Test fun singlePalmPhotosAreRejectedInsteadOfMistakenForHandBack() {
        val image=photo("/hand-pose/woman_hands.jpg")
        val crops=listOf(Bitmap.createBitmap(image,120,270,400,220),Bitmap.createBitmap(image,0,560,380,240))
        var palmSideRecognized=false
        detector().use { detector -> crops.forEachIndexed { index,crop ->
            val hands=detector.detect(crop)
            assertEquals("Single palm crop $index",1,hands.size)
            val issues=AlbumHandPolicy.poseProblems(hands,crop.width,crop.height)
            println("palm crop $index handedness=${hands.single().mirroredLeftProbability} issues=$issues")
            assertFalse(issues.isEmpty())
            if(issues.contains(HandPhotoProblem.BACK_REQUIRED))palmSideRecognized=true
        } }
        assertTrue("At least the left-palm crop must fail the dorsal-side check",palmSideRecognized)
    }
    @Test fun obscuredNailPhotoCannotPassEvenIfHandLandmarksAreFound() {
        val image=photo("/nail-detection/hand-1.jpg").copy(Bitmap.Config.ARGB_8888,true)
        Canvas(image).drawRect(520f,185f,595f,280f,Paint().apply{color=Color.BLUE})
        detector().use { pose -> NailDetector(model("nail-seg.onnx")).use { segmenter ->
            val hands=pose.detect(image);assertEquals(1,hands.size)
            val result=AlbumHandPolicy.inspect(hands,image.width,image.height,segmenter.detect(image))
            assertFalse(result.accepted)
            assertTrue(result.problems.isNotEmpty())
        } }
    }
}
