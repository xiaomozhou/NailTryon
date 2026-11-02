package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun NailKitReview(
    uri:Uri,onAccepted:(File)->Unit,onBack:()->Unit,
    analyze:suspend(Context,Bitmap)->NailKitAnalysis=::analyzeNailKit,
    pointed:suspend(Context,Bitmap,List<NailPoint>)->List<NailRegion> = ::preparePointedNails,
) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var source by remember(uri){mutableStateOf<Bitmap?>(null)}
    var analysis by remember(uri){mutableStateOf<NailKitAnalysis?>(null)}
    var order by remember(uri){mutableStateOf<List<NailRegion>>(emptyList())}
    var points by remember(uri){mutableStateOf<List<NailPoint>>(emptyList())}
    var manual by remember(uri){mutableStateOf(false)}
    var busy by remember(uri){mutableStateOf(true)}
    var message by remember(uri){mutableStateOf("正在提取甲片和识别左右手标记…")}
    var job by remember{mutableStateOf<Job?>(null)}
    var selection by remember(uri){mutableStateOf("user-confirmed-row")}
    LaunchedEffect(uri) {
        try {
            val image=withContext(Dispatchers.IO){CameraPhotos.read(context,uri)}?:error("无法读取美甲图")
            source=image
            val result=analyze(context,image);analysis=result;message=result.selection.reason
            result.selection.rowIndex?.let { index ->
                val assigned=NailKitLayout.fingerOrder(result.rows[index])
                order=assigned?:result.rows[index].regions
                if(assigned==null)message+="；拇指方向不确定，请核对下方对应关系"
                selection=result.selection.reason
            }
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){message="未能自动提取完整五片，可点选同一排五片美甲"}
        finally{busy=false}
    }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFFBF9F7)).padding(20.dp)) {
        TextButton(onClick=onBack){Text("返回")}
        Text("准备五片美甲",style=MaterialTheme.typography.headlineSmall)
        Text(message,Modifier.padding(vertical=12.dp))
        val image=source
        if(image!=null) {
            Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height).testTag("kit-source")) {
                Image(image.asImageBitmap(),"原始美甲展示图",Modifier.fillMaxSize())
                if(manual) Canvas(Modifier.fillMaxSize().pointerInput(image,points){detectTapGestures{offset->
                    if(points.size<5 && !busy)points=points+NailPoint(offset.x/size.width*image.width,offset.y/size.height*image.height)
                }}.testTag("kit-point-picker")) {
                    points.forEach{p->drawCircle(Color(0xFFFF6168),8.dp.toPx(),androidx.compose.ui.geometry.Offset(p.x/image.width*size.width,p.y/image.height*size.height))}
                }
                else if(order.isNotEmpty())NailDetectionOverlay(NailDetection(image.width,image.height,order),Modifier.fillMaxSize())
            }
        }
        if(busy)CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp))
        if(!manual)analysis?.let { result ->
            if(result.selection.rowIndex==null)result.rows.forEachIndexed { index,row ->
                TextButton(onClick={order=NailKitLayout.fingerOrder(row)?:row.regions;selection="user-confirmed-row-${index+1}"},enabled=!busy){Text("选择第${index+1}排五片")}
            }
        }
        if(!busy && image!=null)TextButton(onClick={manual=true;points=emptyList();order=emptyList();message="有左右标记请选择左手；否则任选一排，按拇指到小指依次点选五片"}){Text("手动点选五片")}
        if(manual){
            Text("已点选 ${points.size}/5 片")
            TextButton(onClick={points=emptyList()},enabled=!busy){Text("重新点选")}
            Button(onClick={
                if(image!=null){busy=true;job=scope.launch{
                    try{order=pointed(context,image,points);selection="user-pointed-thumb-to-little";manual=false;message="请确认五片甲片的对应顺序"}
                    catch(cancel:CancellationException){throw cancel}catch(_:Exception){message="部分甲片未能完整提取，请重新点选或更换图片"}finally{busy=false}
                }}
            },enabled=points.size==5 && !busy){Text("提取这五片")}
        }
        if(order.size==5 && image!=null){
            Text("从拇指到小指；点按名称可调整对应关系",Modifier.padding(top=12.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                order.forEachIndexed { index,region ->
                    var expanded by remember(index,region){mutableStateOf(false)}
                    val cutout=remember(image,region){nailCutout(image,region)}
                    DisposableEffect(cutout){onDispose{cutout.recycle()}}
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                        Image(cutout.asImageBitmap(),"甲片${index+1}",Modifier.height(90.dp).fillMaxWidth())
                        Box{TextButton(onClick={expanded=true},enabled=!busy){Text(NailFinger.entries[index].title)}
                            DropdownMenu(expanded,onDismissRequest={expanded=false}){NailFinger.entries.forEachIndexed{target,finger->
                                DropdownMenuItem(text={Text(finger.title)},onClick={val list=order.toMutableList();val old=list[target];list[target]=list[index];list[index]=old;order=list;expanded=false})
                            }}
                        }
                    }
                }
            }
        }
        Button(onClick={if(image!=null){busy=true;job=scope.launch{
            try{val directory=withContext(Dispatchers.IO){NailKitStore.save(context,image,order,selection)};onAccepted(directory)}
            catch(cancel:CancellationException){throw cancel}catch(_:Exception){message="预处理保存失败，请重试"}finally{busy=false}
        }}},enabled=order.size==5 && !busy && !manual,modifier=Modifier.fillMaxWidth().padding(top=12.dp).testTag("accept-nail-kit")){Text("使用这组五片")}
    }
    DisposableEffect(Unit){onDispose{job?.cancel()}}
}
