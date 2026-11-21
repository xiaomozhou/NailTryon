package com.example.nailtryon

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeedreamFusionScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun waitTag(tag:String)=compose.waitUntil(15000){shadowOf(Looper.getMainLooper()).idle();compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun selectHand() {
        val file=File(compose.activity.cacheDir,"fusion-hand.jpg")
        file.outputStream().use{output->javaClass.getResourceAsStream("/nail-detection/hand-1.jpg")!!.use{it.copyTo(output)}}
        compose.onAllNodesWithText("试戴")[0].performScrollTo().performClick();compose.onNodeWithText("从相册选择").performClick()
        val pick=shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(pick.requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))}
        waitTag("accept-album-hand")
        compose.waitUntil(15000){compose.onNodeWithTag("accept-album-hand").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()}
        compose.onNodeWithTag("accept-album-hand").performClick()
    }
    @Test fun unavailableCloudAutomaticallyShowsLocalImageWithoutModeLabels() {
        val gallery = TestGalleryProvider.install(compose.activity.applicationContext)
        val port = java.net.ServerSocket(0).use { it.localPort }
        val store = TryOnHistoryStore.forContext(compose.activity)
        val baseline = store.load().map { it.id }.toSet()
        compose.runOnUiThread {
            compose.activity.setContent {
                NailTryOnApp(cloudClientOverride = SeedreamTryOnClient("http://127.0.0.1:$port/try-on"))
            }
        }

        selectHand()
        waitTag("result-image")
        compose.onNodeWithText("本机试戴预览", substring = true).assertDoesNotExist()
        compose.onNodeWithText("云端 AI 生成", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("generation-error").assertDoesNotExist()
        compose.onNodeWithText("保存").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle()
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == "已保存到相册"
        }
        val file = gallery.rows.values.single().file
        val saved = android.graphics.BitmapFactory.decodeFile(file.absolutePath)!!
        val nailPixel = saved.getPixel(450, 148)
        assertTrue(Color.red(nailPixel) > Color.green(nailPixel) + 35)
        compose.waitUntil(10000) { store.load().any { it.id !in baseline && it.onDevice } }
        assertEquals(1, store.load().count { it.id !in baseline })
        val entry = store.load().single { it.id !in baseline }
        compose.onNodeWithText("历史").performClick()
        waitTag("history-image-${entry.id}")
        compose.onNodeWithText("本机试戴预览", substring = true).assertDoesNotExist()
    }

    @Test fun failedCloudDoesNotFakeSuccessOrSaveHistoryAndExplicitRetryCanSucceed() {
        val calls=AtomicInteger()
        val store=TryOnHistoryStore.forContext(compose.activity)
        val before=store.load().size
        compose.runOnUiThread{compose.activity.setContent{NailTryOnApp(generateTryOn={_,_,_->
            if(calls.incrementAndGet()==1)null else Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        })}}
        selectHand();waitTag("generation-error")
        compose.onNodeWithContentDescription("试戴结果").assertDoesNotExist()
        compose.onNodeWithText("保存").performScrollTo().assertIsNotEnabled()
        assertEquals(before,store.load().size);assertEquals(1,calls.get())
        compose.onNodeWithTag("retry-generation").performScrollTo().performClick();waitTag("result-image")
        compose.onNodeWithText("保存").performScrollTo().assertIsEnabled()
        compose.waitUntil(10000){store.load().size==before+1};assertEquals(2,calls.get())
    }
    @Test fun thrownProviderExceptionIsRecoverableAndLeavingCancelsStaleResult() {
        val pending=CompletableDeferred<Bitmap?>();val calls=AtomicInteger()
        val store=TryOnHistoryStore.forContext(compose.activity);val before=store.load().size
        compose.runOnUiThread{compose.activity.setContent{NailTryOnApp(generateTryOn={_,_,_->
            if(calls.incrementAndGet()==1)throw java.io.IOException("private provider details") else pending.await()
        })}}
        selectHand();waitTag("generation-error")
        compose.onNodeWithText("试戴生成失败，请稍后重试").assertExists()
        compose.onNodeWithTag("retry-generation").performClick()
        compose.waitUntil(5000){calls.get()==2}
        compose.onNodeWithText("正在生成试戴效果").assertExists()
        compose.onNodeWithText("先看本机预览").assertDoesNotExist()
        compose.onNodeWithText("美甲库").performClick()
        compose.runOnIdle{pending.complete(Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888))}
        compose.waitForIdle();assertEquals(before,store.load().size)
        compose.onNodeWithContentDescription("试戴结果").assertDoesNotExist()
    }
}
