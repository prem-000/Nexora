package com.fury.peerconnect.network.routing

data class RouteEntry(
    val destinationPeerId: String,
    val nextHopAddress: String,
    val transportId: String,
    val hopCount: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS
) {
    val isDirect: Boolean get() = hopCount == 1

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt

    companion object {
        const val DEFAULT_TTL_MS: Long = 35000L
    }
}
