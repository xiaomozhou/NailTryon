package com.example.nailtryon

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private val Bg=Color(0xFFFBF9F7); private val Coral=Color(0xFFFF6168); private val Soft=Color(0xFFFFE9E9); private val Ink=Color(0xFF211F21); private val Muted=Color(0xFF777174)

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{NailTryOnApp()}}}

@Composable internal fun NailTryOnApp(
 generateTryOn: (suspend (Bitmap, Bitmap, NailStyle) -> Bitmap?)? = null,
 albumInspector: suspend (android.content.Context,Bitmap)->AlbumHandCheck = ::inspectAlbumHand,
 cloudClientOverride: SeedreamTryOnClient? = null,
 kitAnalyzer: suspend (android.content.Context,Bitmap)->NailKitAnalysis = ::analyzeNailKit,
){
 var category by remember{mutableStateOf(NailCatalog.categories.first())}; var selectedId by rememberSaveable{mutableStateOf<String?>(null)}; var showSheet by remember{mutableStateOf(false)}; var screen by remember{mutableStateOf("library")}; var history by remember{mutableStateOf(listOf<TryOnHistoryEntry>())}; var source by remember{mutableStateOf<Bitmap?>(null)}; var result by remember{mutableStateOf<GeneratedTryOn?>(null)}
 val context=LocalContext.current
 val historyStore=remember(context){TryOnHistoryStore.forContext(context.applicationContext)}
 var historyLoaded by remember{mutableStateOf(false)}
 LaunchedEffect(historyStore,screen){
  if(!historyLoaded||screen=="history"){
   history=withContext(Dispatchers.IO){historyStore.load()}
   historyLoaded=true
  }
 }
 val gallerySave=rememberGallerySave()
 val selected=NailCatalog.styles.firstOrNull{it.id==selectedId} ?: if(selectedId=="custom")NailStyle("custom","我的美甲","自选美甲","")else null
 var customNailUri by rememberSaveable{mutableStateOf<String?>(null)}
 var customKitDirectory by rememberSaveable{mutableStateOf<String?>(null)}
 val nailAlbumLauncher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->
  if(uri!=null){
   customNailUri=uri.toString();customKitDirectory=null;selectedId="custom";showSheet=false;screen="kit-review"
  }
 }
 val cloudClient = remember(cloudClientOverride) {
  cloudClientOverride ?: SeedreamTryOnClient(
   endpoint = "https://br-bonny-anoa-678c6d38.supabase.aidap-global.cn-beijing.volces.com/functions/v1/seedream-tryon",
   anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1wbGF0Zm9ybSIsInJvbGUiOiJhbm9uIiwiZXhwIjozNjkyMDcwNzM5fQ.Do_vXMyaGebYAZAudL7Jn-HiyneTM_1ksRSUrsrDk_0",
  )
 }
 var albumHandUri by rememberSaveable{mutableStateOf<String?>(null)}
 val albumLauncher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->if(uri!=null){showSheet=false;albumHandUri=uri.toString();screen="album-review"}}
 var generationError by remember{mutableStateOf<String?>(null)}
 var generationAttempt by remember{mutableIntStateOf(0)}
 var tryOnMode by remember{mutableStateOf(TryOnMode.AUTOMATIC)}
 val capturePhoto=rememberCameraCapture{bitmap->showSheet=false;result=null;tryOnMode=TryOnMode.AUTOMATIC;source=bitmap;screen="result"}
 LaunchedEffect(source,selected,screen,generationAttempt,tryOnMode){
  if(screen=="result"&&source!=null&&selected!=null){
   result=null;generationError=null
   try {
   val hand=source!!
   val style=selected!!
   val nail=withContext(Dispatchers.IO){runCatching{
    if(style.id=="custom")customKitDirectory?.let{BitmapFactory.decodeFile(java.io.File(it,"reference.png").absolutePath)}
    else NailKitStore.catalogReference(context,style.drawableName)
   }.getOrNull()}
   if(nail==null){withContext(Dispatchers.Main.immediate){Toast.makeText(context,"美甲预处理结果不可用，请重新选择",Toast.LENGTH_LONG).show()};screen="library";return@LaunchedEffect}
   val completed = if (generateTryOn != null) {
    GeneratedTryOn(generateTryOn(hand, nail, style) ?: error("试戴生成失败，请稍后重试"))
   } else {
    try {
     SeedreamFusion.generate(context, hand, nail, style, cloudClient, customKitDirectory, tryOnMode)
    } finally {
     nail.recycle()
    }
   }
   result=completed
   try {
    history=withContext(Dispatchers.IO){historyStore.save(style,completed.bitmap,completed.onDevice);historyStore.load()}
    historyLoaded=true
   }catch(cancelled:CancellationException){throw cancelled}
   catch(_:Exception){withContext(Dispatchers.Main.immediate){Toast.makeText(context,"试戴已生成，历史记录保存失败",Toast.LENGTH_LONG).show()}}
   } catch(cancelled:CancellationException){throw cancelled}
   catch(error:Exception){generationError=error.message?.takeIf{it.startsWith("请")||it.startsWith("试戴")}?:"试戴生成失败，请稍后重试";withContext(Dispatchers.Main.immediate){Toast.makeText(context,generationError,Toast.LENGTH_LONG).show()}}
  }
 }
 if(screen=="kit-review" && customNailUri!=null){
  NailKitReview(Uri.parse(customNailUri),onAccepted={directory->customKitDirectory=directory.absolutePath;screen="library";showSheet=true},
   onBack={screen="library"},analyze=kitAnalyzer)
  return
 }
 if(screen=="album-review" && albumHandUri!=null){
  AlbumHandReview(Uri.parse(albumHandUri),onAccepted={photo,_->result=null;tryOnMode=TryOnMode.AUTOMATIC;source=photo;albumHandUri=null;screen="result"},
   onCamera={capturePhoto()},onBack={albumHandUri=null;screen="library"},inspect=albumInspector)
  return
 }
 Scaffold(containerColor=Bg,bottomBar={NavigationBar(containerColor=Color.White){NavigationBarItem(selected=screen=="library",onClick={screen="library"},icon={Text("✦")},label={Text("美甲库")});NavigationBarItem(selected=screen=="history",onClick={screen="history"},icon={Text("◷")},label={Text("历史")})}}){pad->
  Box(Modifier.padding(pad).fillMaxSize()){when(screen){"history"->TryOnHistoryScreen(history,historyStore,historyLoaded);"result"->selected?.let{style->ResultScreen(style,result,generationError,onRetry={generationAttempt++},onLocal=if(tryOnMode==TryOnMode.LOCAL || generateTryOn!=null)null else { { tryOnMode=TryOnMode.LOCAL } },onCloud={
   tryOnMode=TryOnMode.CLOUD
   generationAttempt++
  },saving=gallerySave.saving,onSave={photo->gallerySave.save(photo){}},onBack={screen="library"})};else->LibraryScreen(category,{category=it},{selectedId=it.id;showSheet=true},onUploadNail={nailAlbumLauncher.launch("image/*")})}}
  if(showSheet)UploadSheet(onDismiss={showSheet=false},onAlbum={albumLauncher.launch("image/*")},onCamera={showSheet=false;capturePhoto()})
 }
}

