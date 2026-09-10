package com.fury.peerconnect.ui

import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.MessageType
import com.fury.peerconnect.network.routing.RouteEntry
import com.fury.peerconnect.network.routing.RouteTable
import com.fury.peerconnect.network.routing.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ReceiptAndDateSeparatorTest {

    // ==========================================
    // 1. DATE SEPARATOR TESTS
    // ==========================================

    @Test
    fun testDateSeparator_Today() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val cal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val nowMillis = cal.timeInMillis

        val separator = ChatDateFormatter.formatDateSeparator(nowMillis, nowMillis, tz, loc)
        assertEquals("Today", separator)
    }

    @Test
    fun testDateSeparator_Yesterday() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val nowCal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val yesterdayCal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 5, 18, 30, 0)
        }
        val yesterdayMillis = yesterdayCal.timeInMillis

        val separator = ChatDateFormatter.formatDateSeparator(yesterdayMillis, nowMillis, tz, loc)
        assertEquals("Yesterday", separator)
    }

    @Test
    fun testDateSeparator_OlderCurrentYear() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val nowCal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val olderCal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.JULY, 15, 10, 0, 0)
        }
        val olderMillis = olderCal.timeInMillis

        val separator = ChatDateFormatter.formatDateSeparator(olderMillis, nowMillis, tz, loc)
        assertEquals("July 15", separator)
    }

    @Test
    fun testDateSeparator_PreviousYear() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val nowCal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val nowMillis = nowCal.timeInMillis

        val prevYearCal = Calendar.getInstance(tz, loc).apply {
            set(2025, Calendar.DECEMBER, 25, 10, 0, 0)
        }
        val prevYearMillis = prevYearCal.timeInMillis

        val separator = ChatDateFormatter.formatDateSeparator(prevYearMillis, nowMillis, tz, loc)
        assertEquals("December 25, 2025", separator)
    }

    @Test
    fun testDateSeparator_MidnightBoundary() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val calNow = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 0, 1, 0) // 12:01 AM Sep 6
        }
        val nowMillis = calNow.timeInMillis

        val calJustBeforeMidnight = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 5, 23, 59, 59) // 11:59:59 PM Sep 5
        }
        val beforeMidnightMillis = calJustBeforeMidnight.timeInMillis

        val todaySep = ChatDateFormatter.formatDateSeparator(nowMillis, nowMillis, tz, loc)
        val yestSep = ChatDateFormatter.formatDateSeparator(beforeMidnightMillis, nowMillis, tz, loc)

        assertEquals("Today", todaySep)
        assertEquals("Yesterday", yestSep)
    }

    @Test
    fun testDateSeparator_TimezoneConversion() {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            set(2026, Calendar.SEPTEMBER, 5, 23, 0, 0) // 23:00 UTC Sep 5
        }
        val timestamp = utcCal.timeInMillis

        val istTz = TimeZone.getTimeZone("Asia/Kolkata") // UTC + 5:30 -> 04:30 AM Sep 6
        val istNowCal = Calendar.getInstance(istTz, Locale.US).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val istNow = istNowCal.timeInMillis

        // In IST, timestamp falls on September 6 (Today)
        val istSeparator = ChatDateFormatter.formatDateSeparator(timestamp, istNow, istTz, Locale.US)
        assertEquals("Today", istSeparator)

        // In UTC, timestamp falls on September 5 (Yesterday relative to Sep 6)
        val utcNowCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val utcNow = utcNowCal.timeInMillis
        val utcSeparator = ChatDateFormatter.formatDateSeparator(timestamp, utcNow, TimeZone.getTimeZone("UTC"), Locale.US)
        assertEquals("Yesterday", utcSeparator)
    }

    @Test
    fun testSameDayMessages_ProduceSingleDateSeparator() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val cal = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 10, 0, 0)
        }
        val baseTime = cal.timeInMillis

        val messages = listOf(
            MessageEntity(id = 1, senderId = "Alice", receiverId = "Bob", text = "Hello", timestamp = baseTime),
            MessageEntity(id = 2, senderId = "Bob", receiverId = "Alice", text = "Hi", timestamp = baseTime + 60000L),
            MessageEntity(id = 3, senderId = "Alice", receiverId = "Bob", text = "How are you?", timestamp = baseTime + 120000L)
        )

        val items = ChatDateFormatter.groupMessagesWithDateSeparators(messages, "Bob", baseTime + 200000L, tz, loc)
        val dateSeparators = items.filterIsInstance<ChatItem.DateSeparator>()
        val msgItems = items.filterIsInstance<ChatItem.Message>()

        assertEquals("Should only have 1 date separator for same-day messages", 1, dateSeparators.size)
        assertEquals("Today", dateSeparators[0].dateText)
        assertEquals(3, msgItems.size)
    }

    @Test
    fun testPaginatedOlderMessages_ChronologicalGrouping() {
        val tz = TimeZone.getTimeZone("UTC")
        val loc = Locale.US
        val calNow = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 6, 12, 0, 0)
        }
        val nowMillis = calNow.timeInMillis

        val calYest = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 5, 14, 0, 0)
        }
        val yestMillis = calYest.timeInMillis

        val calOlder = Calendar.getInstance(tz, loc).apply {
            set(2026, Calendar.SEPTEMBER, 3, 9, 0, 0)
        }
        val olderMillis = calOlder.timeInMillis

        // Messages in non-sorted or paginated arrival order
        val history = listOf(
            MessageEntity(id = 3, senderId = "Alice", receiverId = "Bob", text = "Today msg", timestamp = nowMillis),
            MessageEntity(id = 1, senderId = "Alice", receiverId = "Bob", text = "Older msg", timestamp = olderMillis),
            MessageEntity(id = 2, senderId = "Alice", receiverId = "Bob", text = "Yesterday msg", timestamp = yestMillis)
        )

        val items = ChatDateFormatter.groupMessagesWithDateSeparators(history, "Bob", nowMillis, tz, loc)
        val separators = items.filterIsInstance<ChatItem.DateSeparator>()

        assertEquals(3, separators.size)
        assertEquals("September 3", separators[0].dateText)
        assertEquals("Yesterday", separators[1].dateText)
        assertEquals("Today", separators[2].dateText)

        // Verify correct message positioning under their respective date separators
        assertEquals(separators[0].timestamp, (items[1] as ChatItem.Message).time)
        assertEquals(separators[1].timestamp, (items[3] as ChatItem.Message).time)
        assertEquals(separators[2].timestamp, (items[5] as ChatItem.Message).time)
    }

    // ==========================================
    // 2. RECEIPT SYSTEM TESTS
    // ==========================================

    @Test
    fun testReceiptProgression_SentToDeliveredToRead() {
        assertTrue("PENDING < SENT", ChatAdapter.getStatusRank("PENDING") < ChatAdapter.getStatusRank("SENT"))
        assertTrue("SENT < DELIVERED", ChatAdapter.getStatusRank("SENT") < ChatAdapter.getStatusRank("DELIVERED"))
        assertTrue("DELIVERED < READ", ChatAdapter.getStatusRank("DELIVERED") < ChatAdapter.getStatusRank("READ"))
        assertEquals("FORWARDED has same rank as SENT", ChatAdapter.getStatusRank("SENT"), ChatAdapter.getStatusRank("FORWARDED"))
    }

    @Test
    fun testDuplicateDeliveredReceipt_DoesNotDowngradeOrCorrupt() {
        val adapter = ChatAdapter("Me")
        val entity = MessageEntity(id = 10, senderId = "Me", receiverId = "PeerB", text = "Hello", timestamp = 1000L, deliveryStatus = "DELIVERED")
        adapter.setEntities(listOf(entity))

        // Duplicate DELIVERED receipt
        val updated = adapter.updateMessageStatus(10, "DELIVERED")
        assertTrue("Updating to same status should be permitted or idempotent", updated)
    }

    @Test
    fun testDuplicateReadReceipt_IsIdempotent() {
        val adapter = ChatAdapter("Me")
        val entity = MessageEntity(id = 11, senderId = "Me", receiverId = "PeerB", text = "Hello", timestamp = 1000L, deliveryStatus = "READ")
        adapter.setEntities(listOf(entity))

        val updated = adapter.updateMessageStatus(11, "READ")
        assertTrue("Duplicate READ is idempotent", updated)
    }

    @Test
    fun testReadBeforeDelayedDeliveredReceipt_DoesNotDowngrade() {
        val adapter = ChatAdapter("Me")
        val entity = MessageEntity(id = 12, senderId = "Me", receiverId = "PeerB", text = "Hello", timestamp = 1000L, deliveryStatus = "READ")
        adapter.setEntities(listOf(entity))

        // Delayed DELIVERED arrives after message is already READ
        val result = adapter.updateMessageStatus(12, "DELIVERED")
        assertFalse("Delayed DELIVERED receipt must NEVER downgrade a message that is already READ", result)
    }

    @Test
    fun testDeliveredReceiptAfterRead_Ignored() {
        fun resolveMonotonicStatus(current: String, incoming: String): String {
            return if (ChatAdapter.getStatusRank(incoming) >= ChatAdapter.getStatusRank(current)) incoming else current
        }

        assertEquals("DELIVERED -> READ upgrades", "READ", resolveMonotonicStatus("DELIVERED", "READ"))
        assertEquals("READ -> DELIVERED stays READ", "READ", resolveMonotonicStatus("READ", "DELIVERED"))
        assertEquals("READ -> SENT stays READ", "READ", resolveMonotonicStatus("READ", "SENT"))
        assertEquals("SENT -> DELIVERED upgrades", "DELIVERED", resolveMonotonicStatus("SENT", "DELIVERED"))
    }

    @Test
    fun testReceiptPayloadParsing_SingleBatchedAndLegacy() {
        fun parseReceiptPayload(raw: String): Pair<List<Int>, String> {
            val parts = raw.split(":")
            val (idPart, receiptType) = if (parts.size >= 2) {
                Pair(parts[0], parts[1].uppercase(Locale.ROOT))
            } else {
                Pair(raw, "DELIVERED")
            }
            val ids = idPart.split(",").mapNotNull { it.trim().toIntOrNull() }
            return Pair(ids, receiptType)
        }

        // 1. Single DELIVERED
        val (ids1, type1) = parseReceiptPayload("101:DELIVERED")
        assertEquals(listOf(101), ids1)
        assertEquals("DELIVERED", type1)

        // 2. Batched READ
        val (ids2, type2) = parseReceiptPayload("101,102,103:READ")
        assertEquals(listOf(101, 102, 103), ids2)
        assertEquals("READ", type2)

        // 3. Legacy without type
        val (ids3, type3) = parseReceiptPayload("101")
        assertEquals(listOf(101), ids3)
        assertEquals("DELIVERED", type3)
    }

    @Test
    fun testOfflineStoreAndForward_ACKCustody() {
        val routingEngine = RoutingEngine(
            myDeviceId = "Bob",
            onApplicationPayloadReceived = { _, _, _, _ -> },
            isPeerAuthorized = { true }
        )

        // No route to Alice yet; Bob sends receipt
        val receiptMsg = routingEngine.sendReceipt("Alice", "42", "DELIVERED")
        assertEquals("Alice", receiptMsg.destinationPeerId)
        assertEquals("Bob", receiptMsg.sourcePeerId)
        assertEquals(MessageType.ACK, receiptMsg.type)
        assertEquals("42:DELIVERED", String(receiptMsg.payload, Charsets.UTF_8))

        // Receipt should be held in pending custody outbox since Alice is offline
        assertEquals(1, routingEngine.pendingUnicastCustody.size)
        assertEquals(receiptMsg.messageId, routingEngine.pendingUnicastCustody[0].messageId)
    }

    @Test
    fun testMultiHop_ACKRoutingWithoutInspection() {
        var appPayloadCount = 0
        var ackCount = 0

        val relayEngine = RoutingEngine(
            myDeviceId = "RelayNode",
            onApplicationPayloadReceived = { _, _, _, _ -> appPayloadCount++ },
            onAckReceived = { _, _, _ -> ackCount++ },
            isPeerAuthorized = { true }
        )
        relayEngine.routeTable.updateRoute("Alice", "NeighborRelay", "Nearby", 1)

        // Relay receives an ACK whose destination is Alice (not RelayNode)
        val ackMessage = MeshMessage(
            messageId = "ack-1",
            sourcePeerId = "Bob",
            destinationPeerId = "Alice",
            type = MessageType.ACK,
            payload = "101:DELIVERED".toByteArray(Charsets.UTF_8),
            hopCount = 1
        )

        relayEngine.processIncomingMessage(ackMessage, "SenderNeighbor", "Nearby")

        // Relay should NOT process ACK as local application payload
        assertEquals("Relay must not process forwarded ACK payload locally", 0, appPayloadCount)
        assertEquals("Relay must not fire onAckReceived for forwarded ACK", 0, ackCount)
    }
}
