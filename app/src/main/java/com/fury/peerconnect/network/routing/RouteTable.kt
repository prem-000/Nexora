package com.fury.peerconnect.network.routing

import java.util.concurrent.ConcurrentHashMap

class RouteTable {
    private val routes = ConcurrentHashMap<String, RouteEntry>()

    fun updateRoute(
        destinationPeerId: String,
        nextHopAddress: String,
        transportId: String,
        hopCount: Int,
        ttlMs: Long = 35000L,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val existing = routes[destinationPeerId]
        if (existing == null || existing.isExpired(now)) {
            routes[destinationPeerId] = RouteEntry(destinationPeerId, nextHopAddress, transportId, hopCount, now, now + ttlMs)
            return true
        }
        if (hopCount < existing.hopCount) {
            routes[destinationPeerId] = RouteEntry(destinationPeerId, nextHopAddress, transportId, hopCount, now, now + ttlMs)
            return true
        }
        if (hopCount == existing.hopCount && nextHopAddress == existing.nextHopAddress) {
            routes[destinationPeerId] = existing.copy(lastUpdated = now, expiresAt = now + ttlMs)
            return false
        }
        return false
    }

    fun getRoute(destinationPeerId: String, now: Long = System.currentTimeMillis()): RouteEntry? {
        val route = routes[destinationPeerId] ?: return null
        if (route.isExpired(now)) {
            routes.remove(destinationPeerId)
            return null
        }
        return route
    }

    fun removeRoute(destinationPeerId: String): RouteEntry? {
        return routes.remove(destinationPeerId)
    }

    fun invalidateRoutesForNeighbor(neighborAddress: String): List<String> {
        val affected = mutableListOf<String>()
        val it = routes.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.nextHopAddress == neighborAddress) {
                affected.add(entry.key)
                it.remove()
            }
        }
        return affected
    }

    fun purgeExpiredRoutes(now: Long = System.currentTimeMillis()): List<String> {
        val expired = mutableListOf<String>()
        val it = routes.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isExpired(now)) {
                expired.add(entry.key)
                it.remove()
            }
        }
        return expired
    }

    fun getAllActiveRoutes(now: Long = System.currentTimeMillis()): List<RouteEntry> {
        return routes.values.filter { !it.isExpired(now) }
    }

    fun clear() {
        routes.clear()
    }
}
