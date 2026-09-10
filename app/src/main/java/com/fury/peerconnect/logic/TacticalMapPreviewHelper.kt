package com.fury.peerconnect.logic

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Provides static map previews for chat location messages.
 * When online, fetches and caches real OpenStreetMap / Carto map tiles.
 * When offline, generates an ultra-crisp tactical radar/coordinate grid preview.
 */
object TacticalMapPreviewHelper {

    private val memoryCache = LruCache<String, Bitmap>(30)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun loadLocationPreview(
        context: Context,
        latitude: Double,
        longitude: Double,
        imageView: ImageView
    ) {
        val cacheKey = String.format(Locale.US, "loc_%.4f_%.4f", latitude, longitude)

        // 1. Check memory cache
        val memBmp = memoryCache.get(cacheKey)
        if (memBmp != null) {
            imageView.setImageBitmap(memBmp)
            return
        }

        // 2. Generate immediate tactical radar placeholder (so it is never black)
        val placeholder = generateTacticalRadarBitmap(latitude, longitude)
        imageView.setImageBitmap(placeholder)

        // 3. Asynchronously load cached file or fetch online tile
        val cacheFile = File(context.cacheDir, "preview_$cacheKey.png")
        scope.launch {
            if (cacheFile.exists() && cacheFile.length() > 0) {
                try {
                    val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (bmp != null) {
                        memoryCache.put(cacheKey, bmp)
                        withContext(Dispatchers.Main) {
                            imageView.setImageBitmap(bmp)
                        }
                        return@launch
                    }
                } catch (_: Exception) {}
            }

            // Fetch online tile
            val tileBmp = fetchOnlineTile(latitude, longitude)
            if (tileBmp != null) {
                try {
                    cacheFile.outputStream().use { out ->
                        tileBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                } catch (_: Exception) {}

                memoryCache.put(cacheKey, tileBmp)
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(tileBmp)
                }
            }
        }
    }

    private fun fetchOnlineTile(lat: Double, lon: Double): Bitmap? {
        val zoom = 15
        val n = 1 shl zoom
        val x = Math.floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)

        val urls = listOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$x/$y.png",
            "https://tile.openstreetmap.org/$zoom/$x/$y.png"
        )

        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Nexora-Tactical-Mesh/1.2")
                conn.connect()
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun generateTacticalRadarBitmap(lat: Double, lon: Double, width: Int = 400, height: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Tactical dark background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid lines
        val gridPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val step = 35f
        var x = step
        while (x < width.toFloat()) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = step
        while (y < height.toFloat()) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }

        // Concentric radar circles
        val cx = width / 2f
        val cy = height / 2f
        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0284C7")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            alpha = 70
        }
        canvas.drawCircle(cx, cy, 35f, radarPaint)
        canvas.drawCircle(cx, cy, 75f, radarPaint)
        canvas.drawCircle(cx, cy, 110f, radarPaint)

        // Crosshairs
        val axisPaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            alpha = 100
        }
        canvas.drawLine(cx - 90f, cy, cx + 90f, cy, axisPaint)
        canvas.drawLine(cx, cy - 70f, cx, cy + 70f, axisPaint)

        // Cardinal markers (N, S, E, W)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            alpha = 150
        }
        canvas.drawText("N", cx, cy - 78f, textPaint)
        canvas.drawText("S", cx, cy + 92f, textPaint)
        canvas.drawText("W", cx - 100f, cy + 6f, textPaint)
        canvas.drawText("E", cx + 100f, cy + 6f, textPaint)

        return bmp
    }
}
