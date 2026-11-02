package com.example.nailtryon

import android.graphics.*
import java.io.File
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
class NailKitStoreTest {
    @Test fun exportsExactlyFiveFingerPngsContextsReferenceAndOriginalCoordinatesWithoutMutatingSource() {
        val source=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.MAGENTA)}
        val regions=KitTestFixture.row().regions
        val directory=NailKitStore.save(RuntimeEnvironment.getApplication(),source,regions,"explicit-left")
        val manifest=Json.parseToJsonElement(File(directory,"manifest.json").readText()).jsonObject
        assertEquals("explicit-left",manifest.getValue("selection").jsonPrimitive.content)
        assertEquals(800,manifest.getValue("width").jsonPrimitive.int)
        assertEquals(NailFinger.entries.map{it.name},manifest.getValue("pieces").jsonArray.map{it.jsonObject.getValue("finger").jsonPrimitive.content})
        regions.forEachIndexed { i,region ->
            val file=File(directory,"${NailFinger.entries[i].name}.png");val cut=BitmapFactory.decodeFile(file.path)
            assertEquals(region.box.width,cut.width);assertEquals(region.box.height,cut.height)
            assertEquals(Color.MAGENTA,cut.getPixel(cut.width/2,cut.height/2))
            assertTrue(File(directory,"${NailFinger.entries[i].name}-context.png").isFile)
            val saved=manifest.getValue("pieces").jsonArray[i].jsonObject
            assertEquals(region.box.left,saved.getValue("sourceBox").jsonArray[0].jsonPrimitive.int)
            assertEquals(region.contour.size,saved.getValue("contour").jsonArray.size)
        }
        val reference=BitmapFactory.decodeFile(File(directory,"reference.png").path)
        assertTrue(reference.width>reference.height);assertEquals(Color.WHITE,reference.getPixel(0,0))
        assertEquals(Color.MAGENTA,source.getPixel(0,0))
        assertEquals(5,directory.listFiles()!!.count{it.name in NailFinger.entries.map{f->"${f.name}.png"}})
        assertTrue(File(directory,"source.png").isFile)
    }
    @Test fun cutoutKeepsOriginalColorAndUsesActualSilhouetteInsteadOfRectangle() {
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(40,80,120))}
        val box=NailBox(20,20,60,80);val mask=ByteArray(40*60)
        for(y in 5..54)for(x in 5..34)if((x-20)*(x-20)/225f+(y-30)*(y-30)/625f<=1)mask[y*40+x]=(-1).toByte()
        val region=NailMaskGeometry.region(mask,box,.95f,100,100)!!
        val cut=nailCutout(source,region)
        assertEquals(0,Color.alpha(cut.getPixel(0,0)))
        assertEquals(source.getPixel(40,50),cut.getPixel(20,30))
        assertEquals(255,Color.alpha(source.getPixel(20,20)))
    }
    @Test fun overlapResolutionAssignsEachPixelToOnlyOneNailAndKeepsFiveUniqueInstances() {
        val regions=KitTestFixture.row().regions.toMutableList()
        val a=regions[0];val b=regions[1]
        val wide=NailBox(a.box.left,a.box.top,b.box.right,a.box.bottom)
        regions[0]=NailMaskGeometry.region(ByteArray(wide.width*wide.height){(-1).toByte()},wide,.95f,800,800)!!
        val separated=separateKitMasks(regions,800,800,listOf(a.center,b.center)+regions.drop(2).map{it.center})
        val occupied=mutableSetOf<Pair<Int,Int>>()
        separated.forEach{r->for(y in r.box.top until r.box.bottom)for(x in r.box.left until r.box.right){
            if(r.mask[(y-r.box.top)*r.box.width+x-r.box.left].toInt()!=0)assertTrue(occupied.add(x to y))
        }}
        assertEquals(5,separated.size)
    }
    @Test fun incompleteOrDuplicateSelectionCannotProducePreparedKit() {
        val source=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888)
        assertThrows(IllegalArgumentException::class.java){NailKitStore.save(RuntimeEnvironment.getApplication(),source,KitTestFixture.row().regions.take(4),"bad")}
        val duplicate=List(5){KitTestFixture.row().regions[0]}
        assertThrows(IllegalArgumentException::class.java){NailKitStore.save(RuntimeEnvironment.getApplication(),source,duplicate,"bad")}
    }
    @Test fun whitePaintHolesAreSealedButOuterBackgroundRemainsTransparent() {
        val regions=KitTestFixture.row().regions.toMutableList()
        val first=regions[0];val mask=first.mask.copyOf();val x=first.box.width/2;val y=first.box.height/2
        mask[y*first.box.width+x]=0
        regions[0]=first.copy(mask=mask)
        val prepared=separateKitMasks(regions,800,800)
        assertEquals(-1,prepared[0].mask[y*first.box.width+x].toInt())
    }
}
