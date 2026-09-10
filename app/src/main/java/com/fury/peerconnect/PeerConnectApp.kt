package com.fury.peerconnect

import android.app.Application
import android.util.Log
import org.maplibre.android.MapLibre

class PeerConnectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MapLibre.getInstance(this)
            Log.d("PeerConnectApp", "MapLibre initialized successfully in Application.onCreate")
        } catch (e: Throwable) {
            Log.e("PeerConnectApp", "Failed to initialize MapLibre", e)
        }
    }
}
