package com.fury.peerconnect.network

import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.transport.MeshTransport
import com.fury.peerconnect.network.transport.NeighborEndpoint
import java.util.concurrent.ConcurrentHashMap

class FakeMeshTransport(
    val localAddress: String,
    val localPeerName: String,
    override val transportId: String = "fake_transport"
) : MeshTransport {

    private val neighbors = ConcurrentHashMap<String, FakeMeshTransport>()
    private var packetReceiver: ((packet: MeshMessage, fromNeighborAddress: String) -> Unit)? = null
    private var neighborListener: ((neighbor: NeighborEndpoint, isConnected: Boolean) -> Unit)? = null

    var simulateDropPackets: Boolean = false

    fun connectTo(other: FakeMeshTransport) {
        neighbors[other.localAddress] = other
        other.neighbors[this.localAddress] = this

        this.neighborListener?.invoke(
            NeighborEndpoint(other.localAddress, other.localPeerName, transportId),
            true
        )
        other.neighborListener?.invoke(
            NeighborEndpoint(this.localAddress, this.localPeerName, other.transportId),
            true
        )
    }

    fun disconnectFrom(other: FakeMeshTransport) {
        neighbors.remove(other.localAddress)
        other.neighbors.remove(this.localAddress)

        this.neighborListener?.invoke(
            NeighborEndpoint(other.localAddress, other.localPeerName, transportId),
            false
        )
        other.neighborListener?.invoke(
            NeighborEndpoint(this.localAddress, this.localPeerName, other.transportId),
            false
        )
    }

    override fun send(packet: MeshMessage, nextHopAddress: String): Boolean {
        if (simulateDropPackets) return false
        val neighbor = neighbors[nextHopAddress] ?: return false
        neighbor.packetReceiver?.invoke(packet, localAddress)
        return true
    }

    override fun broadcast(packet: MeshMessage, excludeAddress: String?) {
        if (simulateDropPackets) return
        for ((addr, neighbor) in neighbors) {
            if (addr != excludeAddress) {
                neighbor.packetReceiver?.invoke(packet, localAddress)
            }
        }
    }

    override fun getConnectedNeighbors(): List<NeighborEndpoint> {
        return neighbors.values.map {
            NeighborEndpoint(it.localAddress, it.localPeerName, transportId)
        }
    }

    override fun setPacketReceiver(receiver: (packet: MeshMessage, fromNeighborAddress: String) -> Unit) {
        this.packetReceiver = receiver
    }

    override fun setNeighborStateListener(listener: (neighbor: NeighborEndpoint, isConnected: Boolean) -> Unit) {
        this.neighborListener = listener
    }
}
