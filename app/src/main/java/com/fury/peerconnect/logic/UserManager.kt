package com.fury.peerconnect.logic

import android.content.Context
import android.content.SharedPreferences

class UserManager(context: Context) {
    private val PREFS_NAME = "PeerConnectPrefs"
    private val KEY_USERNAME = "username"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveUsername(name: String) {
        prefs.edit().putString(KEY_USERNAME, name).apply()
    }

    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    fun hasIdentity(): Boolean {
        val username = getUsername()
        return !username.isNullOrEmpty()
    }
}
