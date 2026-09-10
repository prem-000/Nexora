package com.fury.peerconnect.network.routing

import com.fury.peerconnect.network.model.MeshMessage
import java.util.concurrent.ConcurrentHashMap

class AlertCustodyStore(private val maxAlerts: Int = 100) {

    private final val custodyMap = ConcurrentHashMap<String, CustodyAlert>()

    fun storeAlert(
        message: MeshMessage,
        originPeerId: String,
        ttlMs: Long = 86400000L,
        now: Long = System.currentTimeMillis()
    ) {
        if (custodyMap.size >= maxAlerts) {
            var oldestEntry: Map.Entry<String, CustodyAlert>? = null
            var oldestTime = Long.MAX_VALUE
            for (entry in custodyMap.entries) {
                if (entry.value.createdAt < oldestTime) {
                    oldestTime = entry.value.createdAt
                    oldestEntry = entry
                }
            }
            oldestEntry?.key?.let { custodyMap.remove(it) }
        }
        val alert = CustodyAlert(
            message = message,
            originPeerId = originPeerId,
            createdAt = now,
            expiresAt = now + ttlMs
        )
        custodyMap[message.messageId] = alert
    }

    fun markDeliveredTo(messageId: String, neighborAddress: String) {
        custodyMap[messageId]?.deliveredNeighbors?.add(neighborAddress)
    }

    fun hasDeliveredTo(messageId: String, neighborAddress: String): Boolean {
        return custodyMap[messageId]?.deliveredNeighbors?.contains(neighborAddress) == true
    }

    fun getActiveAlertsForNeighbor(
        neighborAddress: String,
        now: Long = System.currentTimeMillis()
    ): List<MeshMessage> {
        val result = mutableListOf<MeshMessage>()
        val it = custodyMap.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isExpired(now)) {
                it.remove()
            } else if (!entry.value.deliveredNeighbors.contains(neighborAddress)) {
                result.add(entry.value.message)
            }
        }
        return result
    }

    fun getAllActiveAlerts(now: Long = System.currentTimeMillis()): List<MeshMessage> {
        return custodyMap.values
            .filter { !it.isExpired(now) }
            .map { it.message }
    }

    fun clear() {
        custodyMap.clear()
    }
}
