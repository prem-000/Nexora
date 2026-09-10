package com.fury.peerconnect.logic

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline Map Manager utilizing MapLibre Native.
 * Operates completely offline with zero telemetry or remote tile servers,
 * powered by locally provisioned geographic raster tiles.
 */
class OfflineMapManager(
    private val context: Context,
    private val mapView: MapView
) {
    private var mapLibreMap: MapLibreMap? = null
    private var myMarker: Marker? = null
    private val peerMarkers = ConcurrentHashMap<String, Marker>()
    private val alertMarkers = ConcurrentHashMap<String, Marker>()
    private var straightLinePolyline: Polyline? = null

    private var myLastLatLng: LatLng? = null

    interface OnMapReadyCallback {
        fun onMapReady()
    }

    init {
        // Initialize MapLibre before using MapView
        try {
            Log.i(TAG, "MAP_INIT: Initializing MapLibre instance")
            MapLibre.getInstance(context)
            provisionBundledTilesIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "MAP_INIT: Failed to initialize MapLibre singleton", e)
        }
    }

    fun onCreate(savedInstanceState: Bundle?, onReady: (() -> Unit)? = null) {
        Log.i(TAG, "MAP_INIT: mapView.onCreate with savedInstanceState=${savedInstanceState != null}")
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            configureMapLibreOffline(map, onReady)
        }
    }

    enum class MapLayerStyle(val label: String) {
        TACTICAL_VOYAGER("Tactical Voyager"),
        TACTICAL_DARK("Tactical Dark"),
        OPEN_STREET_MAP("OpenStreetMap")
    }

    private var currentLayerStyle: MapLayerStyle = MapLayerStyle.TACTICAL_VOYAGER

    private val peerLocationsData = ConcurrentHashMap<String, Triple<String, LatLng, Boolean>>()
    private val alertLocationsData = ConcurrentHashMap<String, Triple<String, LatLng, String?>>()

    /**
     * Provisions bundled offline tiles from assets to internal storage on fresh install.
     */
    private fun provisionBundledTilesIfNeeded() {
        try {
            val targetDir = File(context.filesDir, "map/tiles")
            val markerFile = File(targetDir, ".provisioned")
            if (markerFile.exists()) {
                val tileCount = countTiles(targetDir)
                Log.i(TAG, "MAP_TILE_READ: Verified $tileCount existing offline tiles in ${targetDir.absolutePath}")
                return
            }

            Log.i(TAG, "MAP_TILE_READ: Fresh install detected, provisioning bundled tiles from assets/map/tiles...")
            copyAssetFolder("map/tiles", targetDir)
            markerFile.createNewFile()
            val tileCount = countTiles(targetDir)
            Log.i(TAG, "MAP_TILE_READ: Successfully provisioned $tileCount offline tiles to ${targetDir.absolutePath}")
            val sample = File(targetDir, "0/0/0.png")
            if (sample.exists()) {
                Log.i(TAG, "MAP_TILE_READ: Verified root tile 0/0/0.png exists, size=${sample.length()} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAP_TILE_READ: Exception while provisioning bundled tiles", e)
        }
    }

    private fun copyAssetFolder(srcName: String, dstDir: File) {
        val assetManager = context.assets
        val list = assetManager.list(srcName) ?: return
        if (list.isEmpty()) {
            dstDir.parentFile?.mkdirs()
            assetManager.open(srcName).use { input ->
                FileOutputStream(dstDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            dstDir.mkdirs()
            for (file in list) {
                val childSrc = "$srcName/$file"
                val childDst = File(dstDir, file)
                copyAssetFolder(childSrc, childDst)
            }
        }
    }

    private fun countTiles(dir: File): Int {
        var count = 0
        if (!dir.exists()) return 0
        dir.walkTopDown().forEach {
            if (it.isFile && it.extension.equals("png", ignoreCase = true)) count++
        }
        return count
    }

    private fun getLocalTileUri(): String {
        val localTile0 = File(context.filesDir, "map/tiles/0/0/0.png")
        return if (localTile0.exists()) {
            "file://${File(context.filesDir, "map/tiles").absolutePath}/{z}/{x}/{y}.png"
        } else {
            "asset://map/tiles/{z}/{x}/{y}.png"
        }
    }

    private fun configureMapLibreOffline(map: MapLibreMap, onReady: (() -> Unit)?) {
        Log.i(TAG, "MAP_STYLE_LOAD: Configuring MapLibre style with '${currentLayerStyle.label}'")
        val styleBuilder = getStyleBuilder(currentLayerStyle)
        try {
            map.setStyle(styleBuilder) { style ->
                Log.i(TAG, "MAP_RENDER_READY: Style loaded and base raster tiles ready for rendering")
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isDoubleTapGesturesEnabled = true

                // Default camera
                val initialTarget = myLastLatLng ?: LatLng(20.0, 0.0)
                val initialZoom = if (myLastLatLng != null) 14.0 else 2.0
                map.cameraPosition = CameraPosition.Builder()
                    .target(initialTarget)
                    .zoom(initialZoom)
                    .build()
                Log.i(TAG, "MAP_CAMERA: Set initial camera target=(${initialTarget.latitude}, ${initialTarget.longitude}), zoom=$initialZoom")

                reapplyMarkers()
                onReady?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAP_STYLE_ERROR: Failed to set map style", e)
        }
    }

    private fun getStyleBuilder(layerStyle: MapLayerStyle): Style.Builder {
        val customStyle = File(context.getExternalFilesDir(null), "offline_maps/style.json")
        if (customStyle.exists()) {
            Log.i(TAG, "MAP_TILE_SOURCE: Using custom external style: ${customStyle.absolutePath}")
            return Style.Builder().fromUri(customStyle.toURI().toString())
        }

        val tileSourceUri = getLocalTileUri()
        Log.i(TAG, "MAP_TILE_SOURCE: Using offline tile source: $tileSourceUri, minzoom=0, maxzoom=4, tileSize=256")

        val bgColor = when (layerStyle) {
            MapLayerStyle.TACTICAL_VOYAGER -> "#111827"
            MapLayerStyle.TACTICAL_DARK -> "#0B0F19"
            MapLayerStyle.OPEN_STREET_MAP -> "#E5E7EB"
        }

        val json = """
            {
              "version": 8,
              "name": "${layerStyle.label}",
              "sources": {
                "raster-tiles": {
                  "type": "raster",
                  "tiles": [
                    "$tileSourceUri"
                  ],
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": 4,
                  "attribution": "© OpenStreetMap contributors, © CARTO"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "$bgColor" }
                },
                {
                  "id": "raster-layer",
                  "type": "raster",
                  "source": "raster-tiles",
                  "minzoom": 0,
                  "maxzoom": 22
                }
              ]
            }
        """.trimIndent()

        return Style.Builder().fromJson(json)
    }

    fun cycleMapStyle(onStyleChanged: ((String) -> Unit)? = null) {
        val nextStyle = when (currentLayerStyle) {
            MapLayerStyle.TACTICAL_VOYAGER -> MapLayerStyle.TACTICAL_DARK
            MapLayerStyle.TACTICAL_DARK -> MapLayerStyle.OPEN_STREET_MAP
            MapLayerStyle.OPEN_STREET_MAP -> MapLayerStyle.TACTICAL_VOYAGER
        }
        currentLayerStyle = nextStyle
        val map = mapLibreMap ?: return
        Log.i(TAG, "MAP_STYLE_LOAD: Cycling to style: ${nextStyle.label}")
        try {
            map.setStyle(getStyleBuilder(nextStyle)) {
                Log.i(TAG, "MAP_RENDER_READY: Style cycled to ${nextStyle.label} successfully")
                reapplyMarkers()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAP_STYLE_ERROR: Error during cycleMapStyle", e)
        }
        onStyleChanged?.invoke(nextStyle.label)
    }

    private fun reapplyMarkers() {
        val map = mapLibreMap ?: return
        myLastLatLng?.let { latLng ->
            myMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("📍 You")
                    .snippet("Your current GPS position")
            )
        }
        peerMarkers.clear()
        peerLocationsData.forEach { (peerId, info) ->
            val (title, latLng, _) = info
            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(title)
            )
            peerMarkers[peerId] = marker
        }
        alertMarkers.clear()
        alertLocationsData.forEach { (alertId, info) ->
            val (title, latLng, dist) = info
            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("🚨 $title")
                    .snippet(dist ?: "Alert Location")
            )
            alertMarkers[alertId] = marker
        }
    }

    /**
     * Updates or creates the user's GPS location marker (📍 You).
     */
    fun updateMyGPSLocation(latitude: Double, longitude: Double, animateCamera: Boolean = false) {
        val latLng = LatLng(latitude, longitude)
        myLastLatLng = latLng

        val map = mapLibreMap ?: return
        if (myMarker == null) {
            myMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("📍 You")
                    .snippet("Your current GPS position")
            )
        } else {
            myMarker?.position = latLng
        }

        if (animateCamera) {
            Log.i(TAG, "MAP_CAMERA: Animating camera to user GPS ($latitude, $longitude), zoom=15.0")
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15.0), 800)
        }
    }

    /**
     * Centers map on user's current GPS position.
     */
    fun recenterMyGPSLocation() {
        myLastLatLng?.let { latLng ->
            Log.i(TAG, "MAP_CAMERA: Recentering camera to user GPS (${latLng.latitude}, ${latLng.longitude})")
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng), 600)
        }
    }

    /**
     * Updates a peer's live or static location marker.
     */
    fun updatePeerLocation(
        peerId: String,
        peerName: String,
        latitude: Double,
        longitude: Double,
        isLive: Boolean = false,
        lastUpdatedText: String = "Just now"
    ) {
        val map = mapLibreMap ?: return
        val latLng = LatLng(latitude, longitude)

        val title = if (isLive) "🟢 $peerName (Live)" else "📍 $peerName"
        val snippet = "Coords: ${String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)} • $lastUpdatedText"

        peerLocationsData[peerId] = Triple(title, latLng, isLive)
        val existing = peerMarkers[peerId]
        if (existing != null) {
            existing.position = latLng
            existing.title = title
            existing.snippet = snippet
        } else {
            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(title)
                    .snippet(snippet)
            )
            peerMarkers[peerId] = marker
        }
    }

    /**
     * Removes a peer location marker.
     */
    fun removePeerLocation(peerId: String) {
        peerLocationsData.remove(peerId)
        peerMarkers.remove(peerId)?.let {
            mapLibreMap?.removeMarker(it)
        }
    }

    /**
     * Updates or pins an emergency / missing-person alert location.
     */
    fun updateAlertLocation(
        alertId: String,
        title: String,
        latitude: Double,
        longitude: Double,
        distanceText: String? = null
    ) {
        val latLng = LatLng(latitude, longitude)
        val snippet = if (!distanceText.isNullOrBlank()) {
            "Distance: $distanceText"
        } else {
            "Emergency Location (${String.format(java.util.Locale.US, "%.4f, %.4f", latitude, longitude)})"
        }
        alertLocationsData[alertId] = Triple(title, latLng, snippet)

        val map = mapLibreMap ?: return
        val existing = alertMarkers[alertId]
        if (existing != null) {
            existing.position = latLng
            existing.title = "🚨 $title"
            existing.snippet = snippet
        } else {
            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("🚨 $title")
                    .snippet(snippet)
            )
            alertMarkers[alertId] = marker
        }
    }

    /**
     * Focuses camera on a specific target coordinate with optional bounding box with user.
     */
    fun focusOnLocation(
        targetLat: Double,
        targetLon: Double,
        includeMyPosition: Boolean = true
    ) {
        val map = mapLibreMap ?: return
        val target = LatLng(targetLat, targetLon)
        Log.i(TAG, "MAP_CAMERA: focusOnLocation target=($targetLat, $targetLon), includeMyPosition=$includeMyPosition")

        if (includeMyPosition && myLastLatLng != null) {
            val my = myLastLatLng!!
            val bounds = LatLngBounds.Builder()
                .include(my)
                .include(target)
                .build()

            // Draw straight-line vector between user and target
            drawStraightLineToTarget(my, target)

            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 900)
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0), 800)
        }
    }

    /**
     * Draws an offline straight-line polyline connecting the user to the target point.
     */
    private fun drawStraightLineToTarget(start: LatLng, end: LatLng) {
        val map = mapLibreMap ?: return
        straightLinePolyline?.let { map.removePolyline(it) }

        straightLinePolyline = map.addPolyline(
            PolylineOptions()
                .add(start, end)
                .color(Color.parseColor("#38BDF8")) // cyan tactical line
                .width(3.5f)
        )
    }

    fun clearStraightLine() {
        straightLinePolyline?.let {
            mapLibreMap?.removePolyline(it)
            straightLinePolyline = null
        }
    }

    // Lifecycle forwarding
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    companion object {
        private const val TAG = "OfflineMapManager"
    }
}
