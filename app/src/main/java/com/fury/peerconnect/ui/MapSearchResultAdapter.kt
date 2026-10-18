package com.fury.peerconnect.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.logic.AvatarManager
import com.google.android.material.imageview.ShapeableImageView

data class MapSearchResult(
    val id: String,
    val type: Type,
    val title: String,
    val subtitle: String,
    val distanceText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val avatarId: String? = null,
    val isLive: Boolean = false,
    val emergencyCategory: com.fury.peerconnect.data.EmergencyCategory? = null,
    val verifiedContact: String? = null
) {
    enum class Type { PEER, ALERT, POI }
}

class MapSearchResultAdapter(
    private val onResultClick: (MapSearchResult) -> Unit
) : RecyclerView.Adapter<MapSearchResultAdapter.SearchViewHolder>() {

    private val items = mutableListOf<MapSearchResult>()

    fun submitList(newItems: List<MapSearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_search_result, parent, false)
        return SearchViewHolder(view)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(items[position], onResultClick)
    }

    override fun getItemCount(): Int = items.size

    class SearchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ShapeableImageView = itemView.findViewById(R.id.imgSearchResultIcon)
        private val textTitle: TextView = itemView.findViewById(R.id.textSearchResultTitle)
        private val textSubtitle: TextView = itemView.findViewById(R.id.textSearchResultSubtitle)
        private val textDistance: TextView = itemView.findViewById(R.id.textSearchResultDistance)

        fun bind(item: MapSearchResult, onClick: (MapSearchResult) -> Unit) {
            textTitle.text = item.title
            textSubtitle.text = item.subtitle
            textDistance.text = item.distanceText ?: ""
            textDistance.visibility = if (item.distanceText.isNullOrBlank()) View.GONE else View.VISIBLE

            when (item.type) {
                MapSearchResult.Type.PEER -> {
                    imgIcon.imageTintList = null
                    AvatarManager.loadAvatar(imgIcon, item.avatarId)
                }
                MapSearchResult.Type.ALERT -> {
                    imgIcon.setImageResource(R.drawable.ic_nav_alerts)
                    imgIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                }
                MapSearchResult.Type.POI -> {
                    imgIcon.setImageResource(R.drawable.ic_attach_location)
                    val colorHex = item.emergencyCategory?.badgeColorHex ?: "#38BDF8"
                    imgIcon.imageTintList = ColorStateList.valueOf(Color.parseColor(colorHex))
                }
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
