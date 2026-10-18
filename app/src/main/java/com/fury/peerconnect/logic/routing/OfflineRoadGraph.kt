package com.fury.peerconnect.logic.routing

import android.content.Context
import android.util.Log
import com.fury.peerconnect.logic.DistanceEngine
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.PriorityQueue

/**
 * Thread-safe local road network graph representation for offline A* navigation.
 * Nodes and edges are provisioned from OpenStreetMap regional extracts without
 * runtime network access.
 */
class OfflineRoadGraph {

    data class GraphNode(
        val id: Long,
        val name: String,
        val lat: Double,
        val lon: Double
    )

    data class GraphEdge(
        val fromId: Long,
        val toId: Long,
        val distanceMeters: Double,
        val name: String
    )

    data class GraphBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    private val nodes = HashMap<Long, GraphNode>()
    private val adjacency = HashMap<Long, MutableList<GraphEdge>>()
    private var bounds: GraphBounds? = null

    val isLoaded: Boolean
        get() = nodes.isNotEmpty()

    val nodeCount: Int
        get() = nodes.size

    val edgeCount: Int
        get() = adjacency.values.sumOf { it.size } / 2

    fun loadFromJson(jsonString: String): Boolean {
        try {
            val root = JSONObject(jsonString)

            val boundsObj = root.optJSONObject("bounds")
            if (boundsObj != null) {
                bounds = GraphBounds(
                    minLat = boundsObj.optDouble("minLat", -90.0),
                    maxLat = boundsObj.optDouble("maxLat", 90.0),
                    minLon = boundsObj.optDouble("minLon", -180.0),
                    maxLon = boundsObj.optDouble("maxLon", 180.0)
                )
            }

            val nodesArray = root.optJSONArray("nodes") ?: return false
            synchronized(this) {
                nodes.clear()
                adjacency.clear()

                for (i in 0 until nodesArray.length()) {
                    val nObj = nodesArray.getJSONObject(i)
                    val id = nObj.optLong("id")
                    val name = nObj.optString("name", "Node $id")
                    val lat = nObj.optDouble("lat", Double.NaN)
                    val lon = nObj.optDouble("lon", Double.NaN)

                    if (!lat.isNaN() && !lon.isNaN()) {
                        nodes[id] = GraphNode(id, name, lat, lon)
                        adjacency[id] = mutableListOf()
                    }
                }

                val edgesArray = root.optJSONArray("edges")
                if (edgesArray != null) {
                    for (i in 0 until edgesArray.length()) {
                        val eObj = edgesArray.getJSONObject(i)
                        val from = eObj.optLong("from")
                        val to = eObj.optLong("to")
                        val dist = eObj.optDouble("dist", 0.0)
                        val name = eObj.optString("name", "Road")

                        if (nodes.containsKey(from) && nodes.containsKey(to)) {
                            // Compute exact distance if not specified
                            val effectiveDist = if (dist > 0.0) {
                                dist
                            } else {
                                val n1 = nodes[from]!!
                                val n2 = nodes[to]!!
                                DistanceEngine.calculateHaversineDistance(n1.lat, n1.lon, n2.lat, n2.lon)
                            }

                            // Bi-directional road graph edges
                            adjacency[from]?.add(GraphEdge(from, to, effectiveDist, name))
                            adjacency[to]?.add(GraphEdge(to, from, effectiveDist, name))
                        }
                    }
                }
            }

            Log.i(TAG, "Loaded offline road graph: ${nodes.size} nodes, ${adjacency.values.sumOf { it.size } / 2} edges")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse road graph JSON", e)
            return false
        }
    }

    /**
     * Checks if coordinates fall within the provisioned road graph bounds
     * with an allowable tolerance (default: 8,000 meters).
     */
    fun isWithinCoverage(lat: Double, lon: Double, toleranceMeters: Double = 8000.0): Boolean {
        val b = bounds ?: return isLoaded
        val latToleranceDeg = toleranceMeters / 111320.0
        val lonToleranceDeg = toleranceMeters / 111320.0

        return lat >= (b.minLat - latToleranceDeg) && lat <= (b.maxLat + latToleranceDeg) &&
                lon >= (b.minLon - lonToleranceDeg) && lon <= (b.maxLon + lonToleranceDeg)
    }

