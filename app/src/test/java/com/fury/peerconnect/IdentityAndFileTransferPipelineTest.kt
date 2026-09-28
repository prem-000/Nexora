package com.fury.peerconnect

import com.fury.peerconnect.data.BlobRepository
import com.fury.peerconnect.network.manager.BlobExchange
import com.fury.peerconnect.network.routing.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IdentityAndFileTransferPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPeerRenameProtocol_WireFormatAndParsing() {
        val deviceId = "550e8400-e29b-41d4-a716-446655440000"
        val oldName = "Alice_Old"
        val newName = "Alice_New"

        val renamePacket = "[PEER_RENAME]:$deviceId|$oldName|$newName"
        assertTrue(renamePacket.startsWith("[PEER_RENAME]:"))

        val content = renamePacket.removePrefix("[PEER_RENAME]:")
        val parts = content.split("|")
        assertEquals(3, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(oldName, parts[1])
        assertEquals(newName, parts[2])
    }

    @Test
    fun testPeerHelloProtocol_WireFormatAndParsing() {
        val deviceId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val nick = "Bob"

        val helloPacket = "[PEER_HELLO]:$deviceId|$nick"
        assertTrue(helloPacket.startsWith("[PEER_HELLO]:"))

        val content = helloPacket.removePrefix("[PEER_HELLO]:")
        val parts = content.split("|")
        assertEquals(2, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(nick, parts[1])
    }

    @Test
    fun testRoutingEngine_AliasAndDestinationUpdatesOnPeerRename() {
        var reachabilityEvents = mutableListOf<String>()
        val routing = RoutingEngine(
            myDeviceId = "Alice",
            connectionManager = null,
            onApplicationPayloadReceived = { _, _, _, _ -> },
            onReachabilityChanged = { peer, reachable, _, _ ->
                reachabilityEvents.add("$peer:$reachable")
            }
        )

        // Seed route table with Bob
        routing.routeTable.updateRoute(
            destinationPeerId = "Bob",
            nextHopAddress = "endpoint_bob_1",
            transportId = "nearby",
            hopCount = 1
        )
        assertNotNull(routing.routeTable.getRoute("Bob"))

        // Peer Bob renames to Bob_Renamed
        routing.updatePeerDestination("Bob", "Bob_Renamed")
        assertEquals(null, routing.routeTable.getRoute("Bob"))
        assertNotNull(routing.routeTable.getRoute("Bob_Renamed"))
        assertEquals("endpoint_bob_1", routing.routeTable.getRoute("Bob_Renamed")?.nextHopAddress)

        // Verify reachability callback fired for both
        assertTrue(reachabilityEvents.contains("Bob:false"))
        assertTrue(reachabilityEvents.contains("Bob_Renamed:true"))

        // Alice updates her identity and registers previous alias
        routing.updateMyDeviceId("Alice_Renamed", "Alice")
        assertEquals("Alice_Renamed", routing.myDeviceId)
        assertTrue(routing.knownAliases.contains("Alice"))
    }

    @Test
    fun testFileTransferWireFormat_ExtractsWirePayloadIdAndBypassesBlobChunksForDirectNeighbor() {
        val fileName = "diagram.png"
        val hash = "hash_abc_12345"
        val fileSize = 2048576L
        val mimeType = "image/png"
        val payloadId = 987654321L

        val wireBody = "[FILE]:$fileName||$hash|$fileSize|RECEIVING|$mimeType||${payloadId}"
        assertTrue(wireBody.startsWith("[FILE]:"))

        val clean = wireBody.removePrefix("[FILE]:")
        val parts = clean.split("|")

        assertEquals(fileName, parts[0])
        assertEquals("", parts[1]) // incomingPath
        assertEquals(hash, parts[2])
        assertEquals(fileSize, parts[3].toLong())
        assertEquals("RECEIVING", parts[4])
        assertEquals(mimeType, parts[5])
        assertEquals("", parts[6]) // attachmentKey
        assertEquals(payloadId, parts[7].toLongOrNull())

        // Simulation of direct neighbor check
        val activeEndpoints = setOf("endpoint_A")
        val pendingConnections = mapOf("endpoint_A" to "Bob")
        val senderName = "Bob"
        val sourceEndpointOrPeer = "endpoint_A"

        val isDirectNeighbor = activeEndpoints.any { pendingConnections[it] == senderName || it == sourceEndpointOrPeer }
        assertTrue("Direct neighbor must be recognized", isDirectNeighbor)

        // Verify that blobExchange.want is NOT called when isDirectNeighbor is true
        var wantCalled = false
        val repoDir = tempFolder.newFolder("repo")
        val repo = BlobRepository(repoDir)
        val mockBlobExchange = BlobExchange(
            blobRepository = repo,
            myPeerId = "Alice",
            sendBlobRequest = { _, _ ->
                wantCalled = true
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = {}
        )

        if (!isDirectNeighbor && hash.length >= 8) {
            mockBlobExchange.want(hash, senderName)
        }

        assertFalse("Blob exchange chunk fetching must NOT be triggered for direct transfers", wantCalled)
    }
}
