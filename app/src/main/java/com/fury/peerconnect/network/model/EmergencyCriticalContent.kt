package com.fury.peerconnect.network.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

enum class EmergencyCategory {
    MEDICAL,
    SEARCH_AND_RESCUE,
    DISASTER,
    SECURITY,
    HAZARD,
    OTHER;

    companion object {
        fun fromString(value: String?): EmergencyCategory {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: OTHER
        }
    }
}

data class EmergencyCriticalContent(
    val category: EmergencyCategory = EmergencyCategory.OTHER,
    val severity: String = "CRITICAL",
    val title: String,
    val message: String,
    val expiresAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Double? = null,
    val attachmentHash: String? = null,
    val attachmentMime: String? = null
) : Serializable {

    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(this)
        oos.flush()
        return baos.toByteArray()
    }

    companion object {
        private const val serialVersionUID = 1L

        fun deserialize(bytes: ByteArray): EmergencyCriticalContent? {
            return try {
                val bais = ByteArrayInputStream(bytes)
                val ois = ObjectInputStream(bais)
                val obj = ois.readObject()
                ois.close()
                obj as? EmergencyCriticalContent
            } catch (_: Exception) {
                null
            }
        }
    }
}