    /**
     * Finds nearest node in graph using Haversine distance.
     */
    fun findNearestNode(lat: Double, lon: Double, maxDistanceMeters: Double = 15000.0): GraphNode? {
        synchronized(this) {
            var bestNode: GraphNode? = null
            var minDistance = Double.MAX_VALUE

            for (node in nodes.values) {
                val dist = DistanceEngine.calculateHaversineDistance(lat, lon, node.lat, node.lon)
                if (dist < minDistance && dist <= maxDistanceMeters) {
                    minDistance = dist
                    bestNode = node
                }
            }
            return bestNode
        }
    }

    /**
     * Computes the shortest path between startNodeId and targetNodeId using A* search.
     * Heuristic: Haversine distance between nodes.
     */
    fun findShortestPath(startNodeId: Long, targetNodeId: Long): List<GraphNode>? {
        synchronized(this) {
            if (!nodes.containsKey(startNodeId) || !nodes.containsKey(targetNodeId)) {
                return null
            }
            if (startNodeId == targetNodeId) {
                return listOf(nodes[startNodeId]!!)
            }

            val targetNode = nodes[targetNodeId]!!
            val gScore = HashMap<Long, Double>()
            val fScore = HashMap<Long, Double>()
            val cameFrom = HashMap<Long, Long>()

            gScore[startNodeId] = 0.0
            val initialH = DistanceEngine.calculateHaversineDistance(
                nodes[startNodeId]!!.lat, nodes[startNodeId]!!.lon,
                targetNode.lat, targetNode.lon
            )
            fScore[startNodeId] = initialH

            // Priority queue comparing fScores
            val openSet = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
            openSet.add(Pair(startNodeId, initialH))

            val closedSet = HashSet<Long>()

            while (openSet.isNotEmpty()) {
                val current = openSet.poll() ?: break
                val currentId = current.first

                if (currentId == targetNodeId) {
                    // Reconstruct path
                    val path = mutableListOf<GraphNode>()
                    var curr: Long? = targetNodeId
                    while (curr != null) {
                        nodes[curr]?.let { path.add(0, it) }
                        curr = cameFrom[curr]
                    }
                    return path
                }

                if (closedSet.contains(currentId)) continue
                closedSet.add(currentId)

                val currentG = gScore[currentId] ?: Double.MAX_VALUE
                val neighbors = adjacency[currentId] ?: continue

                for (edge in neighbors) {
                    val neighborId = edge.toId
                    if (closedSet.contains(neighborId)) continue

                    val tentativeG = currentG + edge.distanceMeters
                    if (tentativeG < (gScore[neighborId] ?: Double.MAX_VALUE)) {
                        cameFrom[neighborId] = currentId
                        gScore[neighborId] = tentativeG

                        val neighborNode = nodes[neighborId] ?: continue
                        val h = DistanceEngine.calculateHaversineDistance(
                            neighborNode.lat, neighborNode.lon,
                            targetNode.lat, targetNode.lon
                        )
                        val estimatedTotal = tentativeG + h
                        fScore[neighborId] = estimatedTotal

                        openSet.add(Pair(neighborId, estimatedTotal))
                    }
                }
            }

            return null // No connected road path between components
        }
    }

    companion object {
        private const val TAG = "OfflineRoadGraph"
        const val DEFAULT_ROAD_NETWORK_ASSET = "routing/road_network.json"

        @Volatile
        private var instance: OfflineRoadGraph? = null

        fun getInstance(context: Context): OfflineRoadGraph {
            return instance ?: synchronized(this) {
                instance ?: OfflineRoadGraph().also { graph ->
                    try {
                        val json = context.assets.open(DEFAULT_ROAD_NETWORK_ASSET).use { input ->
                            BufferedReader(InputStreamReader(input)).use { it.readText() }
                        }
                        graph.loadFromJson(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load default road network asset: $DEFAULT_ROAD_NETWORK_ASSET", e)
                    }
                    instance = graph
                }
            }
        }
    }
}
