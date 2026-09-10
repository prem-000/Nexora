package com.fury.peerconnect.network.manager

import android.util.Log
import com.fury.peerconnect.network.model.MeshMessage
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class PeerConnection(
    val peerId: String,
    val socket: Socket
) {
    private val TAG = "PeerConnection"
    private val isRunning = AtomicBoolean(true)
    private var outputStream: ObjectOutputStream? = null
    private var inputStream: ObjectInputStream? = null

    init {
        try {
            socket.tcpNoDelay = true
            outputStream = ObjectOutputStream(socket.getOutputStream()).apply { flush() }
            inputStream = ObjectInputStream(socket.getInputStream())
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing streams for peer $peerId", e)
        }
    }

    val isConnected: Boolean
        get() = isRunning.get() && !socket.isClosed && socket.isConnected

    @Synchronized
    fun send(message: MeshMessage): Boolean {
        if (!isRunning.get() || socket.isClosed) {
            return false
        }
        return try {
            outputStream?.let { stream ->
                stream.writeObject(message)
                stream.flush()
                stream.reset()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message ${message.messageId} to $peerId", e)
            close()
            false
        }
    }

    fun startReading(
        onMessageReceived: (MeshMessage) -> Unit,
        onDisconnected: () -> Unit
    ) {
        Thread {
            try {
                while (isRunning.get() && !socket.isClosed) {
                    try {
                        val obj = inputStream?.readObject()
                        if (obj is MeshMessage) {
                            onMessageReceived(obj)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.d(TAG, "Read loop ended for peer $peerId: ${e.message}")
                        }
                        break
                    }
                }
            } finally {
                close()
                onDisconnected()
            }
        }.start()
    }

    fun close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                inputStream?.close()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
            Log.d(TAG, "Closed connection to peer $peerId")
        }
    }
}
