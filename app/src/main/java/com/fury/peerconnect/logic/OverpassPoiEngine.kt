package com.fury.peerconnect.logic

import android.util.Log
import com.fury.peerconnect.data.TileCacheDbHelper
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Queries the Overpass API (https://overpass-api.de/api/interpreter) for nearby
 * hospitals, pharmacies, and police stations — no API key required.
 *
 * Results are cached in SQLite (poi_cache table via TileCacheDbHelper) keyed by
 * rounded lat/lon + radius so they remain available when offline.
 *
 * Fallback: when offline and no cached data exists, returns an empty list silently.
 */
object OverpassPoiEngine {

    private const val TAG = "OverpassPoiEngine"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    const val DEFAULT_RADIUS_METERS = 3000

    enum class PoiType(val label: String, val osmKey: String, val osmValue: String) {
        HOSPITAL("Hospital", "amenity", "hospital"),
        PHARMACY("Pharmacy", "amenity", "pharmacy"),
        POLICE("Police", "amenity", "police")
    }

    data class PoiResult(
        val id: String,
        val type: PoiType,
        val name: String,
        val lat: Double,
        val lon: Double
    )

    // In-memory cache: keyed by "lat4_lon4_radius"
    private val memCache = ConcurrentHashMap<String, List<PoiResult>>()

    /**
     * Returns POIs near the given location.
     * Checks SQLite cache first, then Overpass API if online.
     * Returns empty list if offline and nothing cached.
     */
    fun fetchPois(
        context: Context,
        lat: Double,
        lon: Double,
        radiusMeters: Int = DEFAULT_RADIUS_METERS
    ): List<PoiResult> {
        val cacheKey = buildCacheKey(lat, lon, radiusMeters)

        // 1. In-memory cache
        memCache[cacheKey]?.let { return it }

        // 2. SQLite cache
        val dbHelper = TileCacheDbHelper.getInstance(context)
        val cachedJson = dbHelper.getPoisForArea(cacheKey)
        if (cachedJson != null) {
            val pois = parsePoisFromJson(cachedJson)
            memCache[cacheKey] = pois
            return pois
        }

        // 3. Online fetch
        if (!OfflineAuditLogger.isNetworkConnected(context)) {
            Log.d(TAG, "Overpass skipped: offline and no cached POIs for $cacheKey")
            return emptyList()
        }

        val rawJson = executeOverpassQuery(lat, lon, radiusMeters) ?: return emptyList()
        val pois = parseOverpassResponse(rawJson)

        // Store in SQLite and memory cache
        dbHelper.cachePois(cacheKey, rawJson)
        memCache[cacheKey] = pois
        Log.i(TAG, "OVERPASS: Fetched ${pois.size} POIs near ($lat, $lon) r=${radiusMeters}m")
        return pois
    }

    private fun buildCacheKey(lat: Double, lon: Double, radius: Int): String {
        // Round to ~500m grid to reuse nearby queries
        val latKey = (lat * 200).toLong()
        val lonKey = (lon * 200).toLong()
        return "${latKey}_${lonKey}_$radius"
    }

    private fun executeOverpassQuery(lat: Double, lon: Double, radiusMeters: Int): String? {
        val query = buildQuery(lat, lon, radiusMeters)
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(OVERPASS_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "NeXoRa-Mesh-POI/1.4")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
            val body = "data=${URLEncoder.encode(query, "UTF-8")}"
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                Log.w(TAG, "Overpass returned HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overpass request failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildQuery(lat: Double, lon: Double, radius: Int): String {
        val latF = String.format(java.util.Locale.US, "%.6f", lat)
        val lonF = String.format(java.util.Locale.US, "%.6f", lon)
        return """
            [out:json][timeout:10];
            (
              node["amenity"="hospital"](around:$radius,$latF,$lonF);
              node["amenity"="pharmacy"](around:$radius,$latF,$lonF);
              node["amenity"="police"](around:$radius,$latF,$lonF);
              way["amenity"="hospital"](around:$radius,$latF,$lonF);
              way["amenity"="pharmacy"](around:$radius,$latF,$lonF);
              way["amenity"="police"](around:$radius,$latF,$lonF);
            );
            out center;
        """.trimIndent()
    }

    /**
     * Parses the Overpass JSON response into PoiResult objects.
     */
    private fun parseOverpassResponse(json: String): List<PoiResult> {
        val results = mutableListOf<PoiResult>()
        return try {
            val root = JSONObject(json)
            val elements = root.optJSONArray("elements") ?: return emptyList()

            for (i in 0 until elements.length()) {
                val elem = elements.getJSONObject(i)
                val tags = elem.optJSONObject("tags") ?: continue
                val amenity = tags.optString("amenity", "") ?: continue
                val poiType = when (amenity) {
                    "hospital" -> PoiType.HOSPITAL
                    "pharmacy" -> PoiType.PHARMACY
                    "police" -> PoiType.POLICE
                    else -> continue
                }
                val name = tags.optString("name", poiType.label).let {
                    if (it.isBlank()) poiType.label else it
                }
                val elemType = elem.optString("type")
                val lat: Double
                val lon: Double
                if (elemType == "node") {
                    lat = elem.optDouble("lat", Double.NaN)
                    lon = elem.optDouble("lon", Double.NaN)
                } else {
                    // way: use center coords
                    val center = elem.optJSONObject("center") ?: continue
                    lat = center.optDouble("lat", Double.NaN)
                    lon = center.optDouble("lon", Double.NaN)
                }
                if (lat.isNaN() || lon.isNaN()) continue
                val id = "${elem.optLong("id")}"
                results.add(PoiResult(id, poiType, name, lat, lon))
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Overpass parse error: ${e.message}")
            emptyList()
        }
    }

    /** Serializes POI list to JSON string for SQLite storage (reuses Overpass response). */
    private fun parsePoisFromJson(json: String): List<PoiResult> = parseOverpassResponse(json)

    /** Clears in-memory cache (e.g. for testing). */
    fun clearMemoryCache() = memCache.clear()
}
