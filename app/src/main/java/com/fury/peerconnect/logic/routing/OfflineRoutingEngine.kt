package com.fury.peerconnect.logic.routing

import android.content.Context
import android.util.Log
import com.fury.peerconnect.logic.DistanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * High-performance, 100% offline routing engine.
 * Calculates road-following navigation paths using local A* search over an
 * embedded road network graph derived from OpenStreetMap regional extracts.
 *
 * Runs without cellular network, SIM card, Wi-Fi, or internet connection.
 * Provides explicit status reporting for out-of-coverage coordinates,
 * missing routing data, disconnected road components, and invalid coordinates.
 */
class OfflineRoutingEngine(
    val roadGraph: OfflineRoadGraph
) : MapRoutingEngine {

    constructor(context: Context) : this(OfflineRoadGraph.getInstance(context))

    enum class RoutingStatus {
        SUCCESS,
        ROUTING_DATA_UNAVAILABLE,   // Local road graph missing or empty
        OUT_OF_COVERAGE,           // Origin or destination outside road graph bounds
        NO_PATH_FOUND,             // Disconnected road network components
        INVALID_COORDINATES        // NaN or out-of-range coordinates
    }

    var lastRoutingStatus: RoutingStatus = RoutingStatus.SUCCESS
        private set

    override suspend fun calculateRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String
    ): MapRouteResult? = withContext(Dispatchers.Default) {
        // 1. Validate coordinates
        if (!isValidCoordinate(fromLat, fromLon) || !isValidCoordinate(toLat, toLon)) {
            Log.w(TAG, "Invalid coordinates provided: ($fromLat, $fromLon) -> ($toLat, $toLon)")
            lastRoutingStatus = RoutingStatus.INVALID_COORDINATES
            return@withContext null
        }

        // 2. Check if local road graph is provisioned and non-empty
        if (!roadGraph.isLoaded || roadGraph.nodeCount == 0) {
            Log.w(TAG, "Offline road network graph is not provisioned or empty")
            lastRoutingStatus = RoutingStatus.ROUTING_DATA_UNAVAILABLE
            return@withContext null
        }

        // 3. Check spatial coverage
        if (!roadGraph.isWithinCoverage(fromLat, fromLon) || !roadGraph.isWithinCoverage(toLat, toLon)) {
            Log.i(TAG, "Coordinates outside offline road graph coverage: ($fromLat, $fromLon) -> ($toLat, $toLon)")
            lastRoutingStatus = RoutingStatus.OUT_OF_COVERAGE
            return@withContext null
        }

        // 4. Snap coordinates to nearest road network nodes
        val startNode = roadGraph.findNearestNode(fromLat, fromLon)
        val endNode = roadGraph.findNearestNode(toLat, toLon)

        if (startNode == null || endNode == null) {
            Log.w(TAG, "Could not snap coordinates to nearby road nodes")
            lastRoutingStatus = RoutingStatus.OUT_OF_COVERAGE
            return@withContext null
        }

        // 5. Execute A* search on road graph
        val pathNodes = roadGraph.findShortestPath(startNode.id, endNode.id)
        if (pathNodes.isNullOrEmpty()) {
            Log.w(TAG, "No connected path between nodes ${startNode.id} and ${endNode.id}")
            lastRoutingStatus = RoutingStatus.NO_PATH_FOUND
            return@withContext null
        }

        // 6. Build route polyline: [Origin] -> [Path Nodes] -> [Destination]
        val points = mutableListOf<LatLng>()
        points.add(LatLng(fromLat, fromLon))
        for (node in pathNodes) {
            points.add(LatLng(node.lat, node.lon))
        }
        points.add(LatLng(toLat, toLon))

        // 7. Calculate exact cumulative road distance along coordinates
        var totalDistanceMeters = 0.0
        for (i in 0 until points.size - 1) {
            totalDistanceMeters += DistanceEngine.calculateHaversineDistance(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
        }

        // Standard tactical walking speed: ~1.35 m/s (4.86 km/h)
        val durationSeconds = totalDistanceMeters / 1.35

        lastRoutingStatus = RoutingStatus.SUCCESS
        Log.i(TAG, "OFFLINE_ROUTE_SUCCESS: ${points.size} points, ${totalDistanceMeters.toInt()}m, ${durationSeconds.toInt()}s")
        MapRouteResult(
            distanceMeters = totalDistanceMeters,
            durationSeconds = durationSeconds,
            points = points
        )
    }

    override fun formatRouteSummary(result: MapRouteResult): String {
        val distStr = DistanceEngine.formatDistance(result.distanceMeters)
        val minutes = (result.durationSeconds / 60.0).roundToInt()
        return when {
            minutes < 1 -> "$distStr walk (Offline Road Graph)"
            minutes == 1 -> "$distStr • ~1 min walk (Offline)"
            else -> "$distStr • ~$minutes min walk (Offline)"
        }
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return !lat.isNaN() && !lon.isNaN() &&
                lat >= -90.0 && lat <= 90.0 &&
                lon >= -180.0 && lon <= 180.0 &&
                !(lat == 0.0 && lon == 0.0)
    }

    companion object {
        private const val TAG = "OfflineRoutingEngine"
    }
}
