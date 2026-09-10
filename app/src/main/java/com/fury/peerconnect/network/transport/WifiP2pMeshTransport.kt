package com.fury.peerconnect.network.transport

import android.util.Log
import com.fury.peerconnect.network.manager.ConnectionManager
import com.fury.peerconnect.network.model.MeshMessage

class WifiP2pMeshTransport(
    private val connectionManager: ConnectionManager
) : MeshTransport {
    private val TAG = "WifiP2pMeshTransport"
    override val transportId: String = "WIFI_P2P"

    private var packetReceiver: ((MeshMessage, String) -> Unit)? = null
    private var neighborStateListener: ((NeighborEndpoint, Boolean) -> Unit)? = null

    override fun send(packet: MeshMessage, nextHopAddress: String): Boolean {
        Log.d(TAG, "send ${packet.messageId} to $nextHopAddress via Wi-Fi P2P")
        return connectionManager.sendTo(nextHopAddress, packet)
    }

    override fun broadcast(packet: MeshMessage, excludeAddress: String?) {
        Log.d(TAG, "broadcast ${packet.messageId} via Wi-Fi P2P (exclude=$excludeAddress)")
        connectionManager.broadcast(packet, excludeAddress)
    }

    override fun getConnectedNeighbors(): List<NeighborEndpoint> {
        return connectionManager.getConnectedPeerIds().map {
            NeighborEndpoint(it, it, transportId)
        }
    }

    override fun setPacketReceiver(receiver: (MeshMessage, String) -> Unit) {
        packetReceiver = receiver
    }

    override fun setNeighborStateListener(listener: (NeighborEndpoint, Boolean) -> Unit) {
        neighborStateListener = listener
    }

    fun onInboundPacket(packet: MeshMessage, fromPeerId: String) {
        packetReceiver?.invoke(packet, fromPeerId)
    }

    fun onPeerConnected(peerId: String) {
        neighborStateListener?.invoke(NeighborEndpoint(peerId, peerId, transportId), true)
    }

    fun onPeerDisconnected(peerId: String) {
        neighborStateListener?.invoke(NeighborEndpoint(peerId, peerId, transportId), false)
    }
}
