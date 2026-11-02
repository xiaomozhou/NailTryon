package com.example.nailtryon

import android.graphics.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NailDetectorModelTest {
    private fun photo(n:Int)=javaClass.getResourceAsStream("/nail-detection/hand-$n.jpg").use { BitmapFactory.decodeStream(it)!! }
    private fun detector()=NailDetector(File("src/main/assets/models/nail-seg.onnx").readBytes())
    @Test fun bundledWeightsMatchPinnedUpstreamFileAndAreNotATruncatedDownload() {
        val bytes=File("src/main/assets/models/nail-seg.onnx").readBytes()
        assertEquals(47417145,bytes.size)
        val hash=MessageDigest.getInstance("SHA-1").apply {
            update("blob ${bytes.size}\u0000".toByteArray());update(bytes)
        }.digest().joinToString(""){"%02x".format(it)}
        assertEquals("a72775888aab612bacffff4c48669a5f67b59dc7",hash)
    }
    private fun contains(nail:NailRegion,x:Int,y:Int):Boolean {
        val b=nail.box
        return x in b.left until b.right && y in b.top until b.bottom && nail.mask[(y-b.top)*b.width+x-b.left].toInt()!=0
    }
    @Test fun realModelFindsAllFiveNailsOnTwoHandsAndExcludesSkinAndBackground() {
        val cores=listOf(listOf(214 to 310,338 to 190,450 to 148,558 to 235,551 to 526),
            listOf(442 to 295,632 to 357,721 to 436,724 to 577,543 to 905))
        val outside=listOf(listOf(220 to 395,338 to 275,455 to 244,546 to 320,505 to 606,366 to 590,50 to 100),
            listOf(386 to 337,569 to 393,654 to 485,658 to 607,448 to 884,285 to 635,50 to 50))
        detector().use { detector ->
            for(n in 1..2) {
                val source=photo(n)
                val result=detector.detect(source)
                assertEquals("hand $n",5,result.regions.size)
                assertTrue("Five confident complete masks",result.readyForAlignment)
                cores[n-1].forEach { (x,y) -> assertEquals("Independent nail interior $n ($x,$y)",1,result.regions.count{contains(it,x,y)}) }
                outside[n-1].forEach { (x,y) -> assertFalse("Skin/background must stay outside $n ($x,$y)",result.regions.any{contains(it,x,y)}) }
                result.regions.forEach { nail ->
                    assertTrue(nail.contour.size>=8)
                    assertTrue(nail.contour.all { it.x>=0 && it.x<=source.width && it.y>=0 && it.y<=source.height })
                }
                val overlay=source.copy(Bitmap.Config.ARGB_8888,true)
                val canvas=Canvas(overlay)
                result.regions.forEach { nail ->
                    val path=Path();nail.contour.forEachIndexed { i,p -> if(i==0)path.moveTo(p.x,p.y)else path.lineTo(p.x,p.y) };path.close()
                    canvas.drawPath(path,Paint().apply{color=Color.GREEN;style=Paint.Style.STROKE;strokeWidth=2f})
                }
                val file=File("build/reports/nail-detection/hand-$n.png").apply{parentFile!!.mkdirs()}
                file.outputStream().use{overlay.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
    }
    @Test fun realModelDoesNotInventNailsOnBlankImage() {
        detector().use { detector ->
            val source=Bitmap.createBitmap(752,984,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(180,180,180))}
            assertTrue(detector.detect(source).regions.isEmpty())
        }
    }
    @Test fun realModelMaintainsOriginalCoordinatesWhenPhotoIsResized() {
        detector().use { detector ->
            val source=photo(2);val original=detector.detect(source)
            val smaller=Bitmap.createScaledBitmap(source,451,544,true)
            val resized=detector.detect(smaller)
            assertEquals(5,resized.regions.size)
            original.regions.forEach { a ->
                val b=resized.regions.minBy { kotlin.math.hypot(it.center.x*2-a.center.x,it.center.y*2-a.center.y) }
                assertEquals(a.center.x,b.center.x*2,6f);assertEquals(a.center.y,b.center.y*2,6f)
            }
        }
    }
}
