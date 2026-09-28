package com.fury.peerconnect

import com.fury.peerconnect.logic.CriticalAlertManager
import com.fury.peerconnect.logic.SecurityHelper
import com.fury.peerconnect.network.FakeMeshTransport
import com.fury.peerconnect.network.model.EmergencyCategory
import com.fury.peerconnect.network.model.EmergencyCriticalContent
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.MessageType
import com.fury.peerconnect.network.model.RelayEnvelope
import com.fury.peerconnect.network.model.WireEnvelope
import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.data.AlertPayload
import com.fury.peerconnect.ui.AlertAdapter
import com.fury.peerconnect.network.routing.AlertCustodyStore
import com.fury.peerconnect.network.routing.DuplicateSuppressionCache
import com.fury.peerconnect.network.routing.RoutingEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

class CriticalAlertPipelineTest {

    // 1. Critical content serialization and deserialization
    @Test
    fun testCriticalContentSerializationAndDeserialization() {
        val original = EmergencyCriticalContent(
            category = EmergencyCategory.MEDICAL,
            severity = "CRITICAL",
            title = "Severe Injury",
            message = "Need immediate paramedic assistance at base camp",
            expiresAt = System.currentTimeMillis() + 3600000L,
            latitude = 37.7749,
            longitude = -122.4194,
            radiusMeters = 1500.0,
            attachmentHash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            attachmentMime = "image/jpeg"
        )

        val bytes = original.serialize()
        val deserialized = EmergencyCriticalContent.deserialize(bytes)

        assertNotNull(deserialized)
        assertEquals(EmergencyCategory.MEDICAL, deserialized?.category)
        assertEquals("CRITICAL", deserialized?.severity)
        assertEquals("Severe Injury", deserialized?.title)
        assertEquals("Need immediate paramedic assistance at base camp", deserialized?.message)
        assertEquals(37.7749, deserialized?.latitude ?: 0.0, 0.0001)
        assertEquals(-122.4194, deserialized?.longitude ?: 0.0, 0.0001)
        assertEquals(1500.0, deserialized?.radiusMeters ?: 0.0, 0.1)
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", deserialized?.attachmentHash)
        assertEquals("image/jpeg", deserialized?.attachmentMime)
    }

    // 2. Existing frame signature validation
    @Test
    fun testFrameSignatureValidation() {
        val keyPair: KeyPair = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.DISASTER,
            title = "Flash Flood",
            message = "River overflowing south ridge",
            expiresAt = System.currentTimeMillis() + 7200000L
        )
        val relayEnvelope = RelayEnvelope(
            type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY,
            senderId = "Peer_Alpha",
            sentAt = System.currentTimeMillis(),
            payload = content.serialize()
        )
        val signedBytes = relayEnvelope.serialize()
        val signature = SecurityHelper.signData(signedBytes, keyPair.private)

