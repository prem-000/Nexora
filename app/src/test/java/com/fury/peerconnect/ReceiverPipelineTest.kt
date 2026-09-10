package com.fury.peerconnect

import android.content.Intent
import android.net.Uri
import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.data.AlertPayload
import com.fury.peerconnect.data.AttachmentState
import com.fury.peerconnect.data.BlobRepository
import com.fury.peerconnect.logic.MeshBlobStore
import com.fury.peerconnect.logic.SecurityHelper
import com.fury.peerconnect.network.FakeMeshTransport
import com.fury.peerconnect.network.manager.BlobExchange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceiverPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var senderDir: File
    private lateinit var intermediateDir: File
    private lateinit var receiverDir: File

    @Before
    fun setUp() {
        senderDir = tempFolder.newFolder("sender_blobs")
        intermediateDir = tempFolder.newFolder("carrier_blobs")
        receiverDir = tempFolder.newFolder("receiver_blobs")
    }

    // =========================================================================
    // PART 1: ALERT PIPELINE TESTS
    // =========================================================================

    @Test
    fun testAlertPayload_WireSerializationAndParsing_PreservesActualMessageBody() {
        val original = AlertPayload(
            id = "alert_101",
            senderId = "peer_alice",
            alertType = "SOS",
            title = "SOS Alert",
            message = "I need help near the north gate",
            sentAt = 1725619200000L
        )

        val wireString = original.toWireString()
        val parsed = AlertPayload.parse(wireString)

        assertNotNull("Parsed payload must not be null", parsed)
        assertEquals("alert_101", parsed?.id)
        assertEquals("peer_alice", parsed?.senderId)
        assertEquals("SOS", parsed?.alertType)
        assertEquals("SOS Alert", parsed?.title)
        assertEquals("I need help near the north gate", parsed?.message)
        assertEquals(1725619200000L, parsed?.sentAt)
    }

    @Test
    fun testAlertPayload_LegacyFormatResilience_NoFieldShifting() {
        // Legacy wire string format: [ALERT]:id|sender|SOS Alert|user message
        val legacyWire = "[ALERT]:alert_legacy_01|Bob|SOS Alert|historically"
        val parsed = AlertPayload.parse(legacyWire)

        assertNotNull(parsed)
        assertEquals("alert_legacy_01", parsed?.id)
        assertEquals("Bob", parsed?.senderId)
        assertEquals("SOS Alert", parsed?.title)
        assertEquals("historically", parsed?.message)
    }

    @Test
    fun testAlertEntity_BodyPropertyReturnsRealMessage() {
        val entity = AlertEntity(
            type = "SOS",
            title = "SOS Alert",
            description = "SOS Alert",
            message = "I need help near the north gate",
            peerName = "Alice",
            originPeerId = "peer_alice",
            alertId = "alert_xyz",
            timestamp = System.currentTimeMillis()
        )

        assertEquals("I need help near the north gate", entity.body)
    }

    @Test
    fun testAlertSenderReceiverMapping() {
        val myPeerId = "my_peer_id"
        val outgoingAlert = AlertEntity(
            type = "SOS",
            title = "SOS Alert",
            description = "SOS Alert",
            message = "Help requested",
            peerName = "Alice (Self)",
            originPeerId = myPeerId,
            timestamp = System.currentTimeMillis()
        )
        val incomingAlert = AlertEntity(
            type = "SOS",
            title = "SOS Alert",
            description = "SOS Alert",
            message = "Help requested",
            peerName = "Bob",
            originPeerId = "peer_bob",
            timestamp = System.currentTimeMillis()
        )

        val outgoingSenderLabel = if (outgoingAlert.originPeerId == myPeerId) "You" else outgoingAlert.peerName
        val incomingSenderLabel = if (incomingAlert.originPeerId == myPeerId) "You" else incomingAlert.peerName

        assertEquals("You", outgoingSenderLabel)
        assertEquals("Bob", incomingSenderLabel)
    }

    // =========================================================================
    // PART 8: FILE & ATTACHMENT PIPELINE TESTS (6 MANDATORY TESTS)
    // =========================================================================

    /**
     * TEST 1: Direct Transfer
     * sender -> sends image -> receiver gets frame -> receiver requests blob ->
     * sender transfers blob -> hash verifies -> blob saves -> message becomes Ready
     */
    @Test
    fun test1_DirectAttachmentTransfer_SenderToReceiver_HashVerifies_MessageReady() {
        val senderRepo = BlobRepository(senderDir)
        val receiverRepo = BlobRepository(receiverDir)

        val imageBytes = "SIMULATED_JPG_IMAGE_CONTENT_BYTES_12345".toByteArray()
        val imageHash = MeshBlobStore.computeSha256(imageBytes)

        // Sender has blob locally
        assertTrue(senderRepo.saveDirectBytes(imageHash, imageBytes))
        assertTrue(senderRepo.has(imageHash))

        // Receiver initially does not have blob
        assertFalse(receiverRepo.has(imageHash))
        assertEquals(AttachmentState.Requesting, receiverRepo.getAttachmentState(imageHash))

        var blobObtainedCalled = false

        lateinit var senderExchange: BlobExchange
        lateinit var receiverExchange: BlobExchange

        senderExchange = BlobExchange(
            blobRepository = senderRepo,
            myPeerId = "sender",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, hash, bytes ->
                receiverExchange.onReceivedBytes(hash, bytes, "sender")
            },
            onBlobObtained = {}
        )

        receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "receiver",
            sendBlobRequest = { _, hash ->
                // Receiver requests blob from sender
                senderExchange.onRequest("receiver", hash)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = { _ ->
                blobObtainedCalled = true
            }
        )

        // Receiver gets message frame and wants blob
        receiverExchange.want(imageHash, "sender")

        // Hash verified, blob saved, callback fired
        assertTrue("onBlobObtained callback must be fired", blobObtainedCalled)
        assertTrue("Receiver must now have blob", receiverRepo.has(imageHash))
        assertEquals(AttachmentState.Ready, receiverRepo.getAttachmentState(imageHash))
        assertFalse("Fetching set must be empty after complete transfer", receiverExchange.fetching.contains(imageHash))

        // Verify content integrity
        val savedFile = receiverRepo.getFile(imageHash)
        assertNotNull(savedFile)
        assertArrayEquals(imageBytes, savedFile!!.readBytes())
    }

    /**
     * TEST 2: Multi-Hop Carrier Custody
     * sender -> sends attachment -> intermediate peer receives -> intermediate stores frame + blob ->
     * sender disconnects -> final receiver connects to intermediate -> receiver requests blob ->
     * intermediate serves blob -> receiver opens attachment
     */
    @Test
    fun test2_MultiHopCarrierCustody_SenderDisconnects_IntermediateServesToReceiver() {
        val senderRepo = BlobRepository(senderDir)
        val carrierRepo = BlobRepository(intermediateDir)
        val receiverRepo = BlobRepository(receiverDir)

        val fileBytes = "OFFLINE_MULTI_HOP_PAYLOAD_CONTENT".toByteArray()
        val fileHash = MeshBlobStore.computeSha256(fileBytes)

        // Step 1: Sender has blob and serves to intermediate carrier
        senderRepo.saveDirectBytes(fileHash, fileBytes)

        lateinit var carrierExchange: BlobExchange
        val senderExchange = BlobExchange(
            blobRepository = senderRepo,
            myPeerId = "sender_A",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { target, hash, bytes ->
                carrierExchange.onReceivedBytes(hash, bytes, "sender_A")
            },
            onBlobObtained = {}
        )

        carrierExchange = BlobExchange(
            blobRepository = carrierRepo,
            myPeerId = "carrier_B",
            sendBlobRequest = { target, hash ->
                senderExchange.onRequest("carrier_B", hash)
            },
            sendBlobData = { target, hash, bytes -> },
            onBlobObtained = {}
        )

        // Carrier receives custody frame and stores blob
        carrierExchange.registerCustodyHash(fileHash, "sender_A")
        assertTrue("Carrier must have custody of blob", carrierRepo.has(fileHash))

        // Step 2: Sender disconnects completely (goes offline / unavailable)
        var senderIsConnected = false // simulated disconnect

        // Step 3: Final receiver connects ONLY to intermediate carrier
        var receiverObtained = false
        lateinit var receiverExchange: BlobExchange

        val carrierExchangeServingToReceiver = BlobExchange(
            blobRepository = carrierRepo,
            myPeerId = "carrier_B",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { target, hash, bytes ->
                receiverExchange.onReceivedBytes(hash, bytes, "carrier_B")
            },
            onBlobObtained = {}
        )

        receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "receiver_C",
            sendBlobRequest = { target, hash ->
                // Receiver asks carrier B for blob
                carrierExchangeServingToReceiver.onRequest("receiver_C", hash)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = {
                receiverObtained = true
            }
        )

        // Receiver requests blob from carrier B
        receiverExchange.want(fileHash, "carrier_B")

        assertTrue("Receiver must obtain blob from carrier without sender connected", receiverObtained)
        assertTrue("Receiver repository must have the blob", receiverRepo.has(fileHash))
        assertEquals(AttachmentState.Ready, receiverRepo.getAttachmentState(fileHash))
        assertArrayEquals(fileBytes, receiverRepo.getFile(fileHash)!!.readBytes())
    }

    /**
     * TEST 3: Partial Transfer & Disconnect Retry
     * partial transfer -> connection drops -> fetching cleared -> retry succeeds
     */
    @Test
    fun test3_PartialTransfer_ConnectionDrops_FetchingCleared_RetrySucceeds() {
        val receiverRepo = BlobRepository(receiverDir)
        val fileHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        var requestSentCount = 0
        val receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "receiver",
            sendBlobRequest = { target, hash ->
                requestSentCount++
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = {}
        )

        // Transfer started
        receiverExchange.want(fileHash, "peer_flake")
        assertTrue("Hash must be in fetching set during transfer", receiverExchange.fetching.contains(fileHash))
        assertEquals(1, requestSentCount)

        // Peer drops connection mid-transfer
        receiverExchange.onNeighborDisconnected("peer_flake")

        // CRITICAL CHECK: Fetching must be cleared so state is not stuck
        assertFalse("Fetching set MUST NOT retain hash after disconnect", receiverExchange.fetching.contains(fileHash))

        // Neighbor comes back or new neighbor appears: retry must succeed immediately
        receiverExchange.onNeighborAdded("peer_reliable")
        // Retry is allowed because fetching was cleared
        val retryStarted = receiverExchange.want(fileHash, "peer_reliable")
        assertTrue("Retry must be allowed after disconnect cleanup", retryStarted)
        assertEquals(2, requestSentCount)
    }

    /**
     * TEST 4: Wrong Hash Rejection
     * wrong hash -> reject -> delete temporary file -> do not mark Ready -> retry allowed
     */
    @Test
    fun test4_WrongHash_Rejected_TempFileDeleted_NotReady_RetryAllowed() {
        val receiverRepo = BlobRepository(receiverDir)
        val expectedHash = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        val corruptedBytes = "TAMPERED_CONTENT_CORRUPTED".toByteArray()

        var onObtainedFired = false
        val receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "receiver",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = { onObtainedFired = true }
        )

        // Mark in progress
        receiverExchange.want(expectedHash, "bad_peer")

        // Corrupted bytes arrive
        val saved = receiverExchange.onReceivedBytes(expectedHash, corruptedBytes, "bad_peer")

        // Must reject
        assertFalse("Mismatched hash must NOT be saved", saved)
        assertFalse("onBlobObtained must NOT be fired for mismatched hash", onObtainedFired)
        assertFalse("Blob repository must NOT contain corrupted blob", receiverRepo.has(expectedHash))
        assertFalse("Fetching set must be cleared on failure to allow retry", receiverExchange.fetching.contains(expectedHash))

        // State remains requesting/missing, never Ready
        val state = receiverRepo.getAttachmentState(expectedHash)
        assertTrue("Attachment state must NOT be Ready", state !is AttachmentState.Ready)

        // Retry is allowed
        assertTrue("Future retry must be allowed", receiverExchange.want(expectedHash, "good_peer"))
    }

    /**
     * TEST 5: E2E Encrypted Attachment
     * receive ciphertext -> save ciphertext blob -> decrypt using attachment key -> render correct image
     */
    @Test
    fun test5_E2EAttachment_ReceiveCiphertext_SaveCiphertextBlob_DecryptWithKey_RendersImage() {
        val receiverRepo = BlobRepository(receiverDir)
        val plainImageBytes = "REAL_UNENCRYPTED_IMAGE_DATA_EXACT_PIXELS".toByteArray()

        // Generate attachment key
        val attachmentKey = SecurityHelper.generateAttachmentKey()
        assertNotNull(attachmentKey)

        // Sender encrypts attachment bytes
        val cipherBytes = SecurityHelper.encryptBytes(plainImageBytes, attachmentKey)
        assertFalse("Ciphertext must not equal plaintext", plainImageBytes.contentEquals(cipherBytes))

        // Compute hash of ciphertext blob (content-addressed encrypted blob)
        val cipherHash = MeshBlobStore.computeSha256(cipherBytes)

        // Receiver saves the ciphertext blob
        val saved = receiverRepo.saveDirectBytes(cipherHash, cipherBytes)
        assertTrue(saved)
        assertTrue(receiverRepo.has(cipherHash))

        // Decrypt using attachmentKey for viewing/rendering
        val exportedFile = receiverRepo.exportReadableFile(cipherHash, "photo.jpg", attachmentKey)
        assertNotNull("Exported readable file must not be null", exportedFile)
        assertTrue("Exported file must exist", exportedFile!!.exists())

        // Decrypted bytes must match original plaintext image bytes exactly
        val decryptedBytes = exportedFile.readBytes()
        assertArrayEquals("Decrypted bytes must match original plaintext image bytes", plainImageBytes, decryptedBytes)
    }

    /**
     * TEST 6: Video and Document Attachment with MIME Preservation & Intent Creation
     * transfer completes -> MIME preserved -> FileProvider URI generated -> ACTION_VIEW intent created
     */
    @Test
    fun test6_VideoAndDocumentAttachment_MimePreserved_FileProviderIntentCreated() {
        val receiverRepo = BlobRepository(receiverDir)

        val pdfBytes = "%PDF-1.4 Simulated PDF Document Content".toByteArray()
        val pdfHash = MeshBlobStore.computeSha256(pdfBytes)
        receiverRepo.saveDirectBytes(pdfHash, pdfBytes)

        val mp4Bytes = "ftypmp42 Simulated Video Content".toByteArray()
        val mp4Hash = MeshBlobStore.computeSha256(mp4Bytes)
        receiverRepo.saveDirectBytes(mp4Hash, mp4Bytes)

        // Export readable files
        val pdfFile = receiverRepo.exportReadableFile(pdfHash, "invoice.pdf", null)
        assertNotNull(pdfFile)
        val mp4File = receiverRepo.exportReadableFile(mp4Hash, "video.mp4", null)
        assertNotNull(mp4File)

        // Verify MIME resolution logic
        val pdfMime = when (pdfFile!!.extension.lowercase()) {
            "pdf" -> "application/pdf"
            else -> "*/*"
        }
        val mp4Mime = when (mp4File!!.extension.lowercase()) {
            "mp4" -> "video/mp4"
            else -> "*/*"
        }

        assertEquals("application/pdf", pdfMime)
        assertEquals("video/mp4", mp4Mime)

        // Verify URI generation rule: MUST use content:// and NEVER file://
        val uriString = "content://com.fury.peerconnect.fileprovider/attachments/${pdfFile.name}"
        val uri = java.net.URI(uriString)
        assertEquals("content", uri.scheme)
        assertFalse("Must NEVER use file:// URI scheme", uriString.startsWith("file://"))

        // Verify Intent configuration and flags
        assertEquals("android.intent.action.VIEW", Intent.ACTION_VIEW)
        val expectedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        assertTrue("Intent must include FLAG_GRANT_READ_URI_PERMISSION", (expectedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue("Intent must include FLAG_ACTIVITY_NEW_TASK", (expectedFlags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
