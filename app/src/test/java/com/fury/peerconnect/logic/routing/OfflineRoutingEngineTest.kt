package com.fury.peerconnect.logic.routing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoutingEngineTest {

    private val sampleRoadNetworkJson = """
        {
          "version": "1.0",
          "region": "Delhi NCR Sector",
          "bounds": {
            "minLat": 28.50,
            "maxLat": 28.70,
            "minLon": 77.15,
            "maxLon": 77.30
          },
          "nodes": [
            { "id": 1, "name": "Node A", "lat": 28.550, "lon": 77.200 },
            { "id": 2, "name": "Node B", "lat": 28.560, "lon": 77.210 },
            { "id": 3, "name": "Node C", "lat": 28.570, "lon": 77.220 },
            { "id": 4, "name": "Isolated Node", "lat": 28.600, "lon": 77.250 }
          ],
          "edges": [
            { "from": 1, "to": 2, "dist": 1500.0, "name": "A-B Link" },
            { "from": 2, "to": 3, "dist": 1600.0, "name": "B-C Link" }
          ]
        }
    """.trimIndent()

    @Test
    fun testRoadGraphLoading() {
        val graph = OfflineRoadGraph()
        val loaded = graph.loadFromJson(sampleRoadNetworkJson)

        assertTrue("Graph should load successfully", loaded)
        assertTrue("Graph isLoaded should be true", graph.isLoaded)
        assertEquals("Should parse 4 nodes", 4, graph.nodeCount)
        assertEquals("Should parse 2 undirected edges", 2, graph.edgeCount)

        assertTrue(graph.isWithinCoverage(28.56, 77.21))
        assertFalse(graph.isWithinCoverage(35.0, 75.0))
    }

    @Test
    fun testRoadGraphNearestNode() {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)

        val nearest = graph.findNearestNode(28.551, 77.201)
        assertNotNull(nearest)
        assertEquals(1L, nearest?.id)
    }

    @Test
    fun testShortestPathAStar() {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)

        // Path from 1 to 3 should go 1 -> 2 -> 3
        val path = graph.findShortestPath(1L, 3L)
        assertNotNull(path)
        assertEquals(3, path?.size)
        assertEquals(1L, path?.get(0)?.id)
        assertEquals(2L, path?.get(1)?.id)
        assertEquals(3L, path?.get(2)?.id)
    }

    @Test
    fun testShortestPathDisconnectedNodes() {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)

        // Node 4 is isolated; path should return null
        val path = graph.findShortestPath(1L, 4L)
        assertNull("Path to disconnected component must be null", path)
    }

    @Test
    fun testOfflineRoutingEngineSuccessfulRoute() = runBlocking {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)
        val engine = OfflineRoutingEngine(graph)

        val route = engine.calculateRoute(
            fromLat = 28.5505,
            fromLon = 77.2005,
            toLat = 28.5695,
            toLon = 77.2195
        )

        assertNotNull("Offline route must be calculated", route)
        assertEquals(OfflineRoutingEngine.RoutingStatus.SUCCESS, engine.lastRoutingStatus)
        assertTrue("Route should have at least 3 points", (route?.points?.size ?: 0) >= 3)
        assertTrue("Distance should be positive", (route?.distanceMeters ?: 0.0) > 0.0)
        assertTrue("Duration should be positive", (route?.durationSeconds ?: 0.0) > 0.0)

        val summary = engine.formatRouteSummary(route!!)
        assertTrue("Summary should contain 'walk' or 'Offline'", summary.contains("walk") || summary.contains("Offline"))
    }

    @Test
    fun testOfflineRoutingEngineOutOfCoverage() = runBlocking {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)
        val engine = OfflineRoutingEngine(graph)

        // Coordinates in London (far outside Delhi NCR coverage)
        val route = engine.calculateRoute(
            fromLat = 51.5074,
            fromLon = -0.1278,
            toLat = 51.5155,
            toLon = -0.1419
        )

        assertNull("Route outside coverage must return null", route)
        assertEquals(OfflineRoutingEngine.RoutingStatus.OUT_OF_COVERAGE, engine.lastRoutingStatus)
    }

    @Test
    fun testOfflineRoutingEngineRoutingDataUnavailable() = runBlocking {
        val emptyGraph = OfflineRoadGraph()
        val engine = OfflineRoutingEngine(emptyGraph)

        val route = engine.calculateRoute(
            fromLat = 28.55,
            fromLon = 77.20,
            toLat = 28.57,
            toLon = 77.22
        )

        assertNull("Route without road graph must return null", route)
        assertEquals(OfflineRoutingEngine.RoutingStatus.ROUTING_DATA_UNAVAILABLE, engine.lastRoutingStatus)
    }

    @Test
    fun testOfflineRoutingEngineInvalidCoordinates() = runBlocking {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)
        val engine = OfflineRoutingEngine(graph)

        val route = engine.calculateRoute(
            fromLat = Double.NaN,
            fromLon = 77.20,
            toLat = 28.57,
            toLon = 77.22
        )

        assertNull("NaN coordinates must return null", route)
        assertEquals(OfflineRoutingEngine.RoutingStatus.INVALID_COORDINATES, engine.lastRoutingStatus)
    }

    @Test
    fun testOfflineRoutingEngineDisconnectedPath() = runBlocking {
        val graph = OfflineRoadGraph()
        graph.loadFromJson(sampleRoadNetworkJson)
        val engine = OfflineRoutingEngine(graph)

        // Origin near node 1, destination near isolated node 4
        val route = engine.calculateRoute(
            fromLat = 28.5501,
            fromLon = 77.2001,
            toLat = 28.6001,
            toLon = 77.2501
        )

        assertNull("Disconnected road route must return null", route)
        assertEquals(OfflineRoutingEngine.RoutingStatus.NO_PATH_FOUND, engine.lastRoutingStatus)
    }
}
