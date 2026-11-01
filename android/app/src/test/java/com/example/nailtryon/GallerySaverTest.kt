package com.example.nailtryon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GallerySaverTest {
    private lateinit var context: Context
    private lateinit var provider: TestGalleryProvider

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        provider = TestGalleryProvider.install(context)
    }

    private fun photo() = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    @Test fun writesRealJpegAndPublishesItToGalleryWithoutStoragePermissionOnModernAndroid() = runBlocking {
        assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val result = GallerySaver(context).save(photo())
        assertTrue(result is GallerySaveResult.Saved)
        val uri = (result as GallerySaveResult.Saved).uri
        assertTrue(uri.toString().startsWith("content://media/external_primary/images/media/"))
        assertEquals(listOf("insert", "write", "publish"), provider.events)
        val originalMetadata = provider.insertedMetadata.single()
        assertEquals(1, originalMetadata.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/jpeg", originalMetadata.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/NailTryOn", originalMetadata.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertTrue(originalMetadata.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(".jpg"))
        assertTrue(provider.jpegCompleteWhenPublished)
        assertEquals(0, provider.rows.getValue(uri).metadata.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        val saved = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
        assertEquals(80, saved.width)
        assertEquals(120, saved.height)
        assertTrue(Color.red(saved.getPixel(10, 10)) > 240 && Color.green(saved.getPixel(10, 10)) < 15)
        context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), null, null, null, null)!!.use {
            assertEquals("The completed image must be visible in the gallery collection", 1, it.count)
        }
    }

    @Test fun nullInsertionReportsFailureAndDoesNotCreateAPhoto() = runBlocking {
        provider.failure = TestGalleryProvider.Failure.INSERT_NULL
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(photo()))
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun deniedInsertionReportsFailureInsteadOfCrashing() = runBlocking {
        provider.failure = TestGalleryProvider.Failure.INSERT_DENIED
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(photo()))
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun nullOutputStreamRollsBackIncompletePhoto() = runBlocking {
        provider.failure = TestGalleryProvider.Failure.OPEN_NULL
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(photo()))
        assertTrue(provider.rows.isEmpty())
        assertFalse(provider.events.contains("publish"))
        assertEquals("delete", provider.events.last())
    }

    @Test fun storageWriteFailureRollsBackIncompletePhoto() = runBlocking {
        provider.failure = TestGalleryProvider.Failure.WRITE
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(photo()))
        assertTrue(provider.rows.isEmpty())
        assertFalse(provider.events.contains("publish"))
        assertEquals("delete", provider.events.last())
    }

    @Test fun failedPublicationDoesNotReportSuccessAndRemovesThePendingRow() = runBlocking {
        provider.failure = TestGalleryProvider.Failure.PUBLISH
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(photo()))
        assertTrue(provider.rows.isEmpty())
        assertEquals(listOf("insert", "write", "publish", "delete"), provider.events)
    }

    @Test fun savingTwiceCreatesDifferentNamesWithoutOverwritingEarlierPhoto() = runBlocking {
        val saver = GallerySaver(context)
        assertTrue(saver.save(photo()) is GallerySaveResult.Saved)
        assertTrue(saver.save(photo()) is GallerySaveResult.Saved)
        assertEquals(2, provider.rows.size)
        assertEquals(2, provider.insertedMetadata.map { it.getAsString(MediaStore.Images.Media.DISPLAY_NAME) }.toSet().size)
    }

    @Test fun recycledBitmapIsRejectedBeforeCreatingAnyGalleryRow() = runBlocking {
        val image = photo().apply { recycle() }
        assertEquals(GallerySaveResult.Failed, GallerySaver(context).save(image))
        assertTrue(provider.rows.isEmpty())
        assertTrue(provider.events.isEmpty())
    }
}
