package com.example.nailtryon

import org.junit.Assert.*
import org.junit.Test

internal object KitTestFixture {
    fun row(y:Int=200,reverse:Boolean=false):KitRow {
        val widths=if(reverse)listOf(30,42,48,45,70)else listOf(70,45,48,42,30)
        return KitRow(widths.mapIndexed{i,w->
            val b=NailBox(100+i*110-w/2,y-45,100+i*110+w/2,y+45)
            NailMaskGeometry.region(ByteArray(b.width*b.height){(-1).toByte()},b,.95f,800,800)!!
        })
    }
}
class NailKitLayoutTest {
    @Test fun lowerRowExplicitLeftWinsOverUpperRight() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        val choice=NailKitLayout.choose(rows,listOf(KitText("Right",NailPoint(20f,200f)),KitText("Left",NailPoint(20f,500f))),true)
        assertEquals(1,choice.rowIndex);assertFalse(choice.needsConfirmation)
    }
    @Test fun chineseAndPairedSingleLetterLabelsAreSupportedButSizeLIsNotLeft() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        for(left in listOf("左手","左"))assertEquals(1,NailKitLayout.choose(rows,listOf(KitText(left,NailPoint(20f,500f))),true).rowIndex)
        assertEquals(1,NailKitLayout.choose(rows,listOf(KitText("R",NailPoint(20f,200f)),KitText("L",NailPoint(20f,500f))),true).rowIndex)
        assertEquals(0,NailKitLayout.choose(rows,listOf(KitText("Size",NailPoint(20f,700f)),KitText("L",NailPoint(60f,700f))),true).rowIndex)
    }
    @Test fun unmarkedSelectsOneWholeRowAndOcrFailureOrConflictingLabelsRequiresConfirmation() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        assertEquals(0,NailKitLayout.choose(rows,emptyList(),true).rowIndex)
        assertTrue(NailKitLayout.choose(rows,emptyList(),false).needsConfirmation)
        val conflict=listOf(KitText("Left",NailPoint(20f,200f)),KitText("Right",NailPoint(20f,200f)))
        assertNull(NailKitLayout.choose(rows,conflict,true).rowIndex)
        assertNull(NailKitLayout.choose(rows,listOf(KitText("Right",NailPoint(20f,200f))),true).rowIndex)
    }
    @Test fun thumbCanBeEitherEndpointAndMiddleThreeKeepPhysicalOrderInsteadOfSizeSorting() {
        val direct=KitTestFixture.row();assertEquals(direct.regions,NailKitLayout.fingerOrder(direct))
        val reverse=KitTestFixture.row(reverse=true);assertEquals(reverse.regions.reversed(),NailKitLayout.fingerOrder(reverse))
        val equal=KitRow(List(5){direct.regions[1]});assertNull(NailKitLayout.fingerOrder(equal))
    }
    @Test fun tiltedTenPieceBoardIsSplitIntoTwoCompleteRowsWithoutMixingThem() {
        val rows=listOf(KitTestFixture.row(),KitTestFixture.row(500))
        val tilted=rows.flatMap{it.regions}.map{r->r.copy(center=NailPoint(r.center.x,r.center.y+r.center.x*.25f))}
        val groups=NailKitLayout.rows(tilted)
        assertEquals(2,groups.size);groups.forEach{assertEquals(5,it.regions.size)}
        assertEquals(rows[0].regions.map{it.center.x},groups[0].regions.map{it.center.x})
        assertTrue(NailKitLayout.rows(tilted.take(4)).isEmpty())
    }
}
