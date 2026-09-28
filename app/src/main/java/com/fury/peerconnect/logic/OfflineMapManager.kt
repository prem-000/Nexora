package com.fury.peerconnect.logic

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import com.fury.peerconnect.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline-first Vector & Satellite Map Manager utilizing MapLibre Native SDK and an embedded NanoHTTPD tile server.
 * Supports STREET (offline vector), SATELLITE (MapTiler high-res raster imagery), and HYBRID (satellite + vector overlays:
 * roads, building footprints, railways, labels, POIs).
 *
 * Routes are drawn as real GeoJSON LineString layers rather than straight lines.
 */
class OfflineMapManager(
    private val context: Context,
    private val mapView: MapView
) {
    private var mapLibreMap: MapLibreMap? = null
    private var myMarker: Marker? = null
    private val peerMarkers = ConcurrentHashMap<String, Marker>()
    private val alertMarkers = ConcurrentHashMap<String, Marker>()
    private val poiMarkers = ConcurrentHashMap<String, Marker>()

    private var myLastLatLng: LatLng? = null
    private var lastUserInteractionTime: Long = 0L
    private var hasInitialZoomed: Boolean = false

    // Smoothers
    private val myCoordinateSmoother = DistanceEngine.CoordinateSmoother()
    private val peerSmoothers = ConcurrentHashMap<String, DistanceEngine.CoordinateSmoother>()
    private val peerLastSeenTimes = ConcurrentHashMap<String, Long>()

    // Route layer IDs & current route cache for style switching persistence
    private val ROUTE_SOURCE_ID = "nexora-route-source"
    private val ROUTE_LAYER_ID = "nexora-route-layer"
    private var currentRoutePoints: List<LatLng>? = null

    // Embedded loopback vector tile server and prefetcher
    val localTileServer = LocalTileServer.create(context)
    val prefetcher = MapTilePrefetcher(context)

    interface OnMapReadyCallback {
        fun onMapReady()
    }

    init {
        try {
            Log.i(TAG, "MAP_INIT: Initializing MapLibre instance and starting local tile server")
            MapLibre.getInstance(context)
            localTileServer.start()
            Log.i(TAG, "MAP_INIT: Local vector tile server active on port ${localTileServer.port}")
        } catch (e: Exception) {
            Log.e(TAG, "MAP_INIT: Error during initialization", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun onCreate(savedInstanceState: Bundle?, onReady: (() -> Unit)? = null) {
        Log.i(TAG, "MAP_INIT: mapView.onCreate with savedInstanceState=${savedInstanceState != null}")
        mapView.onCreate(savedInstanceState)

        // Fix gesture stealing by parent scroll containers
        mapView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    lastUserInteractionTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }
            configureMapLibre(map, onReady)
        }
    }

    enum class MapLayerStyle(val label: String) {
        STREET("Street"),
        SATELLITE("Satellite"),
        HYBRID("Hybrid")
    }

    private var currentLayerStyle: MapLayerStyle = MapLayerStyle.STREET

    private val peerLocationsData = ConcurrentHashMap<String, Triple<String, LatLng, Boolean>>()
    private val alertLocationsData = ConcurrentHashMap<String, Triple<String, LatLng, String?>>()
    private val poiLocationsData = mutableListOf<OverpassPoiEngine.PoiResult>()

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun configureMapLibre(map: MapLibreMap, onReady: (() -> Unit)?) {
        Log.i(TAG, "MAP_STYLE_LOAD: Initializing MapLibre with '${currentLayerStyle.label}'")
        val styleBuilder = getStyleBuilder(currentLayerStyle)
        try {
            map.setStyle(styleBuilder) { style ->
                Log.i(TAG, "MAP_RENDER_READY: Initial style loaded (${currentLayerStyle.label})")

                // Enable all gestures, rotation, compass, and tilt
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = true
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isDoubleTapGesturesEnabled = true
                map.uiSettings.isScrollGesturesEnabled = true

                // Initial camera
                val initialTarget = myLastLatLng ?: LatLng(20.0, 0.0)
                val initialZoom = if (myLastLatLng != null) 14.5 else 3.0
                map.cameraPosition = CameraPosition.Builder()
                    .target(initialTarget)
                    .zoom(initialZoom)
                    .build()

                addRouteLayerToStyle(style)
                reapplyMarkers()
                onReady?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAP_STYLE_ERROR: Failed to set map style", e)
        }
    }

    private fun addRouteLayerToStyle(style: Style) {
        try {
            if (style.getSource(ROUTE_SOURCE_ID) == null) {
                val emptyCollection = FeatureCollection.fromFeatures(emptyList())
                val source = GeoJsonSource(ROUTE_SOURCE_ID, emptyCollection)
                style.addSource(source)
            }
            if (style.getLayer(ROUTE_LAYER_ID) == null) {
                val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#38BDF8"),  // Sky-400 — tactical cyan
                    PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                )
                style.addLayer(layer)
            }

            // Restore active route polyline if present
            currentRoutePoints?.let { pts ->
                if (pts.isNotEmpty()) {
                    val linePoints = pts.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val lineString = LineString.fromLngLats(linePoints)
                    val feature = Feature.fromGeometry(lineString)
                    val collection = FeatureCollection.fromFeature(feature)
                    (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(collection)
                    Log.d(TAG, "MAP_ROUTE_LAYER: Restored ${pts.size} route points to style")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MAP_ROUTE_LAYER: Error configuring route layer", e)
        }
    }

    /**
     * Draws an online road-following route path on the map as a GeoJSON LineString.
     * Replaces any previously drawn route.
     */
    fun drawOsrmRoute(points: List<LatLng>) {
        currentRoutePoints = points
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        try {
            val linePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val lineString = LineString.fromLngLats(linePoints)
            val feature = Feature.fromGeometry(lineString)
            val collection = FeatureCollection.fromFeature(feature)
            (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(collection)
            Log.i(TAG, "MAP_ROUTE: Rendered route with ${points.size} points")
        } catch (e: Exception) {
            Log.w(TAG, "MAP_ROUTE: Error drawing route", e)
        }
    }

    /**
     * Clears the route layer.
     */
    fun clearRoute() {
        currentRoutePoints = null
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        try {
            val emptyCollection = FeatureCollection.fromFeatures(emptyList())
            (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(emptyCollection)
        } catch (e: Exception) {
            Log.w(TAG, "MAP_ROUTE: Error clearing route", e)
        }
    }

    fun getCurrentMapStyle(): MapLayerStyle = currentLayerStyle

    /**
     * Switches the active map layer style (STREET, SATELLITE, HYBRID).
     * If SATELLITE or HYBRID is requested without internet or without an API key,
     * it falls back gracefully to STREET without crashing or showing a blank map.
     */
    fun setMapStyle(
        requestedStyle: MapLayerStyle,
        onResult: ((success: Boolean, activeStyle: MapLayerStyle, message: String?) -> Unit)? = null
    ) {
        val map = mapLibreMap ?: return

        var targetStyle = requestedStyle
        var message: String? = null
        var isSuccess = true

        if (requestedStyle == MapLayerStyle.SATELLITE || requestedStyle == MapLayerStyle.HYBRID) {
            val apiKey = BuildConfig.MAPTILER_API_KEY.trim()
            if (apiKey.isBlank()) {
                Log.w(TAG, "MAP_STYLE: MapTiler API key missing, falling back to STREET style")
                targetStyle = MapLayerStyle.STREET
                message = "MapTiler API key not configured. Using offline street map."
                isSuccess = false
            } else if (!isNetworkAvailable()) {
                Log.w(TAG, "MAP_STYLE: Internet unavailable, falling back to offline STREET style")
                targetStyle = MapLayerStyle.STREET
                message = "Offline mode • Satellite imagery requires internet."
                isSuccess = false
            }
        }

        currentLayerStyle = targetStyle
        Log.i(TAG, "MAP_STYLE_LOAD: Applying style: ${targetStyle.label}")

        val savedCamera = map.cameraPosition

        try {
            map.setStyle(getStyleBuilder(targetStyle)) { loadedStyle ->
                Log.i(TAG, "MAP_RENDER_READY: Style ${targetStyle.label} loaded successfully")

                if (savedCamera != null && savedCamera.zoom > 0) {
                    map.cameraPosition = savedCamera
                }

                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = true
                map.uiSettings.isZoomGesturesEnabled = true
                map.uiSettings.isDoubleTapGesturesEnabled = true
                map.uiSettings.isScrollGesturesEnabled = true

                addRouteLayerToStyle(loadedStyle)
                reapplyMarkers()
                onResult?.invoke(isSuccess, targetStyle, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MAP_STYLE_ERROR: Error loading style ${targetStyle.label}", e)
            onResult?.invoke(false, targetStyle, "Failed to load map style: ${e.message}")
        }
    }

    /**
     * Cycles through STREET -> HYBRID -> SATELLITE -> STREET.
     */
    fun cycleMapStyle(onStyleChanged: ((activeStyle: MapLayerStyle, message: String?) -> Unit)? = null) {
        val nextStyle = when (currentLayerStyle) {
            MapLayerStyle.STREET -> MapLayerStyle.HYBRID
            MapLayerStyle.HYBRID -> MapLayerStyle.SATELLITE
            MapLayerStyle.SATELLITE -> MapLayerStyle.STREET
        }
        setMapStyle(nextStyle) { _, activeStyle, message ->
            onStyleChanged?.invoke(activeStyle, message)
        }
    }

    private fun getStyleBuilder(layerStyle: MapLayerStyle): Style.Builder {
        val customStyle = File(context.getExternalFilesDir(null), "offline_maps/style.json")
        if (customStyle.exists() && layerStyle == MapLayerStyle.STREET) {
            Log.i(TAG, "MAP_TILE_SOURCE: Using custom external style: ${customStyle.absolutePath}")
            return Style.Builder().fromUri(customStyle.toURI().toString())
        }

        val tileUrl = "http://127.0.0.1:${localTileServer.port}/{z}/{x}/{y}.pbf"
        val maptilerKey = BuildConfig.MAPTILER_API_KEY.trim()

        val json = when (layerStyle) {
            MapLayerStyle.SATELLITE -> buildSatelliteStyleJson(maptilerKey)
            MapLayerStyle.HYBRID -> buildHybridStyleJson(maptilerKey, tileUrl)
            MapLayerStyle.STREET -> buildStreetStyleJson(tileUrl)
        }

        return Style.Builder().fromJson(json)
    }

    private fun buildSatelliteStyleJson(apiKey: String): String {
        return """
            {
              "version": 8,
              "name": "Satellite",
              "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "sources": {
                "maptiler_satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$apiKey"
                  ],
                  "tileSize": 512,
                  "maxzoom": 20,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                }
              },
              "layers": [
                {
                  "id": "maptiler_satellite_base",
                  "type": "raster",
                  "source": "maptiler_satellite",
                  "minzoom": 0,
                  "maxzoom": 20
                }
              ]
            }
        """.trimIndent()
    }

    private fun buildHybridStyleJson(apiKey: String, vectorTileUrl: String): String {
        return """
            {
              "version": 8,
              "name": "Hybrid",
              "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "sources": {
                "maptiler_satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$apiKey"
                  ],
                  "tileSize": 512,
                  "maxzoom": 20,
                  "attribution": "© MapTiler © OpenStreetMap contributors"
                },
                "openmaptiles": {
                  "type": "vector",
                  "tiles": [
                    "$vectorTileUrl"
                  ],
                  "minzoom": 0,
                  "maxzoom": 14,
                  "attribution": "© OpenFreeMap © OpenStreetMap contributors"
                }
              },
              "layers": [
                {
                  "id": "maptiler_satellite_base",
                  "type": "raster",
                  "source": "maptiler_satellite",
                  "minzoom": 0,
                  "maxzoom": 20
                },
                {
                  "id": "building",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "building",
                  "minzoom": 13,
                  "paint": {
                    "fill-color": "#FFFFFF",
                    "fill-opacity": 0.22,
                    "fill-outline-color": "#FFFFFF"
                  }
                },
                {
                  "id": "railway",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["==", "class", "rail"],
                  "paint": {
                    "line-color": "#E4E4E7",
                    "line-width": 1.6,
                    "line-dasharray": [3, 2]
                  }
                },
                {
                  "id": "transportation_minor",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["in", "class", "minor", "service", "track"],
                  "paint": {
                    "line-color": "#FFFFFF",
                    "line-opacity": 0.7,
                    "line-width": 1.5
                  }
                },
                {
                  "id": "transportation_secondary",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["in", "class", "primary", "secondary", "tertiary"],
                  "paint": {
                    "line-color": "#FDE047",
                    "line-width": 2.6
                  }
                },
                {
                  "id": "transportation_motorway",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["in", "class", "motorway", "trunk"],
                  "paint": {
                    "line-color": "#FB923C",
                    "line-width": 3.6
                  }
                },
                {
                  "id": "boundary",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "boundary",
                  "paint": {
                    "line-color": "#F1F5F9",
                    "line-width": 1.5,
                    "line-dasharray": [2, 2]
                  }
                },
                {
                  "id": "poi_label",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "poi",
                  "minzoom": 14,
                  "layout": {
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Regular"],
                    "text-size": 10
                  },
                  "paint": {
                    "text-color": "#F8FAFC",
                    "text-halo-color": "#000000",
                    "text-halo-width": 1.8
                  }
                },
                {
                  "id": "transportation_name",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "transportation_name",
                  "minzoom": 13,
                  "layout": {
                    "symbol-placement": "line",
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Regular"],
                    "text-size": 11
                  },
                  "paint": {
                    "text-color": "#FFFFFF",
                    "text-halo-color": "#000000",
                    "text-halo-width": 2.0
                  }
                },
                {
                  "id": "place_label",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "place",
                  "minzoom": 7,
                  "layout": {
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Bold"],
                    "text-size": 13,
                    "text-transform": "uppercase"
                  },
                  "paint": {
                    "text-color": "#FFFFFF",
                    "text-halo-color": "#000000",
                    "text-halo-width": 2.5
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun buildStreetStyleJson(vectorTileUrl: String): String {
        return """
            {
              "version": 8,
              "name": "Street",
              "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "sources": {
                "openmaptiles": {
                  "type": "vector",
                  "tiles": [
                    "$vectorTileUrl"
                  ],
                  "minzoom": 0,
                  "maxzoom": 14,
                  "attribution": "© OpenFreeMap © OpenStreetMap contributors"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#0F172A" }
                },
                {
                  "id": "countries",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "countries",
                  "paint": { "fill-color": "#131E35" }
                },
                {
                  "id": "landcover",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "landcover",
                  "paint": { "fill-color": "#131E35" }
                },
                {
                  "id": "park",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "park",
                  "paint": { "fill-color": "#064E3B" }
                },
                {
                  "id": "water",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "water",
                  "paint": { "fill-color": "#1E3A8A" }
                },
                {
                  "id": "waterway",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "waterway",
                  "paint": {
                    "line-color": "#2563EB",
                    "line-width": 1.2
                  }
                },
                {
                  "id": "building",
                  "type": "fill",
                  "source": "openmaptiles",
                  "source-layer": "building",
                  "minzoom": 13,
                  "paint": {
                    "fill-color": "#1E293B",
                    "fill-outline-color": "#334155"
                  }
                },
                {
                  "id": "railway",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["==", "class", "rail"],
                  "paint": {
                    "line-color": "#64748B",
                    "line-width": 1.4,
                    "line-dasharray": [3, 2]
                  }
                },
                {
                  "id": "transportation_minor",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "paint": {
                    "line-color": "#334155",
                    "line-width": 1.2
                  }
                },
                {
                  "id": "transportation_secondary",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["in", "class", "primary", "secondary", "tertiary"],
                  "paint": {
                    "line-color": "#475569",
                    "line-width": 2.2
                  }
                },
                {
                  "id": "transportation_motorway",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "transportation",
                  "filter": ["in", "class", "motorway", "trunk"],
                  "paint": {
                    "line-color": "#38BDF8",
                    "line-width": 3.2
                  }
                },
                {
                  "id": "boundary",
                  "type": "line",
                  "source": "openmaptiles",
                  "source-layer": "boundary",
                  "paint": {
                    "line-color": "#38BDF8",
                    "line-width": 1.0,
                    "line-dasharray": [2, 2]
                  }
                },
                {
                  "id": "poi_label",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "poi",
                  "minzoom": 14,
                  "layout": {
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Regular"],
                    "text-size": 10
                  },
                  "paint": {
                    "text-color": "#94A3B8",
                    "text-halo-color": "#0F172A",
                    "text-halo-width": 1.5
                  }
                },
                {
                  "id": "transportation_name",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "transportation_name",
                  "minzoom": 13,
                  "layout": {
                    "symbol-placement": "line",
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Regular"],
                    "text-size": 11
                  },
                  "paint": {
                    "text-color": "#94A3B8",
                    "text-halo-color": "#0F172A",
                    "text-halo-width": 1.5
                  }
                },
                {
                  "id": "place_label",
                  "type": "symbol",
                  "source": "openmaptiles",
                  "source-layer": "place",
                  "minzoom": 8,
                  "layout": {
                    "text-field": "{name}",
                    "text-font": ["Noto Sans Bold"],
                    "text-size": 13,
                    "text-transform": "uppercase"
                  },
                  "paint": {
                    "text-color": "#94A3B8",
                    "text-halo-color": "#0F172A",
                    "text-halo-width": 2.0
                  }
                }
              ]
            }
        """.trimIndent()
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
        if (poiLocationsData.isNotEmpty()) {
            showPoiMarkers(poiLocationsData)
        }
    }

    /**
     * Updates user's GPS marker with EMA smoothing and non-disruptive camera following.
     */
    fun updateMyGPSLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 0f,
        timestamp: Long = System.currentTimeMillis(),
        animateCamera: Boolean = false
    ) {
        val smoothed = myCoordinateSmoother.update(latitude, longitude, accuracy, timestamp)
        val latLng = LatLng(smoothed.latitude, smoothed.longitude)
        myLastLatLng = latLng

        val map = mapLibreMap ?: return
        if (myMarker == null) {
            myMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("📍 You")
                    .snippet("Your current GPS position (±${accuracy.toInt()}m)")
            )
        } else {
            myMarker?.position = latLng
            myMarker?.snippet = "Your current GPS position (±${accuracy.toInt()}m)"
        }

        if (!hasInitialZoomed) {
            hasInitialZoomed = true
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14.5), 800)
            prefetcher.prefetchViewport(latLng.latitude, latLng.longitude, 14.5)
        } else if (animateCamera) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14.5), 800)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastUserInteractionTime > 10_000L) {
                map.easeCamera(CameraUpdateFactory.newLatLng(latLng), 400)
            }
        }
    }

    /**
     * Centers map on user's current GPS position.
     */
    fun recenterMyGPSLocation() {
        lastUserInteractionTime = 0L
        myLastLatLng?.let { latLng ->
            Log.i(TAG, "MAP_CAMERA: Recentering camera to user GPS (${latLng.latitude}, ${latLng.longitude})")
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14.5), 600)
        }
    }

    /**
     * Updates a peer's location marker with EMA smoothing and staleness tracking.
     */
    fun updatePeerLocation(
        peerId: String,
        peerName: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float = 0f,
        timestamp: Long = System.currentTimeMillis(),
        isLive: Boolean = false,
        lastUpdatedText: String = "Just now"
    ) {
        val smoother = peerSmoothers.getOrPut(peerId) { DistanceEngine.CoordinateSmoother() }
        val smoothed = smoother.update(latitude, longitude, accuracy, timestamp)
        peerLastSeenTimes[peerId] = timestamp

        val map = mapLibreMap ?: return
        val latLng = LatLng(smoothed.latitude, smoothed.longitude)

        val isStale = DistanceEngine.isFixStale(timestamp)
        val title = when {
            isStale -> "⚪ $peerName (Stale)"
            isLive -> "🟢 $peerName (Live)"
            else -> "📍 $peerName"
        }
        val snippet = "Coords: ${String.format(java.util.Locale.US, "%.5f, %.5f", smoothed.latitude, smoothed.longitude)} • $lastUpdatedText"

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

    fun removePeerLocation(peerId: String) {
        peerLocationsData.remove(peerId)
        peerSmoothers.remove(peerId)
        peerLastSeenTimes.remove(peerId)
        peerMarkers.remove(peerId)?.let {
            mapLibreMap?.removeMarker(it)
        }
    }

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

    fun showPoiMarkers(pois: List<OverpassPoiEngine.PoiResult>) {
        poiLocationsData.clear()
        poiLocationsData.addAll(pois)
        val map = mapLibreMap ?: return
        clearPoiMarkers()
        for (poi in pois) {
            val icon = when (poi.type) {
                OverpassPoiEngine.PoiType.HOSPITAL -> "🏥"
                OverpassPoiEngine.PoiType.PHARMACY -> "💊"
                OverpassPoiEngine.PoiType.POLICE -> "👮"
            }
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(poi.lat, poi.lon))
                    .title("$icon ${poi.name}")
                    .snippet(poi.type.label)
            )
            poiMarkers[poi.id] = marker
        }
        Log.i(TAG, "MAP_POI: Rendered ${pois.size} POI markers")
    }

    fun clearPoiMarkers() {
        val map = mapLibreMap ?: return
        poiMarkers.values.forEach { map.removeMarker(it) }
        poiMarkers.clear()
    }

    fun focusOnLocation(
        targetLat: Double,
        targetLon: Double,
        includeMyPosition: Boolean = true
    ) {
        lastUserInteractionTime = System.currentTimeMillis()
        val map = mapLibreMap ?: return
        val target = LatLng(targetLat, targetLon)
        Log.i(TAG, "MAP_CAMERA: focusOnLocation target=($targetLat, $targetLon), includeMyPosition=$includeMyPosition")

        if (includeMyPosition && myLastLatLng != null) {
            val my = myLastLatLng!!
            val bounds = LatLngBounds.Builder()
                .include(my)
                .include(target)
                .build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 900)
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 14.5), 800)
        }
    }

    fun clearStraightLine() {
        clearRoute()
    }

    fun downloadAreaForOffline(onComplete: ((Int) -> Unit)? = null) {
        val center = myLastLatLng ?: mapLibreMap?.cameraPosition?.target
        if (center != null) {
            prefetcher.prefetchArea(
                latitude = center.latitude,
                longitude = center.longitude,
                radiusMeters = MapTilePrefetcher.DEFAULT_RADIUS_METERS,
                onComplete = onComplete
            )
        } else {
            onComplete?.invoke(0)
        }
    }

    fun getVisibleViewportBounds(): DoubleArray? {
        val map = mapLibreMap ?: return null
        val bounds = map.projection.visibleRegion.latLngBounds ?: return null
        return doubleArrayOf(
            bounds.latitudeSouth,
            bounds.latitudeNorth,
            bounds.longitudeWest,
            bounds.longitudeEast,
            map.cameraPosition.zoom
        )
    }

    // Lifecycle forwarding
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() {
        mapView.onDestroy()
        prefetcher.cancel()
        try {
            localTileServer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping local tile server", e)
        }
    }
    fun onLowMemory() = mapView.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)

    companion object {
        private const val TAG = "OfflineMapManager"
    }
}
