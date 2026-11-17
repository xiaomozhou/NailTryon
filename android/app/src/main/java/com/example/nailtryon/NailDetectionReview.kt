package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun detectPhotoNails(context:Context,photo:Bitmap):NailDetection = withContext(Dispatchers.Default) {
    val model=context.assets.open("models/nail-seg.onnx").use { it.readBytes() }
    NailDetector(model).use { it.detect(photo) }
}

@Composable
internal fun NailDetectionReview(
    output:Uri,
    onAccepted:(NailDetection)->Unit,
    onRetake:()->Unit,
    detect:suspend (Context,Bitmap)->NailDetection = ::detectPhotoNails,
) {
    val context=LocalContext.current
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<NailDetection?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(output) {
        try {
            val image=withContext(Dispatchers.IO) { CameraPhotos.read(context,output) }
                ?: error("Unreadable photo")
            photo=image
            val detection = detect(context,image)
            require(detection.width == image.width && detection.height == image.height)
            result = detection.copy(regions = detection.usableRegions)
        } catch(cancel:CancellationException) { throw cancel }
        catch(_:Exception) { failed=true }
    }
    BackHandler(onBack=onRetake)
    Column(Modifier.fillMaxSize().background(Color(0xFF15191D)).systemBarsPadding().padding(20.dp)) {
        TextButton(onClick=onRetake) { Text("重新拍摄",color=Color.White) }
        Text("指甲检测",color=Color.White,style=MaterialTheme.typography.headlineSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical=16.dp),contentAlignment=Alignment.Center) {
            photo?.let { image ->
                val ratio=image.width.toFloat()/image.height
                val frameWidth=minOf(maxWidth,maxHeight*ratio)
                Box(Modifier.width(frameWidth).aspectRatio(ratio).testTag("nail-detection-photo")) {
                    Image(image.asImageBitmap(),"拍摄的手部照片",Modifier.fillMaxSize())
                    result?.let { NailDetectionOverlay(it,Modifier.fillMaxSize()) }
                }
            }
            if(result==null && !failed) {
                Column(Modifier.background(Color.Black.copy(alpha=.7f)).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp),color=Color.White)
                    Text("正在检测指甲…",color=Color.White,modifier=Modifier.padding(top=12.dp))
                }
            }
        }
        val detection=result
        val message=when {
            failed -> "指甲检测失败，请重新拍摄"
            detection==null -> "正在分析指甲边缘，请稍候"
            detection.readyForAlignment -> "已检测到${detection.regions.size}片清晰指甲，可继续试戴"
            else -> "未检测到清晰指甲，请调整光线后重拍"
        }
        Text(message,color=Color.White,modifier=Modifier.padding(bottom=16.dp))
        Button(onClick={ detection?.let(onAccepted) },enabled=detection?.readyForAlignment==true,
            modifier=Modifier.fillMaxWidth().testTag("accept-nail-detection")) { Text("使用照片") }
    }
}

@Composable
internal fun NailDetectionOverlay(result:NailDetection,modifier:Modifier=Modifier) {
    Canvas(modifier.testTag("nail-detection-contours")) {
        val sx=size.width/result.width; val sy=size.height/result.height
        result.regions.forEach { region ->
            val path=Path()
            val points = region.contour
            points.forEachIndexed { i, point ->
                val previous = points[(i + points.size - 1) % points.size]
                val next = points[(i + 1) % points.size]
                fun roundedToward(other: NailPoint): NailPoint {
                    val dx = other.x - point.x
                    val dy = other.y - point.y
                    val fraction = (2.5f / kotlin.math.hypot(dx, dy)).coerceAtMost(.5f)
                    return NailPoint(point.x + dx * fraction, point.y + dy * fraction)
                }
                val start = roundedToward(previous)
                val end = roundedToward(next)
                if (i == 0) path.moveTo(start.x * sx, start.y * sy)
                else path.lineTo(start.x * sx, start.y * sy)
                path.quadraticTo(point.x * sx, point.y * sy, end.x * sx, end.y * sy)
            }
            path.close()
            drawPath(path,Color(0xFF72E8CB).copy(alpha=.16f))
            drawPath(path,Color(0xFF72E8CB),style=Stroke(1.5.dp.toPx()))
        }
    }
}
