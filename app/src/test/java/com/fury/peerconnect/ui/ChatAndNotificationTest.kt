package com.fury.peerconnect.ui

import com.fury.peerconnect.ui.ChatAdapter
import com.fury.peerconnect.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

class ChatAndNotificationTest {

    @Test
    fun testTimestampFormatting_FormatsWhatsAppStyle() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.set(2026, Calendar.SEPTEMBER, 6, 10, 22, 0)
        val timeMillis = cal.timeInMillis

        val formatted = ChatAdapter.formatMessageTime(timeMillis, Locale.US)
        // Check that format matches "10:22 AM" (or matching hh:mm a pattern)
        assertTrue("Formatted time should match hh:mm a, got: $formatted", formatted.matches(Regex("\\d{1,2}:\\d{2}\\s?[AP]M", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun testTimestampFormatting_InvalidTimestampFallback() {
        val formatted = ChatAdapter.formatMessageTime(0L, Locale.US)
        assertTrue("Should produce non-empty formatted time on 0L fallback", formatted.isNotEmpty())
    }

    @Test
    fun testNotificationDecision_WhenChatIsClosed_ShouldNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = true,
            isChatOpen = false,
            currentChatPeerName = null,
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertTrue("Should notify when chat is closed", shouldNotify)
    }

    @Test
    fun testNotificationDecision_WhenAppInBackground_ShouldNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = false,
            isChatOpen = true,
            currentChatPeerName = "PeerB",
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertTrue("Should notify when app is in background even if chat peer matches", shouldNotify)
    }

    @Test
    fun testNotificationDecision_WhenDifferentChatIsOpen_ShouldNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = true,
            isChatOpen = true,
            currentChatPeerName = "PeerC",
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertTrue("Should notify when a different conversation is open", shouldNotify)
    }

    @Test
    fun testNotificationDecision_WhenChatIsActivelyOpen_ShouldNotNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = true,
            isChatOpen = true,
            currentChatPeerName = "PeerB",
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertFalse("Must NOT notify when user is actively viewing that exact conversation", shouldNotify)
    }

    @Test
    fun testNotificationDecision_SentByLocalUser_ShouldNotNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = false,
            isChatOpen = false,
            currentChatPeerName = null,
            senderName = "PeerA",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertFalse("Must NOT notify for messages sent by local user", shouldNotify)
    }

    @Test
    fun testNotificationDecision_DuplicateOrReplayedMessage_ShouldNotNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = false,
            isChatOpen = false,
            currentChatPeerName = null,
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = true,
            isInternalProtocol = false,
            hasNotificationPermission = true
        )
        assertFalse("Must NOT notify for duplicate/replayed messages", shouldNotify)
    }

    @Test
    fun testNotificationDecision_InternalProtocolFrame_ShouldNotNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = false,
            isChatOpen = false,
            currentChatPeerName = null,
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = true,
            hasNotificationPermission = true
        )
        assertFalse("Must NOT notify for internal protocol frames", shouldNotify)
    }

    @Test
    fun testNotificationDecision_NoPermission_ShouldNotNotify() {
        val shouldNotify = MainActivity.shouldNotifyForInboundMessage(
            isAppResumed = false,
            isChatOpen = false,
            currentChatPeerName = null,
            senderName = "PeerB",
            myNickName = "PeerA",
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = false
        )
        assertFalse("Must NOT notify when notification permission is not granted", shouldNotify)
    }

    @Test
    fun testInboundDeduplication_SuppressesDuplicates() {
        val processedSet = Collections.synchronizedSet(LinkedHashSet<String>())
        fun recordInbound(key: String): Boolean {
            synchronized(processedSet) {
                if (processedSet.contains(key)) return false
                if (processedSet.size >= 1000) {
                    val first = processedSet.iterator().next()
                    processedSet.remove(first)
                }
                processedSet.add(key)
                return true
            }
        }

        val key = "PeerB|1725610000000|Hello"
        assertTrue("First arrival must be recorded", recordInbound(key))
        assertFalse("Duplicate arrival must be suppressed", recordInbound(key))
        assertFalse("Triplicate arrival must be suppressed", recordInbound(key))
    }

    @Test
    fun testSafeNotificationPreview_FormatsCorrectly() {
        assertEquals("Hello there", MainActivity.buildSafeNotificationPreview("Hello there", "TEXT", null))
        assertEquals("📷 vacation.jpg", MainActivity.buildSafeNotificationPreview("[FILE]:vacation.jpg|/path/img|1|100|SUCCESS", "IMAGE", "vacation.jpg"))
        assertEquals("📄 document.pdf", MainActivity.buildSafeNotificationPreview("[FILE]:document.pdf|/path/doc|2|200|SUCCESS", "FILE", "document.pdf"))
        assertEquals("📄 notes.txt", MainActivity.buildSafeNotificationPreview("[FILE_DATA]:notes.txt|base64...", "TEXT", null))
    }

    @Test
    fun testStableNotificationId_PerPeer() {
        val id1 = MainActivity.getNotificationIdForPeer("Alice")
        val id2 = MainActivity.getNotificationIdForPeer("Alice")
        val idBob = MainActivity.getNotificationIdForPeer("Bob")

        assertEquals("Same peer must produce identical notification ID", id1, id2)
        assertTrue("Notification ID must be positive", id1 >= 0)
        assertTrue("Different peers should typically produce different IDs", id1 != idBob)
    }

    @Test
    fun testNavigationBackStateCalculation() {
        fun isBackCallbackEnabled(isChatOpen: Boolean, isAlertThreadOpen: Boolean, selectedTab: Int, homeTabId: Int): Boolean {
            return isChatOpen || isAlertThreadOpen || selectedTab != homeTabId
        }

        val HOME = 100
        val MESSAGES = 200

        // Chat open on Home -> back intercepts to close chat
        assertTrue(isBackCallbackEnabled(isChatOpen = true, isAlertThreadOpen = false, selectedTab = HOME, homeTabId = HOME))

        // Chat closed on Home -> back does not intercept, letting system exit/finish naturally
        assertFalse(isBackCallbackEnabled(isChatOpen = false, isAlertThreadOpen = false, selectedTab = HOME, homeTabId = HOME))

        // Chat closed on Messages tab -> back intercepts to return to Home
        assertTrue(isBackCallbackEnabled(isChatOpen = false, isAlertThreadOpen = false, selectedTab = MESSAGES, homeTabId = HOME))
    }
}
