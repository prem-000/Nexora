package com.fury.peerconnect.logic.routing

import android.util.Log
import com.fury.peerconnect.BuildConfig
import com.fury.peerconnect.logic.DistanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Open online routing implementation using configurable OSRM endpoints.
 * Completely decoupled from MapLibre map rendering.
 */
class OnlineRoutingEngine(
    endpoint: String? = null
) : MapRoutingEngine {

    private val effectiveBaseUrl: String = when {
        !endpoint.isNullOrBlank() -> endpoint.trim()
        BuildConfig.OSRM_BASE_URL.isNotBlank() -> BuildConfig.OSRM_BASE_URL.trim()
        else -> DEFAULT_OSRM_URL
    }

    private data class CacheEntry(
        val result: MapRouteResult?,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun calculateRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String
    ): MapRouteResult? = withContext(Dispatchers.IO) {
        // Validate coordinates
        if (!isValidCoordinate(fromLat, fromLon) || !isValidCoordinate(toLat, toLon)) {
            Log.w(TAG, "Invalid coordinates provided for routing: ($fromLat, $fromLon) -> ($toLat, $toLon)")
            return@withContext null
        }

        // Cache lookup (~10m tolerance)
        val key = "${(fromLat * 10000).toLong()}_${(fromLon * 10000).toLong()}_${(toLat * 10000).toLong()}_${(toLon * 10000).toLong()}_$profile"
        val cached = cache[key]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
            Log.d(TAG, "Route cache hit for key: $key")
            return@withContext cached.result
        }

        val requestUrl = buildUrl(effectiveBaseUrl, profile, fromLon, fromLat, toLon, toLat)
        Log.i(TAG, "ROUTING_REQUEST: $requestUrl")

        val result = executeRequest(requestUrl)
        cache[key] = CacheEntry(result, System.currentTimeMillis())
        result
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return !lat.isNaN() && !lon.isNaN() &&
                lat >= -90.0 && lat <= 90.0 &&
                lon >= -180.0 && lon <= 180.0 &&
                !(lat == 0.0 && lon == 0.0)
    }

    private fun buildUrl(
        baseUrl: String,
        profile: String,
        fromLon: Double,
        fromLat: Double,
        toLon: Double,
        toLat: Double
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        return "$cleanBase/route/v1/$profile/" +
                "${String.format(java.util.Locale.US, "%.6f", fromLon)}," +
                "${String.format(java.util.Locale.US, "%.6f", fromLat)};" +
                "${String.format(java.util.Locale.US, "%.6f", toLon)}," +
                "${String.format(java.util.Locale.US, "%.6f", toLat)}" +
                "?geometries=geojson&overview=full&steps=false"
    }

    private fun executeRequest(urlString: String): MapRouteResult? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "NeXoRa-Mesh-Router/1.4")
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Routing service returned HTTP $statusCode")
                return null
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            parseRouteResponse(responseBody)
        } catch (e: Exception) {
            Log.w(TAG, "Routing request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRouteResponse(json: String): MapRouteResult? {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") {
                Log.w(TAG, "Routing service response code != Ok: ${root.optString("code")}")
                return null
            }

            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null

            val route = routes.getJSONObject(0)
            val distanceMeters = route.optDouble("distance", 0.0)
            val durationSeconds = route.optDouble("duration", 0.0)

            val geometry = route.optJSONObject("geometry") ?: return null
            val coordinates = geometry.optJSONArray("coordinates") ?: return null

            val points = mutableListOf<LatLng>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                val lon = coord.optDouble(0)
                val lat = coord.optDouble(1)
                if (!lon.isNaN() && !lat.isNaN()) {
                    points.add(LatLng(lat, lon))
                }
            }

            if (points.isEmpty()) {
                Log.w(TAG, "Route response contained no geometry points")
                return null
            }

            Log.i(TAG, "ROUTING_SUCCESS: ${points.size} points, ${distanceMeters.toInt()}m, ${durationSeconds.toInt()}s")
            MapRouteResult(distanceMeters, durationSeconds, points)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse route response: ${e.message}")
            null
        }
    }

    override fun formatRouteSummary(result: MapRouteResult): String {
        val distStr = DistanceEngine.formatDistance(result.distanceMeters)
        val minutes = (result.durationSeconds / 60.0).toInt()
        return when {
            minutes < 1 -> "$distStr walk"
            minutes == 1 -> "$distStr • ~1 min walk"
            else -> "$distStr • ~$minutes min walk"
        }
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        private const val TAG = "OnlineRoutingEngine"
        private const val DEFAULT_OSRM_URL = "https://router.project-osrm.org"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val CACHE_TTL_MS = 60_000L
    }
}
