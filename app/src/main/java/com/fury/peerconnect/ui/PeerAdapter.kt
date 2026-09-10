package com.fury.peerconnect.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.PeerEntity
import java.util.Locale

class PeerAdapter(
    private val onPeerClicked: (PeerEntity) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val peers = mutableListOf<PeerEntity>()

    fun updateList(newPeers: List<PeerEntity>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    fun updatePeerStatus(name: String, isOnline: Boolean) {
        val index = peers.indexOfFirst { it.name == name }
        if (index != -1) {
            peers[index] = peers[index].copy(isOnline = isOnline)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]
        holder.bind(peer)
        holder.itemView.setOnClickListener {
            onPeerClicked(peer)
        }
    }

    override fun getItemCount(): Int = peers.size

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.peerName)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val peerAvatar: TextView? = itemView.findViewById(R.id.peerAvatar)
        private val lastSeenText: TextView? = itemView.findViewById(R.id.lastSeenText)

        fun bind(peer: PeerEntity) {
            nameText.text = peer.name
            peerAvatar?.text = peer.name.take(1).uppercase(Locale.ROOT)

            if (peer.isOnline && (!peer.isReachable || peer.hopDistance <= 1)) {
                statusText.text = "● Direct"
                statusText.setTextColor(Color.parseColor("#16A34A"))
                nameText.setTextColor(Color.parseColor("#151515"))
                lastSeenText?.text = "Direct peer-to-peer connection active"
                return
            }

            if (peer.isReachable || peer.isOnline) {
                val hops = if (peer.hopDistance > 0) peer.hopDistance else 2
                val via = if (peer.nextHop.isNullOrEmpty()) "" else " via ${peer.nextHop}"
                statusText.text = "● Reachable ($hops hops$via)"
                statusText.setTextColor(Color.parseColor("#0284C7"))
                nameText.setTextColor(Color.parseColor("#151515"))
                lastSeenText?.text = "Reachable via multi-hop mesh routing"
                return
            }

            statusText.text = "○ Offline"
            statusText.setTextColor(Color.parseColor("#9CA3AF"))
            nameText.setTextColor(Color.parseColor("#6B7280"))
            lastSeenText?.text = "Offline · Tap to compose queued message"
        }
    }
}
