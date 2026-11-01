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
import androidx.compose.ui.unit.DpRect
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

private val DpRect.widthDp: Float get() = right.value - left.value
private val DpRect.heightDp: Float get() = bottom.value - top.value

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TryOnHistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var storage: TryOnHistoryStore
    private val generationCount = AtomicInteger()

    @Before fun setUp() { storage = TryOnHistoryStore.forContext(compose.activity.applicationContext) }

    private fun openResult(width: Int, height: Int) {
        compose.runOnUiThread {
            compose.activity.setContent {
                NailTryOnApp(generateTryOn = { _, _, _ ->
                    generationCount.incrementAndGet()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                })
            }
        }
        val source = File(compose.activity.cacheDir, "history-source.jpg")
        source.outputStream().use { output ->
            Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                .compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        compose.onAllNodesWithText("试戴")[0].performClick()
        compose.onNodeWithText("从相册选择").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
        }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); generationCount.get() == 1 }
        compose.onNodeWithContentDescription("试戴结果").assertExists()
    }

    private fun entry(): TryOnHistoryEntry {
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); storage.load().size == 1 }
        return storage.load().single()
    }

    private fun awaitHistoryImage(entry: TryOnHistoryEntry) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("history-image-${entry.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("历史试戴效果：${entry.name}").assertExists()
    }

    @Test fun portraitResultAutomaticallyAppearsInHistoryAtIdenticalDisplayDimensions() {
        openResult(160, 240)
        val resultBounds = compose.onNodeWithTag("result-image").getUnclippedBoundsInRoot()
        val saved = entry()
        assertEquals(160, saved.width)
        assertEquals(240, saved.height)
        // No tap on 保存: history is independent of exporting to the phone's gallery.
        compose.onNodeWithText("历史").performClick()
        awaitHistoryImage(saved)
        val imageBounds = compose.onNodeWithTag("history-image-${saved.id}").getUnclippedBoundsInRoot()
        assertEquals(resultBounds.widthDp, imageBounds.widthDp, .6f)
        assertEquals(resultBounds.heightDp, imageBounds.heightDp, .6f)
        assertEquals(160f / 240f, imageBounds.widthDp / imageBounds.heightDp, .01f)
        val nameBounds = compose.onNodeWithTag("history-name-${saved.id}").getUnclippedBoundsInRoot()
        val timeBounds = compose.onNodeWithTag("history-time-${saved.id}").getUnclippedBoundsInRoot()
        assertTrue(nameBounds.bottom <= imageBounds.top)
        assertTrue(timeBounds.left >= imageBounds.left && timeBounds.right <= imageBounds.right)
        assertTrue(timeBounds.top >= imageBounds.top && timeBounds.bottom <= imageBounds.bottom)
        assertEquals(1, generationCount.get())
    }

    @Test fun landscapeHistoryUsesOriginalAspectInsteadOfFixedPortraitOrShortPlaceholder() {
        openResult(400, 160)
        val resultBounds = compose.onNodeWithTag("result-image").getUnclippedBoundsInRoot()
        val saved = entry()
        compose.onNodeWithText("历史").performClick()
        awaitHistoryImage(saved)
        val bounds = compose.onNodeWithTag("history-image-${saved.id}").getUnclippedBoundsInRoot()
        assertEquals(2.5f, bounds.widthDp / bounds.heightDp, .01f)
        assertEquals(resultBounds.widthDp, bounds.widthDp, .6f)
        assertEquals(resultBounds.heightDp, bounds.heightDp, .6f)
    }

    @Test fun reopeningActivityStillDisplaysPreviouslyGeneratedPhoto() {
        openResult(160, 240)
        val saved = entry()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("历史").performClick()
        awaitHistoryImage(saved)
        assertEquals(listOf(saved), TryOnHistoryStore.forContext(compose.activity).load())
        assertEquals(1, generationCount.get())
    }

    @Test fun savingToPhoneGalleryTwiceDoesNotDuplicateHistory() {
        TestGalleryProvider.install(compose.activity.applicationContext)
        openResult(160, 240)
        val saved = entry()
        repeat(2) {
            ShadowToast.reset()
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); ShadowToast.getTextOfLatestToast() == "已保存到相册" }
        }
        assertEquals(listOf(saved), storage.load())
        compose.onNodeWithText("历史").performClick()
        awaitHistoryImage(saved)
    }

    @Test fun historyCanScrollToOlderRealPhotos() {
        val entries = (1..6).map { index ->
            storage.save(NailCatalog.styles.first().copy(name = "款式$index"), Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888))
        }
        val oldest = storage.load().last()
        compose.onNodeWithText("历史").performClick()
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); compose.onAllNodesWithTag("history-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-list").performScrollToNode(hasText(oldest.name))
        awaitHistoryImage(oldest)
        assertEquals(6, storage.load().size)
        assertTrue(entries.contains(oldest))
    }

    @Test fun historyStorageFailureKeepsResultVisibleAndShowsFailureToast() {
        val sample = TryOnHistoryEntry("00000000-0000-0000-0000-000000000000", "测试", "测试", 0, 1, 1)
        val directory = storage.imageFile(sample).parentFile!!
        directory.parentFile!!.mkdirs()
        directory.writeText("storage unavailable")
        openResult(160, 240)
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); ShadowToast.getTextOfLatestToast() == "试戴已生成，历史记录保存失败" }
        compose.onNodeWithContentDescription("试戴结果").assertExists()
        assertTrue(storage.load().isEmpty())
    }

    @Test fun emptyHistoryShowsEmptyStateRatherThanFakeImageCards() {
        compose.onNodeWithText("历史").performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("还没有试戴记录").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasContentDescription("历史试戴效果：", substring = true)).assertCountEquals(0)
    }
}
