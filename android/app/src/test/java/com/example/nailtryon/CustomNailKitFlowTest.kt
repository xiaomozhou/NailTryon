package com.example.nailtryon

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
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
class CustomNailKitFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun confirmedKitContinuesToHandUploadAndOnlyFivePieceReferenceReachesExistingGenerator() {
        PortableFileProviderPaths.install(compose.activity)
        val reference=AtomicReference<Bitmap?>();val hand=AtomicReference<Bitmap?>()
        compose.runOnUiThread{compose.activity.setContent{
            NailTryOnApp(generateTryOn={photo,nails,style->assertEquals("custom",style.id);hand.set(photo);reference.set(nails);photo},
                kitAnalyzer={_,_->NailKitAnalysis(listOf(KitTestFixture.row()),KitSelection(0,"已选择左手五片",false))})
        }}
        val source=File(compose.activity.cacheDir,"my-nail-kit.png")
        source.outputStream().use{Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}.compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("上传我的美甲图").performClick()
        val request=shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(request.requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(source)))}
        compose.waitUntil(10000){compose.onAllNodesWithText("已选择左手五片").fetchSemanticsNodes().isNotEmpty()}
        assertNull(reference.get())
        compose.onNodeWithTag("accept-nail-kit").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("选择照片").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("拍照").performClick()
        val capture=shadowOf(compose.activity).nextStartedActivityForResult
        val output=capture.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)!!
        compose.activity.contentResolver.openOutputStream(output).use{Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(255,120,0))}.compress(Bitmap.CompressFormat.JPEG,95,it!!)}
        compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(capture.requestCode,Activity.RESULT_OK,Intent())}
        compose.waitUntil(10000){shadowOf(Looper.getMainLooper()).idle();reference.get()!=null}
        val image=reference.get()!!
        assertEquals(Color.WHITE,image.getPixel(0,0))
        assertTrue(image.width!=800 || image.height!=800)
        var groups=0;var active=false
        for(x in 0 until image.width){
            val present=(0 until image.height).any{y->val p=image.getPixel(x,y);Color.blue(p)>240 && Color.red(p)<10}
            if(present && !active)groups++;active=present
        }
        assertEquals(5,groups)
        assertEquals(800,hand.get()!!.width);assertTrue(Color.red(hand.get()!!.getPixel(400,400))>250)
        compose.onNodeWithContentDescription("试戴结果").assertExists()
    }
}
