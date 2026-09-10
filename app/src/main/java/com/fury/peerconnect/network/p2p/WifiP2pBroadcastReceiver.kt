package com.fury.peerconnect.network.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

class WifiP2pBroadcastReceiver(
    private val manager: WifiP2pManager?,
    private val channel: WifiP2pManager.Channel?,
    private val meshManager: WifiP2pMeshManager
) : BroadcastReceiver() {
    private val TAG = "WifiP2pBroadcastReceiver"

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "Wi-Fi P2P state changed: enabled=$isEnabled")
                meshManager.onP2pStateChanged(isEnabled)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Wi-Fi P2P peers changed action received")
                meshManager.onPeersChanged()
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "Wi-Fi P2P connection changed action received")
                val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                } else {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                }
                meshManager.onConnectionChanged(networkInfo)
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "Wi-Fi P2P this device changed action received")
            }
        }
    }
}
