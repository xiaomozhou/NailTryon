package com.example.nailtryon

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GallerySaveScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var provider: TestGalleryProvider

    @Before fun setUp() { provider = TestGalleryProvider.install(compose.activity.applicationContext) }

    private fun openGeneratedResult() {
        val generated = AtomicBoolean(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                NailTryOnApp(generateTryOn = { _, _, _ ->
                    Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.MAGENTA)
                        generated.set(true)
                    }
                })
            }
        }
        val source = File(compose.activity.cacheDir, "green-source.jpg")
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
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); generated.get() }
        compose.onNodeWithContentDescription("试戴结果").assertExists()
    }

    private fun awaitToast(message: String) {
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); ShadowToast.getTextOfLatestToast() == message }
    }

    @Test fun saveButtonWritesGeneratedEffectNotOriginalPhotoAndShowsSuccessToast() {
        openGeneratedResult()
        compose.onNodeWithText("保存").performScrollTo().assertIsEnabled().performClick()
        awaitToast("已保存到相册")
        assertEquals(1, provider.rows.size)
        val saved = BitmapFactory.decodeFile(provider.rows.values.single().file.absolutePath)!!
        assertEquals(160, saved.width)
        assertEquals(240, saved.height)
        assertTrue("Must save magenta generated result, not green hand source", Color.red(saved.getPixel(20, 20)) > 240 && Color.green(saved.getPixel(20, 20)) < 15)
    }

    @Test fun failureShowsToastAndRetryCanSucceed() {
        provider.failure = TestGalleryProvider.Failure.PUBLISH
        openGeneratedResult()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        awaitToast("保存失败，请重试")
        assertTrue(provider.rows.isEmpty())
        compose.onNodeWithText("保存").assertIsEnabled()
        provider.failure = TestGalleryProvider.Failure.NONE
        compose.onNodeWithText("保存").performClick()
        awaitToast("已保存到相册")
        assertEquals(1, provider.rows.size)
    }

    @Test fun saveButtonIsDisabledDuringWriteAndDoesNotCreateDuplicateEntries() {
        val release = CountDownLatch(1)
        provider.releaseWrite = release
        openGeneratedResult()
        try {
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); provider.writeStarted.count == 0L }
            compose.onNodeWithText("保存中…").assertIsNotEnabled()
            assertEquals(1, provider.insertedMetadata.size)
        } finally { release.countDown() }
        awaitToast("已保存到相册")
        compose.onNodeWithText("保存").assertIsEnabled()
        assertEquals(1, provider.rows.size)
    }
}
