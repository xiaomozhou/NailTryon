package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AlbumHandReview(
    uri:Uri,
    onAccepted:(Bitmap,NailDetection)->Unit,
    onCamera:()->Unit,
    onBack:()->Unit,
    inspect:suspend(Context,Bitmap)->AlbumHandCheck=::inspectAlbumHand,
) {
    val context=LocalContext.current
    var photo by remember(uri){mutableStateOf<Bitmap?>(null)}
    var check by remember(uri){mutableStateOf<AlbumHandCheck?>(null)}
    var failed by remember(uri){mutableStateOf(false)}
    LaunchedEffect(uri) {
        try {
            val image=withContext(Dispatchers.IO){CameraPhotos.read(context,uri)} ?: error("Unreadable album photo")
            photo=image
            check=inspect(context,image).also { result ->
                result.nails?.let { require(it.width==image.width && it.height==image.height) }
                require(result.problems.isNotEmpty() || result.accepted)
            }
        } catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){failed=true}
    }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF15191D)).systemBarsPadding().padding(20.dp)) {
        TextButton(onClick=onBack){Text("返回",color=Color.White)}
        Text("检查手部照片",color=Color.White,style=MaterialTheme.typography.headlineSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical=16.dp),contentAlignment=Alignment.Center) {
            photo?.let { image ->
                val ratio=image.width.toFloat()/image.height
                val frameWidth=minOf(maxWidth,maxHeight*ratio)
                Box(Modifier.width(frameWidth).aspectRatio(ratio).testTag("album-hand-photo")) {
                    Image(image.asImageBitmap(),"相册手部照片",Modifier.fillMaxSize())
                    check?.nails?.let{NailDetectionOverlay(it,Modifier.fillMaxSize())}
                }
            }
            if(check==null && !failed) {
                Column(Modifier.background(Color.Black.copy(alpha=.7f)).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp),color=Color.White)
                    Text("正在检查左手手背和五指状态…",color=Color.White,modifier=Modifier.padding(top=12.dp))
                }
            }
        }
        val inspection=check;val image=photo
        if(failed) Text("照片检查失败，请重新选择或拍照",color=Color.White,modifier=Modifier.padding(bottom=12.dp))
        else if(inspection?.accepted==true) Text("照片符合要求，已检测到五片指甲，请检查轮廓",color=Color.White,modifier=Modifier.padding(bottom=12.dp))
        else inspection?.problems?.forEach { Text(it.message,color=Color.White,modifier=Modifier.padding(bottom=8.dp)) }
        if(failed || inspection?.accepted==false) Button(onClick=onCamera,modifier=Modifier.fillMaxWidth().testTag("album-open-camera")){Text("去拍一张")}
        Button(onClick={if(image!=null && inspection?.accepted==true)onAccepted(image,inspection.nails!!)},
            enabled=image!=null && inspection?.accepted==true,
            modifier=Modifier.fillMaxWidth().testTag("accept-album-hand")){Text("使用照片")}
    }
}
