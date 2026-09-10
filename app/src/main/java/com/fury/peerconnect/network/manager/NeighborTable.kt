package com.fury.peerconnect.network.manager

import com.fury.peerconnect.network.model.Peer
import com.fury.peerconnect.network.model.PeerState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class NeighborTable {
    private val peersMap = ConcurrentHashMap<String, Peer>()
    private val listeners = CopyOnWriteArrayList<(List<Peer>) -> Unit>()

    fun updatePeer(peer: Peer) {
        peersMap[peer.deviceAddress] = peer
        notifyListeners()
    }

    fun updatePeerState(deviceAddress: String, state: PeerState) {
        val existing = peersMap[deviceAddress] ?: return
        peersMap[deviceAddress] = existing.copy(
            state = state,
            lastSeen = System.currentTimeMillis()
        )
        notifyListeners()
    }

    fun removePeer(deviceAddress: String) {
        if (peersMap.remove(deviceAddress) != null) {
            notifyListeners()
        }
    }

    fun getPeer(deviceAddress: String): Peer? = peersMap[deviceAddress]

    fun getPeers(): List<Peer> = peersMap.values.toList()

    fun getRoutablePeers(): List<Peer> = peersMap.values.filter {
        it.state == PeerState.ROUTABLE || it.state == PeerState.TRANSPORT_CONNECTED
    }

    fun addListener(listener: (List<Peer>) -> Unit) {
        listeners.add(listener)
        listener(getPeers())
    }

    fun removeListener(listener: (List<Peer>) -> Unit) {
        listeners.remove(listener)
    }

    fun purgeStalePeers(staleThresholdMs: Long = 30000L, now: Long = System.currentTimeMillis()): List<Peer> {
        val stale = mutableListOf<Peer>()
        for ((addr, peer) in peersMap) {
            if (peer.state != PeerState.DISCONNECTED && (now - peer.lastSeen > staleThresholdMs)) {
                val updated = peer.copy(state = PeerState.DISCONNECTED, lastSeen = now)
                peersMap[addr] = updated
                stale.add(updated)
            }
        }
        if (stale.isNotEmpty()) {
            notifyListeners()
        }
        return stale
    }

    fun clear() {
        peersMap.clear()
        notifyListeners()
    }

    private fun notifyListeners() {
        val peers = getPeers()
        for (listener in listeners) {
            try {
                listener(peers)
            } catch (_: Exception) {}
        }
    }
}
