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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailUploadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun pick(photo: File) {
        compose.onNodeWithText("上传我的美甲图").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, request.intent.action)
        assertEquals("image/*", request.intent.type)
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(
                request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo)),
            )
        }
    }

    private fun photo(name: String): File = File(compose.activity.cacheDir, name).also { file ->
        val image = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    private fun waitCard() = compose.waitUntil(10000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag("custom-nail-card").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun uploadEntryOpensAlbumAndCancellationKeepsCatalogUsable() {
        compose.onNodeWithText("美甲试戴").assertExists()
        compose.onNodeWithTag("category-自选美甲").assertDoesNotExist()
        compose.onNodeWithText("上传我的美甲图").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, request.intent.action)
        assertEquals("image/*", request.intent.type)
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithTag("custom-nail-card").assertDoesNotExist()
        compose.onNodeWithText("上传我的美甲图").performClick()
        assertNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }

    @Test fun preprocessingRunsWithoutConfirmationAndNewAppInstanceRestoresCard() {
        val analysis = CompletableDeferred<NailKitAnalysis>()
        compose.runOnUiThread {
            compose.activity.setContent { NailTryOnApp(kitAnalyzer = { _, _ -> analysis.await() }) }
        }
        val source = photo("custom-nail.png")
        compose.onNodeWithTag("category-${NailCatalog.categories.last()}").performScrollTo().performClick()
        pick(source)
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("正在处理美甲图…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("custom-nail-card").assertDoesNotExist()
        compose.onNodeWithText("准备五片美甲").assertDoesNotExist()
        compose.onNodeWithText("使用这组五片").assertDoesNotExist()
        analysis.complete(NailKitAnalysis(listOf(KitTestFixture.row()), KitSelection(0, "first", false)))
        waitCard()
        compose.onNodeWithTag("category-自选美甲").assertIsSelected()
        compose.onNodeWithText(NailCatalog.byCategory(NailCatalog.categories.first()).first().name).assertDoesNotExist()
        val customBounds=compose.onNodeWithTag("category-自选美甲").fetchSemanticsNode().boundsInRoot
        val solidBounds=compose.onNodeWithTag("category-${NailCatalog.categories.first()}").fetchSemanticsNode().boundsInRoot
        assertTrue(customBounds.left<solidBounds.left)
        compose.onNodeWithTag("category-${NailCatalog.categories.first()}").performClick()
        compose.onNodeWithTag("custom-nail-card").assertDoesNotExist()
        compose.onNodeWithText(NailCatalog.byCategory(NailCatalog.categories.first()).first().name).assertExists()
        compose.onNodeWithTag("category-自选美甲").performClick()
        waitCard()
        compose.onNodeWithText("选择照片").assertDoesNotExist()
        compose.waitUntil(10000){
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithContentDescription("我的美甲").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread {
            val view=compose.activity.window.decorView
            val screenshot=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(screenshot))
                val file=File("build/reports/custom-upload/library.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            } finally { screenshot.recycle() }
        }
        val store = CustomNailKitStore(compose.activity)
        val directory = store.load()!!
        assertTrue(File(directory, "source.png").isFile)
        assertTrue(File(directory, "reference.png").isFile)
        assertTrue(source.delete())
        // Destroy the composition and its saved state; reload from app-private files.
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.setContent { NailTryOnApp() } }
        waitCard()
        assertEquals(directory, CustomNailKitStore(compose.activity).load())
        compose.onNodeWithTag("custom-nail-card").onChildren().filterToOne(hasText("试戴")).performScrollTo().performClick()
        compose.onNodeWithText("选择照片").assertExists()
    }

    @Test fun failedReplacementKeepsPreviousCardAndAllowsAnotherUpload() {
        var fail = false
        compose.runOnUiThread {
            compose.activity.setContent {
                NailTryOnApp(kitAnalyzer = { _, _ ->
                    if (fail) error("segmentation failure")
                    NailKitAnalysis(listOf(KitTestFixture.row()), KitSelection(0, "first", false))
                })
            }
        }
        val source = photo("replace-nail.png")
        pick(source)
        waitCard()
        val before = CustomNailKitStore(compose.activity).load()
        fail = true
        pick(source)
        compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle()
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == "美甲图处理失败，请换一张图片重试"
        }
        assertEquals(before, CustomNailKitStore(compose.activity).load())
        compose.onNodeWithTag("custom-nail-card").assertExists()
        compose.onNodeWithText("上传我的美甲图").performClick()
        assertNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }
}
