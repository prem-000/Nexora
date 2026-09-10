package com.fury.peerconnect.network.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fury.peerconnect.network.manager.ConnectionManager
import com.fury.peerconnect.network.manager.NeighborTable
import com.fury.peerconnect.network.model.MeshGroupState
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.Peer
import com.fury.peerconnect.network.model.PeerState
import java.util.concurrent.CopyOnWriteArrayList

class WifiP2pMeshManager(
    private val context: Context,
    val myDeviceId: String,
    val neighborTable: NeighborTable,
    val connectionManager: ConnectionManager,
    val onMessageReceived: (MeshMessage) -> Unit
) {
    private val TAG = "WifiP2pMeshManager"
    var manager: WifiP2pManager? = null
    var channel: WifiP2pManager.Channel? = null
    private var tcpTransportManager: TcpTransportManager? = null
    var meshGroupState = MeshGroupState()
        private set

    private val groupStateListeners = CopyOnWriteArrayList<(MeshGroupState) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var isP2pEnabled = false
    private var isDiscovering = false

    private val rediscoveryRunnable = object : Runnable {
        override fun run() {
            if (isP2pEnabled) {
                startDiscovery()
            }
            handler.postDelayed(this, 30000L)
        }
    }

    init {
        val systemService = context.getSystemService("wifip2p")
        manager = if (systemService is WifiP2pManager) systemService else null
        channel = manager?.initialize(context, Looper.getMainLooper(), null)
        tcpTransportManager = TcpTransportManager(
            connectionManager = connectionManager,
            myDeviceId = myDeviceId,
            onMessageReceived = onMessageReceived,
            onPeerDisconnected = { peerId ->
                Log.d(TAG, "Peer disconnected in transport: $peerId")
                neighborTable.updatePeerState(peerId, PeerState.DISCONNECTED)
            }
        )
    }

    fun onP2pStateChanged(enabled: Boolean) {
        isP2pEnabled = enabled
        if (enabled) {
            startDiscovery()
            handler.post(rediscoveryRunnable)
        } else {
            handler.removeCallbacks(rediscoveryRunnable)
            updateMeshGroupState(MeshGroupState())
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!isP2pEnabled || manager == null || channel == null) return
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                Log.d(TAG, "Wi-Fi Direct peer discovery initiated")
            }

            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "Peer discovery failed with reason: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun onPeersChanged() {
        if (manager == null || channel == null) return
        manager?.requestPeers(channel) { peerList: WifiP2pDeviceList ->
            val deviceList = peerList.deviceList
            Log.d(TAG, "Discovered ${deviceList.size} Wi-Fi Direct peers")
            for (device in deviceList) {
                val existing = neighborTable.getPeer(device.deviceAddress)
                val currentState = existing?.state ?: PeerState.DISCOVERED
                val updatedPeer = Peer(
                    deviceAddress = device.deviceAddress,
                    deviceName = device.deviceName,
                    isGroupOwner = device.isGroupOwner,
                    state = if (currentState == PeerState.DISCONNECTED) PeerState.DISCOVERED else currentState
                )
                neighborTable.updatePeer(updatedPeer)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun onConnectionChanged(networkInfo: NetworkInfo?) {
        if (manager == null || channel == null) return
        if (networkInfo != null && networkInfo.isConnected) {
            Log.d(TAG, "P2P Network connected. Requesting connection & group info...")
            manager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                onRequestConnectionInfoReceived(info)
            }
        } else {
            Log.d(TAG, "P2P Network disconnected")
            tcpTransportManager?.stopServer()
            updateMeshGroupState(MeshGroupState())
        }
    }

    @SuppressLint("MissingPermission")
    private fun onRequestConnectionInfoReceived(info: WifiP2pInfo) {
        val groupFormed = info.groupFormed
        val isGroupOwner = info.isGroupOwner
        val ownerAddress = info.groupOwnerAddress?.hostAddress
        Log.d(TAG, "Connection info: groupFormed=$groupFormed, isGO=$isGroupOwner, ownerIP=$ownerAddress")
        if (groupFormed) {
            manager?.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                val clientList = mutableListOf<Peer>()
                if (group != null) {
                    for (clientDevice in group.clientList) {
                        clientList.add(
                            Peer(
                                deviceAddress = clientDevice.deviceAddress,
                                deviceName = clientDevice.deviceName,
                                isGroupOwner = false,
                                state = PeerState.GROUP_MEMBER
                            )
                        )
                    }
                }
                val newState = MeshGroupState(
                    groupFormed = true,
                    isGroupOwner = isGroupOwner,
                    ownerAddress = ownerAddress,
                    clients = clientList
                )
                updateMeshGroupState(newState)
                if (isGroupOwner) {
                    tcpTransportManager?.startServer()
                } else if (!ownerAddress.isNullOrEmpty()) {
                    tcpTransportManager?.connectToOwner(ownerAddress)
                }
                for (client in clientList) {
                    neighborTable.updatePeer(client.copy(state = PeerState.GROUP_MEMBER))
                }
            }
        } else {
            updateMeshGroupState(MeshGroupState())
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onResult: (Boolean) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false)
            return
        }
        neighborTable.updatePeerState(deviceAddress, PeerState.CONNECTING)
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request sent to $deviceAddress")
                onResult(true)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to connect to $deviceAddress, reason: $reason")
                neighborTable.updatePeerState(deviceAddress, PeerState.DISCONNECTED)
                onResult(false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup(onResult: (Boolean) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false)
            return
        }
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group created successfully")
                onResult(true)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to create Wi-Fi Direct group, reason: $reason")
                onResult(false)
            }
        })
    }

    fun addGroupStateListener(listener: (MeshGroupState) -> Unit) {
        groupStateListeners.add(listener)
        listener(meshGroupState)
    }

    fun removeGroupStateListener(listener: (MeshGroupState) -> Unit) {
        groupStateListeners.remove(listener)
    }

    private fun updateMeshGroupState(newState: MeshGroupState) {
        meshGroupState = newState
        for (listener in groupStateListeners) {
            try {
                listener(newState)
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        handler.removeCallbacks(rediscoveryRunnable)
        tcpTransportManager?.shutdown()
    }
}
