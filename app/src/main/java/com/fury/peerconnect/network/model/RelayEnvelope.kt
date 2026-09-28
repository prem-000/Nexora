package com.fury.peerconnect.network.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

data class RelayEnvelope(
    val type: String = TYPE_CRITICAL_EMERGENCY,
    val senderId: String,
    val sentAt: Long = System.currentTimeMillis(),
    val payload: ByteArray
) : Serializable {

    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(this)
        oos.flush()
        return baos.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RelayEnvelope

        if (type != other.type) return false
        if (senderId != other.senderId) return false
        if (sentAt != other.sentAt) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + sentAt.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        private const val serialVersionUID = 1L
        const val TYPE_CRITICAL_EMERGENCY = "critical_emergency"

        fun deserialize(bytes: ByteArray): RelayEnvelope? {
            return try {
                val bais = ByteArrayInputStream(bytes)
                val ois = ObjectInputStream(bais)
                val obj = ois.readObject()
                ois.close()
                obj as? RelayEnvelope
            } catch (_: Exception) {
                null
            }
        }
    }
}
