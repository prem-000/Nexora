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

    private const val PREFERRED_SIGN_ALGO = "Ed25519"
    private const val FALLBACK_KEY_ALGO = "EC"
    private const val FALLBACK_SIGN_ALGO = "SHA256withECDSA"

    fun generateIdentityKeyPair(): java.security.KeyPair {
        return try {
            val kpg = java.security.KeyPairGenerator.getInstance(PREFERRED_SIGN_ALGO)
            kpg.generateKeyPair()
        } catch (_: java.security.NoSuchAlgorithmException) {
            val kpg = java.security.KeyPairGenerator.getInstance(FALLBACK_KEY_ALGO)
            kpg.initialize(256)
            kpg.generateKeyPair()
        }
    }

    fun signData(data: ByteArray, privateKey: java.security.PrivateKey): ByteArray {
        val algo = if (privateKey.algorithm.equals("Ed25519", ignoreCase = true) || privateKey.algorithm.equals("EdDSA", ignoreCase = true)) {
            PREFERRED_SIGN_ALGO
        } else {
            FALLBACK_SIGN_ALGO
        }
        val sig = java.security.Signature.getInstance(algo)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verifySignature(data: ByteArray, signatureBytes: ByteArray, publicKeyBytes: ByteArray?): Boolean {
        if (publicKeyBytes == null || publicKeyBytes.isEmpty() || signatureBytes.isEmpty()) return false
        return try {
            val (pubKey, algo) = decodePublicKey(publicKeyBytes) ?: return false
            val sig = java.security.Signature.getInstance(algo)
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun decodePublicKey(bytes: ByteArray): Pair<java.security.PublicKey, String>? {
        val spec = java.security.spec.X509EncodedKeySpec(bytes)
        for (algo in listOf(PREFERRED_SIGN_ALGO, "EdDSA", FALLBACK_KEY_ALGO)) {
            try {
                val kf = java.security.KeyFactory.getInstance(algo)
                val pub = kf.generatePublic(spec)
                val signAlgo = if (algo.startsWith("Ed", ignoreCase = true)) PREFERRED_SIGN_ALGO else FALLBACK_SIGN_ALGO
                return Pair(pub, signAlgo)
            } catch (_: Exception) {}
        }
        return null
    }

    fun decodePrivateKey(bytes: ByteArray): java.security.PrivateKey? {
        val spec = java.security.spec.PKCS8EncodedKeySpec(bytes)
        for (algo in listOf(PREFERRED_SIGN_ALGO, "EdDSA", FALLBACK_KEY_ALGO)) {
            try {
                val kf = java.security.KeyFactory.getInstance(algo)
                return kf.generatePrivate(spec)
            } catch (_: Exception) {}
        }
        return null
    }
}
