package com.fury.peerconnect.logic

import android.content.Context
import android.util.Log
import com.fury.peerconnect.data.TileCacheDbHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * Low-bandwidth background tile prefetcher using OpenFreeMap (no API key required).
 * Fetches and populates the local SQLite vector tile cache without blocking UI.
 */
class MapTilePrefetcher(private val context: Context) {

    private val dbHelper = TileCacheDbHelper.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetchDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val _isPrefetching = MutableStateFlow(false)
    val isPrefetching: StateFlow<Boolean> = _isPrefetching.asStateFlow()

    private val isRunning = AtomicBoolean(false)

    /**
     * Prefetches tiles for a radius around the given coordinates at the specified zoom levels.
     * Uses OpenFreeMap planet tiles — no API key required.
     */
    fun prefetchArea(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = DEFAULT_RADIUS_METERS,
        minZoom: Int = 10,
        maxZoom: Int = 14,
        onComplete: ((cachedCount: Int) -> Unit)? = null
    ) {
        if (!OfflineAuditLogger.isNetworkConnected(context)) {
            Log.d(TAG, "Prefetch skipped: Network offline")
            onComplete?.invoke(0)
            return
        }

        scope.launch(prefetchDispatcher) {
            _isPrefetching.value = true
            isRunning.set(true)
            var fetchedCount = 0

            try {
                for (z in minZoom..maxZoom) {
                    if (!isRunning.get() || !OfflineAuditLogger.isNetworkConnected(context)) break
                    val tiles = calculateTilesForRadius(latitude, longitude, radiusMeters, z)
                    for ((x, y) in tiles) {
                        if (!isRunning.get()) break
                        if (!dbHelper.hasTile(z, x, y)) {
                            val data = downloadTile(z, x, y)
                            if (data != null && data.isNotEmpty()) {
                                dbHelper.putTile(z, x, y, data)
                                fetchedCount++
                            }
                            delay(60) // Polite non-aggressive pacing
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during area prefetch", e)
            } finally {
                _isPrefetching.value = false
                isRunning.set(false)
                Log.i(TAG, "Prefetch completed. Newly cached tiles: $fetchedCount")
                onComplete?.invoke(fetchedCount)
            }
        }
    }

    /**
     * Prefetches tiles for current center viewport at zoom z-1, z, z+1.
     */
    fun prefetchViewport(latitude: Double, longitude: Double, currentZoom: Double) {
        val baseZ = currentZoom.roundToInt().coerceIn(0, 14)
        val zoomLevels = (baseZ - 1..baseZ + 1).filter { it in 0..14 }
        prefetchArea(
            latitude = latitude,
            longitude = longitude,
            radiusMeters = 800.0,
            minZoom = zoomLevels.first(),
            maxZoom = zoomLevels.last()
        )
    }

    /**
     * Downloads a single tile from OpenFreeMap (no API key required).
     */
    private fun downloadTile(z: Int, x: Int, y: Int): ByteArray? {
        OpenFreeMapHelper.resolveTemplateAsync()
        val url = OpenFreeMapHelper.getTileUrl(z, x, y)
        return executeHttpDownload(url)
    }

    private fun executeHttpDownload(urlString: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 5000
                setRequestProperty("User-Agent", "NeXoRa-Mesh-Prefetch/1.4")
                setRequestProperty("Accept", "application/x-protobuf, application/octet-stream, */*")
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(8192)
                    var n: Int
                    while (input.read(data).also { n = it } != -1) {
                        buffer.write(data, 0, n)
                    }
                    val bytes = buffer.toByteArray()
                    OfflineAuditLogger.logOutboundRequest(urlString, 200, bytes.size, false)
                    bytes
                }
            } else {
                OfflineAuditLogger.logOutboundRequest(urlString, code, 0, false)
                null
            }
        } catch (e: Exception) {
            OfflineAuditLogger.logOutboundRequest(urlString, -1, 0, false)
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun cancel() {
        isRunning.set(false)
        _isPrefetching.value = false
    }

    companion object {
        private const val TAG = "MapTilePrefetcher"

        const val DEFAULT_RADIUS_METERS = 2000.0

        fun calculateTilesForRadius(
            lat: Double,
            lon: Double,
            radiusMeters: Double,
            zoom: Int
        ): List<Pair<Int, Int>> {
            val deltaLat = radiusMeters / 111320.0
            val latRad = Math.toRadians(lat)
            val deltaLon = radiusMeters / (111320.0 * cos(latRad).coerceAtLeast(0.01))

            val minLat = (lat - deltaLat).coerceIn(-85.0511, 85.0511)
            val maxLat = (lat + deltaLat).coerceIn(-85.0511, 85.0511)
            val minLon = (lon - deltaLon).coerceIn(-180.0, 180.0)
            val maxLon = (lon + deltaLon).coerceIn(-180.0, 180.0)

            val minTileX = lonToTileX(minLon, zoom)
            val maxTileX = lonToTileX(maxLon, zoom)
            val minTileY = latToTileY(maxLat, zoom) // Y is inverted in TMS/slippy tiles
            val maxTileY = latToTileY(minLat, zoom)

            val tiles = mutableListOf<Pair<Int, Int>>()
            for (x in minTileX..maxTileX) {
                for (y in minTileY..maxTileY) {
                    tiles.add(Pair(x, y))
                }
            }
            return tiles
        }

        private fun lonToTileX(lon: Double, zoom: Int): Int {
            val n = 1 shl zoom
            return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        }

        private fun latToTileY(lat: Double, zoom: Int): Int {
            val n = 1 shl zoom
            val latRad = Math.toRadians(lat)
            return floor((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        }
    }
}
