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

    @Test
    fun testCoordinateSmoother_emaCalculation() {
        val smoother = DistanceEngine.CoordinateSmoother(alpha = 0.3)
        // First fix initializes coordinates directly
        val fix1 = smoother.update(10.0, 20.0, 5.0f, 1000L)
        assertEquals(10.0, fix1.latitude, 0.0001)
        assertEquals(20.0, fix1.longitude, 0.0001)

        // Second fix: smoothed = 0.3 * new + 0.7 * old
        // lat = 0.3 * 20.0 + 0.7 * 10.0 = 6.0 + 7.0 = 13.0
        // lon = 0.3 * 30.0 + 0.7 * 20.0 = 9.0 + 14.0 = 23.0
        val fix2 = smoother.update(20.0, 30.0, 6.0f, 2000L)
        assertEquals(13.0, fix2.latitude, 0.0001)
        assertEquals(23.0, fix2.longitude, 0.0001)
    }

    @Test
    fun testAccuracyAcceptable_thresholds() {
        assertTrue(DistanceEngine.isAccuracyAcceptable(5.0f))
        assertTrue(DistanceEngine.isAccuracyAcceptable(24.9f))
        assertTrue(DistanceEngine.isAccuracyAcceptable(25.0f))

        org.junit.Assert.assertFalse(DistanceEngine.isAccuracyAcceptable(25.1f))
        org.junit.Assert.assertFalse(DistanceEngine.isAccuracyAcceptable(100.0f))
        org.junit.Assert.assertFalse(DistanceEngine.isAccuracyAcceptable(null))
        org.junit.Assert.assertFalse(DistanceEngine.isAccuracyAcceptable(0.0f))
    }

    @Test
    fun testIsFixStale() {
        val now = System.currentTimeMillis()
        org.junit.Assert.assertFalse(DistanceEngine.isFixStale(now - 2000L)) // 2s old -> fresh
        assertTrue(DistanceEngine.isFixStale(now - 15000L)) // 15s old -> stale
    }

    @Test
    fun testTileCoordinateCalculation() {
        val tiles = MapTilePrefetcher.calculateTilesForRadius(
            lat = 37.7749,
            lon = -122.4194,
            radiusMeters = 500.0,
            zoom = 12
        )
        assertTrue("Tiles list should not be empty", tiles.isNotEmpty())
        for ((x, y) in tiles) {
            assertTrue(x in 0..(1 shl 12))
            assertTrue(y in 0..(1 shl 12))
        }
    }
}
