package com.fury.peerconnect.network.model

data class MeshGroupState(
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val ownerAddress: String? = null,
    val clients: List<Peer> = emptyList()
) {
    val isGroupFormed: Boolean get() = groupFormed
    val groupOwnerAddress: String? get() = ownerAddress
}
