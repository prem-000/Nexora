package com.fury.peerconnect.logic

import android.widget.ImageView
import com.fury.peerconnect.R

object AvatarManager {
    const val DEFAULT_AVATAR_ID = "avatar_01"

    val AVATAR_IDS: List<String> = listOf(
        "avatar_01",
        "avatar_02",
        "avatar_03",
        "avatar_04",
        "avatar_05",
        "avatar_06",
        "avatar_07",
        "avatar_08",
        "avatar_09",
        "avatar_10"
    )

    fun getDrawableId(avatarId: String?): Int {
        return when (avatarId?.trim()) {
            "avatar_01" -> R.drawable.avatar_01
            "avatar_02" -> R.drawable.avatar_02
            "avatar_03" -> R.drawable.avatar_03
            "avatar_04" -> R.drawable.avatar_04
            "avatar_05" -> R.drawable.avatar_05
            "avatar_06" -> R.drawable.avatar_06
            "avatar_07" -> R.drawable.avatar_07
            "avatar_08" -> R.drawable.avatar_08
            "avatar_09" -> R.drawable.avatar_09
            "avatar_10" -> R.drawable.avatar_10
            else -> R.drawable.avatar_01
        }
    }

    fun loadAvatar(imageView: ImageView, avatarId: String?) {
        imageView.setImageResource(getDrawableId(avatarId))
    }

    fun isValidAvatar(avatarId: String?): Boolean {
        if (avatarId == null) return false
        return AVATAR_IDS.contains(avatarId.trim())
    }
}
