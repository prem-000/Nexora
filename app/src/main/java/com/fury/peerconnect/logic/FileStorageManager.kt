package com.fury.peerconnect.logic

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import com.google.android.gms.nearby.connection.Payload
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

object FileStorageManager {
    private const val TAG = "FileStorageManager"

    fun getStorageDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun generateUniqueFile(context: Context, originalFileName: String, prefix: String = "rec"): File {
        val storageDir = getStorageDir(context)
        var cleanName = Regex("[^a-zA-Z0-9._-]").replace(originalFileName, "_")
        if (cleanName.isEmpty()) {
            cleanName = "file"
        }
        val randomSuffix = UUID.randomUUID().toString().take(6)
        val uniqueName = "${prefix}_${System.currentTimeMillis()}_${randomSuffix}_$cleanName"
        return File(storageDir, uniqueName)
    }

    fun saveReceivedPayloadFile(context: Context, filePayload: Payload, originalFileName: String): File? {
        val destinationFile = generateUniqueFile(context, originalFileName, "rec_${filePayload.id}")
        try {
            val fileAsFile = filePayload.asFile()
            if (fileAsFile == null) {
                Log.e(TAG, "Payload is not a file payload (id=${filePayload.id})")
                return null
            }
            val javaFile = fileAsFile.asJavaFile()
            val pfd = fileAsFile.asParcelFileDescriptor()
            if (javaFile != null && javaFile.exists() && javaFile.length() > 0) {
                javaFile.copyTo(destinationFile, overwrite = true)
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return if (destinationFile.exists() && destinationFile.length() > 0) {
                Log.d(TAG, "Successfully stored received file: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")
                destinationFile
            } else {
                Log.e(TAG, "Destination file is empty or missing after save: ${destinationFile.absolutePath}")
                if (destinationFile.exists()) destinationFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving received payload file", e)
            if (destinationFile.exists()) destinationFile.delete()
            return null
        }
    }

    fun saveDirectBytes(context: Context, bytes: ByteArray, originalFileName: String, prefix: String = "mesh"): File? {
        if (bytes.isEmpty()) return null
        val destinationFile = generateUniqueFile(context, originalFileName, prefix)
        return try {
            FileOutputStream(destinationFile).use { output ->
                output.write(bytes)
                output.flush()
            }
            if (destinationFile.exists() && destinationFile.length() > 0) {
                Log.d(TAG, "Successfully stored mesh bytes: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")
                destinationFile
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving direct mesh bytes", e)
            if (destinationFile.exists()) destinationFile.delete()
            null
        }
    }

    fun copyUriToLocalStorage(context: Context, uri: Uri, originalFileName: String): File? {
        val destinationFile = generateUniqueFile(context, originalFileName, "sent")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (destinationFile.exists() && destinationFile.length() > 0) {
                Log.d(TAG, "Copied sent file to local storage: ${destinationFile.absolutePath}")
                destinationFile
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying uri to local storage", e)
            if (destinationFile.exists()) destinationFile.delete()
            null
        }
    }

    fun getFileUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1048576) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    }

    fun isImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast(".", "").lowercase(Locale.ROOT)
        return listOf("jpg", "jpeg", "png", "webp", "gif", "bmp").contains(ext)
    }
}