internal fun decodeUri(context: android.content.Context, uri: Uri): Bitmap? {
    val decoded=if(android.os.Build.VERSION.SDK_INT>=28)runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,_,_->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull() else null
    return decoded ?: runCatching {context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}}.getOrNull()
}

@Composable private fun LibraryScreen(category:String,onCategory:(String)->Unit,onStyle:(NailStyle)->Unit,onUploadNail:()->Unit){
 Column(Modifier.fillMaxSize().padding(horizontal=16.dp)){Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=18.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){NailCatalog.categories.forEach{c->FilterChip(selected=c==category,onClick={onCategory(c)},label={Text(c.removeSuffix("美甲"))})}}
 Row(Modifier.fillMaxWidth().background(Soft,RoundedCornerShape(16.dp)).clickable{onUploadNail()},verticalAlignment=Alignment.CenterVertically){Text("＋",color=Coral,fontSize=22.sp,modifier=Modifier.padding(start=16.dp));Text("上传我的美甲图",color=Coral,fontSize=14.sp,modifier=Modifier.padding(12.dp));Spacer(Modifier.weight(1f));Text("›",color=Coral,fontSize=22.sp,modifier=Modifier.padding(end=16.dp))}
 Spacer(Modifier.height(14.dp)); LazyVerticalGrid(columns=GridCells.Fixed(2),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().testTag("nail-catalog")){items(NailCatalog.byCategory(category)){style->NailCard(style,onStyle)}}}
}

