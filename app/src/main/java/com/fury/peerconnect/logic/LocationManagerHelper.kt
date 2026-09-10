package com.fury.peerconnect.logic

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Offline-first location manager helper.
 * Uses GPS and Android native location services with zero cellular or cloud network requirements.
 */
class LocationManagerHelper(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val systemLocationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    data class GeoCoordinates(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Obtains the single best last-known or fresh GPS location.
     * Fallback to Android LocationManager GPS provider if Google Play Services fused client is unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): GeoCoordinates? = suspendCancellableCoroutine { continuation ->
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        continuation.resume(
                            GeoCoordinates(
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                accuracy = loc.accuracy,
                                timestamp = loc.time
                            )
                        )
                    } else {
                        // Fallback to getLastLocation or native GPS provider
                        fusedClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                            if (lastLoc != null) {
                                continuation.resume(
                                    GeoCoordinates(
                                        latitude = lastLoc.latitude,
                                        longitude = lastLoc.longitude,
                                        accuracy = lastLoc.accuracy,
                                        timestamp = lastLoc.time
                                    )
                                )
                            } else {
                                val nativeLoc = getNativeGpsLocation()
                                continuation.resume(nativeLoc)
                            }
                        }.addOnFailureListener {
                            continuation.resume(getNativeGpsLocation())
                        }
                    }
                }
                .addOnFailureListener {
                    continuation.resume(getNativeGpsLocation())
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location from fused client, falling back", e)
            continuation.resume(getNativeGpsLocation())
        }
    }

    /**
     * Pure native Android LocationManager GPS fallback for fully offline devices
     * without Google Play Services.
     */
    @SuppressLint("MissingPermission")
    private fun getNativeGpsLocation(): GeoCoordinates? {
        return try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var bestLocation: Location? = null

            for (provider in providers) {
                if (systemLocationManager.isProviderEnabled(provider)) {
                    val loc = systemLocationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                            bestLocation = loc
                        }
                    }
                }
            }

            bestLocation?.let {
                GeoCoordinates(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    timestamp = it.time
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native GPS acquisition failed", e)
            null
        }
    }

    /**
     * Emits periodic location updates for Live Location Sharing.
     * Throttled to [intervalMillis] (e.g. 15s to 30s) or [minDistanceMeters].
     */
    @SuppressLint("MissingPermission")
    fun requestLocationUpdates(
        intervalMillis: Long = 20_000L,
        minDistanceMeters: Float = 10f
    ): Flow<GeoCoordinates> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(
                        GeoCoordinates(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            timestamp = loc.time
                        )
                    )
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Fused location updates failed, trying native GPS listener", e)
            val nativeListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    trySend(
                        GeoCoordinates(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            timestamp = loc.time
                        )
                    )
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                if (systemLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    systemLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        intervalMillis,
                        minDistanceMeters,
                        nativeListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (err: Exception) {
                Log.e(TAG, "Native GPS listener failed", err)
            }
        }

        awaitClose {
            try {
                fusedClient.removeLocationUpdates(callback)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "LocationManagerHelper"
    }
}
