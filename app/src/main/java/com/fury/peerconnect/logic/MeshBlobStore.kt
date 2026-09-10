package com.fury.peerconnect.logic

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object MeshBlobStore {
    const val TAG = "AttachmentPipeline"
    private const val BLOB_DIR_NAME = "mesh_blobs"

    fun getBlobDir(context: Context): File {
        val dir = File(context.filesDir, BLOB_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun log(tag: String, msg: String, err: Throwable? = null) {
        try {
            if (err != null) Log.e(tag, msg, err) else Log.d(tag, msg)
        } catch (_: Throwable) {
            if (err != null) println("[$tag] $msg: ${err.message}") else println("[$tag] $msg")
        }
    }

    fun has(blobDir: File, hash: String): Boolean {
        if (hash.isBlank()) return false
        val file = File(blobDir, "${hash.lowercase()}.blob")
        return file.exists() && file.length() > 0L
    }

    fun has(context: Context, hash: String): Boolean = has(getBlobDir(context), hash)

    fun getFile(blobDir: File, hash: String): File? {
        if (!has(blobDir, hash)) return null
        return File(blobDir, "${hash.lowercase()}.blob")
    }

    fun getFile(context: Context, hash: String): File? = getFile(getBlobDir(context), hash)

    fun computeSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha256(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun saveIncoming(blobDir: File, expectedHash: String, incomingFile: File): Boolean {
        val cleanExpected = expectedHash.trim().lowercase()
        val actualHash = computeSha256(incomingFile).lowercase()
        log(TAG, "ATTACHMENT_HASH_VERIFY: expected=$cleanExpected, actual=$actualHash")

        if (cleanExpected.isNotEmpty() && actualHash != cleanExpected) {
            log(TAG, "ATTACHMENT_HASH_MISMATCH: expected $cleanExpected but got $actualHash. Deleting temp file.")
            if (incomingFile.exists()) {
                incomingFile.delete()
            }
            return false
        }

        val finalHash = if (cleanExpected.isNotEmpty()) cleanExpected else actualHash
        if (!blobDir.exists()) blobDir.mkdirs()
        val destinationFile = File(blobDir, "$finalHash.blob")

        return try {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            val moved = incomingFile.renameTo(destinationFile)
            if (!moved) {
                incomingFile.copyTo(destinationFile, overwrite = true)
                incomingFile.delete()
            }
            if (destinationFile.exists() && destinationFile.length() > 0L) {
                log(TAG, "ATTACHMENT_SAVE_SUCCESS: hash=$finalHash, bytes=${destinationFile.length()}")
                true
            } else {
                log(TAG, "Failed saving blob file: file is empty or missing")
                false
            }
        } catch (e: Exception) {
            log(TAG, "Error moving incoming file to blob store", e)
            if (destinationFile.exists()) destinationFile.delete()
            false
        }
    }

    fun saveIncoming(context: Context, expectedHash: String, incomingFile: File): Boolean =
        saveIncoming(getBlobDir(context), expectedHash, incomingFile)

    fun saveDirectBytes(blobDir: File, expectedHash: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val cleanExpected = expectedHash.trim().lowercase()
        val actualHash = computeSha256(bytes).lowercase()
        log(TAG, "ATTACHMENT_HASH_VERIFY: expected=$cleanExpected, actual=$actualHash")

        if (cleanExpected.isNotEmpty() && actualHash != cleanExpected) {
            log(TAG, "ATTACHMENT_HASH_MISMATCH: expected $cleanExpected but got $actualHash")
            return false
        }

        val finalHash = if (cleanExpected.isNotEmpty()) cleanExpected else actualHash
        if (!blobDir.exists()) blobDir.mkdirs()
        val destinationFile = File(blobDir, "$finalHash.blob")
        return try {
            FileOutputStream(destinationFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            if (destinationFile.exists() && destinationFile.length() > 0L) {
                log(TAG, "ATTACHMENT_SAVE_SUCCESS: hash=$finalHash, bytes=${destinationFile.length()}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log(TAG, "Error writing direct bytes to blob store", e)
            if (destinationFile.exists()) destinationFile.delete()
            false
        }
    }

    fun saveDirectBytes(context: Context, expectedHash: String, bytes: ByteArray): Boolean =
        saveDirectBytes(getBlobDir(context), expectedHash, bytes)

    fun exportReadableFile(blobDir: File, cacheDir: File, hash: String, originalFileName: String, attachmentKey: String?): File? {
        val blobFile = getFile(blobDir, hash) ?: return null
        val attachDir = File(cacheDir, "attachments").apply { if (!exists()) mkdirs() }
        var cleanName = Regex("[^a-zA-Z0-9._-]").replace(originalFileName, "_")
        if (cleanName.isEmpty()) cleanName = "attachment"
        val exportFile = File(attachDir, "${hash.take(8)}_$cleanName")

        return try {
            if (!attachmentKey.isNullOrBlank()) {
                val cipherBytes = blobFile.readBytes()
                val plainBytes = SecurityHelper.decryptBytes(cipherBytes, attachmentKey)
                FileOutputStream(exportFile).use { fos ->
                    fos.write(plainBytes)
                    fos.flush()
                }
            } else {
                blobFile.copyTo(exportFile, overwrite = true)
            }
            if (exportFile.exists() && exportFile.length() > 0L) {
                exportFile
            } else {
                null
            }
        } catch (e: Exception) {
            log(TAG, "Error exporting readable file from blob store", e)
            null
        }
    }

    fun exportReadableFile(context: Context, hash: String, originalFileName: String, attachmentKey: String?): File? =
        exportReadableFile(getBlobDir(context), context.cacheDir, hash, originalFileName, attachmentKey)
}
