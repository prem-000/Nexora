package com.fury.peerconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val type: String,
    val title: String,
    val description: String,
    val peerName: String? = null,
    val timestamp: Long,
    val isRead: Boolean = false,
    val alertId: String? = null,
    val attachmentPath: String? = null,
    val originPeerId: String? = null,
    val isCustodyActive: Boolean = true,
    val expiresAt: Long = 0L,
    val message: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
) {
    val body: String
        get() = if (!message.isNullOrBlank()) message else description
}