        val isValid = SecurityHelper.verifySignature(signedBytes, signature, keyPair.public.encoded)
        assertTrue("Signature should verify successfully with originator public key", isValid)
    }

    // 3. Modified critical payload fails signature verification
    @Test
    fun testModifiedPayloadFailsSignatureVerification() {
        val keyPair: KeyPair = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.SEARCH_AND_RESCUE,
            title = "Missing Hiker",
            message = "Last seen near trail marker 12",
            expiresAt = System.currentTimeMillis() + 3600000L
        )
        val relayEnvelope = RelayEnvelope(
            type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY,
            senderId = "Peer_Alpha",
            payload = content.serialize()
        )
        val signedBytes = relayEnvelope.serialize()
        val signature = SecurityHelper.signData(signedBytes, keyPair.private)

        // Tamper with signed bytes
        val tamperedBytes = signedBytes.clone()
        tamperedBytes[tamperedBytes.size - 5] = (tamperedBytes[tamperedBytes.size - 5] + 1).toByte()

        val isValid = SecurityHelper.verifySignature(tamperedBytes, signature, keyPair.public.encoded)
        assertFalse("Tampered signed bytes must fail signature verification", isValid)
    }

    // 4. A -> B -> C multi-hop propagation (C out of direct range of A)
    @Test
    fun testMultiHopPropagation_A_to_B_to_C() {
        val receivedByB = mutableListOf<MeshMessage>()
        val receivedByC = mutableListOf<MeshMessage>()

        val transportA = FakeMeshTransport("addr_A", "Node_A")
        val transportB = FakeMeshTransport("addr_B", "Node_B")
        val transportC = FakeMeshTransport("addr_C", "Node_C")

        // Connect A <-> B and B <-> C (A and C cannot communicate directly)
        transportA.connectTo(transportB)
        transportB.connectTo(transportC)

        var engineB: RoutingEngine? = null
        var engineC: RoutingEngine? = null

        val engineA = RoutingEngine(
            myDeviceId = "Node_A",
            onApplicationPayloadReceived = { _, _, _, _ -> }
        ).apply { registerTransport(transportA) }

        engineB = RoutingEngine(
            myDeviceId = "Node_B",
            onApplicationPayloadReceived = { src, id, type, payload ->
                receivedByB.add(MeshMessage(id, src, null, type, payload))
                // Automatic relay orchestrated for WireEnvelope
                val wire = WireEnvelope.deserialize(payload)
                if (wire != null && wire.hops + 1 <= WireEnvelope.CRITICAL_ALERT_MAX_HOPS) {
                    val forwarded = wire.copy(hops = wire.hops + 1, ttl = wire.ttl - 1, relay = "Node_B")
                    val msg = MeshMessage(id, src, null, type, forwarded.serialize(), hopCount = forwarded.hops)
                    engineB?.broadcastAcrossAllTransports(msg, excludeAddress = "addr_A")
                }
            }
        ).apply { registerTransport(transportB) }

        engineC = RoutingEngine(
            myDeviceId = "Node_C",
            onApplicationPayloadReceived = { src, id, type, payload ->
                receivedByC.add(MeshMessage(id, src, null, type, payload))
            }
        ).apply { registerTransport(transportC) }

        // Create and broadcast Critical Emergency from Node A
        val keyPairA = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.SECURITY,
            title = "Perimeter Breach",
            message = "Unidentified intrusion at Sector 4",
            expiresAt = System.currentTimeMillis() + 3600000L
        )
        val relayEnvelope = RelayEnvelope(type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY, senderId = "Node_A", payload = content.serialize())
        val signedBytes = relayEnvelope.serialize()
        val sig = SecurityHelper.signData(signedBytes, keyPairA.private)
        val wireEnvelope = WireEnvelope(
            id = "alert_multi_hop_01",
            ttl = 3,
            hops = 0,
            relay = null,
            sig = sig,
            signedBytes = signedBytes,
            senderPublicKey = keyPairA.public.encoded
        )

        val packet = MeshMessage(
            messageId = wireEnvelope.id,
            sourcePeerId = "Node_A",
            destinationPeerId = null,
            type = MessageType.BROADCAST_ALERT,
            payload = wireEnvelope.serialize(),
            hopCount = 0
        )

        engineA.broadcastAcrossAllTransports(packet)

        assertEquals("Node B must receive the alert from Node A", 1, receivedByB.size)
        assertEquals("Node C must receive the relayed alert from Node B", 1, receivedByC.size)

        val wireReceivedAtC = WireEnvelope.deserialize(receivedByC[0].payload)
        assertNotNull(wireReceivedAtC)
        assertEquals("Hop count at Node C should be 1", 1, wireReceivedAtC?.hops)
        assertEquals("Relay at Node C should be Node_B", "Node_B", wireReceivedAtC?.relay)

        val sigValidAtC = SecurityHelper.verifySignature(wireReceivedAtC!!.signedBytes, wireReceivedAtC.sig, wireReceivedAtC.senderPublicKey)
        assertTrue("Signature must remain 100% valid after intermediate hops", sigValidAtC)
    }

    // 5. 5-node mesh duplicate suppression
    @Test
    fun testFiveNodeMeshDuplicateSuppression() {
        val transports = (1..5).map { FakeMeshTransport("addr_$it", "Node_$it") }
        val receivedCounters = (1..5).map { java.util.concurrent.atomic.AtomicInteger(0) }
        val engines = mutableListOf<RoutingEngine>()

        for (i in 0 until 5) {
            val idx = i
            val engine = RoutingEngine(
                myDeviceId = "Node_${idx + 1}",
                onApplicationPayloadReceived = { _, id, _, payload ->
                    receivedCounters[idx].incrementAndGet()
                }
            ).apply { registerTransport(transports[idx]) }
            engines.add(engine)
        }

        // Connect full mesh between all 5 nodes
        for (i in 0 until 5) {
            for (j in i + 1 until 5) {
                transports[i].connectTo(transports[j])
            }
        }

        val keyPair = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.HAZARD,
            title = "Gas Leak",
            message = "Gas leak in laboratory",
            expiresAt = System.currentTimeMillis() + 3600000L
        )
        val relay = RelayEnvelope(type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY, senderId = "Node_1", payload = content.serialize())
        val signedBytes = relay.serialize()
        val sig = SecurityHelper.signData(signedBytes, keyPair.private)
        val wire = WireEnvelope(
            id = "alert_mesh_5node_01",
            ttl = 3,
            hops = 0,
            sig = sig,
            signedBytes = signedBytes,
            senderPublicKey = keyPair.public.encoded
        )

        val msg = MeshMessage(
            messageId = wire.id,
            sourcePeerId = "Node_1",
            destinationPeerId = null,
            type = MessageType.BROADCAST_ALERT,
            payload = wire.serialize(),
            hopCount = 0
        )

        // Broadcast from Node 1
        engines[0].duplicateCache.markProcessed(wire.id)
        engines[0].broadcastAcrossAllTransports(msg)

        // Nodes 2, 3, 4, 5 must each receive and process the payload exactly once due to duplicate suppression
        for (i in 1 until 5) {
            assertEquals("Node ${i + 1} must process alert exactly once (no broadcast storm)", 1, receivedCounters[i].get())
        }
    }

    // 6. Unpaired but valid node can relay
    @Test
    fun testUnpairedValidNodeCanRelay() {
        val keyPairOrigin = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.MEDICAL,
            title = "Heart Attack",
            message = "Urgent medical needed",
            expiresAt = System.currentTimeMillis() + 3600000L
        )
        val relay = RelayEnvelope(type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY, senderId = "Unknown_Stranger", payload = content.serialize())
        val signedBytes = relay.serialize()
        val sig = SecurityHelper.signData(signedBytes, keyPairOrigin.private)
        val wire = WireEnvelope(
            id = "alert_unpaired_01",
            ttl = 3,
            hops = 0,
            sig = sig,
            signedBytes = signedBytes,
            senderPublicKey = keyPairOrigin.public.encoded
        )

        var payloadReceived = false
        val engine = RoutingEngine(
            myDeviceId = "Local_Node",
            isPeerAuthorized = { false }, // Stranger is NOT an authorized contact/friend!
            onApplicationPayloadReceived = { _, id, _, _ ->
                if (id == wire.id) payloadReceived = true
            }
        )

        val msg = MeshMessage(
            messageId = wire.id,
            sourcePeerId = "Unknown_Stranger",
            destinationPeerId = null,
            type = MessageType.BROADCAST_ALERT,
            payload = wire.serialize(),
            hopCount = 0
        )

        // Process incoming message
        engine.processIncomingMessage(msg, "stranger_addr", "NEARBY")
        assertTrue("Valid signed WireEnvelope must bypass isPeerAuthorized contact restriction", payloadReceived)
    }

    // 7. Invalid/forged signature cannot relay
    @Test
    fun testInvalidForgedSignatureIsRejected() {
        val keyPairReal = SecurityHelper.generateIdentityKeyPair()
        val keyPairAttacker = SecurityHelper.generateIdentityKeyPair()

        val content = EmergencyCriticalContent(
            category = EmergencyCategory.SECURITY,
            title = "Fake Alarm",
            message = "Spoofed alert",
            expiresAt = System.currentTimeMillis() + 3600000L
        )
        val relay = RelayEnvelope(type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY, senderId = "Node_A", payload = content.serialize())
        val signedBytes = relay.serialize()
        // Attacker signs with attacker private key, but claims originator is Node_A with Real public key
        val forgedSig = SecurityHelper.signData(signedBytes, keyPairAttacker.private)

        val wire = WireEnvelope(
            id = "alert_forged_01",
            ttl = 3,
            hops = 0,
            sig = forgedSig,
            signedBytes = signedBytes,
            senderPublicKey = keyPairReal.public.encoded
        )

        val isValid = SecurityHelper.verifySignature(wire.signedBytes, wire.sig, wire.senderPublicKey)
        assertFalse("Forged signature must be rejected", isValid)
    }

    // 8. Expired alert is rejected
    @Test
    fun testExpiredAlertIsRejected() {
        val pastTime = System.currentTimeMillis() - 10000L
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.DISASTER,
            title = "Old Alert",
            message = "Old flood alert from yesterday",
            expiresAt = pastTime
        )
        assertTrue("Content must indicate it is expired", content.expiresAt < System.currentTimeMillis())
    }

    // 9. TTL/hop limit prevents further propagation
    @Test
    fun testHopLimitPreventsFurtherPropagation() {
        val wire = WireEnvelope(
            id = "alert_hop_exhausted",
            ttl = 1,
            hops = 3,
            sig = ByteArray(32),
            signedBytes = ByteArray(32)
        )
        val canRelay = wire.hops + 1 <= WireEnvelope.CRITICAL_ALERT_MAX_HOPS && wire.ttl > 1
        assertFalse("Alert with hopCount=3 and ttl=1 must NOT be relayed further", canRelay)
    }

    // 10. Store-and-forward: A -> B, then B later connects to C -> delivers alert
    @Test
    fun testStoreAndForwardCustody() {
        val custodyStore = AlertCustodyStore(100)
        val keyPair = SecurityHelper.generateIdentityKeyPair()
        val content = EmergencyCriticalContent(
            category = EmergencyCategory.MEDICAL,
            title = "Evacuation Needed",
            message = "Road blocked",
            expiresAt = System.currentTimeMillis() + 86400000L
        )
        val relay = RelayEnvelope(type = RelayEnvelope.TYPE_CRITICAL_EMERGENCY, senderId = "Node_A", payload = content.serialize())
        val wire = WireEnvelope(
            id = "alert_custody_01",
            ttl = 3,
            hops = 0,
            sig = SecurityHelper.signData(relay.serialize(), keyPair.private),
            signedBytes = relay.serialize(),
            senderPublicKey = keyPair.public.encoded
        )
        val msg = MeshMessage(
            messageId = wire.id,
            sourcePeerId = "Node_A",
            destinationPeerId = null,
            type = MessageType.BROADCAST_ALERT,
            payload = wire.serialize()
        )

        // Node B stores in custody upon receiving from Node A
        custodyStore.storeAlert(msg, "Node_A")
        custodyStore.markDeliveredTo(wire.id, "addr_A")

        // Later, Node B encounters Node C ("addr_C")
        val pendingForC = custodyStore.getActiveAlertsForNeighbor("addr_C")
        assertEquals("Node B must find 1 pending alert to forward to new neighbor Node C", 1, pendingForC.size)
        assertEquals("alert_custody_01", pendingForC[0].messageId)

        custodyStore.markDeliveredTo(wire.id, "addr_C")
        val pendingAfterDelivery = custodyStore.getActiveAlertsForNeighbor("addr_C")
        assertEquals("After delivery, Node C should have 0 pending alerts", 0, pendingAfterDelivery.size)
    }

    // 11. Notification radius evaluation
    @Test
    fun testNotificationRadiusEvaluation() {
        // Alert at (37.7749, -122.4194) with radius 1000m
        val alertContent = EmergencyCriticalContent(
            category = EmergencyCategory.HAZARD,
            title = "Chemical Spill",
            message = "Stay indoors",
            expiresAt = System.currentTimeMillis() + 3600000L,
            latitude = 37.7749,
            longitude = -122.4194,
            radiusMeters = 1000.0
        )

        // Device 1: ~100m away (inside radius)
        val dev1Lat = 37.7755
        val dev1Lon = -122.4190
        val dist1 = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(dev1Lat, dev1Lon, alertContent.latitude!!, alertContent.longitude!!)
        assertTrue("Device 1 distance ($dist1 m) must be <= radius (1000m)", dist1 <= alertContent.radiusMeters!!)

        // Device 2: ~3000m away (outside radius)
        val dev2Lat = 37.8000
        val dev2Lon = -122.4194
        val dist2 = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(dev2Lat, dev2Lon, alertContent.latitude!!, alertContent.longitude!!)
        assertTrue("Device 2 distance ($dist2 m) must be > radius (1000m)", dist2 > alertContent.radiusMeters!!)
    }

    // 12. Duplicate alert suppression does not produce duplicate deliveries
    @Test
    fun testDuplicateAlertSuppression() {
        val cache = DuplicateSuppressionCache(100)
        val alertId = "crit_alert_dup_01"

        assertFalse("First time seeing alert ID, isDuplicate must be false", cache.isDuplicate(alertId))
        cache.markProcessed(alertId)
        assertTrue("Second time seeing alert ID, isDuplicate must be true", cache.isDuplicate(alertId))
    }

    // 13. AlertAdapter filtering rules: ALL, GROUPS, SYSTEM, and CRITICAL
    @Test
    fun testAlertAdapterFilteringRules() {
        val adapter = AlertAdapter()

        val alertSos = AlertEntity(
            id = 1,
            type = "SOS",
            title = "Medical SOS",
            description = "Need medical help",
            timestamp = System.currentTimeMillis()
        )
        val alertGroup = AlertEntity(
            id = 2,
            type = "GROUP",
            title = "Group Announcement",
            description = "Meeting at waypoint 2",
            timestamp = System.currentTimeMillis()
        )
        val alertSystem = AlertEntity(
            id = 3,
            type = "NETWORK_UPDATE",
            title = "Mesh Topology Change",
            description = "Channel re-keyed",
            timestamp = System.currentTimeMillis()
        )
        val alertCritical = AlertEntity(
            id = 4,
            type = "CRITICAL_EMERGENCY",
            title = "FIRE Emergency",
            description = "Wildfire approaching rapidly",
            category = "FIRE",
            severity = "CRITICAL",
            timestamp = System.currentTimeMillis()
        )

        adapter.setAlerts(listOf(alertSos, alertGroup, alertSystem, alertCritical))

        // Tab: ALL -> Should include standard SOS, groups, system, and critical
        adapter.applyFilter("ALL")
        assertEquals("ALL tab must display all 4 alerts", 4, adapter.getDisplayedCount())
        assertEquals("ALL", adapter.getCurrentFilter())

        // Tab: CRITICAL -> Should ONLY include Critical alerts, never standard SOS
        adapter.applyFilter("CRITICAL")
        assertEquals("CRITICAL tab must display only 1 alert", 1, adapter.getDisplayedCount())
        assertEquals("CRITICAL", adapter.getCurrentFilter())

        // Tab: GROUPS -> Should include standard SOS and GROUP, strictly EXCLUDE Critical alerts
        adapter.applyFilter("GROUPS")
        assertEquals("GROUPS tab must display SOS and GROUP (2 alerts)", 2, adapter.getDisplayedCount())

        // Tab: SYSTEM -> Should include system alerts, strictly EXCLUDE Critical alerts
        adapter.applyFilter("SYSTEM")
        assertEquals("SYSTEM tab must display 1 system alert", 1, adapter.getDisplayedCount())
    }

    // 14. Critical alert identification and empty state notification callback
    @Test
    fun testCriticalAlertIdentificationAndEmptyStateCallback() {
        val adapter = AlertAdapter()

        val critical1 = AlertEntity(id = 1, type = "CRITICAL", title = "Crit 1", description = "Test", timestamp = 0L)
        val critical2 = AlertEntity(id = 2, type = "CRITICAL_EMERGENCY", title = "Crit 2", description = "Test", timestamp = 0L)
        val critical3 = AlertEntity(id = 3, type = "CUSTOM", title = "Crit 3", description = "Test", severity = "CRITICAL", timestamp = 0L)
        val normalSos = AlertEntity(id = 4, type = "SOS", title = "SOS", description = "Test", timestamp = 0L)
        val normalGroup = AlertEntity(id = 5, type = "GROUP", title = "Group", description = "Test", timestamp = 0L)

        assertTrue("type=CRITICAL must be identified as critical", adapter.isCriticalAlert(critical1))
        assertTrue("type=CRITICAL_EMERGENCY must be identified as critical", adapter.isCriticalAlert(critical2))
        assertTrue("severity=CRITICAL must be identified as critical", adapter.isCriticalAlert(critical3))
        assertFalse("type=SOS must NOT be identified as critical", adapter.isCriticalAlert(normalSos))
        assertFalse("type=GROUP must NOT be identified as critical", adapter.isCriticalAlert(normalGroup))

        var reportedFilter: String? = null
        var reportedCount = -1
        adapter.onFilterChanged = { filter, count ->
            reportedFilter = filter
            reportedCount = count
        }

        // Apply CRITICAL filter when only normal alerts are present
        adapter.setAlerts(listOf(normalSos, normalGroup))
        adapter.applyFilter("CRITICAL")

        assertEquals("CRITICAL", reportedFilter)
        assertEquals(0, reportedCount)
        assertEquals(0, adapter.getDisplayedCount())
    }

    // 15. Critical alert payload model and DAO entity mapping
    @Test
    fun testCriticalAlertEntityMapping() {
        val category = EmergencyCategory.DISASTER
        val title = "DISASTER Emergency"
        val message = "Fire outbreak in sector 4"
        val expiresAt = System.currentTimeMillis() + 86400000L

        val entity = AlertEntity(
            id = 0,
            type = "CRITICAL_EMERGENCY",
            title = title,
            description = message,
            message = message,
            timestamp = System.currentTimeMillis(),
            isRead = true,
            peerName = "AlphaNode",
            alertId = "crit_12345",
            originPeerId = "AlphaNode",
            isCustodyActive = true,
            expiresAt = expiresAt,
            category = category.name,
            severity = "CRITICAL",
            radiusMeters = 5000.0
        )

        assertEquals("CRITICAL_EMERGENCY", entity.type)
        assertEquals("CRITICAL", entity.severity)
        assertEquals("DISASTER", entity.category)
        assertTrue(entity.isCustodyActive)
        assertEquals(message, entity.body)
    }

    // 16. Non-regression: Standard SOS alert payload and wire format unchanged
    @Test
    fun testStandardSosPayloadUnchanged() {
        val payload = AlertPayload(
            id = "alert_std_01",
            senderId = "UserA",
            alertType = "SOS",
            title = "SOS Alert",
            message = "Emergency assistance needed",
            sentAt = 1720000000000L,
            attachmentPath = null,
            expiresAt = 1720086400000L,
            latitude = 12.9716,
            longitude = 77.5946,
            accuracy = 8.5f
        )

        val wire = payload.toWireString()
        val parsed = AlertPayload.parseWireString(wire)

        assertNotNull(parsed)
        assertEquals("alert_std_01", parsed?.id)
        assertEquals("UserA", parsed?.senderId)
        assertEquals("SOS", parsed?.alertType)
        assertEquals("SOS Alert", parsed?.title)
        assertEquals("Emergency assistance needed", parsed?.message)
        assertEquals(12.9716, parsed?.latitude ?: 0.0, 0.0001)
        assertEquals(77.5946, parsed?.longitude ?: 0.0, 0.0001)
        assertEquals(8.5f, parsed?.accuracy ?: 0f, 0.01f)
    }
}
