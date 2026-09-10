package com.fury.peerconnect.data

import java.io.Serializable

data class AlertPayload(
    val id: String,
    val senderId: String,
    val alertType: String = "SOS",
    val title: String = "SOS Alert",
    val message: String,
    val sentAt: Long = System.currentTimeMillis(),
    val attachmentPath: String? = null,
    val expiresAt: Long = 0L,
    val senderName: String = senderId,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
) : Serializable {

    fun toWireString(): String {
        val safeAtt = attachmentPath?.replace("|", "::PIPE::") ?: ""
        return if (latitude != null && longitude != null) {
            val accStr = accuracy?.toString() ?: "0.0"
            "[ALERT]:$id|$senderId|$alertType|$title|$message|$sentAt|$safeAtt|$expiresAt|$latitude|$longitude|$accStr"
        } else {
            "[ALERT]:$id|$senderId|$alertType|$title|$message|$sentAt|$safeAtt|$expiresAt"
        }
    }

    companion object {
        private const val PREFIX = "[ALERT]:"

        fun parseWireString(rawText: String) = parse(rawText)

        fun parse(rawText: String, fallbackSender: String = "", fallbackTime: Long = System.currentTimeMillis()): AlertPayload? {
            if (!rawText.startsWith(PREFIX)) return null
            val content = rawText.removePrefix(PREFIX)
            val parts = content.split("|")
            if (parts.isEmpty() || parts[0].isBlank()) return null

            // Format 1: Standard explicit fields
            if (parts.size >= 6 && (parts[0].startsWith("alert_") || parts[0].contains("-") || parts.size >= 7)) {
                val id = parts[0]
                val senderId = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else fallbackSender
                val alertType = if (parts.size > 2 && parts[2].isNotBlank()) parts[2] else "SOS"
                val title = if (parts.size > 3 && parts[3].isNotBlank()) parts[3] else "SOS Alert"
                val message = if (parts.size > 4) parts[4] else ""
                val sentAt = parts.getOrNull(5)?.toLongOrNull() ?: fallbackTime

                var expiresAt = sentAt + 86400000L
                var latitude: Double? = null
                var longitude: Double? = null
                var accuracy: Float? = null
                var attachmentEndIdx = parts.size

                // Check if last parts represent latitude, longitude, accuracy
                if (parts.size >= 10) {
                    val candidateAcc = parts[parts.size - 1].toFloatOrNull()
                    val candidateLon = parts[parts.size - 2].toDoubleOrNull()
                    val candidateLat = parts[parts.size - 3].toDoubleOrNull()

                    if (candidateAcc != null && candidateLon != null && candidateLat != null &&
                        candidateLat in -90.0..90.0 && candidateLon in -180.0..180.0) {
                        latitude = candidateLat
                        longitude = candidateLon
                        accuracy = candidateAcc

                        val candidateExp = parts[parts.size - 4].toLongOrNull()
                        if (candidateExp != null) {
                            expiresAt = candidateExp
                            attachmentEndIdx = parts.size - 4
                        } else {
                            attachmentEndIdx = parts.size - 3
                        }
                    }
                } else if (parts.size >= 9) {
                    val candidateLon = parts[parts.size - 1].toDoubleOrNull()
                    val candidateLat = parts[parts.size - 2].toDoubleOrNull()
                    if (candidateLon != null && candidateLat != null &&
                        candidateLat in -90.0..90.0 && candidateLon in -180.0..180.0) {
                        latitude = candidateLat
                        longitude = candidateLon
                        accuracy = 0.0f
                        val candidateExp = parts[parts.size - 3].toLongOrNull()
                        if (candidateExp != null) {
                            expiresAt = candidateExp
                            attachmentEndIdx = parts.size - 3
                        } else {
                            attachmentEndIdx = parts.size - 2
                        }
                    }
                }

                // If lat/lon were not detected at the very end, check if the last element is expiresAt
                if (latitude == null && parts.size >= 7) {
                    val lastVal = parts.last().toLongOrNull()
                    if (lastVal != null) {
                        expiresAt = lastVal
                        attachmentEndIdx = parts.size - 1
                    }
                }

                var attachment: String? = null
                if (attachmentEndIdx > 6) {
                    attachment = parts.subList(6, attachmentEndIdx)
                        .joinToString("|")
                        .replace("::PIPE::", "|")
                        .takeIf { it.isNotBlank() }
                }

                return AlertPayload(
                    id = id,
                    senderId = senderId,
                    alertType = alertType,
                    title = title,
                    message = message,
                    sentAt = sentAt,
                    attachmentPath = attachment,
                    expiresAt = expiresAt,
                    latitude = latitude,
                    longitude = longitude,
                    accuracy = accuracy
                )
            }

            // Format 2: 4 or 5 fields:
            // parts[0] = id (or type)
            // parts[1] = senderId (or title)
            // parts[2] = title (or message)
            // parts[3] = message (or originPeerId)
            if (parts.size in 4..5) {
                val first = parts[0]
                if (first.startsWith("alert_")) {
                    // id | sender | title | message (| attachment)
                    val id = first
                    val senderId = if (parts[1].isNotBlank()) parts[1] else fallbackSender
                    val title = if (parts[2].isNotBlank()) parts[2] else "SOS Alert"
                    val message = parts[3]
                    val attachment = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                    return AlertPayload(
                        id = id,
                        senderId = senderId,
                        alertType = "SOS",
                        title = title,
                        message = message,
                        sentAt = fallbackTime,
                        attachmentPath = attachment,
                        expiresAt = fallbackTime + 86400000L
                    )
                } else if (first == "SOS" || first == "EMERGENCY" || first == "BROADCAST") {
                    // type | title | message | senderId (| id)
                    val alertType = first
                    val title = parts[1]
                    val message = parts[2]
                    val senderId = if (parts[3].isNotBlank()) parts[3] else fallbackSender
                    val id = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "alert_${fallbackTime}"
                    return AlertPayload(
                        id = id,
                        senderId = senderId,
                        alertType = alertType,
                        title = title,
                        message = message,
                        sentAt = fallbackTime,
                        expiresAt = fallbackTime + 86400000L
                    )
                }
            }

            // Fallback for any other pipe-delimited string
            val id = if (parts[0].startsWith("alert_")) parts[0] else "alert_${fallbackTime}"
            val senderId = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else fallbackSender
            val message = if (parts.size > 3) parts[3] else (parts.getOrNull(2) ?: parts.getOrNull(1) ?: "")
            val title = if (parts.size > 2 && parts[2].isNotBlank() && parts[2] != message) parts[2] else "SOS Alert"

            return AlertPayload(
                id = id,
                senderId = senderId,
                alertType = "SOS",
                title = title,
                message = message,
                sentAt = fallbackTime,
                expiresAt = fallbackTime + 86400000L
            )
        }
    }
}
