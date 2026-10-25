package com.example.nailtryon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionGeometryTest {
    @Test fun createsFiveEvenlySpacedNails() { val result=FusionGeometry.placements(400f,600f); assertEquals(5,result.size); assertTrue(result.zipWithNext().all{it.second.left>it.first.left}) }
    @Test fun keepsNailsInsideCanvas() { FusionGeometry.placements(400f,600f).forEach{assertTrue(it.left>=0 && it.left+it.width<=400 && it.top+it.height<=600)} }
}