@Composable private fun NailCard(style:NailStyle,onStyle:(NailStyle)->Unit){val ctx=LocalContext.current;val res=ctx.resources.getIdentifier(style.drawableName,"drawable",ctx.packageName);Card(colors=CardDefaults.cardColors(Color.White),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(8.dp)){if(res!=0)Image(painterResource(res),contentDescription=style.name,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height(138.dp).background(Soft,RoundedCornerShape(14.dp))) else Box(Modifier.fillMaxWidth().height(138.dp).background(Soft,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text("美甲图",color=Muted,fontSize=13.sp)};Text(style.name,color=Ink,fontSize=14.sp,modifier=Modifier.padding(top=8.dp,start=4.dp));Text(style.category,color=Muted,fontSize=11.sp,modifier=Modifier.padding(start=4.dp,top=3.dp));Button(onClick={onStyle(style)},colors=ButtonDefaults.buttonColors(Coral),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().padding(top=6.dp)){Text("试戴",fontSize=12.sp)}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UploadSheet(onDismiss:()->Unit,onAlbum:()->Unit,onCamera:()->Unit){ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Color.White){Column(Modifier.padding(24.dp).fillMaxWidth()){Text("选择照片",fontSize=18.sp,color=Ink);ListItem(headlineContent={Text("拍照")},leadingContent={Text("◉",color=Coral)},modifier=Modifier.clickable{onCamera()});ListItem(headlineContent={Text("从相册选择")},leadingContent={Text("▧",color=Coral)},modifier=Modifier.clickable{onAlbum()});TextButton(onClick=onDismiss,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("取消",color=Muted)}}}}

@Composable
private fun ResultScreen(
    style: NailStyle,
    result: GeneratedTryOn?,
    error: String?,
    onRetry: () -> Unit,
    onLocal: (() -> Unit)?,
    onCloud: () -> Unit,
    saving: Boolean,
    onSave: (Bitmap) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 32.sp, modifier = Modifier.clickable { onBack() })
            Text("试戴效果", fontSize = 20.sp, color = Ink, modifier = Modifier.padding(start = 16.dp))
        }
        Spacer(Modifier.height(18.dp))
        when {
            error != null -> {
                Text(error, modifier = Modifier.testTag("generation-error"))
                Button(onClick = onRetry, modifier = Modifier.testTag("retry-generation")) { Text("重新生成") }
                if (onLocal != null) TextButton(onClick = onLocal) { Text("使用本机预览") }
            }
            result == null -> {
                Box(Modifier.fillMaxWidth().aspectRatio(.75f).background(Soft, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral)
                    Text("正在生成试戴效果", color = Muted, modifier = Modifier.padding(top = 82.dp))
                }
                if (onLocal != null) {
                    TextButton(onClick = onLocal, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("先看本机预览")
                    }
                }
            }
            else -> {
                Text(if (result.onDevice) "本机试戴预览" else "云端 AI 生成",
                    color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                if (result.onDevice) TextButton(onClick = onCloud, enabled = !saving) {
                    Text("使用云端 AI 生成")
                }
                TryOnPreview(result.bitmap, "试戴结果", Modifier.testTag("result-image"))
            }
        }
        Text(style.name, fontSize = 21.sp, color = Ink, modifier = Modifier.padding(top = 18.dp))
        Text(style.category, fontSize = 13.sp, color = Muted)
        Button(
            onClick = { result?.bitmap?.let(onSave) },
            enabled = result != null && !saving,
            colors = ButtonDefaults.buttonColors(Coral),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) { Text(if (saving) "保存中…" else "保存") }
    }
}
