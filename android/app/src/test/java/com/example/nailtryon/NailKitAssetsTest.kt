package com.example.nailtryon

import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
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
class NailKitAssetsTest {
    @Test fun allTwentyCatalogStylesHaveFiveVerifiedNonOverlappingSourceAccurateAssetsAndReference() {
        val context=RuntimeEnvironment.getApplication();val assets=context.assets
        val prompts=Json.parseToJsonElement(assets.open("nail-kits/catalog-prompts.json").bufferedReader().use{it.readText()}).jsonObject
        assertEquals(20,NailCatalog.styles.size)
        for(style in NailCatalog.styles) {
            val folder="nail-kits/${style.drawableName}"
            val manifest=Json.parseToJsonElement(assets.open("$folder/manifest.json").bufferedReader().use{it.readText()}).jsonObject
            val sourceFile=File("src/main/res/drawable",manifest.getValue("source").jsonPrimitive.content)
            val hash=MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes()).joinToString(""){"%02x".format(it)}
            assertEquals(hash,manifest.getValue("sourceSha256").jsonPrimitive.content)
            val original=BitmapFactory.decodeFile(sourceFile.path)
            val seen=BooleanArray(original.width*original.height)
            val pieces=manifest.getValue("pieces").jsonArray
            assertEquals(NailFinger.entries.map{it.name},pieces.map{it.jsonObject.getValue("finger").jsonPrimitive.content})
            val hint=prompts.getValue(style.drawableName).jsonObject
            val marked=hint.getValue("leftMarked").jsonPrimitive.boolean
            assertEquals(if(marked)"explicit-left"else"unmarked-first-row",manifest.getValue("selection").jsonPrimitive.content)
            val expected=hint.getValue("prompts").jsonArray.let{if(hint.getValue("thumbAtStart").jsonPrimitive.boolean)it else it.reversed()}
            pieces.forEachIndexed { index,element ->
                val p=element.jsonObject;val b=p.getValue("sourceBox").jsonArray.map{it.jsonPrimitive.int}
                val image=assets.open("$folder/${p.getValue("file").jsonPrimitive.content}").use{BitmapFactory.decodeStream(it)}!!
                assertEquals(b[2]-b[0],image.width);assertEquals(b[3]-b[1],image.height)
                assertEquals(expected[index].jsonObject.getValue("point"),p.getValue("sourcePoint"))
                var area=0;var transparent=0
                for(y in 0 until image.height)for(x in 0 until image.width){
                    val pixel=image.getPixel(x,y)
                    if(Color.alpha(pixel)==0){transparent++;continue}
                    assertEquals(255,Color.alpha(pixel));area++
                    val sx=x+b[0];val sy=y+b[1];val offset=sy*original.width+sx
                    assertFalse("A source pixel cannot belong to two different nails: ${style.id}",seen[offset]);seen[offset]=true
                    val rgb=original.getPixel(sx,sy)
                    assertTrue(kotlin.math.abs(Color.red(pixel)-Color.red(rgb))<=6)
                    assertTrue(kotlin.math.abs(Color.green(pixel)-Color.green(rgb))<=6)
                    assertTrue(kotlin.math.abs(Color.blue(pixel)-Color.blue(rgb))<=6)
                }
                assertEquals(p.getValue("maskArea").jsonPrimitive.int,area)
                assertTrue(area>100 && transparent>0)
                val point=p.getValue("sourcePoint").jsonArray.map{it.jsonPrimitive.int}
                assertEquals(255,Color.alpha(image.getPixel(point[0]-b[0],point[1]-b[1])))
                assets.open("$folder/${p.getValue("contextFile").jsonPrimitive.content}").use{assertNotNull(BitmapFactory.decodeStream(it))}
            }
            assertTrue(pieces[0].jsonObject.getValue("maskArea").jsonPrimitive.int>pieces[4].jsonObject.getValue("maskArea").jsonPrimitive.int*1.1f)
            val reference=NailKitStore.catalogReference(context,style.drawableName)
            assertTrue(reference.width>reference.height);assertEquals(Color.WHITE,reference.getPixel(0,0))
        }
    }
}
