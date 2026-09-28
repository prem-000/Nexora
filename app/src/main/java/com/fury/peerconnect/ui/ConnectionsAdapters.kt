package com.fury.peerconnect.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.PeerEntity
import com.fury.peerconnect.logic.AvatarManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

class ConnectedPeersAdapter(
    private val onPeerClick: (PeerEntity) -> Unit
) : RecyclerView.Adapter<ConnectedPeersAdapter.ConnectedPeerViewHolder>() {

    private val peers = mutableListOf<PeerEntity>()

    fun setPeers(newPeers: List<PeerEntity>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectedPeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_connection_peer, parent, false)
        return ConnectedPeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConnectedPeerViewHolder, position: Int) {
        val peer = peers[position]
        holder.bind(peer, onPeerClick)
    }

    override fun getItemCount(): Int = peers.size

    class ConnectedPeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarImg: ShapeableImageView = itemView.findViewById(R.id.connPeerAvatar)
        private val nameText: TextView = itemView.findViewById(R.id.connPeerName)
        private val subtext: TextView = itemView.findViewById(R.id.connPeerSubtext)
        private val badge: TextView = itemView.findViewById(R.id.connPeerBadge)

        fun bind(peer: PeerEntity, onClick: (PeerEntity) -> Unit) {
            nameText.text = peer.name
            AvatarManager.loadAvatar(avatarImg, peer.avatarId)

            if (peer.isOnline && (!peer.isReachable || peer.hopDistance <= 1)) {
                badge.text = "● Direct"
                badge.setTextColor(Color.parseColor("#16A34A"))
                subtext.text = "Direct peer-to-peer connection active"
            } else if (peer.isReachable || peer.isOnline) {
                val hops = if (peer.hopDistance > 0) peer.hopDistance else 2
                badge.text = "● Reachable ($hops hops)"
                badge.setTextColor(Color.parseColor("#0284C7"))
                subtext.text = "Reachable via multi-hop mesh"
            } else {
                badge.text = "○ Offline"
                badge.setTextColor(Color.parseColor("#9CA3AF"))
                subtext.text = "Offline · Tap to view profile"
            }

            itemView.setOnClickListener { onClick(peer) }
        }
    }
}

class ConnectionRequestsAdapter(
    private val onAcceptClick: (PeerEntity) -> Unit,
    private val onDeclineClick: (PeerEntity) -> Unit,
    private val onPeerClick: (PeerEntity) -> Unit
) : RecyclerView.Adapter<ConnectionRequestsAdapter.RequestViewHolder>() {

    private val requests = mutableListOf<PeerEntity>()

    fun setRequests(newRequests: List<PeerEntity>) {
        requests.clear()
        requests.addAll(newRequests)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_connection_request, parent, false)
        return RequestViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        val peer = requests[position]
        holder.bind(peer, onAcceptClick, onDeclineClick, onPeerClick)
    }

    override fun getItemCount(): Int = requests.size

    class RequestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarImg: ShapeableImageView = itemView.findViewById(R.id.reqPeerAvatar)
        private val nameText: TextView = itemView.findViewById(R.id.reqPeerName)
        private val btnAccept: MaterialButton = itemView.findViewById(R.id.btnAcceptRequest)
        private val btnDecline: MaterialButton = itemView.findViewById(R.id.btnDeclineRequest)

        fun bind(
            peer: PeerEntity,
            onAccept: (PeerEntity) -> Unit,
            onDecline: (PeerEntity) -> Unit,
            onClick: (PeerEntity) -> Unit
        ) {
            nameText.text = peer.name
            AvatarManager.loadAvatar(avatarImg, peer.avatarId)

            btnAccept.setOnClickListener { onAccept(peer) }
            btnDecline.setOnClickListener { onDecline(peer) }
            itemView.setOnClickListener { onClick(peer) }
        }
    }
}
