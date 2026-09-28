package com.fury.peerconnect.logic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fury.peerconnect.R
import com.fury.peerconnect.data.AlertDao
import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.data.BlobRepository
import com.fury.peerconnect.network.manager.BlobExchange
import com.fury.peerconnect.network.model.EmergencyCategory
import com.fury.peerconnect.network.model.EmergencyCriticalContent
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.MessageType
import com.fury.peerconnect.network.model.RelayEnvelope
import com.fury.peerconnect.network.model.WireEnvelope
import com.fury.peerconnect.network.routing.RoutingEngine
import com.fury.peerconnect.ui.MainActivity
import java.security.KeyPair

class CriticalAlertManager(
    private val context: Context,
    private val alertDao: AlertDao,
    private val routingEngineProvider: () -> RoutingEngine?,
    private val userManager: UserManager,
    private val blobExchangeProvider: () -> BlobExchange?,
    private val blobRepository: BlobRepository? = null,
    private val offlineMapManagerProvider: () -> OfflineMapManager? = { null },
    private val locationProvider: () -> Pair<Double, Double>? = { null }
) {
    private val TAG = "CriticalAlertManager"

    companion object {
        const val CHANNEL_CRITICAL_EMERGENCY = "nexora_channel_critical_emergency"
        const val NOTIFICATION_ID_CRITICAL_BASE = 900000
    }

    init {
        createNotificationChannelIfNeeded()
    }

    fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_CRITICAL_EMERGENCY,
                "Critical Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent, life-safety critical emergency alerts"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    suspend fun createAndBroadcastCriticalAlert(
        category: EmergencyCategory,
        title: String,
        message: String,
        radiusMeters: Double? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        attachmentUri: Uri? = null,
        attachmentFileName: String? = null,
        expiresAt: Long = System.currentTimeMillis() + 86400000L
    ): WireEnvelope? {
        val alertId = "crit_alert_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val myNickName = userManager.getUsername() ?: "Node_${userManager.getDeviceId().take(6)}"
        val keyPair: KeyPair = userManager.getOrCreateIdentityKeyPair()

        var localAttachmentStr: String? = null
        var wireAttachmentHash: String? = null
        var wireAttachmentMime: String? = null

        if (attachmentUri != null && attachmentFileName != null) {
            val copied = FileStorageManager.copyUriToLocalStorage(context, attachmentUri, attachmentFileName)
            val localPath = copied?.absolutePath ?: attachmentUri.toString()
            val fileSize = copied?.length() ?: 0L
            val hash = if (copied != null) MeshBlobStore.computeSha256(copied) else ""
            if (copied != null && hash.isNotBlank()) {
                wireAttachmentHash = hash
                MeshBlobStore.saveIncoming(context, hash, copied)
                blobRepository?.saveIncoming(hash, copied)
            }
            val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(attachmentFileName ?: "")
            wireAttachmentMime = if (!ext.isNullOrBlank()) {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            } else "application/octet-stream"
            localAttachmentStr = "[FILE]:$attachmentFileName|$localPath|$hash|$fileSize|SUCCESS"
        }

        val content = EmergencyCriticalContent(
            category = category,
            severity = "CRITICAL",
            title = title,
            message = message,
            expiresAt = expiresAt,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            attachmentHash = wireAttachmentHash,
            attachmentMime = wireAttachmentMime
        )

        val contentBytes = content.serialize()
        val relayEnvelope = RelayEnvelope(
            type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY,
            senderId = myNickName,
            sentAt = System.currentTimeMillis(),
            payload = contentBytes
        )

        val signedBytes = relayEnvelope.serialize()
        val signature = try {
            SecurityHelper.signData(signedBytes, keyPair.private)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing critical alert $alertId", e)
            ByteArray(0)
        }
        val publicKeyBytes = keyPair.public.encoded

        val wireEnvelope = WireEnvelope(
            id = alertId,
            ttl = WireEnvelope.CRITICAL_ALERT_MAX_HOPS,
            hops = 0,
            relay = null,
            sig = signature,
            signedBytes = signedBytes,
            senderPublicKey = publicKeyBytes
        )

        val alertEntity = AlertEntity(
            id = 0,
            type = "CRITICAL_EMERGENCY",
            title = title,
            description = message,
            message = message,
            timestamp = relayEnvelope.sentAt,
            isRead = true,
            peerName = myNickName,
            attachmentPath = localAttachmentStr,
            alertId = alertId,
            originPeerId = myNickName,
            isCustodyActive = true,
            expiresAt = expiresAt,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            category = category.name,
            severity = "CRITICAL",
            radiusMeters = radiusMeters
        )
        try {
            alertDao.insertAlert(alertEntity)
            Log.i(TAG, "Successfully saved critical emergency alert $alertId to database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed inserting critical alert $alertId into database", e)
        }

        if (latitude != null && longitude != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    offlineMapManagerProvider()?.updateAlertLocation(
                        alertId = alertId,
                        title = "🚨 ${category.name}: $title (You)",
                        latitude = latitude,
                        longitude = longitude,
                        distanceText = "At your current location"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating map marker on main thread: ${e.message}")
                }
            }
        }

        val engine = routingEngineProvider()
        if (engine != null) {
            val wireBytes = wireEnvelope.serialize()
            val meshMsg = MeshMessage(
                messageId = alertId,
                sourcePeerId = myNickName,
                destinationPeerId = null,
                type = MessageType.BROADCAST_ALERT,
                payload = wireBytes,
                timestamp = relayEnvelope.sentAt,
                hopCount = 0
            )
            engine.duplicateCache.markProcessed(alertId)
            engine.alertCustodyStore.storeAlert(meshMsg, myNickName, ttlMs = maxOf(0L, expiresAt - System.currentTimeMillis()))
            engine.broadcastAcrossAllTransports(meshMsg)
            Log.i(TAG, "Critical emergency alert $alertId created, signed, and broadcast via mesh")
        }

        return wireEnvelope
    }

    suspend fun processIncomingWireEnvelope(
        wireEnvelope: WireEnvelope,
        senderNeighborAddress: String,
        incomingTransportId: String
    ): Boolean {
        val alertId = wireEnvelope.id
        val engine = routingEngineProvider()

        if (engine != null && engine.duplicateCache.isDuplicate(alertId)) {
            Log.d(TAG, "Critical emergency $alertId is duplicate. Dropping.")
            return false
        }
        engine?.duplicateCache?.markProcessed(alertId)

        // 1. Verify cryptographic frame signature
        val isSigValid = SecurityHelper.verifySignature(
            data = wireEnvelope.signedBytes,
            signatureBytes = wireEnvelope.sig,
            publicKeyBytes = wireEnvelope.senderPublicKey
        )
        if (!isSigValid) {
            Log.e(TAG, "Critical emergency $alertId signature verification FAILED! Rejecting frame.")
            return false
        }

        // 2. Decode RelayEnvelope
        val relayEnvelope = RelayEnvelope.deserialize(wireEnvelope.signedBytes)
        if (relayEnvelope == null) {
            Log.e(TAG, "Malformed RelayEnvelope in critical emergency $alertId. Rejecting.")
            return false
        }

        // 3. Decode EmergencyCriticalContent if type matches
        val content = if (relayEnvelope.type == RelayEnvelope.TYPE_CRITICAL_EMERGENCY) {
            EmergencyCriticalContent.deserialize(relayEnvelope.payload)
        } else null

        val now = System.currentTimeMillis()
        if (content != null && content.expiresAt in 1..now) {
            Log.w(TAG, "Critical emergency $alertId is expired (${content.expiresAt} < $now). Dropping.")
            return false
        }

        // 4. Evaluate RELAY ELIGIBILITY (Decoupled from notification eligibility)
        val canRelay = wireEnvelope.hops + 1 <= WireEnvelope.CRITICAL_ALERT_MAX_HOPS && wireEnvelope.ttl > 1
        if (canRelay && engine != null) {
            val forwardedWire = wireEnvelope.copy(
                hops = wireEnvelope.hops + 1,
                ttl = wireEnvelope.ttl - 1,
                relay = engine.myDeviceId
            )
            val forwardedMsg = MeshMessage(
                messageId = alertId,
                sourcePeerId = relayEnvelope.senderId,
                destinationPeerId = null,
                type = MessageType.BROADCAST_ALERT,
                payload = forwardedWire.serialize(),
                timestamp = relayEnvelope.sentAt,
                hopCount = forwardedWire.hops
            )
            val remainingTtlMs = if (content != null) maxOf(0L, content.expiresAt - now) else 86400000L
            engine.alertCustodyStore.storeAlert(forwardedMsg, relayEnvelope.senderId, ttlMs = remainingTtlMs)
            engine.alertCustodyStore.markDeliveredTo(alertId, senderNeighborAddress)
            engine.broadcastAcrossAllTransports(forwardedMsg, excludeAddress = senderNeighborAddress, excludeTransportId = incomingTransportId)
            Log.i(TAG, "Critical emergency $alertId automatically relayed (next hop=${forwardedWire.hops}, ttl=${forwardedWire.ttl})")
        } else {
            Log.d(TAG, "Critical emergency $alertId will not relay further (hops=${wireEnvelope.hops}, ttl=${wireEnvelope.ttl})")
        }

        if (content == null) {
            return true
        }

        // 5. Evaluate USER NOTIFICATION ELIGIBILITY
        val isNotificationEligible = evaluateNotificationEligibility(content)
        var localAttachmentStr: String? = null
        if (!content.attachmentHash.isNullOrBlank()) {
            val hash = content.attachmentHash
            val mime = content.attachmentMime ?: "application/octet-stream"
            val fileName = "emergency_attachment_${hash.take(8)}"
            blobExchangeProvider()?.registerCustodyHash(hash, relayEnvelope.senderId)
            if (MeshBlobStore.has(context, hash)) {
                val file = MeshBlobStore.getFile(context, hash)
                localAttachmentStr = "[FILE]:$fileName|${file?.absolutePath ?: ""}|$hash|0|SUCCESS"
            } else {
                localAttachmentStr = "[FILE]:$fileName||$hash|0|RECEIVING"
                blobExchangeProvider()?.want(hash, relayEnvelope.senderId)
            }
        }

        val alertEntity = AlertEntity(
            id = 0,
            type = "CRITICAL_EMERGENCY",
            title = content.title,
            description = content.message,
            message = content.message,
            timestamp = relayEnvelope.sentAt,
            isRead = false,
            peerName = relayEnvelope.senderId,
            attachmentPath = localAttachmentStr,
            alertId = alertId,
            originPeerId = relayEnvelope.senderId,
            isCustodyActive = true,
            expiresAt = content.expiresAt,
            latitude = content.latitude,
            longitude = content.longitude,
            accuracy = null,
            category = content.category.name,
            severity = content.severity,
            radiusMeters = content.radiusMeters
        )
        alertDao.insertAlert(alertEntity)

        var distanceText: String? = null
        if (content.latitude != null && content.longitude != null) {
            val myCoords = locationProvider()
            if (myCoords != null) {
                val distMeters = DistanceEngine.calculateHaversineDistance(
                    myCoords.first,
                    myCoords.second,
                    content.latitude,
                    content.longitude
                )
                distanceText = DistanceEngine.formatDistance(distMeters)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    offlineMapManagerProvider()?.updateAlertLocation(
                        alertId = alertId,
                        title = "🚨 ${content.category.name}: ${content.title}",
                        latitude = content.latitude,
                        longitude = content.longitude,
                        distanceText = distanceText
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating map marker for incoming alert on main thread: ${e.message}")
                }
            }
        }

        if (isNotificationEligible) {
            showCriticalEmergencyNotification(
                alertId = alertId,
                content = content,
                senderId = relayEnvelope.senderId,
                distanceText = distanceText
            )
        } else {
            Log.d(TAG, "Critical emergency $alertId is outside notification radius (${content.radiusMeters}m). Suppressing local notification.")
        }

        return true
    }

    fun evaluateNotificationEligibility(content: EmergencyCriticalContent): Boolean {
        val radius = content.radiusMeters
        if (radius == null || radius <= 0) return true
        val alertLat = content.latitude ?: return true
        val alertLon = content.longitude ?: return true

        val myCoords = locationProvider() ?: return true
        val distMeters = DistanceEngine.calculateHaversineDistance(
            myCoords.first,
            myCoords.second,
            alertLat,
            alertLon
        )
        return distMeters <= radius
    }

    private fun showCriticalEmergencyNotification(
        alertId: String,
        content: EmergencyCriticalContent,
        senderId: String,
        distanceText: String?
    ) {
        val distSuffix = if (!distanceText.isNullOrBlank()) " • $distanceText away" else ""
        val titleText = "🚨 CRITICAL EMERGENCY: [${content.category.name}] ${content.title}"
        val bodyText = "From $senderId$distSuffix\n${content.message}".trim()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ALERT_ID", alertId)
            putExtra("ALERT_TYPE", "CRITICAL_EMERGENCY")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CRITICAL_EMERGENCY)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.RED)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = NOTIFICATION_ID_CRITICAL_BASE + (alertId.hashCode() and 0x7FFFFFFF % 10000)
        notificationManager?.notify(notificationId, builder.build())
    }
}
