package com.fury.peerconnect.logic

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets

object SecurityHelper {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val SECRET_KEY = "12345678901234567890123456789012"

    fun encrypt(plainText: String): String {
        return try {
            val key = SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(ByteArray(16))
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val androidEncoded = try {
                Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
            androidEncoded ?: java.util.Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            plainText
        }
    }

    fun decrypt(cipherText: String): String {
        return try {
            val key = SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(ByteArray(16))
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decodedBytes = try {
                Base64.decode(cipherText, Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            } ?: java.util.Base64.getDecoder().decode(cipherText)
            val plainBytes = cipher.doFinal(decodedBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "[Decryption Failed]"
        }
    }

    fun encryptBytes(plainBytes: ByteArray, secretKey: String? = null): ByteArray {
        return try {
            val keyString = if (!secretKey.isNullOrBlank()) secretKey.padEnd(32, '0').take(32) else SECRET_KEY
            val key = SecretKeySpec(keyString.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(ByteArray(16))
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(plainBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            plainBytes
        }
    }

    fun decryptBytes(cipherBytes: ByteArray, secretKey: String? = null): ByteArray {
        return try {
            val keyString = if (!secretKey.isNullOrBlank()) secretKey.padEnd(32, '0').take(32) else SECRET_KEY
            val key = SecretKeySpec(keyString.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(ByteArray(16))
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            cipher.doFinal(cipherBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            cipherBytes
        }
    }

    fun generateAttachmentKey(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
