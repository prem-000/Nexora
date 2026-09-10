package com.fury.peerconnect.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.PeerEntity
import java.util.Locale

class ConnectedPeerAdapter(
    private val onPeerClick: (PeerEntity) -> Unit
) : RecyclerView.Adapter<ConnectedPeerAdapter.ChipViewHolder>() {

    private val peers = mutableListOf<PeerEntity>()

    fun setPeers(newPeers: List<PeerEntity>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_connected_peer_chip, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val peer = peers[position]
        holder.bind(peer)
        holder.itemView.setOnClickListener {
            onPeerClick(peer)
        }
    }

    override fun getItemCount(): Int = peers.size

    class ChipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarText: TextView = itemView.findViewById(R.id.chipPeerAvatar)
        private val nameText: TextView = itemView.findViewById(R.id.chipPeerName)
        private val statusText: TextView = itemView.findViewById(R.id.chipStatusDot)

        fun bind(peer: PeerEntity) {
            nameText.text = peer.name
            avatarText.text = peer.name.take(1).uppercase(Locale.ROOT)
            statusText.text = if (peer.isOnline) "● Direct" else "● Mesh"
        }
    }
}
