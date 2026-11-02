package com.example.nailtryon

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.LifecycleOwner
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
class HandCameraScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Uri
    private lateinit var camera: FakeCamera
    private var completed = 0
    private var cancelled = 0
    private var created = 0
    @Before fun setup() {
        PortableFileProviderPaths.install(compose.activity)
        output = CameraPhotos.createOutput(compose.activity)
        camera = FakeCamera(compose.activity)
    }
    private fun show(granted: Boolean = true) {
        if (granted) shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
        else shadowOf(compose.activity.application).denyPermissions(Manifest.permission.CAMERA)
        compose.setContent { MaterialTheme {
            HandCameraScreen(output, { completed++ }, { cancelled++ }, { created++; camera })
        } }
    }
    @Test fun guidanceAndSeparateHandOverlayAppearAboveLivePreviewAndWaitForCamera() {
        show()
        compose.onNodeWithText("请拍左手手背").assertExists()
        compose.onNodeWithText("五指自然分开，不遮挡指甲").assertExists()
        compose.onNodeWithTag("live-camera-preview").assertExists()
        compose.onNodeWithContentDescription("左手手背对齐轮廓，五根手指分开，拇指位于右侧").assertExists()
        compose.onNodeWithText("拍照").assertIsNotEnabled()
        compose.runOnIdle { camera.ready(true) }
        compose.onNodeWithText("拍照").assertIsEnabled()
    }
    @Test fun captureWritesCleanPhotoThenReturnsOnlyAfterSaveAndPreventsDoubleTap() {
        show()
        compose.runOnIdle { camera.ready(true) }
        compose.onNodeWithText("拍照").performClick()
        compose.onNodeWithText("拍摄中").assertIsNotEnabled()
        assertEquals(0, completed)
        assertEquals(output, camera.received)
        compose.runOnIdle { camera.save() }
        assertEquals(1, completed)
        val bitmap = compose.activity.contentResolver.openInputStream(output).use { BitmapFactory.decodeStream(it) }!!
        assertEquals(1200, bitmap.width)
        assertEquals(1600, bitmap.height)
        // A white guide line would change pixels; inspect the entire saved camera frame.
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) {
            assertTrue(Color.red(bitmap.getPixel(x,y)) < 5)
            assertTrue(Color.blue(bitmap.getPixel(x,y)) > 250)
        }
    }
    @Test fun captureFailureShowsToastAndAllowsRetryWithoutReturning() {
        show()
        compose.runOnIdle { camera.ready(true) }
        compose.onNodeWithText("拍照").performClick()
        compose.runOnIdle { camera.fail(); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals("拍摄失败，请重试", ShadowToast.getTextOfLatestToast())
        assertEquals(0, completed)
        compose.onNodeWithText("拍照").assertIsEnabled().performClick()
        compose.runOnIdle { camera.save() }
        assertEquals(1, completed)
    }
    @Test fun deniedPermissionDoesNotInitializeCameraAndCanReturn() {
        show(false)
        compose.onNodeWithText("需要相机权限才能拍摄手部照片").assertExists()
        compose.onNodeWithText("拍照").assertIsNotEnabled()
        assertEquals(0, created)
        compose.onNodeWithText("返回").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, completed)
    }
    @Test fun acceptingFirstPermissionRequestStartsPreviewAndEnablesCaptureWhenReady() {
        show(false)
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
        compose.runOnUiThread {
            shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_GRANTED)))
        }
        compose.onNodeWithTag("left-hand-guide").assertExists()
        compose.runOnIdle { camera.ready(true) }
        compose.onNodeWithText("拍照").assertIsEnabled()
        assertEquals(1, created)
    }
    @Test fun rejectingFirstPermissionRequestShowsExplanationAndKeepsAppUsable() {
        show(false)
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_DENIED)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.onNodeWithText("开启相机").assertExists()
        compose.onNodeWithText("拍照").assertIsNotEnabled()
        assertEquals("需要相机权限才能拍照，可返回从相册选择", ShadowToast.getTextOfLatestToast())
        assertEquals(0, created)
    }
    @Test fun cameraFailureDisablesShutterAndDisposalReleasesCamera() {
        show()
        compose.runOnIdle { camera.error() }
        compose.onNodeWithText("无法打开相机，请返回从相册选择").assertExists()
        compose.onNodeWithText("拍照").assertIsNotEnabled()
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        compose.waitForIdle()
        assertEquals(1, camera.closed)
    }
    private class FakeCamera(val context: Context) : GuidedCameraBackend {
        lateinit var ready: (Boolean) -> Unit
        lateinit var error: () -> Unit
        lateinit var saveCallback: () -> Unit
        lateinit var fail: () -> Unit
        var received: Uri? = null
        var closed = 0
        override fun attach(preview: PreviewView, owner: LifecycleOwner, onReady: (Boolean) -> Unit, onError: () -> Unit) {
            ready = onReady; error = onError
        }
        override fun capture(output: Uri, onSaved: () -> Unit, onError: () -> Unit) {
            received = output; saveCallback = onSaved; fail = onError
        }
        fun save() {
            val bitmap = Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            context.contentResolver.openOutputStream(received!!).use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it!!) }
            saveCallback()
        }
        override fun close() { closed++ }
    }
}
