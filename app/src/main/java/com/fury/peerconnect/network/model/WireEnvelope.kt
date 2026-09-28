package com.fury.peerconnect.network.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

data class WireEnvelope(
    val id: String,
    val ttl: Int = CRITICAL_ALERT_MAX_HOPS,
    val hops: Int = 0,
    val relay: String? = null,
    val sig: ByteArray,
    val signedBytes: ByteArray,
    val senderPublicKey: ByteArray? = null
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

        other as WireEnvelope

        if (id != other.id) return false
        if (ttl != other.ttl) return false
        if (hops != other.hops) return false
        if (relay != other.relay) return false
        if (!sig.contentEquals(other.sig)) return false
        if (!signedBytes.contentEquals(other.signedBytes)) return false
        if (senderPublicKey != null) {
            if (other.senderPublicKey == null) return false
            if (!senderPublicKey.contentEquals(other.senderPublicKey)) return false
        } else if (other.senderPublicKey != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ttl
        result = 31 * result + hops
        result = 31 * result + (relay?.hashCode() ?: 0)
        result = 31 * result + sig.contentHashCode()
        result = 31 * result + signedBytes.contentHashCode()
        result = 31 * result + (senderPublicKey?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private const val serialVersionUID = 1L
        const val CRITICAL_ALERT_MAX_HOPS = 3

        fun deserialize(bytes: ByteArray): WireEnvelope? {
            return try {
                val bais = ByteArrayInputStream(bytes)
                val ois = ObjectInputStream(bais)
                val obj = ois.readObject()
                ois.close()
                obj as? WireEnvelope
            } catch (_: Exception) {
                null
            }
        }
    }
}
