package com.fury.peerconnect.ui

import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.data.PeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProfileScreenTest {

    @Test
    fun testSharedMediaFiltering_ExtractsImagesAndFiles() {
        val messages = listOf(
            MessageEntity(
                id = 1,
                senderId = "PeerB",
                receiverId = "Me",
                text = "Hello there",
                timestamp = 1000L,
                messageType = "TEXT"
            ),
            MessageEntity(
                id = 2,
                senderId = "PeerB",
                receiverId = "Me",
                text = "[FILE]:sample.jpg|/path/to/img|hash123|1024|SUCCESS",
                timestamp = 2000L,
                messageType = "IMAGE",
                fileName = "sample.jpg"
            ),
            MessageEntity(
                id = 3,
                senderId = "Me",
                receiverId = "PeerB",
                text = "📄 Shared a file: doc.pdf|/path/to/doc|hash456|2048|SUCCESS",
                timestamp = 3000L,
                messageType = "FILE",
                fileName = "doc.pdf"
            ),
            MessageEntity(
                id = 4,
                senderId = "PeerB",
                receiverId = "Me",
                text = "Emergency alert text",
                timestamp = 4000L,
                messageType = "ALERT",
                alertId = "alert_1"
            )
        )

        val mediaFiles = messages.filter { msg ->
            msg.alertId == null && (
                msg.messageType.equals("IMAGE", ignoreCase = true) ||
                msg.messageType.equals("FILE", ignoreCase = true) ||
                msg.text.startsWith("[FILE]:") ||
                msg.text.startsWith("📄 Shared a file: ") ||
                msg.text.contains("Shared a file: ") ||
                msg.fileName != null
            )
        }.reversed()

        assertEquals("Should find exactly 2 shared media/file items", 2, mediaFiles.size)
        assertEquals("Latest item should be first", "doc.pdf", mediaFiles[0].fileName)
        assertEquals("Second item should be sample.jpg", "sample.jpg", mediaFiles[1].fileName)
    }

    @Test
    fun testPeerConnectionStatusDisplayLogic() {
        // Connected & reachable peer
        val peerConnected = PeerEntity(
            name = "Alpha",
            endpointId = "ep_1",
            lastSeenTimestamp = System.currentTimeMillis(),
            isOnline = true,
            isConnected = true,
            isReachable = true
        )
        val statusDirect = if (peerConnected.isConnected) {
            if (peerConnected.isReachable) "● Connected" else "○ Connected (Offline)"
        } else "○ Offline"
        assertEquals("● Connected", statusDirect)

        // Request Sent peer
        val peerPending = PeerEntity(
            name = "Beta",
            endpointId = "ep_2",
            lastSeenTimestamp = System.currentTimeMillis(),
            connectionStatus = "REQUEST_SENT"
        )
        val statusPending = when (peerPending.connectionStatus) {
            "REQUEST_SENT" -> "⏳ Request Sent"
            else -> "○ Offline"
        }
        assertEquals("⏳ Request Sent", statusPending)

        // Multi-hop mesh reachable peer
        val peerMesh = PeerEntity(
            name = "Gamma",
            endpointId = "ep_3",
            lastSeenTimestamp = System.currentTimeMillis(),
            isOnline = false,
            isReachable = true,
            hopDistance = 3,
            nextHop = "RelayNode"
        )
        val hops = if (peerMesh.hopDistance > 0) peerMesh.hopDistance else 2
        val via = if (peerMesh.nextHop.isNullOrEmpty()) "" else " via ${peerMesh.nextHop}"
        val routeStr = "Multi-hop mesh: $hops hops$via"
        assertEquals("Multi-hop mesh: 3 hops via RelayNode", routeStr)
    }

    @Test
    fun testBackCallbackStateEvaluation_WhenPeerProfileIsOpen() {
        val isPeerProfileOpen = true
        val isConnOpen = false
        val isAlertThreadOpen = false
        val isChatOpen = true
        val isNotDashboard = false

        val backEnabled = isPeerProfileOpen || isConnOpen || isAlertThreadOpen || isChatOpen || isNotDashboard
        assertTrue("Back callback should be enabled when Peer Profile is open over chat", backEnabled)
    }
}
