package com.fury.peerconnect.network.model

enum class PeerState {
    DISCOVERED,
    CONNECTING,
    GROUP_FORMED,
    GROUP_MEMBER,
    TRANSPORT_CONNECTED,
    ROUTABLE,
    DISCONNECTED
}
