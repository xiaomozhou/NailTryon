package com.example.nailtryon

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=ComposeHostTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailDetectionReviewTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var uri:Uri
    private var retakes=0
    private val accepted=AtomicReference<NailDetection?>()
    @Before fun setup() {
        PortableFileProviderPaths.install(compose.activity)
        uri=CameraPhotos.createOutput(compose.activity)
        val photo=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        compose.activity.contentResolver.openOutputStream(uri).use{photo.compress(Bitmap.CompressFormat.JPEG,95,it!!)}
    }
    private fun result(count:Int=5):NailDetection {
        val regions=(0 until count).map { i ->
            val b=NailBox(50+i*90,100,80+i*90,160)
            NailMaskGeometry.region(ByteArray(b.width*b.height){(-1).toByte()},b,.95f,600,800)!!
        }
        return NailDetection(600,800,regions)
    }
    private fun waitText(text:String) { compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun pendingThenCompleteShowsExactContoursWithoutEditingPhotoAndReturnsDetectionOnlyWhenAccepted() {
        val pending=CompletableDeferred<NailDetection>()
        val analyzed=AtomicReference<Bitmap?>()
        compose.setContent { MaterialTheme {
            NailDetectionReview(uri,{accepted.set(it)},{retakes++}) { _,bitmap -> analyzed.set(bitmap);pending.await() }
        } }
        waitText("正在检测指甲…")
        compose.onNodeWithTag("accept-nail-detection").assertIsNotEnabled()
        val detection=result()
        compose.runOnIdle { pending.complete(detection) }
        waitText("已检测到5片清晰指甲，可继续试戴")
        compose.onNodeWithTag("nail-detection-contours").assertExists()
        assertNull(accepted.get())
        assertEquals(600,analyzed.get()!!.width);assertEquals(800,analyzed.get()!!.height)
        // Visualization must never paint into the captured bitmap.
        for(y in 0 until 800 step 17) for(x in 0 until 600 step 17) {
            val pixel=analyzed.get()!!.getPixel(x,y)
            assertTrue(Color.red(pixel)<5 && Color.blue(pixel)>250)
        }
        compose.onNodeWithTag("accept-nail-detection").assertIsEnabled().performClick()
        assertEquals(detection,accepted.get())
    }
    @Test fun partialDetectionAllowsUsingOnlyTheClearNails() {
        val detected = result(4)
        val mixed = detected.copy(regions = detected.regions + result(1).regions.single().copy(confidence = .4f))
        compose.setContent { MaterialTheme { NailDetectionReview(uri,{accepted.set(it)},{retakes++}) { _,_ -> mixed } } }
        waitText("已检测到4片清晰指甲，可继续试戴")
        compose.onNodeWithTag("accept-nail-detection").assertIsEnabled().performClick()
        assertEquals(detected, accepted.get())
    }
    @Test fun noClearNailsDisablesAcceptanceAndAllowsRetake() {
        compose.setContent { MaterialTheme { NailDetectionReview(uri,{accepted.set(it)},{retakes++}) { _,_ -> result(0) } } }
        waitText("未检测到清晰指甲，请调整光线后重拍")
        compose.onNodeWithTag("accept-nail-detection").assertIsNotEnabled()
        compose.onNodeWithText("重新拍摄").performClick()
        assertEquals(1,retakes);assertNull(accepted.get())
    }
    @Test fun modelFailureIsRecoverableWithoutPassingInvalidPhoto() {
        compose.setContent { MaterialTheme { NailDetectionReview(uri,{accepted.set(it)},{retakes++}) { _,_ -> error("model unavailable") } } }
        waitText("指甲检测失败，请重新拍摄")
        compose.onNodeWithTag("accept-nail-detection").assertIsNotEnabled()
        compose.onNodeWithText("重新拍摄").performClick()
        assertEquals(1,retakes);assertNull(accepted.get())
    }
    @Test fun wrongCoordinateDimensionsAreRejectedInsteadOfDisplayingShiftedContours() {
        compose.setContent { MaterialTheme { NailDetectionReview(uri,{accepted.set(it)},{retakes++}) { _,_ -> result().copy(width=800,height=600) } } }
        waitText("指甲检测失败，请重新拍摄")
        compose.onNodeWithTag("accept-nail-detection").assertIsNotEnabled()
        compose.onNodeWithTag("nail-detection-contours").assertDoesNotExist()
    }
}
