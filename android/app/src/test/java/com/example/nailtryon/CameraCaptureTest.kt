package com.example.nailtryon

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
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
class CameraCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val received = AtomicReference<Bitmap?>()
    @Before fun configureHostPaths() { PortableFileProviderPaths.install(compose.activity) }

    private fun showCapture() {
        compose.setContent {
            val capture = rememberCameraCapture { received.set(it) }
            Button(onClick = capture) { Text("拍照") }
        }
    }

    private fun writePhoto(uri: Uri, width: Int = 1600, height: Int = 1200) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        compose.activity.contentResolver.openOutputStream(uri)!!.use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
        }
        bitmap.recycle()
    }

    @Test fun fullSizePhotoReturnsToUploadCallbackWithoutThumbnailExtras() {
        showCapture()
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, request.intent.clipData!!.getItemAt(0).uri)
        assertTrue(request.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(request.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        writePhoto(uri)
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent())
        }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); received.get() != null }
        val photo = received.get()!!
        assertEquals(1600, photo.width)
        assertEquals(1200, photo.height)
        assertNotEquals(Bitmap.Config.HARDWARE, photo.config)
        val pixel = photo.getPixel(100, 100)
        assertTrue(Color.red(pixel) > 240 && Color.blue(pixel) > 240 && Color.green(pixel) < 15)
    }

    @Test fun cancelDoesNotUploadAndAllowsTakingAnotherPhoto() {
        showCapture()
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        }
        compose.waitUntil(10_000) { File(compose.activity.cacheDir, "camera").listFiles().orEmpty().isEmpty() }
        assertNull(received.get())
        compose.onNodeWithText("拍照").performClick()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
    }

    @Test fun emptyCameraOutputShowsFailureInsteadOfUploadingOrCrashing() {
        showCapture()
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent())
        }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); ShadowToast.getTextOfLatestToast() == CameraPhotos.READ_ERROR }
        assertNull(received.get())
        compose.onNodeWithText("拍照").assertExists()
    }

    @Test fun captureOutputSurvivesSavedStateRestoration() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val capture = rememberCameraCapture { received.set(it) }
            Button(onClick = capture) { Text("拍照") }
        }
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        writePhoto(uri)
        restore.emulateSavedInstanceStateRestore()
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent())
        }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); received.get() != null }
        assertEquals(1600, received.get()!!.width)
    }

    @Test fun cameraLaunchExceptionsAreHandledAndTemporaryFileIsRemoved() {
        listOf(ActivityNotFoundException("no camera"), SecurityException("camera blocked")).forEach { failure ->
            val uri = CameraPhotos.createOutput(compose.activity)
            compose.runOnUiThread { assertFalse(CameraPhotos.launch(compose.activity, uri) { throw failure }) }
            assertEquals(CameraPhotos.OPEN_ERROR, ShadowToast.getTextOfLatestToast())
            assertTrue(File(compose.activity.cacheDir, "camera").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun largeCameraPhotoIsBoundedAndExifRotationIsApplied() {
        val uri = CameraPhotos.createOutput(compose.activity)
        writePhoto(uri, 4000, 3000)
        // Add a real JPEG APP1/Exif TIFF tag (orientation=6), without relying on the host's
        // native Android EXIF writer. This is the same metadata a portrait camera supplies.
        val jpeg = compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val exif = intArrayOf(
            0xff, 0xe1, 0, 0x22, 0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0,
            0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0,
        ).map { it.toByte() }.toByteArray()
        compose.activity.contentResolver.openOutputStream(uri)!!.use {
            it.write(jpeg, 0, 2)
            it.write(exif)
            it.write(jpeg, 2, jpeg.size - 2)
        }
        val photo = CameraPhotos.read(compose.activity, uri)!!
        assertEquals(1536, photo.width)
        assertEquals(2048, photo.height)
        assertNotEquals(Bitmap.Config.HARDWARE, photo.config)
        CameraPhotos.discard(compose.activity, uri)
    }

    @Test fun providerDoesNotShareFilesOutsideCameraDirectory() {
        val context = compose.activity
        val privateFile = File(context.cacheDir, "not-a-camera-photo.jpg").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.camera", privateFile)
        }
        val escaped = Uri.parse("content://${context.packageName}.camera/camera/..%2Fnot-a-camera-photo.jpg")
        assertThrows(SecurityException::class.java) { context.contentResolver.openInputStream(escaped) }
    }
}
