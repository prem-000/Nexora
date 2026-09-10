package com.fury.peerconnect.ui

sealed class ChatItem {
    data class DateSeparator(
        val dateText: String,
        val timestamp: Long
    ) : ChatItem()

    data class Message(
        val id: Int,
        val senderName: String,
        val messageBody: String,
        val time: Long,
        val isMe: Boolean,
        val deliveryStatus: String,
        val messageType: String = "TEXT",
        val fileName: String? = null,
        val localPath: String? = null,
        val fileSize: Long = 0L,
        val transferStatus: String = "SUCCESS",
        val alertId: String? = null,
        val transferProgress: Int = 0
    ) : ChatItem()
}
