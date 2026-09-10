package com.fury.peerconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long,
    val isSent: Boolean = false,
    val alertId: String? = null,
    val deliveryStatus: String = "PENDING",
    val messageType: String = "TEXT",
    val fileName: String? = null,
    val localPath: String? = null,
    val fileSize: Long = 0L,
    val transferStatus: String = "SUCCESS",
    val senderMessageId: String? = null
)
