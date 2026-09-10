package com.fury.peerconnect.network.p2p

import android.util.Log
import com.fury.peerconnect.network.manager.ConnectionManager
import com.fury.peerconnect.network.manager.PeerConnection
import com.fury.peerconnect.network.model.MeshMessage
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TcpTransportManager(
    private val connectionManager: ConnectionManager,
    private val myDeviceId: String,
    private val onMessageReceived: (MeshMessage) -> Unit,
    private val onPeerDisconnected: (String) -> Unit
) {
    val MESH_PORT = 8888
    private val TAG = "TcpTransportManager"
    private val isServerRunning = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    fun startServer() {
        if (isServerRunning.get()) return
        isServerRunning.set(true)
        executor.execute {
            try {
                serverSocket = ServerSocket(MESH_PORT)
                Log.d(TAG, "Server socket started on port $MESH_PORT. Accepting multi-client connections...")
                while (isServerRunning.get()) {
                    val socket = serverSocket
                    if (socket == null || socket.isClosed) break
                    try {
                        val clientSocket = socket.accept()
                        Log.d(TAG, "Accepted incoming connection from ${clientSocket.remoteSocketAddress}")
                        executor.execute {
                            handleIncomingClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isServerRunning.get()) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Server socket failed", e2)
            } finally {
                stopServer()
            }
        }
    }

    private fun handleIncomingClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val out = ObjectOutputStream(socket.getOutputStream())
            out.flush()
            out.writeUTF(myDeviceId)
            out.flush()

            val inStream = ObjectInputStream(socket.getInputStream())
            val remotePeerId = inStream.readUTF()
            Log.d(TAG, "Handshake complete with incoming client: $remotePeerId")

            val connection = PeerConnection(remotePeerId, socket)
            connectionManager.addConnection(remotePeerId, connection)
            connection.startReading(
                onMessageReceived = { msg -> onMessageReceived(msg) },
                onDisconnected = {
                    connectionManager.removeConnection(remotePeerId)
                    onPeerDisconnected(remotePeerId)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed handshake with incoming client socket", e)
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun connectToOwner(ownerAddress: String) {
        executor.execute {
            try {
                Log.d(TAG, "Connecting to Group Owner at $ownerAddress:$MESH_PORT...")
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ownerAddress, MESH_PORT), 5000)

                val inStream = ObjectInputStream(socket.getInputStream())
                val remoteOwnerId = inStream.readUTF()

                val out = ObjectOutputStream(socket.getOutputStream())
                out.flush()
                out.writeUTF(myDeviceId)
                out.flush()

                Log.d(TAG, "Handshake complete with Group Owner: $remoteOwnerId")
                val connection = PeerConnection(remoteOwnerId, socket)
                connectionManager.addConnection(remoteOwnerId, connection)
                connection.startReading(
                    onMessageReceived = { msg -> onMessageReceived(msg) },
                    onDisconnected = {
                        connectionManager.removeConnection(remoteOwnerId)
                        onPeerDisconnected(remoteOwnerId)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Group Owner at $ownerAddress", e)
            }
        }
    }

    fun stopServer() {
        if (isServerRunning.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
            serverSocket = null
            Log.d(TAG, "Server socket stopped")
        }
    }

    fun shutdown() {
        stopServer()
        executor.shutdownNow()
    }
}
