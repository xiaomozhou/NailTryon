package com.example.nailtryon

import android.content.ClipData
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class CameraPhotoProvider : FileProvider()

/** Launch our guided camera with a private output JPEG; no overlay is written into the photo. */
internal class FullSizePicture : ActivityResultContracts.TakePicture() {
    companion object { const val ACTION = "com.example.nailtryon.action.GUIDED_HAND_CAPTURE" }
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            action = ACTION
            component = ComponentName(context, HandCameraActivity::class.java)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("hand-photo", input)
        }
}

internal object CameraPhotos {
    const val OPEN_ERROR = "无法打开相机，请从相册选择照片"
    const val READ_ERROR = "未能读取拍摄照片，请重新拍照"

    fun createOutput(context: Context): Uri {
        val directory = File(context.cacheDir, "camera")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create camera directory" }
        val file = File.createTempFile("hand-", ".jpg", directory)
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun discard(context: Context, uri: Uri) {
        // Only remove the private capture file created by this provider.
        if (uri.authority == "${context.packageName}.camera" && uri.path?.startsWith("/camera/") == true) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun launch(context: Context, uri: Uri, start: (Uri) -> Unit): Boolean = try {
        start(uri)
        true
    } catch (_: Exception) {
        discard(context, uri)
        Toast.makeText(context, OPEN_ERROR, Toast.LENGTH_LONG).show()
        false
    }

    /** Decode off the UI thread, applying orientation and limiting memory for modern phone cameras. */
    fun read(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        require(maxSide > 0)
        if (Build.VERSION.SDK_INT >= 28) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1.0, maxSide.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        // Some camera providers cannot be decoded through a file descriptor. Reopen as a stream.
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (max(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= maxSide) inSampleSize *= 2
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null
            val scale = minOf(1.0, maxSide.toDouble() / max(decoded.width, decoded.height))
            val bitmap = if (scale < 1.0) Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1), true,
            ).also { if (it !== decoded) decoded.recycle() } else decoded
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrNull()
    }
}

@Composable
internal fun rememberCameraCapture(onPhoto: (Bitmap) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPhoto by rememberUpdatedState(onPhoto)
    // Camera apps can cause this activity to be recreated; keep the output URI across that gap.
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(FullSizePicture()) { success ->
        val uri = pendingUri?.let(Uri::parse)
        pendingUri = null
        if (uri != null) {
            scope.launch(Dispatchers.Main.immediate) {
                val photo = withContext(Dispatchers.IO) {
                    val bitmap = if (success) CameraPhotos.read(context, uri) else null
                    CameraPhotos.discard(context, uri)
                    bitmap
                }
                if (photo != null) latestOnPhoto(photo)
                else if (success) Toast.makeText(context, CameraPhotos.READ_ERROR, Toast.LENGTH_LONG).show()
            }
        }
    }
    return {
        if (pendingUri == null) {
            val uri = runCatching { CameraPhotos.createOutput(context) }.getOrNull()
            if (uri == null) Toast.makeText(context, CameraPhotos.OPEN_ERROR, Toast.LENGTH_LONG).show()
            else {
                pendingUri = uri.toString()
                if (!CameraPhotos.launch(context, uri) { launcher.launch(it) }) pendingUri = null
            }
        }
    }
}
