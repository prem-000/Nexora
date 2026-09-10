package com.fury.peerconnect.network.routing

import android.util.Log
import com.fury.peerconnect.network.manager.ConnectionManager
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.MessageType
import com.fury.peerconnect.network.model.PeerStatus
import com.fury.peerconnect.network.model.PresencePayload
import com.fury.peerconnect.network.transport.MeshTransport
import com.fury.peerconnect.network.transport.NeighborEndpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RoutingEngine(
    val myDeviceId: String,
    val connectionManager: ConnectionManager? = null,
    val onApplicationPayloadReceived: (sourcePeerId: String, messageId: String, type: MessageType, payload: ByteArray) -> Unit,
    val onAckReceived: (ackMessageId: String, originalMessageId: String, fromPeerId: String) -> Unit = { _, _, _ -> },
    val isPeerAuthorized: (peerId: String) -> Boolean = { true },
    val onReachabilityChanged: (peerName: String, isReachable: Boolean, nextHop: String?, hopDistance: Int) -> Unit = { _, _, _, _ -> },
    val onRouteDiscovered: (peerName: String) -> Unit = {}
) {
    private val TAG = "RoutingEngine"
    val duplicateCache = DuplicateSuppressionCache(1000)
    val routeTable = RouteTable()
    val alertCustodyStore = AlertCustodyStore(100)
    private val transports = CopyOnWriteArrayList<MeshTransport>()
    internal val pendingUnicastCustody = CopyOnWriteArrayList<MeshMessage>()

    fun registerTransport(transport: MeshTransport) {
        if (!transports.contains(transport)) {
            transports.add(transport)
            transport.setPacketReceiver { packet, fromNeighbor ->
                processIncomingMessage(packet, fromNeighbor, transport.transportId)
            }
            transport.setNeighborStateListener { neighbor, isConnected ->
                handleNeighborStateChanged(neighbor, isConnected)
            }
            Log.d(TAG, "Registered transport: ${transport.transportId}")
        }
    }

    fun unregisterTransport(transport: MeshTransport) {
        transports.remove(transport)
    }

    private fun handleNeighborStateChanged(neighbor: NeighborEndpoint, isConnected: Boolean) {
        Log.d(TAG, "Neighbor state changed: ${neighbor.peerName} (${neighbor.neighborAddress}) on ${neighbor.transportId} connected=$isConnected")
        if (isConnected) {
            routeTable.updateRoute(
                destinationPeerId = neighbor.peerName,
                nextHopAddress = neighbor.neighborAddress,
                transportId = neighbor.transportId,
                hopCount = 1
            )
            onReachabilityChanged(neighbor.peerName, true, neighbor.neighborAddress, 1)
            onRouteDiscovered(neighbor.peerName)
            flushPendingUnicast(neighbor.peerName)

            val pendingAlerts = alertCustodyStore.getActiveAlertsForNeighbor(neighbor.neighborAddress)
            for (alertMsg in pendingAlerts) {
                val transport = transports.find { it.transportId == neighbor.transportId }
                if (transport != null) {
                    val forwarded = alertMsg.copy(hopCount = alertMsg.hopCount + 1)
                    transport.send(forwarded, neighbor.neighborAddress)
                    alertCustodyStore.markDeliveredTo(alertMsg.messageId, neighbor.neighborAddress)
                }
            }
        } else {
            val invalidatedPeers = routeTable.invalidateRoutesForNeighbor(neighbor.neighborAddress)
            for (peer in invalidatedPeers) {
                val alternateRoute = routeTable.getRoute(peer)
                if (alternateRoute == null) {
                    onReachabilityChanged(peer, false, null, 0)
                } else {
                    onReachabilityChanged(peer, true, alternateRoute.nextHopAddress, alternateRoute.hopCount)
                }
            }
        }
    }

    fun processIncomingMessage(
        message: MeshMessage,
        senderNeighborAddress: String,
        incomingTransportId: String = "DEFAULT"
    ) {
        Log.d(TAG, "Processing incoming message ${message.messageId} from $senderNeighborAddress on $incomingTransportId (type=${message.type}, src=${message.sourcePeerId}, dest=${message.destinationPeerId}, hop=${message.hopCount})")

        if (duplicateCache.isDuplicate(message.messageId)) {
            Log.d(TAG, "Message ${message.messageId} is a duplicate. Suppressing.")
            return
        }

        if (message.hopCount > 3) {
            Log.w(TAG, "Presence/Message ${message.messageId} exceeded hop limit (${message.hopCount} > 3). Dropping packet.")
            return
        }

        if (!isPeerAuthorized(message.sourcePeerId)) {
            Log.w(TAG, "Message ${message.messageId} rejected: Originator ${message.sourcePeerId} is unauthorized.")
            return
        }

        val isForMe = message.destinationPeerId == null || message.destinationPeerId == myDeviceId
        if (isForMe) {
            when (message.type) {
                MessageType.ACK -> {
                    val ackedMsgId = String(message.payload, Charsets.UTF_8)
                    Log.d(TAG, "ACK received for message $ackedMsgId from ${message.sourcePeerId}")
                    onAckReceived(message.messageId, ackedMsgId, message.sourcePeerId)
                }
                MessageType.TOPOLOGY_SYNC -> {
                    processTopologySync(message, senderNeighborAddress, incomingTransportId)
                }
                MessageType.BROADCAST_ALERT -> {
                    alertCustodyStore.storeAlert(message, message.sourcePeerId)
                    alertCustodyStore.markDeliveredTo(message.messageId, senderNeighborAddress)
                    onApplicationPayloadReceived(message.sourcePeerId, message.messageId, message.type, message.payload)
                }
                else -> {
                    onApplicationPayloadReceived(message.sourcePeerId, message.messageId, message.type, message.payload)
                }
            }
        }

        if (message.type == MessageType.BROADCAST_ALERT || message.type == MessageType.TOPOLOGY_SYNC || message.destinationPeerId == null) {
            if (message.hopCount + 1 <= 3) {
                val forwarded = message.copy(hopCount = message.hopCount + 1)
                Log.d(TAG, "Forwarding broadcast message ${message.messageId} (next hop=${forwarded.hopCount})")
                broadcastAcrossTransports(forwarded, senderNeighborAddress, incomingTransportId)
            } else {
                Log.d(TAG, "Not forwarding broadcast message ${message.messageId}: Hop limit ceiling reached.")
            }
            return
        }

        if (message.destinationPeerId != null && message.destinationPeerId != myDeviceId) {
            if (message.hopCount + 1 <= 3) {
                val forwarded = message.copy(hopCount = message.hopCount + 1)
                val dest = message.destinationPeerId
                val route = routeTable.getRoute(dest)
                if (route == null) {
                    Log.w(TAG, "No route found for $dest. Storing in custody outbox.")
                    pendingUnicastCustody.add(forwarded)
                    return
                }
                Log.d(TAG, "Routing message ${message.messageId} to $dest via ${route.nextHopAddress} on ${route.transportId}")
                val sent = sendViaTransport(route.transportId, route.nextHopAddress, forwarded)
                if (!sent) {
                    Log.w(TAG, "Failed forwarding to ${route.nextHopAddress}. Storing in custody outbox.")
                    pendingUnicastCustody.add(forwarded)
                }
            } else {
                Log.w(TAG, "Unicast message ${message.messageId} to ${message.destinationPeerId} exceeded hop limit (${message.hopCount + 1} > 3). Dropping.")
            }
        }
    }

    private fun processTopologySync(message: MeshMessage, senderNeighborAddress: String, incomingTransportId: String) {
        try {
            val bais = ByteArrayInputStream(message.payload)
            val ois = ObjectInputStream(bais)
            val presence = ois.readObject() as PresencePayload
            ois.close()

            val subject = presence.subjectPeerId
            if (subject == myDeviceId) return

            if (presence.status == PeerStatus.CONNECTED) {
                val currentHop = message.hopCount + 1
                routeTable.updateRoute(
                    destinationPeerId = subject,
                    nextHopAddress = senderNeighborAddress,
                    transportId = incomingTransportId,
                    hopCount = currentHop
                )
                val bestRoute = routeTable.getRoute(subject)
                if (bestRoute != null) {
                    onReachabilityChanged(subject, true, bestRoute.nextHopAddress, bestRoute.hopCount)
                }
                onRouteDiscovered(subject)
                flushPendingUnicast(subject)
            } else {
                routeTable.removeRoute(subject)
                onReachabilityChanged(subject, false, null, 0)
            }
            onApplicationPayloadReceived(message.sourcePeerId, message.messageId, message.type, message.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse TOPOLOGY_SYNC presence payload", e)
        }
    }

    fun sendDirectMessage(
        destinationPeerId: String,
        payload: ByteArray,
        messageId: String = UUID.randomUUID().toString()
    ): MeshMessage {
        val message = MeshMessage(
            messageId = messageId,
            sourcePeerId = myDeviceId,
            destinationPeerId = destinationPeerId,
            type = MessageType.DIRECT,
            payload = payload
        )
        duplicateCache.markProcessed(message.messageId)

        val route = routeTable.getRoute(destinationPeerId)
        if (route != null) {
            val sent = sendViaTransport(route.transportId, route.nextHopAddress, message)
            if (!sent) {
                Log.w(TAG, "Failed immediate send to ${route.nextHopAddress}. Adding to pending custody.")
                pendingUnicastCustody.add(message)
            }
        } else if (connectionManager?.getConnection(destinationPeerId) != null) {
            connectionManager.sendTo(destinationPeerId, message)
        } else {
            Log.d(TAG, "No route yet to $destinationPeerId. Storing in pending custody.")
            pendingUnicastCustody.add(message)
        }
        return message
    }

    fun broadcastAlert(
        payload: ByteArray,
        messageId: String = UUID.randomUUID().toString()
    ): MeshMessage {
        val message = MeshMessage(
            messageId = messageId,
            sourcePeerId = myDeviceId,
            destinationPeerId = null,
            type = MessageType.BROADCAST_ALERT,
            payload = payload
        )
        duplicateCache.markProcessed(message.messageId)
        alertCustodyStore.storeAlert(message, myDeviceId)
        Log.d(TAG, "Broadcasting alert ${message.messageId} across all transports")
        broadcastAcrossTransports(message)
        return message
    }

    fun broadcastPresence(
        presence: PresencePayload,
        messageId: String = UUID.randomUUID().toString()
    ): MeshMessage {
        val serializedBytes = try {
            val out = ByteArrayOutputStream()
            val oStream = ObjectOutputStream(out)
            oStream.writeObject(presence)
            oStream.flush()
            val bytes = out.toByteArray()
            oStream.close()
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize PresencePayload", e)
            ByteArray(0)
        }

        val message = MeshMessage(
            messageId = messageId,
            sourcePeerId = myDeviceId,
            destinationPeerId = null,
            type = MessageType.TOPOLOGY_SYNC,
            payload = serializedBytes
        )
        duplicateCache.markProcessed(message.messageId)
        Log.d(TAG, "Broadcasting TOPOLOGY_SYNC for subject=${presence.subjectPeerId} status=${presence.status}")
        broadcastAcrossTransports(message)
        return message
    }

    fun sendReceipt(
        targetPeerId: String,
        originalMessageIdOrBatch: String,
        receiptType: String = "DELIVERED"
    ): MeshMessage {
        val payloadStr = if (receiptType.isNotBlank()) "$originalMessageIdOrBatch:$receiptType" else originalMessageIdOrBatch
        val ackMessage = MeshMessage(
            messageId = UUID.randomUUID().toString(),
            sourcePeerId = myDeviceId,
            destinationPeerId = targetPeerId,
            type = MessageType.ACK,
            payload = payloadStr.toByteArray(Charsets.UTF_8)
        )
        duplicateCache.markProcessed(ackMessage.messageId)

        val route = routeTable.getRoute(targetPeerId)
        if (route != null) {
            val sent = sendViaTransport(route.transportId, route.nextHopAddress, ackMessage)
            if (!sent) {
                Log.w(TAG, "Failed sending receipt to ${route.nextHopAddress}. Adding to pending custody.")
                pendingUnicastCustody.add(ackMessage)
            }
        } else if (connectionManager?.getConnection(targetPeerId) != null) {
            connectionManager.sendTo(targetPeerId, ackMessage)
        } else {
            Log.d(TAG, "No route yet to $targetPeerId for receipt. Adding to pending custody.")
            pendingUnicastCustody.add(ackMessage)
        }
        return ackMessage
    }

    fun sendAck(targetPeerId: String, originalMessageId: String) {
        sendReceipt(targetPeerId, originalMessageId, "DELIVERED")
    }

    private fun broadcastAcrossTransports(
        message: MeshMessage,
        excludeAddress: String? = null,
        excludeTransportId: String? = null
    ) {
        for (transport in transports) {
            val excludeForThisTransport = if (transport.transportId == excludeTransportId) excludeAddress else null
            transport.broadcast(message, excludeForThisTransport)
        }
        connectionManager?.broadcast(message, excludeAddress)
    }

    private fun sendViaTransport(transportId: String, nextHopAddress: String, message: MeshMessage): Boolean {
        val transport = transports.find { it.transportId == transportId }
        if (transport != null) {
            return transport.send(message, nextHopAddress)
        }
        if (connectionManager != null) {
            return connectionManager.sendTo(nextHopAddress, message)
        }
        return false
    }

    fun flushPendingUnicast(destinationPeerId: String) {
        val route = routeTable.getRoute(destinationPeerId) ?: return
        for (msg in pendingUnicastCustody) {
            if (msg.destinationPeerId == destinationPeerId) {
                Log.d(TAG, "Flushing pending custody message ${msg.messageId} to $destinationPeerId via ${route.nextHopAddress}")
                val sent = sendViaTransport(route.transportId, route.nextHopAddress, msg)
                if (sent) {
                    pendingUnicastCustody.remove(msg)
                }
            }
        }
    }

    fun isPeerReachable(peerName: String): Boolean {
        if (routeTable.getRoute(peerName) == null) {
            if (connectionManager?.getConnection(peerName) == null) {
                return false
            }
        }
        return true
    }

    fun purgeExpiredRoutes(): List<String> {
        val expired = routeTable.purgeExpiredRoutes()
        for (peer in expired) {
            val alt = routeTable.getRoute(peer)
            if (alt == null) {
                Log.d(TAG, "Route expired for $peer with no alternate. Setting unreachable.")
                onReachabilityChanged(peer, false, null, 0)
            } else {
                Log.d(TAG, "Route expired for $peer, switched to alternate ${alt.nextHopAddress}")
                onReachabilityChanged(peer, true, alt.nextHopAddress, alt.hopCount)
            }
        }
        return expired
    }

    fun clearCache() {
        duplicateCache.clear()
        routeTable.clear()
        alertCustodyStore.clear()
        pendingUnicastCustody.clear()
    }
}
