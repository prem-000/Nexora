package com.fury.peerconnect.logic

import android.content.Context
import android.util.Log
import com.fury.peerconnect.data.TileCacheDbHelper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.regex.Pattern

/**
 * Embedded HTTP server bound to 127.0.0.1 on a dynamic port.
 * Serves vector tiles (/{z}/{x}/{y}.pbf) to MapLibre from local SQLite cache,
 * fetching and caching on-demand from OpenFreeMap (key-free) when online.
 *
 * Fallback chain:
 *  1. SQLite cache hit  → return immediately (fully offline)
 *  2. Cache miss + network available → fetch from OpenFreeMap, store, return
 *  3. Cache miss + no network → return transparent empty tile (HTTP 200, 0 bytes)
 */
class LocalTileServer private constructor(
    private val context: Context,
    val port: Int
) : NanoHTTPD("127.0.0.1", port) {

    private val dbHelper = TileCacheDbHelper.getInstance(context)
    private val tilePattern = Pattern.compile("^/(\\d+)/(\\d+)/(\\d+)\\.pbf$")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val matcher = tilePattern.matcher(uri)

        if (!matcher.matches()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val z = matcher.group(1)?.toIntOrNull() ?: return emptyTileResponse()
        val x = matcher.group(2)?.toIntOrNull() ?: return emptyTileResponse()
        val y = matcher.group(3)?.toIntOrNull() ?: return emptyTileResponse()

        // 1. Check local SQLite tile cache (fast offline path)
        val cachedBytes = dbHelper.getTile(z, x, y)
        if (cachedBytes != null && cachedBytes.isNotEmpty()) {
            OfflineAuditLogger.logOutboundRequest(
                url = uri,
                responseCode = 200,
                bytes = cachedBytes.size,
                wasCacheHit = true
            )
            return tileResponse(cachedBytes)
        }

        // 2. Cache miss: attempt online fetch if network is connected
        if (OfflineAuditLogger.isNetworkConnected(context)) {
            OpenFreeMapHelper.resolveTemplateAsync()
            val openFreeMapUrl = OpenFreeMapHelper.getTileUrl(z, x, y)
            val fetched = fetchRemoteTile(openFreeMapUrl)

            if (fetched != null && fetched.isNotEmpty()) {
                dbHelper.putTile(z, x, y, fetched)
                OfflineAuditLogger.logOutboundRequest(
                    url = openFreeMapUrl,
                    responseCode = 200,
                    bytes = fetched.size,
                    wasCacheHit = false
                )
                return tileResponse(fetched)
            }
        }

        // 3. Fallback: return transparent empty tile (no solid black screen)
        return emptyTileResponse()
    }

    private fun fetchRemoteTile(urlString: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NeXoRa-Mesh-TacticalMap/1.4")
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
                    buffer.toByteArray()
                }
            } else {
                when (code) {
                    404 -> Log.d(TAG, "Tile does not exist upstream (404): $urlString")
                    429 -> Log.w(TAG, "OpenFreeMap rate limit (429): $urlString")
                    else -> Log.w(TAG, "Upstream tile fetch returned HTTP $code: $urlString")
                }
                OfflineAuditLogger.logOutboundRequest(urlString, code, 0, false)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during tile fetch from $urlString: ${e.message}")
            OfflineAuditLogger.logOutboundRequest(urlString, -1, 0, false)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun tileResponse(data: ByteArray): Response {
        val stream = ByteArrayInputStream(data)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            MIME_PROTOBUF,
            stream,
            data.size.toLong()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "max-age=86400, public")
        return response
    }

    private fun emptyTileResponse(): Response {
        // HTTP 200 with 0-byte payload is the cleanest transparent vector tile response in MapLibre
        val stream = ByteArrayInputStream(ByteArray(0))
        val response = newFixedLengthResponse(
            Response.Status.OK,
            MIME_PROTOBUF,
            stream,
            0L
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    companion object {
        private const val TAG = "LocalTileServer"
        const val MIME_PROTOBUF = "application/x-protobuf"

        fun create(context: Context): LocalTileServer {
            val freePort = findFreePort()
            Log.i(TAG, "Binding LocalTileServer to 127.0.0.1:$freePort (OpenFreeMap upstream, no API key)")
            return LocalTileServer(context, freePort)
        }

        private fun findFreePort(): Int {
            return try {
                ServerSocket(0).use { it.localPort }
            } catch (e: Exception) {
                // Fallback to high port range
                8844
            }
        }
    }
}

/**
 * Resolves and caches the active OpenFreeMap planet tile template.
 * Falls back to known build if offline or unresolvable.
 */
object OpenFreeMapHelper {
    private const val TAG = "OpenFreeMapHelper"
    @Volatile
    private var cachedTileTemplate: String = "https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf"
    private var lastResolvedTime: Long = 0L

    fun getTileUrl(z: Int, x: Int, y: Int): String {
        val template = cachedTileTemplate
        return template.replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }

    fun resolveTemplateAsync() {
        val now = System.currentTimeMillis()
        if (now - lastResolvedTime < 3600_000L) return
        Thread {
            try {
                val url = URL("https://tiles.openfreemap.org/planet")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "NeXoRa-Mesh-TacticalMap/1.4")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val matcher = java.util.regex.Pattern.compile("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").matcher(body)
                    if (matcher.find()) {
                        val template = matcher.group(1)
                        if (template != null && template.contains("{z}") && template.contains("{x}") && template.contains("{y}")) {
                            cachedTileTemplate = template
                            lastResolvedTime = now
                            Log.i(TAG, "Resolved active OpenFreeMap template: $template")
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving OpenFreeMap planet template, using fallback: ${e.message}")
            }
        }.start()
    }
}
