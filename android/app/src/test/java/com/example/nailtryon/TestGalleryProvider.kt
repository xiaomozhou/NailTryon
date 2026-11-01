package com.example.nailtryon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowContentResolver

/** Simulates MediaStore while retaining real JPEG encoding and ContentResolver file I/O. */
class TestGalleryProvider : ContentProvider() {
    enum class Failure { NONE, INSERT_NULL, INSERT_DENIED, OPEN_NULL, WRITE, PUBLISH }
    data class Row(val metadata: ContentValues, val file: File)
    @Volatile var failure = Failure.NONE
    val rows = linkedMapOf<Uri, Row>()
    val insertedMetadata = mutableListOf<ContentValues>()
    val events = mutableListOf<String>()
    @Volatile var jpegCompleteWhenPublished = false
    var releaseWrite: CountDownLatch? = null
    val writeStarted = CountDownLatch(1)
    private var nextId = 1

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (failure == Failure.INSERT_DENIED) throw SecurityException("MediaStore refused insertion")
        if (failure == Failure.INSERT_NULL) return null
        val id = nextId++
        val result = uri.buildUpon().appendPath(id.toString()).build()
        val metadata = ContentValues(values!!)
        insertedMetadata += ContentValues(metadata)
        rows[result] = Row(metadata, File(context!!.cacheDir, "gallery-row-$id.jpg").apply { createNewFile() })
        events += "insert"
        return result
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val row = rows[uri] ?: throw java.io.FileNotFoundException(uri.toString())
        if (mode.contains('w')) {
            events += "write"
            writeStarted.countDown()
            if (releaseWrite?.await(10, TimeUnit.SECONDS) == false) throw java.io.IOException("Test write timed out")
            if (failure == Failure.OPEN_NULL) return null
            if (failure == Failure.WRITE) throw java.io.IOException("Photo storage is full")
        }
        return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        events += "publish"
        if (failure == Failure.PUBLISH) return 0
        val row = rows[uri] ?: return 0
        val decoded = BitmapFactory.decodeFile(row.file.absolutePath)
        jpegCompleteWhenPublished = decoded != null && row.file.length() > 0
        decoded?.recycle()
        row.metadata.putAll(values!!)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        events += "delete"
        val row = rows.remove(uri) ?: return 0
        row.file.delete()
        return 1
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.IS_PENDING)
        return MatrixCursor(columns).apply {
            rows.values.filter { it.metadata.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0 }.forEach { row ->
                addRow(columns.map { row.metadata.get(it) }.toTypedArray())
            }
        }
    }

    companion object {
        fun install(context: Context): TestGalleryProvider = TestGalleryProvider().apply {
            attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", this)
        }
    }
}
