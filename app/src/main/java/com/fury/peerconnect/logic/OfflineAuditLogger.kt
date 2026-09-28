package com.fury.peerconnect.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.fury.peerconnect.data.TileCacheDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Offline audit logger and network monitor.
 * Verifies and records outbound HTTP traffic and provides offline cache inspection diagnostics.
 *
 * In debug builds, call dumpCacheStats() to log:
 *  - Total tile count and cache size in bytes
 *  - POI cache entry count
 *  - Visible viewport tile coordinates
 */
object OfflineAuditLogger {
    private const val TAG = "OfflineAuditLogger"
    private const val CACHE_AUDIT_TAG = "NEXORA_CACHE_AUDIT"
    private const val MAX_LOG_ENTRIES = 200

    data class NetworkAuditEntry(
        val timestamp: Long,
        val url: String,
        val responseCode: Int,
        val bytesTransferred: Int,
        val wasCacheHit: Boolean
    )

    private val auditLogs = ConcurrentLinkedQueue<NetworkAuditEntry>()

    fun logOutboundRequest(
        url: String,
        responseCode: Int,
        bytes: Int,
        wasCacheHit: Boolean = false
    ) {
        val entry = NetworkAuditEntry(
            timestamp = System.currentTimeMillis(),
            url = url,
            responseCode = responseCode,
            bytesTransferred = bytes,
            wasCacheHit = wasCacheHit
        )
        auditLogs.add(entry)
        while (auditLogs.size > MAX_LOG_ENTRIES) {
            auditLogs.poll()
        }

        val prefix = if (wasCacheHit) "[LOCAL_CACHE_HIT]" else "[OUTBOUND_NET]"
        Log.d(TAG, "$prefix code=$responseCode bytes=$bytes url=$url")
    }

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun getAuditEntries(): List<NetworkAuditEntry> {
        return auditLogs.toList()
    }

    fun getFormattedAuditSummary(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.append("=== OFFLINE AUDIT LOG ===\n")
        auditLogs.forEach { entry ->
            val time = sdf.format(Date(entry.timestamp))
            val type = if (entry.wasCacheHit) "CACHE" else "NET"
            sb.append("[$time] [$type] HTTP ${entry.responseCode} (${entry.bytesTransferred}b) -> ${entry.url}\n")
        }
        return sb.toString()
    }

    /**
     * Dumps tile cache stats and recent audit entries to logcat.
     * Tag: NEXORA_CACHE_AUDIT
     *
     * Logs:
     * - Total tile rows in SQLite
     * - Total tile cache size in bytes
     * - POI cache entry count
     * - Viewport tile list (z,x,y) if viewportBounds provided
     * - Network audit summary
     *
     * @param context Application context
     * @param offlineMapManager Optional map manager — used to read visible viewport bounds
     */
    fun dumpCacheStats(context: Context, offlineMapManager: OfflineMapManager? = null) {
        val dbHelper = TileCacheDbHelper.getInstance(context)

        val tileCount = dbHelper.getTileCount()
        val totalBytes = dbHelper.getTotalSizeBytes()
        val poiCount = dbHelper.getPoiCacheCount()
        val totalKB = totalBytes / 1024.0
        val totalMB = totalKB / 1024.0

        Log.i(CACHE_AUDIT_TAG, "=== NEXORA CACHE AUDIT ===")
        Log.i(CACHE_AUDIT_TAG, "Tile cache rows   : $tileCount")
        Log.i(CACHE_AUDIT_TAG, "Tile cache size   : ${String.format(Locale.US, "%.2f", totalKB)} KB (${String.format(Locale.US, "%.2f", totalMB)} MB)")
        Log.i(CACHE_AUDIT_TAG, "POI cache entries : $poiCount")
        Log.i(CACHE_AUDIT_TAG, "Network online    : ${isNetworkConnected(context)}")

        // Viewport tiles
        val viewportBounds = offlineMapManager?.getVisibleViewportBounds()
        if (viewportBounds != null && viewportBounds.size == 5) {
            val latSouth = viewportBounds[0]
            val latNorth = viewportBounds[1]
            val lonWest  = viewportBounds[2]
            val lonEast  = viewportBounds[3]
            val zoom     = viewportBounds[4]
            val z = zoom.toInt().coerceIn(0, 14)
            val minX = lonToTileX(lonWest, z)
            val maxX = lonToTileX(lonEast, z)
            val minY = latToTileY(latNorth, z)
            val maxY = latToTileY(latSouth, z)

            Log.i(CACHE_AUDIT_TAG, "Viewport z=$z x=[$minX..$maxX] y=[$minY..$maxY]")
            val cachedInView = dbHelper.getCachedTilesInBounds(z, z, minX, maxX, minY, maxY)
            Log.i(CACHE_AUDIT_TAG, "Cached in viewport: ${cachedInView.size}/${(maxX - minX + 1) * (maxY - minY + 1)} tiles")
            cachedInView.take(20).forEach { (tz, tx, ty) ->
                Log.d(CACHE_AUDIT_TAG, "  cached: z=$tz x=$tx y=$ty")
            }
        }

        // Recent network audit
        val recentNet = auditLogs.filter { !it.wasCacheHit }.takeLast(10)
        if (recentNet.isNotEmpty()) {
            Log.i(CACHE_AUDIT_TAG, "Recent outbound requests (last ${recentNet.size}):")
            recentNet.forEach { Log.d(CACHE_AUDIT_TAG, "  HTTP ${it.responseCode} ${it.url}") }
        } else {
            Log.i(CACHE_AUDIT_TAG, "No outbound network requests recorded.")
        }
        Log.i(CACHE_AUDIT_TAG, "=========================")
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return (Math.floor((lon + 180.0) / 360.0 * n).toInt()).coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat)
        return (Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()).coerceIn(0, n - 1)
    }
}
