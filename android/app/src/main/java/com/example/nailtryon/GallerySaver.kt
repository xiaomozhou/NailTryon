package com.example.nailtryon

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal sealed interface GallerySaveResult {
    data class Saved(val uri: Uri) : GallerySaveResult
    data object Failed : GallerySaveResult
}

/** Write the generated bitmap to the public photo library, not app history or cache. */
internal class GallerySaver(private val context: Context) {
    suspend fun save(bitmap: Bitmap): GallerySaveResult = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext GallerySaveResult.Failed
        val resolver = context.contentResolver
        var inserted: Uri? = null
        try {
            val filename = "NailTryOn_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
            val details = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NailTryOn")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        return@withContext GallerySaveResult.Failed
                    }
                    @Suppress("DEPRECATION")
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NailTryOn")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create photo directory")
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(directory, filename).absolutePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, details) ?: throw IOException("Cannot create gallery entry")
            inserted = uri
            resolver.openOutputStream(uri, "w")?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw IOException("Cannot encode image")
                output.flush()
            } ?: throw IOException("Cannot open gallery output")
            if (Build.VERSION.SDK_INT >= 29) {
                val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("Cannot publish gallery image")
            }
            GallerySaveResult.Saved(uri)
        } catch (_: Exception) {
            inserted?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            GallerySaveResult.Failed
        }
    }
}

internal data class GallerySaveAction(val saving: Boolean, val save: (Bitmap, () -> Unit) -> Unit)
private data class PendingGallerySave(val bitmap: Bitmap, val onSaved: () -> Unit)

@Composable
internal fun rememberGallerySave(): GallerySaveAction {
    val context = LocalContext.current
    val saver = remember(context) { GallerySaver(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingGallerySave?>(null) }

    fun performSave(request: PendingGallerySave) {
        scope.launch(Dispatchers.Main.immediate) {
            val outcome = saver.save(request.bitmap)
            saving = false
            if (outcome is GallerySaveResult.Saved) {
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                request.onSaved()
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pending
        pending = null
        if (granted && request != null) performSave(request)
        else {
            saving = false
            Toast.makeText(context, if (granted) "保存失败，请重试" else "保存失败：未获得相册保存权限", Toast.LENGTH_LONG).show()
        }
    }

    return GallerySaveAction(saving) { bitmap, onSaved ->
        if (!saving) {
            saving = true
            val request = PendingGallerySave(bitmap, onSaved)
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pending = request
                try { permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                catch (_: Exception) {
                    pending = null
                    saving = false
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                }
            } else performSave(request)
        }
    }
}
