package com.fury.peerconnect.data

sealed interface AttachmentState {
    data object Missing : AttachmentState
    data object Requesting : AttachmentState
    data class Receiving(val progress: Float = 0f) : AttachmentState
    data object Ready : AttachmentState
    data class Failed(val reason: String? = null) : AttachmentState
}
