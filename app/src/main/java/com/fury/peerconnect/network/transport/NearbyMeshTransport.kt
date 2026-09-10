package com.fury.peerconnect.network.transport

import android.content.Context
import android.util.Log
import com.fury.peerconnect.network.model.MeshMessage
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap

class NearbyMeshTransport(
    private val context: Context,
    val myPeerName: String
) : MeshTransport {
    private val TAG = "NearbyMeshTransport"
    override val transportId: String = "NEARBY"

    private val endpointToPeerName = ConcurrentHashMap<String, String>()
    private val peerNameToEndpoint = ConcurrentHashMap<String, String>()

    private var packetReceiver: ((MeshMessage, String) -> Unit)? = null
    private var neighborStateListener: ((NeighborEndpoint, Boolean) -> Unit)? = null

    override fun send(packet: MeshMessage, nextHopAddress: String): Boolean {
        val targetEndpointId = resolveEndpointId(nextHopAddress)
        if (targetEndpointId == null) {
            Log.w(TAG, "Cannot send to $nextHopAddress: Endpoint not found")
            return false
        }
        return try {
            val bytes = serializePacket(packet)
            val payload = Payload.fromBytes(bytes)
            Nearby.getConnectionsClient(context).sendPayload(targetEndpointId, payload)
            Log.d(TAG, "Sent packet ${packet.messageId} to $nextHopAddress ($targetEndpointId) via Nearby")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet to $targetEndpointId", e)
            false
        }
    }

    override fun broadcast(packet: MeshMessage, excludeAddress: String?) {
        val excludeEndpointId = excludeAddress?.let { resolveEndpointId(it) ?: it }
        val bytes = serializePacket(packet)
        val payload = Payload.fromBytes(bytes)
        for (endpointId in endpointToPeerName.keys) {
            if (endpointId != excludeEndpointId) {
                try {
                    Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
                    Log.d(TAG, "Broadcast packet ${packet.messageId} to $endpointId via Nearby")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed broadcasting to $endpointId", e)
                }
            }
        }
    }

    override fun getConnectedNeighbors(): List<NeighborEndpoint> {
        return endpointToPeerName.values.map {
            NeighborEndpoint(it, it, transportId)
        }
    }

    override fun setPacketReceiver(receiver: (MeshMessage, String) -> Unit) {
        packetReceiver = receiver
    }

    override fun setNeighborStateListener(listener: (NeighborEndpoint, Boolean) -> Unit) {
        neighborStateListener = listener
    }

    fun onEndpointConnected(endpointId: String, peerName: String) {
        endpointToPeerName[endpointId] = peerName
        peerNameToEndpoint[peerName] = endpointId
        Log.d(TAG, "Endpoint connected: $peerName -> $endpointId")
        neighborStateListener?.invoke(NeighborEndpoint(peerName, peerName, transportId), true)
    }

    fun onEndpointDisconnected(endpointId: String) {
        val peerName = endpointToPeerName.remove(endpointId)
        if (peerName != null) {
            peerNameToEndpoint.remove(peerName)
            Log.d(TAG, "Endpoint disconnected: $peerName ($endpointId)")
            neighborStateListener?.invoke(NeighborEndpoint(peerName, peerName, transportId), false)
        }
    }

    fun handleIncomingPayload(endpointId: String, payload: Payload): Boolean {
        if (payload.type != Payload.Type.BYTES) return false
        val bytes = payload.asBytes() ?: return false
        val senderPeerName = endpointToPeerName[endpointId] ?: endpointId
        return try {
            val bais = ByteArrayInputStream(bytes)
            val ois = ObjectInputStream(bais)
            val obj = ois.readObject()
            ois.close()
            if (obj is MeshMessage) {
                Log.d(TAG, "Processed MeshMessage ${obj.messageId} from $senderPeerName")
                packetReceiver?.invoke(obj, senderPeerName)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveEndpointId(addressOrName: String): String? {
        return if (endpointToPeerName.containsKey(addressOrName)) addressOrName else peerNameToEndpoint[addressOrName]
    }

    private fun serializePacket(packet: MeshMessage): ByteArray {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(packet)
        oos.flush()
        return baos.toByteArray()
    }
}
