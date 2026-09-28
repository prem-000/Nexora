package com.fury.peerconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    val name: String,
    val endpointId: String,
    val lastSeenTimestamp: Long,
    val isOnline: Boolean = false,
    val nextHop: String? = null,
    val hopDistance: Int = 0,
    val isReachable: Boolean = false,
    val deviceId: String? = null,
    val avatarId: String? = "avatar_01",
    val profileVersion: Long = 1L,
    val connectionStatus: String = "DISCOVERED",
    val isConnected: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lastDistanceMeters: Double? = null
)
