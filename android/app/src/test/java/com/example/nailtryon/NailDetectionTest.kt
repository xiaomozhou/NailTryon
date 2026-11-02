package com.example.nailtryon

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NailDetectionTest {
    @Test fun oddPortraitAndLandscapeLetterboxingRoundTripToOriginalCoordinates() {
        listOf(751 to 983, 1089 to 903, 2048 to 1536).forEach { (w,h) ->
            val t=NailLetterbox(w,h,768,768)
            listOf(0f, .3f, 1f).forEach { f ->
                assertEquals(w*f,t.sourceX(w*f*t.gainX+t.left),.001f)
                assertEquals(h*f,t.sourceY(h*f*t.gainY+t.top),.001f)
            }
            assertTrue(t.left>=0 && t.top>=0)
        }
    }
    @Test fun maskSamplingUsesBilinearInterpolationInsteadOfNearestPixel() {
        assertEquals(.5f,NailPostprocess.sample(floatArrayOf(0f,1f,0f,1f),2,2,.5f,.2f),.0001f)
        assertEquals(0f,NailPostprocess.sample(floatArrayOf(0f,1f,0f,1f),2,2,-2f,0f),0f)
    }
    @Test fun decodeSuppressesDuplicateBoxesRejectsWeakAndInvalidScoresAndRetainsMaskShape() {
        val pred=Array(6){FloatArray(4)}
        for(i in 0..3) { pred[0][i]=32f;pred[1][i]=32f;pred[2][i]=20f;pred[3][i]=24f;pred[5][i]=1f }
        pred[4]=floatArrayOf(.95f,.9f,.2f,Float.NaN)
        val proto= arrayOf(Array(16){y-> FloatArray(16){x-> if(x in 7..8 && y in 6..9) 8f else -8f }})
        val result=NailPostprocess.decode(pred,proto,NailLetterbox(128,128,64,64))
        assertEquals(1,result.size)
        val nail=result.single()
        assertEquals(.95f,nail.confidence,0f)
        assertTrue(nail.mask.any { it.toInt()==0 })
        assertTrue(nail.mask.any { it.toInt()!=0 })
        assertTrue(nail.contour.all { it.x in 44f..84f && it.y in 40f..88f })
    }
    @Test fun largestComponentRemovesNoiseAndContourRetainsConcaveEdges() {
        val mask=ByteArray(30*30)
        for(y in 4..23) for(x in 4..23) if(!(x>=14 && y<=12)) mask[y*30+x]=(-1).toByte()
        mask[0]=(-1).toByte()
        val nail=NailMaskGeometry.region(mask,NailBox(10,20,40,50),.9f,100,100)!!
        assertEquals(0,nail.mask[0].toInt())
        assertTrue(nail.contour.any { it.x==24f && it.y==33f })
        val area=nail.contour.indices.sumOf { i ->
            val a=nail.contour[i];val b=nail.contour[(i+1)%nail.contour.size];(a.x*b.y-a.y*b.x).toDouble()
        }.let { abs(it)/2 }
        assertEquals(nail.mask.count{it.toInt()!=0}.toDouble(),area,.01)
        assertEquals(4,nail.orientedCorners.size)
        assertFalse(nail.touchesImageEdge)
    }
    @Test fun fiveRequiredAndClippedOrLowConfidenceMasksAreNeverReadyForAlignment() {
        val full=ByteArray(20*30){(-1).toByte()}
        val nail=NailMaskGeometry.region(full,NailBox(10,10,30,40),.9f,100,100)!!
        assertTrue(NailDetection(100,100,List(5){nail}).readyForAlignment)
        assertFalse(NailDetection(100,100,List(4){nail}).readyForAlignment)
        assertFalse(NailDetection(100,100,List(6){nail}).readyForAlignment)
        assertFalse(NailDetection(100,100,List(5){nail.copy(confidence=.59f)}).readyForAlignment)
        val edge=NailMaskGeometry.region(full,NailBox(0,0,20,30),.9f,100,100)!!
        assertTrue(edge.touchesImageEdge)
        assertFalse(NailDetection(100,100,List(5){edge}).readyForAlignment)
        assertNull(NailMaskGeometry.region(ByteArray(600),NailBox(0,0,20,30),.9f,100,100))
    }
    @Test fun principalAxisFollowsNailRotationAndHasOriginalPhotoCenter() {
        val mask=ByteArray(60*60)
        for(y in 0..59) for(x in 0..59) if(abs(x-y)<4 && x in 10..49 && y in 10..49) mask[y*60+x]=(-1).toByte()
        val region=NailMaskGeometry.region(mask,NailBox(100,200,160,260),.9f,1000,1000)!!
        assertEquals((PI/4).toFloat(),region.axisRadians,.02f)
        assertEquals(130f,region.center.x,.1f);assertEquals(230f,region.center.y,.1f)
    }
}
