package com.fury.peerconnect.network.model

import java.io.Serializable
import java.util.UUID

enum class MessageType {
    DIRECT,
    BROADCAST_ALERT,
    ACK,
    TOPOLOGY_SYNC
}

enum class PeerStatus {
    CONNECTED,
    DISCONNECTED
}

data class PresencePayload(
    val subjectPeerId: String,
    val status: PeerStatus,
    val connectionType: String = "DIRECT",
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val sourcePeerId: String,
    val destinationPeerId: String? = null,
    val type: MessageType,
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshMessage

        if (messageId != other.messageId) return false
        if (sourcePeerId != other.sourcePeerId) return false
        if (destinationPeerId != other.destinationPeerId) return false
        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        if (hopCount != other.hopCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sourcePeerId.hashCode()
        result = 31 * result + (destinationPeerId?.hashCode() ?: 0)
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + hopCount
        return result
    }
}
