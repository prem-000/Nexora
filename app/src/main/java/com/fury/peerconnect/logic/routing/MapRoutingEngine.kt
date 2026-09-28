package com.fury.peerconnect.logic.routing

import org.maplibre.android.geometry.LatLng

/**
 * Result of a calculated road/walking route.
 */
data class MapRouteResult(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<LatLng>
)

/**
 * Clean routing abstraction separating route calculation from MapLibre rendering.
 * Provides road-following navigation coordinates, distance, and ETA travel time.
 */
interface MapRoutingEngine {
    /**
     * Calculates a road-following route between two points.
     * Returns null if offline, service unavailable, coordinates invalid, or route empty.
     */
    suspend fun calculateRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: String = "foot"
    ): MapRouteResult?

    /**
     * Formats route distance and ETA into a human-readable summary string.
     */
    fun formatRouteSummary(result: MapRouteResult): String
}
