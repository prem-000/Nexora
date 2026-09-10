package com.fury.peerconnect.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceEngineTest {

    @Test
    fun testHaversineDistance_samePoint_isZero() {
        val dist = DistanceEngine.calculateHaversineDistance(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun testHaversineDistance_knownCities() {
        // Distance between London (51.5074, -0.1278) and Paris (48.8566, 2.3522) is approx 343 km (343,000m)
        val dist = DistanceEngine.calculateHaversineDistance(51.5074, -0.1278, 48.8566, 2.3522)
        assertTrue("Distance should be around 343km, was $dist", dist in 340_000.0..346_000.0)
    }

    @Test
    fun testFormatDistance() {
        assertEquals("450 m", DistanceEngine.formatDistance(450.0))
        assertEquals("999 m", DistanceEngine.formatDistance(999.0))
        assertEquals("1.0 km", DistanceEngine.formatDistance(1000.0))
        assertEquals("2.5 km", DistanceEngine.formatDistance(2490.0))
        assertEquals("12.3 km", DistanceEngine.formatDistance(12345.0))
    }
}
