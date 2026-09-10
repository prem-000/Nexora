package com.fury.peerconnect.network.transport

import com.fury.peerconnect.network.model.MeshMessage

data class NeighborEndpoint(
    val neighborAddress: String,
    val peerName: String,
    val transportId: String
)

interface MeshTransport {
    val transportId: String

    fun send(packet: MeshMessage, nextHopAddress: String): Boolean

    fun broadcast(packet: MeshMessage, excludeAddress: String? = null)

    fun getConnectedNeighbors(): List<NeighborEndpoint>

    fun setPacketReceiver(receiver: (packet: MeshMessage, fromNeighborAddress: String) -> Unit)

    fun setNeighborStateListener(listener: (neighbor: NeighborEndpoint, isConnected: Boolean) -> Unit)
}
