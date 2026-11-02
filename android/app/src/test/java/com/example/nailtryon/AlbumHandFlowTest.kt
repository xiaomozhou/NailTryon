package com.example.nailtryon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumHandFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inspected=AtomicInteger()
    private val generated=AtomicReference<Pair<Bitmap,NailStyle>?>()
    private lateinit var uri:Uri
    @Before fun setup() {
        PortableFileProviderPaths.install(compose.activity)
        uri=CameraPhotos.createOutput(compose.activity);write(uri,Color.BLUE)
    }
    private fun write(output:Uri,color:Int) {
        val bitmap=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
        compose.activity.contentResolver.openOutputStream(output).use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it!!)}
    }
    private fun app(inspect:suspend(Context,Bitmap)->AlbumHandCheck) {
        compose.runOnUiThread { compose.activity.setContent {
            NailTryOnApp(generateTryOn={hand,_,style->generated.set(hand to style);hand},
                albumInspector={context,image->inspected.incrementAndGet();inspect(context,image)})
        } }
    }
    private fun picker() : org.robolectric.shadows.ShadowActivity.IntentForResult {
        compose.onAllNodesWithText("试戴")[0].performClick()
        compose.onNodeWithText("从相册选择").performClick()
        val request=shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT,request.intent.action);assertEquals("image/*",request.intent.type)
        return request
    }
    private fun choose() {
        val request=picker()
        compose.runOnUiThread { compose.activity.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_OK,Intent().setData(uri)) }
    }
    private fun waitText(value:String) { compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()} }
    @Test fun selectedAlbumPhotoIsCheckedAndSegmentedBeforeAnyGenerationAndKeepsSelectedStyle() {
        val pending=CompletableDeferred<AlbumHandCheck>()
        app{_,image->assertEquals(1000,image.width);pending.await()}
        choose()
        compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();inspected.get()==1}
        compose.onNodeWithText("正在检查左手手背和五指状态…").assertExists()
        compose.onNodeWithTag("accept-album-hand").assertIsNotEnabled()
        assertNull(generated.get())
        compose.runOnIdle{pending.complete(AlbumHandCheck(emptyList(),HandPolicyFixture.nails()))}
        waitText("照片符合要求，已检测到五片指甲，请检查轮廓")
        compose.onNodeWithTag("nail-detection-contours").assertExists()
        assertNull(generated.get())
        compose.onNodeWithTag("accept-album-hand").assertIsEnabled().performClick()
        compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();generated.get()!=null}
        assertEquals(NailCatalog.styles.first(),generated.get()!!.second)
        assertEquals(1000,generated.get()!!.first.height)
        assertTrue(Color.blue(generated.get()!!.first.getPixel(500,500))>250)
        compose.onNodeWithContentDescription("试戴结果").assertExists()
    }
    @Test fun invalidPhotoOffersGuidedCameraAndCancellationThenSuccessfulCapturePreservesSelection() {
        app{_,_->AlbumHandCheck(listOf(HandPhotoProblem.LEFT_REQUIRED))}
        choose();waitText(HandPhotoProblem.LEFT_REQUIRED.message)
        compose.onNodeWithTag("accept-album-hand").assertIsNotEnabled()
        assertNull(generated.get())
        compose.onNodeWithText("去拍一张").performClick()
        val first=shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(FullSizePicture.ACTION,first.intent.action)
        assertEquals(HandCameraActivity::class.java.name,first.intent.component!!.className)
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(first.requestCode,Activity.RESULT_CANCELED,null)}
        waitText(HandPhotoProblem.LEFT_REQUIRED.message)
        compose.onNodeWithText("去拍一张").performClick()
        val next=shadowOf(compose.activity).nextStartedActivityForResult
        val output=next.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        write(output,Color.MAGENTA)
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(next.requestCode,Activity.RESULT_OK,Intent())}
        compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();generated.get()!=null}
        assertEquals(NailCatalog.styles.first(),generated.get()!!.second)
        assertTrue(Color.red(generated.get()!!.first.getPixel(500,500))>250)
        assertEquals(1,inspected.get())
    }
    @Test fun modelErrorOffersCameraInsteadOfUsingUncheckedPhoto() {
        app{_,_->error("Inference failed")}
        choose();waitText("照片检查失败，请重新选择或拍照")
        compose.onNodeWithTag("accept-album-hand").assertIsNotEnabled()
        compose.onNodeWithText("去拍一张").performClick()
        assertEquals(HandCameraActivity::class.java.name,shadowOf(compose.activity).nextStartedActivityForResult.intent.component!!.className)
        assertNull(generated.get())
    }
    @Test fun unreadableAlbumPhotoDoesNotReachInspectionOrGeneration() {
        app{_,_->AlbumHandCheck(emptyList(),HandPolicyFixture.nails())}
        compose.activity.contentResolver.openOutputStream(uri,"w")!!.close()
        choose();waitText("照片检查失败，请重新选择或拍照")
        assertEquals(0,inspected.get());assertNull(generated.get())
        compose.onNodeWithText("去拍一张").assertExists()
    }
    @Test fun pickerCancellationKeepsUploadSheetWithoutStartingInspection() {
        app{_,_->error("Must not inspect cancelled selection")}
        val request=picker()
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_CANCELED,null)}
        compose.onNodeWithText("从相册选择").assertExists()
        assertEquals(0,inspected.get());assertNull(generated.get())
    }
    @Test fun backDuringInspectionCancelsItAndReturnsToLibrary() {
        val pending=CompletableDeferred<AlbumHandCheck>()
        app{_,_->pending.await()};choose()
        compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();inspected.get()==1}
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("上传我的美甲图").assertExists()
        compose.runOnIdle{pending.complete(AlbumHandCheck(emptyList(),HandPolicyFixture.nails()))}
        compose.onNodeWithText("上传我的美甲图").assertExists()
        assertNull(generated.get())
    }
}
