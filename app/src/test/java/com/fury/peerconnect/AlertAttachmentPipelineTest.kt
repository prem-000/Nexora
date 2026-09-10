package com.fury.peerconnect

import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.data.AlertPayload
import com.fury.peerconnect.data.AttachmentState
import com.fury.peerconnect.data.BlobRepository
import com.fury.peerconnect.logic.MeshBlobStore
import com.fury.peerconnect.logic.SecurityHelper
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

class AlertAttachmentPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var senderBlobDir: File
    private lateinit var carrierBlobDir: File
    private lateinit var receiverBlobDir: File

    @Before
    fun setUp() {
        senderBlobDir = tempFolder.newFolder("sender_blobs")
        carrierBlobDir = tempFolder.newFolder("carrier_blobs")
        receiverBlobDir = tempFolder.newFolder("receiver_blobs")
    }

    // =========================================================================
    // SCENARIO 1 & 2: Send and Receive Image in Normal Chat
    // =========================================================================

    @Test
    fun testScenario1And2_NormalChat_SendAndReceiveImageAttachment() {
        val senderRepo = BlobRepository(senderBlobDir)
        val receiverRepo = BlobRepository(receiverBlobDir)

        val imageBytes = "EXIF_JPEG_IMAGE_PIPELINE_DATA_0123456789".toByteArray()
        val imageHash = MeshBlobStore.computeSha256(imageBytes)
        val fileName = "camera_snapshot.jpg"

        // Sender saves blob locally
        senderRepo.saveDirectBytes(imageHash, imageBytes)
        assertTrue(senderRepo.has(imageHash))

        // Wire body has stable hash without leaking sender's private path
        val wireBody = "[FILE]:$fileName||$imageHash|${imageBytes.size}|RECEIVING|image/jpeg"
        val encryptedWire = SecurityHelper.encrypt(wireBody)
        val decrypted = SecurityHelper.decrypt(encryptedWire)

        // Parse wire format
        assertTrue(decrypted.startsWith("[FILE]:"))
        val parts = decrypted.removePrefix("[FILE]:").split("|")
        assertEquals(fileName, parts[0])
        assertEquals("", parts[1]) // No leaked local sender path
        assertEquals(imageHash, parts[2]) // Stable hash

        // Receiver initially does not have blob
        assertFalse(receiverRepo.has(imageHash))

        // Transfer via BlobExchange
        lateinit var senderExchange: BlobExchange
        lateinit var receiverExchange: BlobExchange

        senderExchange = BlobExchange(
            blobRepository = senderRepo,
            myPeerId = "Alice",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, hash, bytes ->
                receiverExchange.onReceivedBytes(hash, bytes, "Alice")
            },
            onBlobObtained = {}
        )

        var receiverObtained = false
        receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "Bob",
            sendBlobRequest = { _, hash ->
                senderExchange.onRequest("Bob", hash)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = { h ->
                if (h == imageHash) receiverObtained = true
            }
        )

        receiverExchange.want(imageHash, "Alice")

        assertTrue("Receiver must obtain blob via BlobExchange", receiverObtained)
        assertTrue("Receiver repo must have blob", receiverRepo.has(imageHash))
        assertArrayEquals(imageBytes, receiverRepo.getFile(imageHash)?.readBytes())
    }

    // =========================================================================
    // SCENARIO 3 & 4: Send and Receive SOS Alert with Image Attachment
    // =========================================================================

    @Test
    fun testScenario3And4_SendAndReceiveSOSAlertWithAttachment() {
        val senderRepo = BlobRepository(senderBlobDir)
        val receiverRepo = BlobRepository(receiverBlobDir)

        val alertImageBytes = "SOS_RESCUE_SITE_IMAGE_PAYLOAD".toByteArray()
        val alertImageHash = MeshBlobStore.computeSha256(alertImageBytes)
        val fileName = "emergency_map.png"

        senderRepo.saveDirectBytes(alertImageHash, alertImageBytes)

        // Sender creates alert: wire attachment has empty localPath but stable hash
        val wireAttachment = "[FILE]:$fileName||$alertImageHash|${alertImageBytes.size}|RECEIVING"
        val alertPayload = AlertPayload(
            id = "alert_sos_001",
            senderId = "Alice",
            alertType = "SOS",
            title = "SOS Alert",
            message = "Bridge collapsed at sector 4",
            sentAt = 1725619200000L,
            attachmentPath = wireAttachment
        )

        val wireString = alertPayload.toWireString()
        val parsed = AlertPayload.parse(wireString)
        assertNotNull(parsed)
        assertEquals(wireAttachment, parsed?.attachmentPath)

        // Receiver checks local blob store
        assertFalse("Receiver initially does not have blob", receiverRepo.has(alertImageHash))

        // When receiver parses attachment:
        val clean = parsed!!.attachmentPath!!.removePrefix("[FILE]:")
        val parts = clean.split("|")
        val parsedFileName = parts[0]
        val parsedHash = parts[2]
        assertEquals(fileName, parsedFileName)
        assertEquals(alertImageHash, parsedHash)

        // Receiver state should be RECEIVING since blob is missing
        val initialStatus = if (receiverRepo.has(parsedHash)) "AVAILABLE" else "RECEIVING"
        assertEquals("RECEIVING", initialStatus)
    }

    // =========================================================================
    // SCENARIO 5, 6, 7 & 8: Missing Blob -> Request -> Completion -> Resolution
    // =========================================================================

    @Test
    fun testScenario5To8_MissingBlob_RequestsViaBlobExchange_Completes_ResolvesAlert() {
        val senderRepo = BlobRepository(senderBlobDir)
        val receiverRepo = BlobRepository(receiverBlobDir)

        val imageBytes = "HIGH_RES_FLOOD_PHOTO".toByteArray()
        val imageHash = MeshBlobStore.computeSha256(imageBytes)
        val fileName = "flood_photo.jpg"

        senderRepo.saveDirectBytes(imageHash, imageBytes)

        // 5. Receiver initially does NOT have the blob
        assertFalse(receiverRepo.has(imageHash))
        assertEquals(AttachmentState.Requesting, receiverRepo.getAttachmentState(imageHash))

        lateinit var senderExchange: BlobExchange
        lateinit var receiverExchange: BlobExchange

        senderExchange = BlobExchange(
            blobRepository = senderRepo,
            myPeerId = "Alice",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, hash, bytes ->
                receiverExchange.onReceivedBytes(hash, bytes, "Alice")
            },
            onBlobObtained = {}
        )

        var completedHash: String? = null
        receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "Bob",
            sendBlobRequest = { _, hash ->
                // 6. Receiver requests blob through existing BlobExchange
                senderExchange.onRequest("Bob", hash)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = { hash ->
                completedHash = hash
            }
        )

        // Trigger request
        receiverExchange.want(imageHash, "Alice")

        // 7. Blob completes
        assertEquals(imageHash, completedHash)
        assertTrue(receiverRepo.has(imageHash))
        assertEquals(AttachmentState.Ready, receiverRepo.getAttachmentState(imageHash))

        // 8. Alert attachment resolves successfully from persistent blob storage
        val resolvedBlobFile = receiverRepo.getFile(imageHash)
        assertNotNull("Resolved blob file must exist", resolvedBlobFile)
        assertTrue("Blob file must not be empty", resolvedBlobFile!!.length() > 0L)
        assertArrayEquals(imageBytes, resolvedBlobFile.readBytes())
    }

    // =========================================================================
    // SCENARIO 9: App Restart Persistence
    // =========================================================================

    @Test
    fun testScenario9_AppRestart_PersistentAlertAttachmentStillResolves() {
        val receiverRepo1 = BlobRepository(receiverBlobDir)
        val fileBytes = "IMPORTANT_MEDICAL_REPORT_PDF".toByteArray()
        val fileHash = MeshBlobStore.computeSha256(fileBytes)

        // Save blob in session 1
        receiverRepo1.saveDirectBytes(fileHash, fileBytes)
        assertTrue(receiverRepo1.has(fileHash))

        val alert = AlertEntity(
            id = 1,
            type = "SOS",
            title = "Medical Emergency",
            description = "Injured person",
            message = "Injured person",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            peerName = "Alice",
            attachmentPath = "[FILE]:medical_report.pdf||$fileHash|${fileBytes.size}|SUCCESS",
            alertId = "alert_med_01"
        )

        // Simulate app restart: instantiate a new BlobRepository with the same directory
        val receiverRepo2 = BlobRepository(receiverBlobDir)

        // Extract hash from alert entity
        val parts = alert.attachmentPath!!.removePrefix("[FILE]:").split("|")
        val hash = parts[2]
        assertEquals(fileHash, hash)

        // Verify resolved file is still available after restart
        assertTrue("Blob must still exist after app restart", receiverRepo2.has(hash))
        val persistentFile = receiverRepo2.getFile(hash)
        assertNotNull(persistentFile)
        assertArrayEquals(fileBytes, persistentFile!!.readBytes())
    }

    // =========================================================================
    // SCENARIO 10: Missing Blob Shows Retry/Request State, Never Stale File Error
    // =========================================================================

    @Test
    fun testScenario10_MissingBlob_ShowsRetryState_NeverStaleFileError() {
        val receiverRepo = BlobRepository(receiverBlobDir)
        val missingHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        // Simulated alert where blob was never received
        val alert = AlertEntity(
            id = 2,
            type = "SOS",
            title = "Missing Attachment Alert",
            description = "Needs file",
            message = "Needs file",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            peerName = "Alice",
            attachmentPath = "[FILE]:evacuation_route.pdf||$missingHash|1048576|RECEIVING",
            alertId = "alert_missing_01"
        )

        val clean = alert.attachmentPath!!.removePrefix("[FILE]:")
        val parts = clean.split("|")
        val fileName = parts[0]
        val pathOrUri = parts[1]
        val hash = parts[2]
        val status = parts[4]

        val isDirectFileAvailable = pathOrUri.isNotEmpty() && File(pathOrUri).exists()
        val isBlobStoreAvailable = hash.isNotEmpty() && receiverRepo.has(hash)
        val isAvailable = isDirectFileAvailable || isBlobStoreAvailable

        assertFalse("Attachment must not be marked available when blob is missing", isAvailable)

        // UI state resolution check
        val uiState = when {
            isAvailable -> "AVAILABLE"
            status == "RECEIVING" -> "RECEIVING"
            status == "FAILED" -> "FAILED"
            else -> "MISSING"
        }

        assertEquals("RECEIVING", uiState)
        // Verify that retry action is available via hash
        assertTrue("Hash must be available for BlobExchange retry request", hash.length == 64)
    }

    // =========================================================================
    // MULTI-HOP CUSTODY TEST: Carrier relays alert attachment
    // =========================================================================

    @Test
    fun testMultiHopCustody_AlertAttachmentServedByCarrier() {
        val senderRepo = BlobRepository(senderBlobDir)
        val carrierRepo = BlobRepository(carrierBlobDir)
        val receiverRepo = BlobRepository(receiverBlobDir)

        val attachmentData = "SATELLITE_EMERGENCY_WEATHER_RADAR".toByteArray()
        val hash = MeshBlobStore.computeSha256(attachmentData)

        senderRepo.saveDirectBytes(hash, attachmentData)

        // Carrier fetches blob from sender
        lateinit var carrierExchange: BlobExchange
        val senderExchange = BlobExchange(
            blobRepository = senderRepo,
            myPeerId = "Alice",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, h, b ->
                carrierExchange.onReceivedBytes(h, b, "Alice")
            },
            onBlobObtained = {}
        )

        carrierExchange = BlobExchange(
            blobRepository = carrierRepo,
            myPeerId = "Carrier_Node",
            sendBlobRequest = { _, h ->
                senderExchange.onRequest("Carrier_Node", h)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = {}
        )

        carrierExchange.want(hash, "Alice")
        assertTrue("Carrier must have custody of blob", carrierRepo.has(hash))

        // Sender goes offline. Receiver requests from Carrier.
        lateinit var receiverExchange: BlobExchange
        val carrierServingExchange = BlobExchange(
            blobRepository = carrierRepo,
            myPeerId = "Carrier_Node",
            sendBlobRequest = { _, _ -> },
            sendBlobData = { _, h, b ->
                receiverExchange.onReceivedBytes(h, b, "Carrier_Node")
            },
            onBlobObtained = {}
        )

        var receiverGotBlob = false
        receiverExchange = BlobExchange(
            blobRepository = receiverRepo,
            myPeerId = "Charlie",
            sendBlobRequest = { _, h ->
                carrierServingExchange.onRequest("Charlie", h)
            },
            sendBlobData = { _, _, _ -> },
            onBlobObtained = { h ->
                if (h == hash) receiverGotBlob = true
            }
        )

        receiverExchange.want(hash, "Carrier_Node")

        assertTrue("Receiver must receive blob from intermediate carrier", receiverGotBlob)
        assertTrue(receiverRepo.has(hash))
        assertArrayEquals(attachmentData, receiverRepo.getFile(hash)?.readBytes())
    }
}
