package com.fury.peerconnect.network.manager

import android.util.Log
import com.fury.peerconnect.data.BlobRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BlobExchange(
    val blobRepository: BlobRepository,
    val myPeerId: String,
    val sendBlobRequest: (targetPeer: String?, hash: String) -> Unit,
    val sendBlobData: (targetPeer: String, hash: String, bytes: ByteArray) -> Unit,
    val onBlobObtained: (hash: String) -> Unit
) {
    companion object {
        const val TAG = "AttachmentPipeline"
    }

    private fun log(msg: String, err: Throwable? = null) {
        try {
            if (err != null) Log.e(TAG, msg, err) else Log.d(TAG, msg)
        } catch (_: Throwable) {
            if (err != null) println("[$TAG] $msg: ${err.message}") else println("[$TAG] $msg")
        }
    }

    val fetching: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingPeerFetches = ConcurrentHashMap<String, MutableSet<String>>()
    private val custodyHashes = ConcurrentHashMap.newKeySet<String>()

    fun want(hash: String, preferredPeer: String? = null): Boolean {
        val cleanHash = hash.trim().lowercase()
        if (cleanHash.isBlank()) return false

        if (blobRepository.has(cleanHash)) {
            log("ATTACHMENT_ALREADY_AVAILABLE: hash=$cleanHash")
            onBlobObtained(cleanHash)
            return true
        }

        if (!fetching.add(cleanHash)) {
            log("ATTACHMENT_FETCH_IN_PROGRESS: hash=$cleanHash already fetching")
            return false
        }

        log("ATTACHMENT_TRANSFER_STARTED: hash=$cleanHash from=${preferredPeer ?: "neighbors"}")
        if (preferredPeer != null) {
            pendingPeerFetches.computeIfAbsent(preferredPeer) { ConcurrentHashMap.newKeySet() }.add(cleanHash)
        }

        try {
            sendBlobRequest(preferredPeer, cleanHash)
        } catch (e: Exception) {
            log("Error sending blob request for $cleanHash", e)
            fetching.remove(cleanHash)
            return false
        }
        return true
    }

    fun onRequest(fromPeer: String, hash: String) {
        val cleanHash = hash.trim().lowercase()
        if (blobRepository.has(cleanHash)) {
            log("ATTACHMENT_SERVING: hash=$cleanHash to $fromPeer")
            val file = blobRepository.getFile(cleanHash)
            if (file != null && file.exists() && file.length() > 0L) {
                try {
                    val bytes = file.readBytes()
                    sendBlobData(fromPeer, cleanHash, bytes)
                } catch (e: Exception) {
                    log("Failed reading blob file for $cleanHash", e)
                }
            }
        } else {
            log("Cannot serve blob $cleanHash to $fromPeer: not found locally")
        }
    }

    fun onReceived(hash: String, file: File, fromPeer: String): Boolean {
        val cleanHash = hash.trim().lowercase()
        log("ATTACHMENT_TRANSFER_COMPLETE: hash=$cleanHash bytes=${file.length()} from=$fromPeer")
        try {
            val saved = blobRepository.saveIncoming(cleanHash, file)
            if (saved) {
                log("ATTACHMENT_ON_OBTAINED: hash=$cleanHash from=$fromPeer")
                onBlobObtained(cleanHash)
                return true
            } else {
                log("ATTACHMENT_VERIFY_OR_SAVE_FAILED: hash=$cleanHash from=$fromPeer")
                return false
            }
        } finally {
            fetching.remove(cleanHash)
            pendingPeerFetches[fromPeer]?.remove(cleanHash)
        }
    }

    fun onReceivedBytes(hash: String, bytes: ByteArray, fromPeer: String): Boolean {
        val cleanHash = hash.trim().lowercase()
        log("ATTACHMENT_TRANSFER_COMPLETE: hash=$cleanHash bytes=${bytes.size} from=$fromPeer")
        try {
            val saved = blobRepository.saveDirectBytes(cleanHash, bytes)
            if (saved) {
                log("ATTACHMENT_ON_OBTAINED: hash=$cleanHash from=$fromPeer")
                onBlobObtained(cleanHash)
                return true
            } else {
                log("ATTACHMENT_VERIFY_OR_SAVE_FAILED: hash=$cleanHash from=$fromPeer")
                return false
            }
        } finally {
            fetching.remove(cleanHash)
            pendingPeerFetches[fromPeer]?.remove(cleanHash)
        }
    }

    fun onNeighborAdded(peer: String) {
        log("BlobExchange onNeighborAdded: $peer")
        // Retry fetching missing blobs from newly arrived peer
        val activeFetches = fetching.toList()
        for (hash in activeFetches) {
            if (!blobRepository.has(hash)) {
                sendBlobRequest(peer, hash)
            } else {
                fetching.remove(hash)
            }
        }
        for (custodyHash in custodyHashes) {
            if (!blobRepository.has(custodyHash)) {
                want(custodyHash, peer)
            }
        }
    }

    fun onNeighborDisconnected(peer: String) {
        log("BlobExchange onNeighborDisconnected: $peer")
        val hashesForPeer = pendingPeerFetches.remove(peer)
        if (hashesForPeer != null) {
            for (hash in hashesForPeer) {
                // Clear fetching flag so retries are immediately permitted on next opportunity
                fetching.remove(hash)
            }
        }
    }

    fun registerCustodyHash(hash: String, fromPeer: String? = null) {
        val cleanHash = hash.trim().lowercase()
        if (cleanHash.isBlank()) return
        custodyHashes.add(cleanHash)
        if (!blobRepository.has(cleanHash)) {
            want(cleanHash, fromPeer)
        }
    }

    fun resumePendingFetches(missingHashes: List<String>) {
        log("BlobExchange resumePendingFetches: ${missingHashes.size} hashes")
        for (hash in missingHashes) {
            val clean = hash.trim().lowercase()
            if (clean.isNotBlank() && !blobRepository.has(clean)) {
                fetching.remove(clean)
                want(clean)
            }
        }
    }
}
