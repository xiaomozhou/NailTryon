package com.example.nailtryon

import android.content.Context
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeedreamFusionTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun photo()=javaClass.getResourceAsStream("/nail-detection/hand-1.jpg")!!.use{BitmapFactory.decodeStream(it)!!}
    @Test fun realHandModelsProduceAnatomicalOrderAndGuideWithoutModifyingOriginal()= runBlocking<Unit> {
        val hand=photo();val original=hand.copy(Bitmap.Config.ARGB_8888,false)
        val check=inspectAlbumHand(context,hand);assertTrue(check.accepted)
        val nails=check.nails!!
        // Anatomical order remains the same even if instance segmentation returns shuffled regions.
        fun model(name:String)=context.assets.open("models/$name").use{it.readBytes()}
        val hands=HandLandmarkDetector(model("palm_detection_full_Nx3x192x192.onnx"),model("hand_landmark_full_Nx3x224x224.onnx")).use{it.detect(hand)}
        val reordered=AlbumHandPolicy.inspect(hands,hand.width,hand.height,nails.copy(regions=nails.regions.reversed())).nails!!
        assertEquals(nails.regions.map{it.center},reordered.regions.map{it.center})
        val guide=SeedreamFusion.guide(hand,nails)
        assertTrue(hand.sameAs(original));assertFalse(guide.sameAs(hand))
        assertEquals(hand.width,guide.width);assertEquals(hand.height,guide.height)
        val kit=NailKitStore.catalogReference(context,NailCatalog.styles.first().drawableName)
        val request=SeedreamTryOnRequest(data(hand),data(kit),SeedreamPrompt.forStyle(NailCatalog.styles.first())+" 输出宽高比 ${hand.width}:${hand.height}，与图1相同的构图。",data(guide))
        File("build/reports/fusion-live").apply{mkdirs()}.let{folder->
            File(folder,"request.json").writeText(Json.encodeToString(SeedreamTryOnRequest.serializer(),request))
            File(folder,"hand.png").outputStream().use{hand.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(folder,"guide.png").outputStream().use{guide.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun invalidHandIsRejectedBeforeCloudGeneration()=runBlocking<Unit> {
        val blank=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        try {
            SeedreamFusion.generate(context,blank,blank,NailCatalog.styles.first(),SeedreamTryOnClient("http://127.0.0.1:1/not-called"))
            fail("Invalid hand must not generate")
        } catch(error:IllegalArgumentException){assertTrue(error.message!!.contains("指甲清晰可见"))}
    }
    private fun data(bitmap:Bitmap):String {
        val output=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,output)
        return "data:image/png;base64,"+android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP)
    }
    @Test fun resolutionChangesPreserveSourceDimensionsButChangedCompositionIsRejected() {
        val hand=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888)
        val output=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        val normalized=SeedreamFusion.normalizeResult(hand,output)
        assertEquals(300,normalized.width);assertEquals(400,normalized.height);assertEquals(Color.RED,normalized.getPixel(100,100))
        assertThrows(IllegalArgumentException::class.java){SeedreamFusion.normalizeResult(hand,Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888))}
        assertThrows(IllegalArgumentException::class.java){SeedreamFusion.normalizeResult(hand,Bitmap.createBitmap(3,4,Bitmap.Config.ARGB_8888))}
    }
    @Test fun fullProductionPipelinePostsRealGuideAndParsesImageBytesAndRejectsHttpErrors()= runBlocking<Unit> {
        val server=ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1"))
        var received:SeedreamTryOnRequest?=null;var status=200
        val failures=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val dimensions=photo()
        val result=Bitmap.createBitmap(dimensions.width,dimensions.height,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val worker=thread(isDaemon=true) {
            try { repeat(2) {
                server.accept().use { socket ->
                    val input=socket.getInputStream()
                    fun line():String {val bytes=java.io.ByteArrayOutputStream();while(true){val b=input.read();check(b>=0);if(b==10)break;bytes.write(b)};return bytes.toString("ISO-8859-1").trimEnd('\r')}
                    line();val headers=mutableMapOf<String,String>()
                    while(true){val value=line();if(value.isEmpty())break;headers[value.substringBefore(':').lowercase()]=value.substringAfter(':').trim()}
                    val bytesIn=ByteArray(headers.getValue("content-length").toInt());var offset=0
                    while(offset<bytesIn.size){val n=input.read(bytesIn,offset,bytesIn.size-offset);check(n>0);offset+=n}
                    received=Json.decodeFromString<SeedreamTryOnRequest>(String(bytesIn,Charsets.UTF_8))
                    assertEquals("demo",headers["x-client-token"])
                    assertEquals("ap-northeast-1", headers["x-region"])
                    val payload=if(status==200)"""{"imageDataUrl":"${data(result)}","model":"${SeedreamPrompt.defaultModel}"}""" else "{}"
                    val bytes=payload.toByteArray()
                    socket.getOutputStream().use { out ->out.write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes);out.flush() }
                }
            }}catch(error:Throwable){failures.set(error)}
        }
        try {
            val hand=photo();val kit=NailKitStore.catalogReference(context,NailCatalog.styles.first().drawableName)
            val client=SeedreamTryOnClient("http://127.0.0.1:${server.localPort}/tryon","demo", region = "ap-northeast-1")
            val generated=SeedreamFusion.generate(context,hand,kit,NailCatalog.styles.first(),client)
            assertFalse(generated.onDevice)
            assertEquals(hand.width,generated.bitmap.width);assertEquals(hand.height,generated.bitmap.height)
            assertEquals(Color.BLUE,generated.bitmap.getPixel(50,50));assertNotNull(received!!.guideImage)
            assertTrue(received!!.prompt.contains("拇指、食指、中指、无名指、小指"))
            assertTrue(received!!.prompt.contains("${hand.width}:${hand.height}"))
            status = 502
            val fallback = SeedreamFusion.generate(context, hand, kit, NailCatalog.styles.first(), client)
            assertTrue(fallback.onDevice)
            assertFalse(hand.sameAs(fallback.bitmap))
        }finally{server.close();worker.join(5000)}
        failures.get()?.let{throw it}
    }
}
