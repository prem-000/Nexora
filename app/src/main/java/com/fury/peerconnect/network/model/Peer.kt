package com.fury.peerconnect.network.model

data class Peer(
    val deviceAddress: String,
    val deviceName: String? = null,
    val ipAddress: String? = null,
    val isGroupOwner: Boolean = false,
    val state: PeerState = PeerState.DISCOVERED,
    val lastSeen: Long = System.currentTimeMillis()
)
