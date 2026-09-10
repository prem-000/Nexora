package com.fury.peerconnect

import com.fury.peerconnect.data.AlertPayload
import org.junit.Assert.*
import org.junit.Test

class AlertLocationPipelineTest {

    @Test
    fun testAlertWithGpsLocation_SerializationAndParsing() {
        val payload = AlertPayload(
            id = "alert_1725888000000",
            senderId = "AlphaNode",
            alertType = "SOS",
            title = "Medical Emergency",
            message = "Need oxygen support immediately",
            sentAt = 1725888000000L,
            attachmentPath = null,
            expiresAt = 1725974400000L,
            latitude = 12.971598,
            longitude = 77.594562,
            accuracy = 4.5f
        )

        val wireString = payload.toWireString()
        assertTrue("Wire string must start with [ALERT]:", wireString.startsWith("[ALERT]:"))
        assertTrue("Wire string must contain latitude", wireString.contains("12.971598"))
        assertTrue("Wire string must contain longitude", wireString.contains("77.594562"))

        val parsed = AlertPayload.parse(wireString)
        assertNotNull("Parsed payload must not be null", parsed)
        assertEquals("alert_1725888000000", parsed!!.id)
        assertEquals("AlphaNode", parsed.senderId)
        assertEquals("Medical Emergency", parsed.title)
        assertEquals("Need oxygen support immediately", parsed.message)
        assertEquals(12.971598, parsed.latitude!!, 0.00001)
        assertEquals(77.594562, parsed.longitude!!, 0.00001)
        assertEquals(4.5f, parsed.accuracy!!, 0.1f)
        assertNull(parsed.attachmentPath)
    }

    @Test
    fun testAlertWithGpsLocationAndAttachment_SerializationAndParsing() {
        val wireAttachment = "[FILE]:scene.jpg||abc123hash|1024|RECEIVING"
        val payload = AlertPayload(
            id = "alert_1725888100000",
            senderId = "BravoNode",
            alertType = "SOS",
            title = "Structural Collapse",
            message = "Building exit blocked",
            sentAt = 1725888100000L,
            attachmentPath = wireAttachment,
            expiresAt = 1725974500000L,
            latitude = -33.8688,
            longitude = 151.2093,
            accuracy = 10.0f
        )

        val wireString = payload.toWireString()
        val parsed = AlertPayload.parse(wireString)

        assertNotNull(parsed)
        assertEquals("alert_1725888100000", parsed!!.id)
        assertEquals(-33.8688, parsed.latitude!!, 0.0001)
        assertEquals(151.2093, parsed.longitude!!, 0.0001)
        assertEquals(10.0f, parsed.accuracy!!, 0.1f)
        assertEquals(wireAttachment, parsed.attachmentPath)
    }

    @Test
    fun testAlertWithoutGpsLocation_SerializationAndParsing() {
        val payload = AlertPayload(
            id = "alert_1725888200000",
            senderId = "CharlieNode",
            alertType = "SOS",
            title = "Power Loss",
            message = "Substation failure",
            sentAt = 1725888200000L,
            attachmentPath = null,
            expiresAt = 1725974600000L,
            latitude = null,
            longitude = null,
            accuracy = null
        )

        val wireString = payload.toWireString()
        val parsed = AlertPayload.parse(wireString)

        assertNotNull(parsed)
        assertNull("Latitude must be null when not attached", parsed!!.latitude)
        assertNull("Longitude must be null when not attached", parsed.longitude)
        assertNull("Accuracy must be null when not attached", parsed.accuracy)
    }
}
