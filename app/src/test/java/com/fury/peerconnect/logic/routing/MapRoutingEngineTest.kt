package com.fury.peerconnect.logic.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.maplibre.android.geometry.LatLng

class MapRoutingEngineTest {

    @Test
    fun testFormatRouteSummary() {
        val engine = OnlineRoutingEngine()
        val result1 = MapRouteResult(
            distanceMeters = 850.0,
            durationSeconds = 600.0,
            points = listOf(LatLng(0.0, 0.0), LatLng(0.01, 0.01))
        )
        val summary1 = engine.formatRouteSummary(result1)
        assertTrue(summary1.contains("850m") || summary1.contains("850 m"))
        assertTrue(summary1.contains("10 min walk"))

        val result2 = MapRouteResult(
            distanceMeters = 1500.0,
            durationSeconds = 45.0,
            points = listOf(LatLng(0.0, 0.0), LatLng(0.01, 0.01))
        )
        val summary2 = engine.formatRouteSummary(result2)
        assertTrue(summary2.contains("1.5 km") || summary2.contains("1.50 km"))
        assertTrue(summary2.contains("walk"))
    }

    @Test
    fun testInvalidCoordinatesReturnNull() = runBlocking {
        val engine = OnlineRoutingEngine()
        // Invalid latitude (> 90)
        val res1 = engine.calculateRoute(95.0, 0.0, 10.0, 10.0)
        assertNull(res1)

        // Invalid longitude (> 180)
        val res2 = engine.calculateRoute(10.0, 200.0, 10.0, 10.0)
        assertNull(res2)

        // NaN coordinates
        val res3 = engine.calculateRoute(Double.NaN, 0.0, 10.0, 10.0)
        assertNull(res3)

        // Both 0,0 coordinates
        val res4 = engine.calculateRoute(0.0, 0.0, 0.0, 0.0)
        assertNull(res4)
    }

    @Test
    fun testRouteResultDataClass() {
        val pts = listOf(LatLng(12.34, 56.78), LatLng(12.35, 56.79))
        val res = MapRouteResult(distanceMeters = 120.0, durationSeconds = 90.0, points = pts)
        assertEquals(120.0, res.distanceMeters, 0.01)
        assertEquals(90.0, res.durationSeconds, 0.01)
        assertEquals(2, res.points.size)
        assertEquals(12.34, res.points[0].latitude, 0.001)
    }
}
