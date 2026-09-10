package com.fury.peerconnect.network.manager

import android.util.Log
import com.fury.peerconnect.network.model.MeshMessage
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager {
    private val TAG = "ConnectionManager"
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    val activeCount: Int
        get() = peerConnections.values.count { it.isConnected }

    fun addConnection(peerId: String, connection: PeerConnection) {
        val existing = peerConnections.put(peerId, connection)
        existing?.close()
        Log.d(TAG, "Added connection for peer: $peerId. Active connections count: ${peerConnections.size}")
    }

    fun removeConnection(peerId: String) {
        val connection = peerConnections.remove(peerId)
        connection?.close()
        Log.d(TAG, "Removed connection for peer: $peerId. Active connections count: ${peerConnections.size}")
    }

    fun getConnection(peerId: String): PeerConnection? {
        val conn = peerConnections[peerId]
        if (conn != null && !conn.isConnected) {
            removeConnection(peerId)
            return null
        }
        return conn
    }

    fun getAllConnections(): List<PeerConnection> {
        return peerConnections.values.filter { it.isConnected }
    }

    fun getConnectedPeerIds(): List<String> {
        return peerConnections.filter { it.value.isConnected }.keys.toList()
    }

    fun sendTo(peerId: String, message: MeshMessage): Boolean {
        val conn = getConnection(peerId)
        if (conn == null) {
            Log.w(TAG, "Cannot send to $peerId: No active connection")
            return false
        }
        return conn.send(message)
    }

    fun broadcast(message: MeshMessage, excludePeerId: String? = null) {
        for ((key, value) in peerConnections) {
            if (key != excludePeerId && value.isConnected) {
                value.send(message)
            }
        }
    }

    fun closeAll() {
        Log.d(TAG, "Closing all peer connections (${peerConnections.size})")
        for (conn in peerConnections.values) {
            conn.close()
        }
        peerConnections.clear()
    }
}
