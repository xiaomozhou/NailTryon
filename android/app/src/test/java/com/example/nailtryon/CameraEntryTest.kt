package com.example.nailtryon

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.compose.setContent
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun configureHostPaths() { PortableFileProviderPaths.install(compose.activity) }

    @Test fun tryOnCameraEntryLaunchesFullSizeCaptureWithoutCameraPermissionAndCancelKeepsAppOpen() {
        val activity = compose.activity
        val permissions = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse("External camera must not require an ungranted CAMERA permission", permissions.contains(Manifest.permission.CAMERA))
        compose.onAllNodesWithText("试戴")[0].performClick()
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        assertEquals("${activity.packageName}.camera", uri.authority)
        compose.runOnUiThread {
            activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithText("上传我的美甲图").assertExists()
        assertFalse(activity.isFinishing)
        compose.onAllNodesWithText("试戴")[0].performClick()
        compose.onNodeWithText("从相册选择").assertExists()
    }

    @Test fun completedCameraPhotoIsPassedWithSelectedNailIntoTryOnFlow() {
        val uploaded = AtomicReference<Pair<Bitmap, NailStyle>?>()
        compose.runOnUiThread {
            compose.activity.setContent {
                NailTryOnApp(generateTryOn = { hand, _, style -> uploaded.set(hand to style); hand })
            }
        }
        compose.onAllNodesWithText("试戴")[0].performClick()
        compose.onNodeWithText("拍照").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val uri = request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        compose.activity.contentResolver.openOutputStream(uri)!!.use {
            Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent())
        }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); uploaded.get() != null }
        assertEquals(1600, uploaded.get()!!.first.width)
        assertEquals(NailCatalog.styles.first(), uploaded.get()!!.second)
        compose.onNodeWithContentDescription("试戴结果").assertExists()
    }
}
