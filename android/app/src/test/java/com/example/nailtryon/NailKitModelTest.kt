package com.example.nailtryon

import android.graphics.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailKitModelTest {
    private fun model(name:String)=File("src/main/assets/models/$name").readBytes()
    @Test fun bundledObjectSegmentationModelsMatchPinnedPublisherChecksums() {
        listOf("mobile_sam_image_encoder.onnx" to "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749",
            "sam_mask_decoder_single.onnx" to "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d").forEach{(name,hash)->
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(model(name)).joinToString(""){"%02x".format(it)})
        }
    }
    @Test fun realObjectModelExtractsFivePressOnNailsOnSolidAndDecoratedCards() {
        val root=File("src/main/assets/nail-kits")
        val hints=Json.parseToJsonElement(File(root,"catalog-prompts.json").readText()).jsonObject
        for(name in listOf("nail_cherry","nail_pearls")) {
            val hint=hints.getValue(name).jsonObject
            val source=BitmapFactory.decodeFile(File("src/main/res/drawable",hint.getValue("source").jsonPrimitive.content).path)
            val prompts=hint.getValue("prompts").jsonArray.map{it.jsonObject}
            NailKitSegmenter(model("mobile_sam_image_encoder.onnx"),model("sam_mask_decoder_single.onnx"),source).use{engine->
                val regions=prompts.map{p->
                    val xy=p.getValue("point").jsonArray.map{it.jsonPrimitive.float};val box=p.getValue("box").jsonArray.map{it.jsonPrimitive.int}
                    val others=prompts.filter{it!=p}.map{v->val a=v.getValue("point").jsonArray;NailPoint(a[0].jsonPrimitive.float,a[1].jsonPrimitive.float)}
                    val region=engine.segment(NailPoint(xy[0],xy[1]),NailBox(box[0],box[1],box[2],box[3]),others)
                    assertNotNull("$name missing selected nail",region)
                    region!!
                }
                val clean=separateKitMasks(regions,source.width,source.height,prompts.map{val p=it.getValue("point").jsonArray;NailPoint(p[0].jsonPrimitive.float,p[1].jsonPrimitive.float)})
                assertEquals(5,clean.size)
                clean.forEach{assertTrue(it.confidence>=.7f);assertFalse(it.touchesImageEdge);assertTrue(it.mask.any{b->b.toInt()==0})}
            }
        }
    }
    @Test fun blankCardDoesNotInventFiveNailCutouts() {
        val blank=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        NailKitSegmenter(model("mobile_sam_image_encoder.onnx"),model("sam_mask_decoder_single.onnx"),blank).use{
            assertNull(it.segment(NailPoint(300f,400f),null))
        }
    }
    @Test fun actualManualPreparationServicePreservesFivePointOrderAndProducesUsableReference() = runBlocking {
        val source=BitmapFactory.decodeFile("src/main/res/drawable/nail_cherry.png")
        val points=listOf(NailPoint(899f,498f),NailPoint(795f,466f),NailPoint(700f,436f),NailPoint(615f,402f),NailPoint(535f,370f))
        val regions=preparePointedNails(RuntimeEnvironment.getApplication(),source,points)
        assertEquals(5,regions.size)
        regions.zip(points).forEach{(r,p)->
            assertTrue(p.x.toInt() in r.box.left until r.box.right && p.y.toInt() in r.box.top until r.box.bottom)
            assertTrue(r.mask[(p.y.toInt()-r.box.top)*r.box.width+p.x.toInt()-r.box.left].toInt()!=0)
        }
        val reference=NailKitStore.reference(source,regions)
        assertTrue(reference.width>reference.height)
        assertEquals(Color.WHITE,reference.getPixel(0,0))
    }

    @Test fun automaticUploadExtractsOneFivePieceRowFromRealFiveAndTenPieceImages() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        for(path in listOf("src/main/res/drawable/nail_cherry.png","src/main/assets/nail-kits/nail_cherry/reference.png")){
            val source=BitmapFactory.decodeFile(path)
            try {
                val directory=prepareCustomNailKit(context,source)
                val pieces=NailKitStore.loadPieces(context,NailStyle("custom","我的美甲","自选美甲",""),directory.path)
                try {
                    assertEquals(5,pieces.size)
                    for(piece in pieces.values){
                        val pixels=IntArray(piece.width*piece.height)
                        piece.getPixels(pixels,0,piece.width,0,0,piece.width,piece.height)
                        val opaque=pixels.filter{Color.alpha(it)>192}
                        assertTrue("Expected red nail texture, not the background: $path",opaque.isNotEmpty())
                        val red=opaque.count{Color.red(it)>Color.green(it)*1.4f && Color.red(it)>Color.blue(it)*1.3f}
                        assertTrue("Every piece should contain the chosen red nail: $path",red>opaque.size*.6)
                    }
                    assertEquals(directory,CustomNailKitStore(context).load())
                } finally { pieces.values.forEach{it.recycle()} }
            } finally { source.recycle() }
        }
    }
}
