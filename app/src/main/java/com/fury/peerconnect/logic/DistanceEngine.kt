package com.fury.peerconnect.logic

import kotlin.math.*

/**
 * Offline distance calculation engine.
 * Computes straight-line distances using the Haversine formula entirely on-device,
 * with zero cellular or internet network dependencies.
 */
object DistanceEngine {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the great-circle distance between two geographic coordinates in meters.
     *
     * @param lat1 Latitude of origin point in degrees
     * @param lon1 Longitude of origin point in degrees
     * @param lat2 Latitude of destination point in degrees
     * @param lon2 Longitude of destination point in degrees
     * @return Distance in meters
     */
    fun calculateHaversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2.0).pow(2.0) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_METERS * c
    }

    const val ACCURACY_THRESHOLD_METERS = 25.0f
    const val PEER_STALE_TIMEOUT_MS = 10_000L
    const val DEFAULT_EMA_ALPHA = 0.3

    data class FilteredCoordinate(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long
    )

    /**
     * Exponential Moving Average (EMA) smoother for GPS coordinates.
     * smoothed = alpha * new + (1.0 - alpha) * old
     */
    class CoordinateSmoother(private val alpha: Double = DEFAULT_EMA_ALPHA) {
        private var smoothedLat: Double? = null
        private var smoothedLon: Double? = null

        @Synchronized
        fun update(newLat: Double, newLon: Double, accuracy: Float, timestamp: Long): FilteredCoordinate {
            val prevLat = smoothedLat
            val prevLon = smoothedLon

            val finalLat = if (prevLat != null) (alpha * newLat + (1.0 - alpha) * prevLat) else newLat
            val finalLon = if (prevLon != null) (alpha * newLon + (1.0 - alpha) * prevLon) else newLon

            smoothedLat = finalLat
            smoothedLon = finalLon

            return FilteredCoordinate(finalLat, finalLon, accuracy, timestamp)
        }

        @Synchronized
        fun reset() {
            smoothedLat = null
            smoothedLon = null
        }
    }

    /**
     * Checks if a GPS fix has high-accuracy (<= 25m) suitable for tactical distance calculation.
     */
    fun isAccuracyAcceptable(accuracy: Float?): Boolean {
        return accuracy != null && accuracy > 0f && accuracy <= ACCURACY_THRESHOLD_METERS
    }

    /**
     * Checks if a peer's location fix is older than the staleness threshold (~10 seconds).
     */
    fun isFixStale(timestamp: Long, maxAgeMs: Long = PEER_STALE_TIMEOUT_MS): Boolean {
        return (System.currentTimeMillis() - timestamp) > maxAgeMs
    }

    /**
     * Formats distance into a human-readable string:
     * - Under 1000m: e.g. "450 m"
     * - 1000m and above: e.g. "2.4 km"
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            val km = meters / 1000.0
            String.format(java.util.Locale.US, "%.1f km", km)
        }
    }

    /**
     * Calculates bearing in degrees from origin to destination (0-360).
     */
    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }
}
