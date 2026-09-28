package com.fury.peerconnect.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import com.google.android.gms.nearby.connection.Payload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.FileInputStream
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
            val javaFile = try { fileAsFile.asJavaFile() } catch (_: Throwable) { null }
            if (javaFile != null && javaFile.exists() && javaFile.length() > 0) {
                javaFile.copyTo(destinationFile, overwrite = true)
            } else {
                val pfd = try { fileAsFile.asParcelFileDescriptor() } catch (_: Throwable) { null }
                if (pfd != null) {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            input.copyTo(output)
                        }
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

    /**
     * Task 9: Pre-compresses an image file before P2P transfer.
     * - Resizes to max 1280px on the long edge (preserves aspect ratio)
     * - Re-encodes as JPEG at quality=80
     * - Skips compression if the source file is already small (< 100KB)
     * Returns the compressed File (in cacheDir) or the original file if compression fails/not needed.
     */
    fun compressImageForTransfer(context: Context, sourceFile: File): File {
        if (!isImageFile(sourceFile.name)) return sourceFile
        if (sourceFile.length() < 100_000L) return sourceFile  // Already small enough

        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
            val origW = opts.outWidth
            val origH = opts.outHeight
            if (origW <= 0 || origH <= 0) return sourceFile

            val maxDim = 1280
            val inSampleSize = if (origW > maxDim || origH > maxDim) {
                val scale = maxOf(origW, origH).toFloat() / maxDim
                scale.toInt().coerceAtLeast(1)
            } else {
                1
            }

            val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val bmp = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts) ?: return sourceFile

            val outFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}_${sourceFile.name}.jpg")
            FileOutputStream(outFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.flush()
            }
            bmp.recycle()

            if (outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "Image compressed: ${sourceFile.length()/1024}KB → ${outFile.length()/1024}KB")
                outFile
            } else {
                sourceFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image compression failed, using original", e)
            sourceFile
        }
    }

    fun saveToPublicDownloads(context: Context, sourceFile: File, displayName: String): Uri? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        return try {
            val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Log.d(TAG, "Saved file to public downloads: $uri")
                    uri
                } else null
            } else {
                @Suppress("DEPRECATION")
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!publicDir.exists()) publicDir.mkdirs()
                val target = File(publicDir, displayName)
                sourceFile.copyTo(target, overwrite = true)
                Uri.fromFile(target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to public downloads", e)
            null
        }
    }
}
