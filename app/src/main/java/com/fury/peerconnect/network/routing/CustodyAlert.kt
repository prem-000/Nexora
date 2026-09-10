package com.fury.peerconnect.network.routing

import com.fury.peerconnect.network.model.MeshMessage
import java.util.concurrent.ConcurrentHashMap

data class CustodyAlert(
    val message: MeshMessage,
    val originPeerId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_ALERT_TTL_MS,
    val deliveredNeighbors: MutableSet<String> = ConcurrentHashMap.newKeySet()
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt

    companion object {
        const val DEFAULT_ALERT_TTL_MS: Long = 86400000L
    }
}
