package com.example.nailtryon

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailUploadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun uploadEntryLaunchesImagePickerAndCancellationKeepsLibraryUsable() {
        compose.onNodeWithText("上传我的美甲图").performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, request.intent.action)
        assertEquals("image/*", request.intent.type)
        activity.receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        compose.onNodeWithText("上传我的美甲图").assertExists()
        compose.onNodeWithText("上传我的美甲图").performClick()
        assertEquals(Intent.ACTION_GET_CONTENT, activity.nextStartedActivityForResult.intent.action)
    }

    @Test fun selectedCustomImageContinuesToHandPhotoSheet() {
        val photo = File(compose.activity.cacheDir, "custom-nail.jpg")
        photo.outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertNotNull("The selected photo must be decodable", decodeUri(compose.activity, Uri.fromFile(photo)))
        compose.onNodeWithText("上传我的美甲图").performClick()
        val activity = shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        compose.runOnUiThread {
            activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(photo)))
        }
        compose.onNodeWithText("选择照片").assertExists()
        compose.onNodeWithText("从相册选择").assertExists()
    }
}
