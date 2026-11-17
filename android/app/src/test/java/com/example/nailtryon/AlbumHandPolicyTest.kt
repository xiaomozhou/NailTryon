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
    @Test fun matchesVisibleNailsToTheSameFingersOnEitherHand() {
        val left = HandPolicyFixture.left()
        val right = left.copy(points = left.points.map { it.copy(x = 1000 - it.x) }, mirroredLeftProbability = .99f)
        for (hand in listOf(left, right)) {
            val nails = HandPolicyFixture.nails(hand)
            val partial = nails.copy(regions = listOf(nails.regions[4], nails.regions[1]))
            val result = AlbumHandPolicy.inspect(listOf(hand), 1000, 1000, partial)
            assertTrue(result.accepted)
            assertEquals(listOf(NailFinger.INDEX, NailFinger.LITTLE), result.nails!!.regions.map { it.finger })
        }
    }

    @Test fun closeAndCroppedFingersDoNotBlockClearNails() {
        val left = HandPolicyFixture.left()
        val points = left.points.toMutableList().apply {
            this[7] = this[11].copy(x = this[11].x + 25)
            this[8] = this[12].copy(x = this[12].x + 25)
            this[4] = this[4].copy(x = 1020f)
        }
        val hand = left.copy(points = points)
        val original = HandPolicyFixture.nails()
        val nails = original.copy(regions = HandPolicyFixture.nails(hand.copy(
            points = points.toMutableList().apply { this[4] = left.points[4] },
        )).regions.drop(1))
        val result = AlbumHandPolicy.inspect(listOf(hand), 1000, 1000, nails)
        assertTrue(result.accepted)
        assertEquals(4, result.nails!!.regions.size)
        assertEquals(listOf(NailFinger.INDEX, NailFinger.MIDDLE, NailFinger.RING, NailFinger.LITTLE),
            result.nails.regions.map { it.finger })
    }

    @Test fun clearMasksRemainUsableWithoutReliableLandmarks() {
        val nails = HandPolicyFixture.nails()
        for (hands in listOf(emptyList(), listOf(HandPolicyFixture.left().copy(presence = .4f)))) {
            val result = AlbumHandPolicy.inspect(hands, 1000, 1000, nails)
            assertTrue(result.accepted)
            assertEquals(nails.regions.size, result.nails!!.regions.size)
            assertTrue(result.nails.regions.all { it.finger == null })
        }
    }

    @Test fun rejectsEmptyWeakAndWrongDimensionResults() {
        val nails = HandPolicyFixture.nails()
        for (invalid in listOf(nails.copy(regions = emptyList()),
                nails.copy(regions = nails.regions.map { it.copy(confidence = .55f) }),
                nails.copy(width = 1200))) {
            val result = AlbumHandPolicy.inspect(listOf(HandPolicyFixture.left()), 1000, 1000, invalid)
            assertFalse(result.accepted)
            assertEquals(listOf(HandPhotoProblem.NAILS_REQUIRED), result.problems)
        }
    }
}
