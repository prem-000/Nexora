package com.fury.peerconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertPayloadLocationTest {

    @Test
    fun testAlertPayloadWithoutLocation_wireCompatibility() {
        val payload = AlertPayload(
            id = "alert_1001",
            senderId = "Alice",
            alertType = "SOS",
            title = "SOS Alert",
            message = "Need medical kit",
            sentAt = 1700000000000L,
            attachmentPath = null,
            expiresAt = 1700086400000L
        )

        val wire = payload.toWireString()
        assertEquals("[ALERT]:alert_1001|Alice|SOS|SOS Alert|Need medical kit|1700000000000||1700086400000", wire)

        val parsed = AlertPayload.parse(wire)
        assertNotNull(parsed)
        assertEquals("alert_1001", parsed!!.id)
        assertEquals("Alice", parsed.senderId)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
    }

    @Test
    fun testAlertPayloadWithLocation_roundTrip() {
        val payload = AlertPayload(
            id = "alert_1002",
            senderId = "Bob",
            alertType = "SOS",
            title = "Missing Person",
            message = "Subject lost near trailhead",
            sentAt = 1700000000000L,
            attachmentPath = null,
            expiresAt = 1700086400000L,
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 5.0f
        )

        val wire = payload.toWireString()
        assertEquals("[ALERT]:alert_1002|Bob|SOS|Missing Person|Subject lost near trailhead|1700000000000||1700086400000|37.7749|-122.4194|5.0", wire)

        val parsed = AlertPayload.parse(wire)
        assertNotNull(parsed)
        assertEquals(37.7749, parsed!!.latitude!!, 0.0001)
        assertEquals(-122.4194, parsed.longitude!!, 0.0001)
        assertEquals(5.0f, parsed.accuracy!!, 0.01f)
    }
}
