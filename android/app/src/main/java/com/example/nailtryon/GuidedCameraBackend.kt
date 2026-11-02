package com.example.nailtryon

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.io.OutputStream

internal interface GuidedCameraBackend {
    fun attach(preview: PreviewView, owner: LifecycleOwner, onReady: (Boolean) -> Unit, onError: () -> Unit)
    fun capture(output: Uri, onSaved: () -> Unit, onError: () -> Unit)
    fun close()
}

/** CameraX manages the preview/capture viewport and orientation together. Analysis is disabled. */
internal class CameraXGuidedCameraBackend(private val context: Context) : GuidedCameraBackend {
    private var controller: LifecycleCameraController? = null
    private var previewView: PreviewView? = null
    private var streamObserver: Observer<PreviewView.StreamState>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var outputStream: OutputStream? = null
    private var closed = false

    override fun attach(preview: PreviewView, owner: LifecycleOwner, onReady: (Boolean) -> Unit, onError: () -> Unit) {
        try {
            closed = false
            previewView = preview
            val camera = LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            }
            controller = camera
            preview.controller = camera
            camera.bindToLifecycle(owner)
            val executor = ContextCompat.getMainExecutor(context)
            camera.initializationFuture.addListener({
                if (!closed) {
                    try {
                        camera.initializationFuture.get()
                        val info = camera.cameraInfo ?: throw IllegalStateException("No back camera")
                        streamObserver = Observer<PreviewView.StreamState> { onReady(it == PreviewView.StreamState.STREAMING) }
                            .also { preview.previewStreamState.observe(owner, it) }
                        cameraObserver = Observer<CameraState> {
                            if (it.error != null) { onReady(false); onError() }
                        }.also { info.cameraState.observe(owner, it) }
                    } catch (_: Exception) { onReady(false); onError() }
                }
            }, executor)
        } catch (_: Exception) { onReady(false); onError() }
    }

    override fun capture(output: Uri, onSaved: () -> Unit, onError: () -> Unit) {
        try {
            val camera = controller ?: throw IllegalStateException("Camera not ready")
            val stream = context.contentResolver.openOutputStream(output, "w") ?: throw IllegalStateException("No output")
            outputStream = stream
            camera.takePicture(ImageCapture.OutputFileOptions.Builder(stream).build(), ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        try {
                            stream.close()
                            outputStream = null
                            if (!closed) onSaved()
                        } catch (_: Exception) { if (!closed) onError() }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        runCatching { stream.close() }
                        outputStream = null
                        if (!closed) onError()
                    }
                })
        } catch (_: Exception) {
            runCatching { outputStream?.close() }
            outputStream = null
            if (!closed) onError()
        }
    }

    override fun close() {
        closed = true
        streamObserver?.let { previewView?.previewStreamState?.removeObserver(it) }
        cameraObserver?.let { controller?.cameraInfo?.cameraState?.removeObserver(it) }
        runCatching { controller?.unbind() }
        runCatching { previewView?.controller = null }
        runCatching { outputStream?.close() }
        outputStream = null
        controller = null
    }
}
