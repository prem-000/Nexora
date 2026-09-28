package com.fury.peerconnect.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.logic.AvatarManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

class AvatarPickerAdapter(
    private var selectedAvatarId: String,
    private val onAvatarSelected: (String) -> Unit
) : RecyclerView.Adapter<AvatarPickerAdapter.AvatarViewHolder>() {

    private val avatars = AvatarManager.AVATAR_IDS

    fun setSelectedAvatar(avatarId: String) {
        val oldIndex = avatars.indexOf(selectedAvatarId)
        selectedAvatarId = avatarId
        val newIndex = avatars.indexOf(avatarId)
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_avatar_select, parent, false)
        return AvatarViewHolder(view)
    }

    override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
        val avatarId = avatars[position]
        val isSelected = avatarId == selectedAvatarId
        holder.bind(avatarId, isSelected) {
            setSelectedAvatar(avatarId)
            onAvatarSelected(avatarId)
        }
    }

    override fun getItemCount(): Int = avatars.size

    class AvatarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.avatarCard)
        private val imgAvatar: ShapeableImageView = itemView.findViewById(R.id.imgAvatarOption)
        private val imgCheckMark: ImageView = itemView.findViewById(R.id.imgCheckMark)

        fun bind(avatarId: String, isSelected: Boolean, onClick: () -> Unit) {
            AvatarManager.loadAvatar(imgAvatar, avatarId)

            if (isSelected) {
                card.strokeColor = Color.parseColor("#E53935") // nexora_red
                card.strokeWidth = 6
                imgCheckMark.visibility = View.VISIBLE
            } else {
                card.strokeColor = Color.parseColor("#E5E7EB") // nexora_border
                card.strokeWidth = 2
                imgCheckMark.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick() }
        }
    }
}
