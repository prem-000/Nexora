package com.fury.peerconnect.logic

import android.util.Log
import com.fury.peerconnect.BuildConfig
import org.maplibre.android.geometry.LatLng
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Fetches real walking/driving routes from an OSRM server (no API key required).
 * Falls back gracefully when OSRM_BASE_URL is blank or the server is unreachable.
 *
 * Route cache: 60-second TTL per peer destination to avoid redundant requests on recompose.
 *
 * Usage:
 *   OsrmRouteEngine.fetchRoute(fromLat, fromLon, toLat, toLon)
 *   → OsrmResult(distanceMeters, durationSeconds, points)
 *   → null if offline, server error, or OSRM_BASE_URL is blank
 */
object OsrmRouteEngine {

    private const val TAG = "OsrmRouteEngine"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val CACHE_TTL_MS = 60_000L

    data class OsrmResult(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<LatLng>
    )

    private data class CacheEntry(
        val result: OsrmResult?,
        val fetchedAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Fetches the OSRM walking route between two coordinates.
     * Returns null if:
     * - OSRM_BASE_URL is blank
     * - No network
     * - Server error / timeout (≤5s)
     * - Route geometry is empty
     */
    fun fetchRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String = "foot"
    ): OsrmResult? {
        val baseUrl = BuildConfig.OSRM_BASE_URL.trim()
        if (baseUrl.isBlank()) {
            Log.d(TAG, "OSRM_BASE_URL is blank — real routing disabled")
            return null
        }

        // Cache key rounded to ~10m precision
        val key = "${(fromLat * 10000).toLong()}_${(fromLon * 10000).toLong()}" +
                "_${(toLat * 10000).toLong()}_${(toLon * 10000).toLong()}"

        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
            Log.d(TAG, "OSRM cache hit for key=$key")
            return cached.result
        }

        val url = buildUrl(baseUrl, profile, fromLon, fromLat, toLon, toLat)
        Log.i(TAG, "OSRM_FETCH: $url")

        val result = executeRequest(url)
        cache[key] = CacheEntry(result, System.currentTimeMillis())
        return result
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

    private fun executeRequest(urlString: String): OsrmResult? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "NeXoRa-Mesh-Router/1.4")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "OSRM returned HTTP $code")
                return null
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            parseOsrmResponse(body)
        } catch (e: Exception) {
            Log.w(TAG, "OSRM request failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parses the OSRM JSON response to extract distance, duration, and GeoJSON LineString points.
     *
     * OSRM response structure:
     * {
     *   "code": "Ok",
     *   "routes": [{
     *     "distance": 1234.5,
     *     "duration": 678.9,
     *     "geometry": {
     *       "type": "LineString",
     *       "coordinates": [[lon, lat], ...]
     *     }
     *   }]
     * }
     */
    private fun parseOsrmResponse(json: String): OsrmResult? {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") {
                Log.w(TAG, "OSRM code != Ok: ${root.optString("code")}")
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
                Log.w(TAG, "OSRM route has no geometry points")
                return null
            }

            Log.i(TAG, "OSRM_PARSED: distance=${distanceMeters.toInt()}m, duration=${durationSeconds.toInt()}s, points=${points.size}")
            OsrmResult(distanceMeters, durationSeconds, points)
        } catch (e: Exception) {
            Log.e(TAG, "OSRM parse error: ${e.message}")
            null
        }
    }

    /** Formats OSRM distance + duration into a user-friendly string. */
    fun formatRouteLabel(result: OsrmResult): String {
        val distStr = DistanceEngine.formatDistance(result.distanceMeters)
        val mins = (result.durationSeconds / 60.0).toInt()
        return when {
            mins < 1 -> "$distStr walk"
            mins == 1 -> "$distStr • ~1 min walk"
            else -> "$distStr • ~$mins min walk"
        }
    }

    /** Clears the route cache (useful for testing). */
    fun clearCache() = cache.clear()
}
