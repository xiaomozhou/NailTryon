package com.example.nailtryon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

class HandCameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
        if (output?.scheme != "content" || output.authority != "$packageName.camera" || output.path?.startsWith("/camera/") != true) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            MaterialTheme {
                HandCameraScreen(output,
                    onCaptured = { setResult(Activity.RESULT_OK); finish() },
                    onCancel = { setResult(Activity.RESULT_CANCELED); finish() })
            }
        }
    }
}

@Composable
internal fun HandCameraScreen(
    output: Uri,
    onCaptured: () -> Unit,
    onCancel: () -> Unit,
    backendFactory: (Context) -> GuidedCameraBackend = { CameraXGuidedCameraBackend(it) },
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf<GuidedCameraBackend?>(null) }
    fun toast(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        if (!allowed) toast("需要相机权限才能拍照，可返回从相册选择")
    }
    fun requestPermission() {
        requested = true
        try { permission.launch(Manifest.permission.CAMERA) }
        catch (_: Exception) { toast("无法申请相机权限，可返回从相册选择") }
    }
    LaunchedEffect(Unit) { if (!granted && !requested) requestPermission() }
    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().background(Color(0xFF15191D)).systemBarsPadding().padding(horizontal = 20.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) { Text("返回", color = Color.White) }
        Text("请拍左手手背", color = Color.White, fontSize = 23.sp, modifier = Modifier.padding(top = 4.dp))
        Text("五指自然分开，不遮挡指甲", color = Color(0xFFD4D8DC), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frameWidth = minOf(maxWidth, maxHeight * .75f)
            Box(Modifier.width(frameWidth).aspectRatio(.75f).clip(RoundedCornerShape(16.dp)).background(Color.Black).testTag("hand-camera-preview")) {
                if (granted) {
                    val owner = LocalLifecycleOwner.current
                    val preview = remember(context) { PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    } }
                    val camera = remember(context) { backendFactory(context) }
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().testTag("live-camera-preview"))
                    DisposableEffect(camera, owner, preview) {
                        backend = camera
                        try { camera.attach(preview, owner, { ready = it }, { ready = false; cameraFailed = true }) }
                        catch (_: Exception) { ready = false; cameraFailed = true }
                        onDispose { ready = false; backend = null; camera.close() }
                    }
                    LeftHandGuideOverlay(Modifier.fillMaxSize().padding(8.dp))
                    if (!ready) Text(
                        if (cameraFailed) "无法打开相机，请返回从相册选择" else "正在打开相机…",
                        color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f)).padding(12.dp),
                    )
                } else {
                    Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("需要相机权限才能拍摄手部照片", color = Color.White)
                        TextButton(onClick = { requestPermission() }) { Text("开启相机", color = Color.White) }
                    }
                }
            }
        }
        Text("将左手手背放入轮廓内，指甲朝向镜头", color = Color(0xFFD4D8DC), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
        Button(
            onClick = {
                if (ready && !capturing) {
                    capturing = true
                    try {
                        backend?.capture(output, { capturing = false; onCaptured() }, { capturing = false; toast("拍摄失败，请重试") })
                            ?: run { capturing = false; toast("相机尚未就绪，请稍后再试") }
                    } catch (_: Exception) { capturing = false; toast("拍摄失败，请重试") }
                }
            }, enabled = granted && ready && !capturing, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp).size(72.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text(if (capturing) "拍摄中" else "拍照") }
    }
}
