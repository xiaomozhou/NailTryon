package com.example.nailtryon

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

internal object HandPolicyFixture {
    fun left():HandObservation {
        val xy=listOf(500 to 850,660 to 790,770 to 740,850 to 680,920 to 620,
            640 to 560,690 to 380,730 to 270,760 to 180,
            500 to 540,510 to 350,530 to 210,540 to 100,
            370 to 560,360 to 390,355 to 270,350 to 180,
            270 to 610,240 to 480,215 to 380,200 to 300)
        return HandObservation(xy.map{HandJoint(it.first.toFloat(),it.second.toFloat())},.99f,.01f)
    }
    fun nails(hand:HandObservation=left()):NailDetection {
        val regions=listOf(4,8,12,16,20).map { index ->
            val tip=hand.points[index];val dip=hand.points[index-1]
            val x=(dip.x*.4f+tip.x*.6f).roundToInt();val y=(dip.y*.4f+tip.y*.6f).roundToInt()
            val b=NailBox(x-10,y-15,x+10,y+15)
            NailMaskGeometry.region(ByteArray(600){(-1).toByte()},b,.95f,1000,1000)!!
        }
        return NailDetection(1000,1000,regions)
    }
}

class AlbumHandPolicyTest {
    @Test fun correctLeftDorsalHandPassesOnlyAfterPoseThenFiveMatchedMasks() {
        val order=mutableListOf<String>()
        val result=AlbumHandPolicy.evaluate(1000,1000,{order.add("pose");listOf(HandPolicyFixture.left())},
            {order.add("nails");HandPolicyFixture.nails()})
        assertEquals(listOf("pose","nails"),order)
        assertTrue(result.accepted)
    }
    @Test fun noHandOrMultipleHandsStopBeforeNailInference() {
        listOf(emptyList(),List(2){HandPolicyFixture.left()}).forEach { hands ->
            var called=false
            val result=AlbumHandPolicy.evaluate(1000,1000,{hands},{called=true;HandPolicyFixture.nails()})
            assertFalse(called);assertFalse(result.accepted)
            assertTrue(result.problems.isNotEmpty())
        }
    }
    @Test fun rightHandAndLeftPalmAreRejectedIndependently() {
        val left=HandPolicyFixture.left()
        val right=left.copy(points=left.points.map{it.copy(x=1000-it.x)},mirroredLeftProbability=.99f)
        assertTrue(AlbumHandPolicy.poseProblems(listOf(right),1000,1000).contains(HandPhotoProblem.LEFT_REQUIRED))
        val palm=left.copy(points=left.points.map{it.copy(x=1000-it.x)})
        assertTrue(AlbumHandPolicy.poseProblems(listOf(palm),1000,1000).contains(HandPhotoProblem.BACK_REQUIRED))
        assertFalse(AlbumHandPolicy.poseProblems(listOf(palm),1000,1000).contains(HandPhotoProblem.LEFT_REQUIRED))
    }
    @Test fun joinedBentAndCrossedFingersAreRejected() {
        val left=HandPolicyFixture.left()
        val variants=listOf(
            left.points.toMutableList().apply{this[8]=this[12].copy(x=this[12].x+5)},
            left.points.toMutableList().apply{this[8]=this[5].copy(y=this[5].y+60)},
            left.points.toMutableList().apply{val a=this[8];this[8]=this[12];this[12]=a})
        variants.forEach { points -> assertTrue(AlbumHandPolicy.poseProblems(listOf(left.copy(points=points)),1000,1000).contains(HandPhotoProblem.FINGERS_REQUIRED)) }
    }
    @Test fun uncertainNonFiniteAndClippedObservationsCannotPass() {
        val left=HandPolicyFixture.left()
        assertTrue(AlbumHandPolicy.poseProblems(listOf(left.copy(presence=.6f)),1000,1000).contains(HandPhotoProblem.NO_HAND))
        assertTrue(AlbumHandPolicy.poseProblems(listOf(left.copy(mirroredLeftProbability=.5f)),1000,1000).contains(HandPhotoProblem.LEFT_REQUIRED))
        assertTrue(AlbumHandPolicy.poseProblems(listOf(left.copy(points=left.points.map{it.copy(x=Float.NaN)})),1000,1000).contains(HandPhotoProblem.NO_HAND))
        val clipped=left.points.toMutableList().apply{this[4]=this[4].copy(x=999f)}
        assertTrue(AlbumHandPolicy.poseProblems(listOf(left.copy(points=clipped)),1000,1000).contains(HandPhotoProblem.CLIPPED))
    }
    @Test fun missingHiddenWeakDuplicateAndUnrelatedNailsCannotPass() {
        val left=HandPolicyFixture.left();val nails=HandPolicyFixture.nails()
        val unrelated=nails.regions.map{it.copy(center=NailPoint(500f,800f))}
        listOf(nails.copy(regions=nails.regions.take(4)),nails.copy(regions=List(5){nails.regions[0]}),
            nails.copy(regions=unrelated),nails.copy(regions=nails.regions.map{it.copy(confidence=.55f)}),
            nails.copy(width=1200)).forEach { detection ->
            val result=AlbumHandPolicy.inspect(listOf(left),1000,1000,detection)
            assertFalse(result.accepted);assertTrue(result.problems.contains(HandPhotoProblem.NAILS_REQUIRED))
        }
    }
    @Test fun geometryIsInvariantUnderRotationAndDoesNotInferHandFromScreenThumbPosition() {
        val left=HandPolicyFixture.left()
        val rotated=left.copy(points=left.points.map{it.copy(x=1000-it.y,y=it.x)})
        assertTrue(AlbumHandPolicy.poseProblems(listOf(rotated),1000,1000).isEmpty())
        assertTrue(AlbumHandPolicy.inspect(listOf(rotated),1000,1000,HandPolicyFixture.nails(rotated)).accepted)
    }
}
