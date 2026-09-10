package com.fury.peerconnect.data

import android.content.Context
import com.fury.peerconnect.logic.MeshBlobStore
import java.io.File

class BlobRepository(
    private val context: Context? = null,
    private val explicitBlobDir: File? = null,
    private val explicitCacheDir: File? = null
) {
    constructor(context: Context) : this(context, null, null)
    constructor(blobDir: File, cacheDir: File? = null) : this(null, blobDir, cacheDir)

    private fun getBlobDir(): File = explicitBlobDir ?: MeshBlobStore.getBlobDir(context!!)
    private fun getCacheDir(): File = explicitCacheDir ?: (context?.cacheDir ?: File(getBlobDir(), "cache"))

    fun has(hash: String): Boolean {
        return MeshBlobStore.has(getBlobDir(), hash)
    }

    fun getFile(hash: String): File? {
        return MeshBlobStore.getFile(getBlobDir(), hash)
    }

    fun getAttachmentState(hash: String, isReceiving: Boolean = false, progress: Float = 0f): AttachmentState {
        return when {
            has(hash) -> AttachmentState.Ready
            isReceiving -> AttachmentState.Receiving(progress)
            hash.isNotBlank() -> AttachmentState.Requesting
            else -> AttachmentState.Missing
        }
    }

    fun saveIncoming(expectedHash: String, incomingFile: File): Boolean {
        return MeshBlobStore.saveIncoming(getBlobDir(), expectedHash, incomingFile)
    }

    fun saveDirectBytes(expectedHash: String, bytes: ByteArray): Boolean {
        return MeshBlobStore.saveDirectBytes(getBlobDir(), expectedHash, bytes)
    }

    fun exportReadableFile(hash: String, originalFileName: String, attachmentKey: String?): File? {
        return MeshBlobStore.exportReadableFile(getBlobDir(), getCacheDir(), hash, originalFileName, attachmentKey)
    }

    fun computeSha256(file: File): String {
        return MeshBlobStore.computeSha256(file)
    }

    fun computeSha256(bytes: ByteArray): String {
        return MeshBlobStore.computeSha256(bytes)
    }
}
