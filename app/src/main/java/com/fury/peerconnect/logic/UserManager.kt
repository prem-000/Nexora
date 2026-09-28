package com.fury.peerconnect.logic

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class UserManager(context: Context) {
    private val PREFS_NAME = "PeerConnectPrefs"
    private val KEY_USERNAME = "username"
    private val KEY_DEVICE_ID = "device_id"
    private val KEY_AVATAR_ID = "avatar_id"
    private val KEY_PROFILE_VERSION = "profile_version"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveUsername(name: String) {
        prefs.edit().putString(KEY_USERNAME, name).apply()
    }

    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    fun getAvatarId(): String {
        return prefs.getString(KEY_AVATAR_ID, null)?.takeIf { it.isNotBlank() } ?: AvatarManager.DEFAULT_AVATAR_ID
    }

    fun saveAvatarId(avatarId: String) {
        val validId = if (AvatarManager.isValidAvatar(avatarId)) avatarId.trim() else AvatarManager.DEFAULT_AVATAR_ID
        prefs.edit().putString(KEY_AVATAR_ID, validId).apply()
    }

    fun getProfileVersion(): Long {
        return prefs.getLong(KEY_PROFILE_VERSION, 1L)
    }

    fun incrementProfileVersion(): Long {
        val nextVersion = getProfileVersion() + 1
        prefs.edit().putLong(KEY_PROFILE_VERSION, nextVersion).apply()
        return nextVersion
    }

    fun saveProfile(name: String, avatarId: String): Long {
        val nextVersion = getProfileVersion() + 1
        val validAvatar = if (AvatarManager.isValidAvatar(avatarId)) avatarId.trim() else AvatarManager.DEFAULT_AVATAR_ID
        prefs.edit()
            .putString(KEY_USERNAME, name.trim())
            .putString(KEY_AVATAR_ID, validAvatar)
            .putLong(KEY_PROFILE_VERSION, nextVersion)
            .apply()
        return nextVersion
    }

    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun hasIdentity(): Boolean {
        val username = getUsername()
        return !username.isNullOrEmpty()
    }

    private val KEY_PUB_KEY = "identity_public_key"
    private val KEY_PRIV_KEY = "identity_private_key"

    fun getOrCreateIdentityKeyPair(): java.security.KeyPair {
        val pubB64 = prefs.getString(KEY_PUB_KEY, null)
        val privB64 = prefs.getString(KEY_PRIV_KEY, null)
        if (!pubB64.isNullOrEmpty() && !privB64.isNullOrEmpty()) {
            val pubBytes = try {
                android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(pubB64)
            }
            val privBytes = try {
                android.util.Base64.decode(privB64, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(privB64)
            }
            val pub = SecurityHelper.decodePublicKey(pubBytes)?.first
            val priv = SecurityHelper.decodePrivateKey(privBytes)
            if (pub != null && priv != null) {
                return java.security.KeyPair(pub, priv)
            }
        }
        val kp = SecurityHelper.generateIdentityKeyPair()
        val pubB64New = try {
            android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        }
        val privB64New = try {
            android.util.Base64.encodeToString(kp.private.encoded, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(kp.private.encoded)
        }
        prefs.edit()
            .putString(KEY_PUB_KEY, pubB64New)
            .putString(KEY_PRIV_KEY, privB64New)
            .apply()
        return kp
    }
}
