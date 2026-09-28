package com.fury.peerconnect

import com.fury.peerconnect.logic.AvatarManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAndConnectionPipelineTest {

    @Test
    fun testPeerHelloFormattingAndParsing() {
        val deviceId = "dev-12345"
        val nick = "Rahul"
        val avatarId = "avatar_03"
        val profileVersion = 5L

        val helloPayload = "[PEER_HELLO]:$deviceId|$nick|$avatarId|$profileVersion"
        assertTrue(helloPayload.startsWith("[PEER_HELLO]:"))

        val clean = helloPayload.removePrefix("[PEER_HELLO]:")
        val parts = clean.split("|")
        assertEquals(4, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(nick, parts[1])
        assertEquals(avatarId, parts[2])
        assertEquals(5L, parts[3].toLong())
    }

    @Test
    fun testPeerHelloLegacyFallback() {
        // Test backward compatibility when older nodes send 2 tokens: [PEER_HELLO]:deviceId|nick
        val legacyPayload = "[PEER_HELLO]:legacy-dev|OldPeer"
        val clean = legacyPayload.removePrefix("[PEER_HELLO]:")
        val parts = clean.split("|")

        val deviceId = parts[0]
        val remoteNick = parts[1]
        val peerAvatar = parts.getOrNull(2) ?: AvatarManager.DEFAULT_AVATAR_ID
        val peerVersion = parts.getOrNull(3)?.toLongOrNull() ?: 1L

        assertEquals("legacy-dev", deviceId)
        assertEquals("OldPeer", remoteNick)
        assertEquals(AvatarManager.DEFAULT_AVATAR_ID, peerAvatar)
        assertEquals(1L, peerVersion)
    }

    @Test
    fun testPeerProfileSyncFormattingAndParsing() {
        val deviceId = "dev-abcde"
        val nick = "Priya"
        val avatarId = "avatar_07"
        val version = 2L

        val profilePayload = "[PEER_PROFILE]:$deviceId|$nick|$avatarId|$version"
        assertTrue(profilePayload.startsWith("[PEER_PROFILE]:"))

        val clean = profilePayload.removePrefix("[PEER_PROFILE]:")
        val parts = clean.split("|")
        assertEquals(4, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(nick, parts[1])
        assertEquals(avatarId, parts[2])
        assertEquals(2L, parts[3].toLong())
    }

    @Test
    fun testConnectionRequestPacket() {
        val deviceId = "dev-999"
        val senderNick = "Commander"
        val avatarId = "avatar_04"

        val reqPayload = "[CONN_REQ]:$deviceId|$senderNick|$avatarId"
        assertTrue(reqPayload.startsWith("[CONN_REQ]:"))

        val clean = reqPayload.removePrefix("[CONN_REQ]:")
        val parts = clean.split("|")
        assertEquals(3, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(senderNick, parts[1])
        assertEquals(avatarId, parts[2])
    }

    @Test
    fun testConnectionResponseAcceptPacket() {
        val deviceId = "dev-888"
        val responderNick = "AlphaTeam"
        val avatarId = "avatar_06"
        val action = "ACCEPT"

        val respPayload = "[CONN_RESP]:$deviceId|$responderNick|$avatarId|$action"
        assertTrue(respPayload.startsWith("[CONN_RESP]:"))

        val clean = respPayload.removePrefix("[CONN_RESP]:")
        val parts = clean.split("|")
        assertEquals(4, parts.size)
        assertEquals(deviceId, parts[0])
        assertEquals(responderNick, parts[1])
        assertEquals(avatarId, parts[2])
        assertEquals("ACCEPT", parts[3])

        val isAccepted = parts[3].equals("ACCEPT", ignoreCase = true)
        val newStatus = if (isAccepted) "CONNECTED" else "NONE"
        assertEquals("CONNECTED", newStatus)
        assertTrue(isAccepted)
    }

    @Test
    fun testConnectionResponseDeclinePacket() {
        val deviceId = "dev-888"
        val responderNick = "AlphaTeam"
        val avatarId = "avatar_06"
        val action = "DECLINE"

        val respPayload = "[CONN_RESP]:$deviceId|$responderNick|$avatarId|$action"
        val clean = respPayload.removePrefix("[CONN_RESP]:")
        val parts = clean.split("|")

        val isAccepted = parts[3].equals("ACCEPT", ignoreCase = true)
        val newStatus = if (isAccepted) "CONNECTED" else "NONE"
        assertEquals("NONE", newStatus)
        assertFalse(isAccepted)
    }

    @Test
    fun testConnectionStateTransitions() {
        // Reachable peer sends request
        var connectionStatus = "NONE"
        var isConnected = false

        // 1. Peer sends request
        connectionStatus = "REQUEST_SENT"
        assertEquals("REQUEST_SENT", connectionStatus)
        assertFalse(isConnected)

        // 2. Peer accepts
        connectionStatus = "CONNECTED"
        isConnected = true
        assertEquals("CONNECTED", connectionStatus)
        assertTrue(isConnected)

        // 3. User disconnects or declines
        connectionStatus = "NONE"
        isConnected = false
        assertEquals("NONE", connectionStatus)
        assertFalse(isConnected)
    }
}
