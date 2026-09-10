package com.fury.peerconnect.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.data.AlertPayload
import com.fury.peerconnect.data.AppDatabase
import com.fury.peerconnect.data.BlobRepository
import com.fury.peerconnect.data.ChatMessage
import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.data.PeerEntity
import com.fury.peerconnect.logic.FileStorageManager
import com.fury.peerconnect.logic.MeshBlobStore
import com.fury.peerconnect.logic.SecurityHelper
import com.fury.peerconnect.logic.UserManager
import com.fury.peerconnect.network.manager.BlobExchange
import com.fury.peerconnect.network.manager.ConnectionManager
import com.fury.peerconnect.network.manager.NeighborTable
import com.fury.peerconnect.network.model.MeshGroupState
import com.fury.peerconnect.network.model.MeshMessage
import com.fury.peerconnect.network.model.MessageType
import com.fury.peerconnect.network.model.PeerStatus
import com.fury.peerconnect.network.model.PresencePayload
import com.fury.peerconnect.network.p2p.WifiP2pBroadcastReceiver
import com.fury.peerconnect.network.p2p.WifiP2pMeshManager
import com.fury.peerconnect.network.routing.DuplicateSuppressionCache
import com.fury.peerconnect.network.routing.RoutingEngine
import com.fury.peerconnect.network.transport.NearbyMeshTransport
import com.fury.peerconnect.network.transport.WifiP2pMeshTransport
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private data class PendingFileMetadata(
        val messageId: Long? = null,
        val alertId: String? = null,
        val fileName: String,
        val senderName: String,
        val endpointId: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private data class CompletedReceivedFile(
        val endpointId: String,
        val file: File,
        val hash: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.fury.peerconnect_v2"
    private val TAG = "NexoraDebug"
    private var myNickName = ""
    private val activeEndpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val MAX_CONCURRENT_PEERS = 6
    private val nearbyPresenceCache = DuplicateSuppressionCache(1000)
    private val discoveredEndpoints = LinkedHashMap<String, String>()
    private val pendingConnections = LinkedHashMap<String, String>()
    private val pendingPayloads = LinkedHashMap<Long, Long>()
    private val incomingFilePayloads = LinkedHashMap<Long, Payload>()
    private val pendingFileMetadataMap: MutableMap<Long, PendingFileMetadata> =
        Collections.synchronizedMap(LinkedHashMap())
    private val pendingCompletedFilesMap: MutableMap<Long, CompletedReceivedFile> =
        Collections.synchronizedMap(LinkedHashMap())
    private val endpointLastSeen = ConcurrentHashMap<String, Long>()
    private val HEARTBEAT_INTERVAL_MS = 10000L
    private val HEARTBEAT_TIMEOUT_MS = 30000L
    private val MAX_DIRECT_P2P_DISTANCE_METERS = 300.0

    private val heartbeatWatchdogRunnable = object : Runnable {
        override fun run() {
            try {
                performHeartbeatAndLivenessCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Error during heartbeat watchdog run", e)
            } finally {
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private var isPairingMode = false
    private var isHost = false
    private var isAdvertising = false
    private var isDiscovering = false
    private var focusedChatEndpointId: String? = null
    private var currentChatPeerName: String? = null
    private var selectionDialog: AlertDialog? = null

    private lateinit var db: AppDatabase
    private lateinit var neighborTable: NeighborTable
    private lateinit var connectionManager: ConnectionManager
    private lateinit var blobRepository: BlobRepository
    private var blobExchange: BlobExchange? = null
    private var routingEngine: RoutingEngine? = null
    private var wifiP2pMeshManager: WifiP2pMeshManager? = null
    private var p2pReceiver: WifiP2pBroadcastReceiver? = null
    private var nearbyMeshTransport: NearbyMeshTransport? = null
    private var wifiP2pMeshTransport: WifiP2pMeshTransport? = null

    private lateinit var peerAdapter: PeerAdapter
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var alertAdapter: AlertAdapter
    private lateinit var recentActivityAdapter: RecentActivityAdapter
    private lateinit var connectedPeerAdapter: ConnectedPeerAdapter
    private lateinit var alertThreadAdapter: ChatAdapter

    private var offlineMapManager: com.fury.peerconnect.logic.OfflineMapManager? = null
    private lateinit var locationManagerHelper: com.fury.peerconnect.logic.LocationManagerHelper
    private var myCurrentCoordinates: com.fury.peerconnect.logic.LocationManagerHelper.GeoCoordinates? = null
    private var liveLocationJob: kotlinx.coroutines.Job? = null
    private var activeLiveSharingDurationMillis: Long = 0L
    private var liveSharingStartTime: Long = 0L

    private lateinit var dashMapView: org.maplibre.android.maps.MapView
    private lateinit var dashMapCoordinatesText: TextView
    private lateinit var mapStatusText: TextView
    private lateinit var mapDistanceMeasureText: TextView
    private lateinit var btnMapRecenter: View
    private lateinit var btnMapLayers: View

    private lateinit var statusText: TextView
    private lateinit var chatStatusText: TextView
    private lateinit var btnAddContact: Button
    private lateinit var layoutConnection: View
    private lateinit var layoutDashboard: View
    private lateinit var layoutAlerts: View
    private lateinit var layoutSettings: View
    private lateinit var layoutChat: ConstraintLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var appGreeting: TextView
    private lateinit var dashConnStatus: TextView
    private lateinit var dashConnDesc: TextView
    private lateinit var dashActiveCount: TextView
    private lateinit var dashKnownCount: TextView
    private lateinit var dashOfflineCount: TextView
    private lateinit var dashConnectedPeersRecyclerView: RecyclerView
    private lateinit var dashEmptyPeersText: TextView
    private lateinit var dashRecentRecyclerView: RecyclerView
    private lateinit var dashEmptyRecentText: TextView
    private lateinit var alertsRecyclerView: RecyclerView
    private lateinit var btnClearAlerts: View
    private lateinit var btnCreateAlert: View
    private lateinit var textEmptyAlerts: View
    private lateinit var chipAlertAll: View
    private lateinit var chipAlertGroups: View
    private lateinit var chipAlertSystem: View
    private lateinit var layoutAlertThread: ConstraintLayout
    private lateinit var alertThreadHeader: TextView
    private lateinit var alertThreadSenderText: TextView
    private lateinit var btnExitAlertThread: View
    private lateinit var alertThreadDescription: TextView
    private lateinit var alertThreadAttachmentCard: View
    private lateinit var alertThreadAttachmentName: TextView
    private lateinit var alertThreadAttachmentStatus: TextView
    private lateinit var alertThreadAttachmentImage: ImageView
    private lateinit var alertThreadLocationCard: View
    private lateinit var alertThreadLocationCoordinates: TextView
    private lateinit var alertThreadLocationDistance: TextView
    private lateinit var btnAlertThreadViewOnMap: View
    private lateinit var alertThreadRecyclerView: RecyclerView
    private lateinit var editAlertThreadReply: EditText
    private lateinit var btnSendAlertThreadReply: View
    private var currentAlertThreadId: String? = null
    private var pendingAlertAttachmentUri: Uri? = null
    private var pendingAlertAttachmentName: String? = null
    private var pendingAlertFileNameText: TextView? = null

    private lateinit var settingsAvatar: TextView
    private lateinit var settingsDisplayName: TextView
    private lateinit var settingsPeerId: TextView
    private lateinit var settingsStatusBadge: TextView
    private lateinit var btnEditIdentity: View
    private lateinit var messagesEmptyText: TextView
    private lateinit var peersRecyclerView: RecyclerView
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatPeerAvatar: TextView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: View
    private lateinit var btnAttach: View
    private lateinit var btnExitChat: View

    private var isActivityResumed = false
    private val unreadMessageCounts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())
    private val processedInboundMessages = Collections.synchronizedSet(LinkedHashSet<String>())
    private var lastKnownStatusBarInset: Int = 0

    private fun getFallbackStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }
    }

    private fun getStatusBarAndCutoutTopInset(insets: WindowInsetsCompat?): Int {
        val calculated = insets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )?.top ?: 0
        return if (calculated > 0) calculated else getFallbackStatusBarHeight()
    }

    private fun applyGlobalTopInsets(topInset: Int) {
        try {
            val finalTop = if (topInset > 0) topInset else getFallbackStatusBarHeight()
            lastKnownStatusBarInset = finalTop
            (findViewById<View>(R.id.mainHeaderCard) as? MaterialCardView)?.setContentPadding(0, finalTop, 0, 0)
            (findViewById<View>(R.id.appBarCard) as? MaterialCardView)?.setContentPadding(0, finalTop, 0, 0)
            (findViewById<View>(R.id.alertThreadAppBar) as? MaterialCardView)?.setContentPadding(0, finalTop, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying top insets", e)
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            try {
                if (::layoutAlertThread.isInitialized && layoutAlertThread.visibility == View.VISIBLE) {
                    closeAlertThread()
                } else if (::layoutChat.isInitialized && layoutChat.visibility == View.VISIBLE) {
                    closeChat()
                } else if (::bottomNavigation.isInitialized && bottomNavigation.selectedItemId != R.id.nav_home) {
                    bottomNavigation.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleOnBackPressed", e)
                finish()
            }
        }
    }

    private val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Log.d(TAG, "All permissions granted")
                startAutoMode()
            } else {
                Toast.makeText(this, "Permissions required for mesh operation", Toast.LENGTH_SHORT).show()
            }
        }

    private val locationResolutionLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startAutoMode()
            }
        }

    private var pendingCameraCaptureUri: Uri? = null

    private val cameraCaptureLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                pendingCameraCaptureUri?.let { uri ->
                    val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    showAttachmentSendPreview(uri, fileName, isImage = true)
                }
            }
        }

    private val filePickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                try {
                    val fileName = getFileNameFromUri(uri)
                    val mime = contentResolver.getType(uri) ?: ""
                    val isImage = mime.startsWith("image/") ||
                            fileName.endsWith(".jpg", ignoreCase = true) ||
                            fileName.endsWith(".jpeg", ignoreCase = true) ||
                            fileName.endsWith(".png", ignoreCase = true)
                    showAttachmentSendPreview(uri, fileName, isImage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process picked attachment", e)
                }
            }
        }

    private val alertFilePickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                try {
                    val fileName = getFileNameFromUri(uri)
                    pendingAlertAttachmentUri = uri
                    pendingAlertAttachmentName = fileName
                    pendingAlertFileNameText?.text = fileName
                    pendingAlertFileNameText?.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e(TAG, "Failed picking alert file", e)
                }
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val foundName = info.endpointName
            val ts = System.currentTimeMillis()
            Log.d(TAG, "[DISCOVERY_FOUND] ts=$ts endpointId=$endpointId foundName='$foundName' myNick='$myNickName'")
            lifecycleScope.launch(Dispatchers.IO) {
                val isKnown = db.peerDao().isKnownPeer(foundName)
                withContext(Dispatchers.Main) {
                    if (!isKnown) {
                        if (isPairingMode && !discoveredEndpoints.containsKey(endpointId)) {
                            discoveredEndpoints[endpointId] = foundName
                            showDeviceSelectionDialog()
                        }
                    } else {
                        emitAlert("DISCOVERY", "DEVICE DETECTED", "Known peer $foundName is nearby", foundName)
                        val isInitiator = if (myNickName != foundName) {
                            myNickName < foundName
                        } else {
                            System.identityHashCode(this@MainActivity) < endpointId.hashCode()
                        }
                        if (isInitiator) {
                            Log.d(TAG, "[TIE_BREAK_INITIATE] Initiating connection to $endpointId")
                            Nearby.getConnectionsClient(this@MainActivity)
                                .requestConnection(myNickName, endpointId, connectionLifecycleCallback)
                                .addOnSuccessListener {
                                    Log.d(TAG, "requestConnection success to $endpointId")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "requestConnection fail to $endpointId", e)
                                }
                        } else {
                            Log.d(TAG, "[TIE_BREAK_WAIT] Waiting for peer to initiate connection")
                        }
                        handler.removeCallbacks(roleSwitchRunnable)
                    }
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "[DISCOVERY_LOST] endpointId=$endpointId")
            if (isPairingMode) {
                discoveredEndpoints.remove(endpointId)
                showDeviceSelectionDialog()
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val incomingName = info.endpointName
            val isIncoming = info.isIncomingConnection
            Log.d(TAG, "[CONN_INITIATED] endpointId=$endpointId incomingName='$incomingName' isIncoming=$isIncoming")
            pendingConnections[endpointId] = incomingName

            Nearby.getConnectionsClient(this@MainActivity)
                .acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "acceptConnection success for $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "acceptConnection fail for $endpointId", e)
                }

            lifecycleScope.launch(Dispatchers.IO) {
                val isKnown = db.peerDao().isKnownPeer(incomingName)
                if (isKnown || isPairingMode) {
                    handler.removeCallbacks(roleSwitchRunnable)
                } else {
                    Log.w(TAG, "[CONN_REJECT_UNKNOWN] Rejecting $endpointId ($incomingName)")
                    Nearby.getConnectionsClient(this@MainActivity).disconnectFromEndpoint(endpointId)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val peerName = pendingConnections[endpointId] ?: endpointId
            Log.d(TAG, "[CONN_RESULT] endpointId=$endpointId peerName='$peerName' statusCode=${result.status.statusCode}")
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                activeEndpoints.add(endpointId)
                endpointLastSeen[endpointId] = System.currentTimeMillis()
                if (focusedChatEndpointId == null) {
                    focusedChatEndpointId = endpointId
                }
                if (!hasCapacityForMorePeers()) {
                    handler.removeCallbacks(roleSwitchRunnable)
                }
                pendingRadioSwitch?.let { handler.removeCallbacks(it) }

                if (isPairingMode) {
                    isPairingMode = false
                    runOnUiThread {
                        btnAddContact.text = "+ Add New Contact"
                        btnAddContact.setBackgroundColor(Color.parseColor("#E53935"))
                    }
                }

                nearbyMeshTransport?.onEndpointConnected(endpointId, peerName)

                lifecycleScope.launch(Dispatchers.IO) {
                    db.peerDao().insertPeer(
                        PeerEntity(
                            name = peerName,
                            endpointId = endpointId,
                            lastSeenTimestamp = System.currentTimeMillis(),
                            isOnline = true,
                            nextHop = peerName,
                            hopDistance = 1,
                            isReachable = true
                        )
                    )
                    emitAlert("CONNECTION", "CONNECTED", "$peerName is now connected", peerName)
                    broadcastPresenceUpdate(peerName, PeerStatus.CONNECTED)
                    val updatedPeers = db.peerDao().getAllPeers()
                    withContext(Dispatchers.Main) {
                        peerAdapter.updateList(updatedPeers)
                        connectedPeerAdapter.setPeers(updatedPeers.filter { it.isOnline || it.isReachable })
                        dashActiveCount.text = updatedPeers.count { it.isOnline }.toString()
                        dashKnownCount.text = updatedPeers.size.toString()
                        dashOfflineCount.text = updatedPeers.count { !it.isOnline && !it.isReachable }.toString()
                        if (currentChatPeerName == peerName) {
                            focusedChatEndpointId = endpointId
                            updateStatus("CONNECTED")
                        }
                    }
                    flushOfflineMessages(peerName)
                }

                if (currentChatPeerName == null) {
                    startAutoMode()
                }
            } else {
                activeEndpoints.remove(endpointId)
                emitAlert("NETWORK", "CONNECTION REJECTED", "Connection with $peerName failed", peerName)
                startAutoMode()
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "[CONN_DISCONNECTED] endpointId=$endpointId")
            handleExplicitDisconnect(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            endpointLastSeen[endpointId] = System.currentTimeMillis()
            Log.d(TAG, "[PAYLOAD_RX] endpointId=$endpointId payloadId=${payload.id} type=${payload.type}")
            when (payload.type) {
                Payload.Type.BYTES -> {
                    var handled = false
                    nearbyMeshTransport?.let {
                        handled = it.handleIncomingPayload(endpointId, payload)
                    }
                    if (!handled) {
                        val bytes = payload.asBytes() ?: return
                        try {
                            val msg = deserialize(bytes)
                            val decrypted = SecurityHelper.decrypt(msg.messageBody)
                            if (decrypted.startsWith("[PRESENCE_UPDATE]:")) {
                                handlePresenceUpdate(decrypted, endpointId)
                            } else {
                                processReceivedDecryptedMessage(msg.senderName, decrypted, msg.time, endpointId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Deserialization failed", e)
                        }
                    }
                }
                Payload.Type.FILE -> {
                    incomingFilePayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                if (update.totalBytes > 0) {
                    val progress = (update.bytesTransferred.toFloat() / update.totalBytes.toFloat()) * 100f
                    Log.d(TAG, "ATTACHMENT_TRANSFER_PROGRESS: id=${update.payloadId} ${progress.toInt()}% (${update.bytesTransferred}/${update.totalBytes})")
                    val pendingMeta = pendingFileMetadataMap[update.payloadId]
                    if (pendingMeta?.messageId != null) {
                        runOnUiThread {
                            chatAdapter.updateTransferProgress(pendingMeta.messageId.toInt(), progress.toInt())
                        }
                    }
                }
            } else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "ATTACHMENT_TRANSFER_COMPLETE: payloadId=${update.payloadId} from=$endpointId")
                val dbMsgId = pendingPayloads.remove(update.payloadId)
                if (dbMsgId != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.messageDao().markAsSent(dbMsgId.toInt())
                        if (pendingPayloads.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (focusedChatEndpointId == endpointId || isConnectedTo(endpointId)) {
                                    updateStatus("CONNECTED")
                                }
                            }
                        }
                    }
                }
                val filePayload = incomingFilePayloads.remove(update.payloadId)
                if (filePayload != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val pendingMeta = pendingFileMetadataMap.remove(update.payloadId)
                        val fileName = pendingMeta?.fileName ?: "attachment"
                        val savedFile = FileStorageManager.saveReceivedPayloadFile(this@MainActivity, filePayload, fileName)
                        if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                            val absolutePath = savedFile.absolutePath
                            val length = savedFile.length()
                            val hash = MeshBlobStore.computeSha256(savedFile)
                            Log.d(TAG, "ATTACHMENT_HASH_VERIFY: hash=$hash bytes=$length file=$fileName")
                            MeshBlobStore.saveIncoming(this@MainActivity, hash, savedFile)
                            pendingCompletedFilesMap[update.payloadId] = CompletedReceivedFile(endpointId, savedFile, hash)

                            val sender = pendingMeta?.senderName ?: endpointId
                            blobExchange?.onReceived(hash, savedFile, sender)

                            if (pendingMeta?.messageId != null) {
                                val strDetails = "[FILE]:$fileName|$absolutePath|$hash|$length|SUCCESS"
                                db.messageDao().updateFileDetails(pendingMeta.messageId, strDetails, absolutePath, "SUCCESS", length)
                                Log.d(TAG, "ATTACHMENT_MESSAGE_UPDATED: id=${pendingMeta.messageId} hash=$hash")
                                emitAlert("TRANSFER", "TRANSFER COMPLETE", "$fileName received successfully", pendingMeta.senderName)
                                withContext(Dispatchers.Main) {
                                    if (currentChatPeerName == pendingMeta.senderName) {
                                        val history = db.messageDao().getChatHistory(myNickName, pendingMeta.senderName)
                                        updateChatUI(history)
                                    }
                                }
                            } else if (pendingMeta?.alertId != null) {
                                val strDetails = "[FILE]:$fileName|$absolutePath|$hash|$length|SUCCESS"
                                val existingAlert = db.alertDao().getAlertByAlertId(pendingMeta.alertId)
                                if (existingAlert != null) {
                                    db.alertDao().updateAlert(existingAlert.copy(attachmentPath = strDetails))
                                }
                                emitAlert("TRANSFER", "TRANSFER COMPLETE", "$fileName received successfully", pendingMeta.senderName)
                                withContext(Dispatchers.Main) {
                                    loadAlertsFromDb()
                                }
                            }
                        } else {
                            if (pendingMeta?.messageId != null) {
                                db.messageDao().updateTransferStatus(pendingMeta.messageId, "FAILED")
                            }
                        }
                    }
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                Log.w(TAG, "ATTACHMENT_TRANSFER_FAILED: id=${update.payloadId} status=${update.status}")
                val pendingMeta = pendingFileMetadataMap.remove(update.payloadId)
                incomingFilePayloads.remove(update.payloadId)
                if (pendingMeta?.messageId != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.messageDao().updateTransferStatus(pendingMeta.messageId, "FAILED")
                        withContext(Dispatchers.Main) {
                            if (currentChatPeerName == pendingMeta.senderName) {
                                val history = db.messageDao().getChatHistory(myNickName, pendingMeta.senderName)
                                updateChatUI(history)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            org.maplibre.android.MapLibre.getInstance(this)
        } catch (e: Throwable) {
            Log.e(TAG, "MapLibre pre-inflation init error", e)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootLayout = findViewById<View>(R.id.rootLayout) ?: window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            applyGlobalTopInsets(topInset)
            insets
        }

        val initialTop = getStatusBarAndCutoutTopInset(ViewCompat.getRootWindowInsets(window.decorView))
        applyGlobalTopInsets(initialTop)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL UNCAUGHT EXCEPTION in ${thread.name}", throwable)
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        createNotificationChannelIfNeeded()

        db = AppDatabase.getDatabase(this)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.peerDao().setAllOffline()
                db.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE text LIKE '[ALERT]:%'")
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                loadPeersFromDb()
            }
        }

        appGreeting = findViewById(R.id.appGreeting)
        statusText = findViewById(R.id.statusText)
        chatStatusText = findViewById(R.id.chatStatusText)
        btnAddContact = findViewById(R.id.btnHost)
        layoutDashboard = findViewById(R.id.layoutDashboard)
        layoutConnection = findViewById(R.id.layoutConnection)
        layoutAlerts = findViewById(R.id.layoutAlerts)
        layoutSettings = findViewById(R.id.layoutSettings)
        layoutChat = findViewById(R.id.layoutChat)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        btnExitChat = findViewById(R.id.btnExitChat)
        dashConnStatus = findViewById(R.id.dashConnStatus)
        dashConnDesc = findViewById(R.id.dashConnDesc)
        dashActiveCount = findViewById(R.id.dashActiveCount)
        dashKnownCount = findViewById(R.id.dashKnownCount)
        dashOfflineCount = findViewById(R.id.dashOfflineCount)
        dashConnectedPeersRecyclerView = findViewById(R.id.dashConnectedPeersRecyclerView)
        dashEmptyPeersText = findViewById(R.id.dashEmptyPeersText)
        dashRecentRecyclerView = findViewById(R.id.dashRecentRecyclerView)
        dashEmptyRecentText = findViewById(R.id.dashEmptyRecentText)
        alertsRecyclerView = findViewById(R.id.alertsRecyclerView)
        btnClearAlerts = findViewById(R.id.btnClearAlerts)
        btnCreateAlert = findViewById(R.id.btnCreateAlert)
        textEmptyAlerts = findViewById(R.id.textEmptyAlerts)
        chipAlertAll = findViewById(R.id.chipAlertAll)
        chipAlertGroups = findViewById(R.id.chipAlertGroups)
        chipAlertSystem = findViewById(R.id.chipAlertSystem)
        layoutAlertThread = findViewById(R.id.layoutAlertThread)
        alertThreadHeader = findViewById(R.id.alertThreadHeader)
        alertThreadSenderText = findViewById(R.id.alertThreadSenderText)
        btnExitAlertThread = findViewById(R.id.btnExitAlertThread)
        alertThreadDescription = findViewById(R.id.alertThreadDescription)
        alertThreadAttachmentCard = findViewById(R.id.alertThreadAttachmentCard)
        alertThreadAttachmentName = findViewById(R.id.alertThreadAttachmentName)
        alertThreadAttachmentStatus = findViewById(R.id.alertThreadAttachmentStatus)
        alertThreadAttachmentImage = findViewById(R.id.alertThreadAttachmentImage)
        alertThreadLocationCard = findViewById(R.id.alertThreadLocationCard)
        alertThreadLocationCoordinates = findViewById(R.id.alertThreadLocationCoordinates)
        alertThreadLocationDistance = findViewById(R.id.alertThreadLocationDistance)
        btnAlertThreadViewOnMap = findViewById(R.id.btnAlertThreadViewOnMap)
        alertThreadRecyclerView = findViewById(R.id.alertThreadRecyclerView)
        alertThreadRecyclerView.layoutManager = LinearLayoutManager(this)
        editAlertThreadReply = findViewById(R.id.editAlertThreadReply)
        btnSendAlertThreadReply = findViewById(R.id.btnSendAlertThreadReply)

        settingsAvatar = findViewById(R.id.settingsAvatar)
        settingsDisplayName = findViewById(R.id.settingsDisplayName)
        settingsPeerId = findViewById(R.id.settingsPeerId)
        settingsStatusBadge = findViewById(R.id.settingsStatusBadge)
        btnEditIdentity = findViewById(R.id.btnEditIdentity)
        messagesEmptyText = findViewById(R.id.messagesEmptyText)
        chatPeerAvatar = findViewById(R.id.chatPeerAvatar)
        peersRecyclerView = findViewById(R.id.peersRecyclerView)
        peersRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        dashRecentRecyclerView.layoutManager = LinearLayoutManager(this)
        alertsRecyclerView.layoutManager = LinearLayoutManager(this)
        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)

        dashMapView = findViewById(R.id.dashMapView)
        dashMapCoordinatesText = findViewById(R.id.dashMapCoordinatesText)
        mapStatusText = findViewById(R.id.mapStatusText)
        mapDistanceMeasureText = findViewById(R.id.mapDistanceMeasureText)
        btnMapRecenter = findViewById(R.id.btnMapRecenter)
        btnMapLayers = findViewById(R.id.btnMapLayers)

        locationManagerHelper = com.fury.peerconnect.logic.LocationManagerHelper(this)
        offlineMapManager = com.fury.peerconnect.logic.OfflineMapManager(this, dashMapView).also { mgr ->
            mgr.onCreate(savedInstanceState) {
                runOnUiThread {
                    startGPSUpdates()
                }
            }
        }

        btnMapRecenter.setOnClickListener {
            offlineMapManager?.recenterMyGPSLocation()
        }
        btnMapLayers.setOnClickListener {
            offlineMapManager?.cycleMapStyle { label ->
                Toast.makeText(this, "Map Layer: $label", Toast.LENGTH_SHORT).show()
            }
        }

        val myLocProvider: () -> Pair<Double, Double>? = {
            myCurrentCoordinates?.let { Pair(it.latitude, it.longitude) }
        }

        chatAdapter = ChatAdapter(
            myNickName = myNickName,
            onAttachmentClick = { fileName, uri -> openAttachment(fileName, uri) },
            onLocationClick = { lat, lon, title -> showLocationOnDashboardMap(lat, lon, title) },
            myLocationProvider = myLocProvider
        )
        chatRecyclerView.adapter = chatAdapter
        alertThreadAdapter = ChatAdapter(
            myNickName = myNickName,
            onAttachmentClick = { fileName, uri -> openAttachment(fileName, uri) },
            onLocationClick = { lat, lon, title -> showLocationOnDashboardMap(lat, lon, title) },
            myLocationProvider = myLocProvider
        )
        alertThreadRecyclerView.adapter = alertThreadAdapter

        val inputCard = findViewById<View>(R.id.inputCard)
        inputCard?.let { card ->
            ViewCompat.setOnApplyWindowInsetsListener(card) { _, insets ->
                val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBarsHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val bottomMargin = if (imeHeight > 0) imeHeight else navBarsHeight
                val params = card.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    val baseMargin = (12 * resources.displayMetrics.density).toInt()
                    params.bottomMargin = bottomMargin + baseMargin
                    card.layoutParams = params
                }
                if (imeHeight > 0 && ::chatAdapter.isInitialized && chatAdapter.itemCount > 0) {
                    chatRecyclerView.post {
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
                insets
            }
        }

        val alertThreadInputCard = findViewById<View>(R.id.alertThreadInputCard)
        alertThreadInputCard?.let { card ->
            ViewCompat.setOnApplyWindowInsetsListener(card) { _, insets ->
                val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBarsHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val bottomMargin = if (imeHeight > 0) imeHeight else navBarsHeight
                val params = card.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    val baseMargin = (12 * resources.displayMetrics.density).toInt()
                    params.bottomMargin = bottomMargin + baseMargin
                    card.layoutParams = params
                }
                if (imeHeight > 0 && ::alertThreadAdapter.isInitialized && alertThreadAdapter.itemCount > 0) {
                    alertThreadRecyclerView.post {
                        alertThreadRecyclerView.scrollToPosition(alertThreadAdapter.itemCount - 1)
                    }
                }
                insets
            }
        }

        alertAdapter = AlertAdapter(
            onAlertClick = { alert -> openAlertThread(alert) },
            onLocationClick = { lat, lon, title -> showLocationOnDashboardMap(lat, lon, title) },
            onFoundPersonClick = { alert -> showFoundPersonCoordination(alert) },
            myLocationProvider = myLocProvider
        )
        alertsRecyclerView.adapter = alertAdapter

        btnCreateAlert.setOnClickListener { showCreateAlertDialog() }
        btnExitAlertThread.setOnClickListener { closeAlertThread() }
        btnSendAlertThreadReply.setOnClickListener { sendAlertThreadReply() }
        btnClearAlerts.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                db.alertDao().clearAlerts()
                loadAlertsFromDb()
            }
        }

        recentActivityAdapter = RecentActivityAdapter { peerName -> openChat(peerName, null) }
        dashRecentRecyclerView.adapter = recentActivityAdapter

        peerAdapter = PeerAdapter { peer -> openChat(peer.name, peer.endpointId) }
        peersRecyclerView.adapter = peerAdapter

        connectedPeerAdapter = ConnectedPeerAdapter { peer -> openChat(peer.name, peer.endpointId) }
        dashConnectedPeersRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dashConnectedPeersRecyclerView.adapter = connectedPeerAdapter

        val setChipSelected: (View, View, View) -> Unit = { selected, other1, other2 ->
            selected.alpha = 1.0f
            other1.alpha = 0.5f
            other2.alpha = 0.5f
        }

        chipAlertAll.setOnClickListener {
            setChipSelected(chipAlertAll, chipAlertGroups, chipAlertSystem)
            alertAdapter.applyFilter("ALL")
        }
        chipAlertGroups.setOnClickListener {
            setChipSelected(chipAlertGroups, chipAlertAll, chipAlertSystem)
            alertAdapter.applyFilter("GROUPS")
        }
        chipAlertSystem.setOnClickListener {
            setChipSelected(chipAlertSystem, chipAlertAll, chipAlertGroups)
            alertAdapter.applyFilter("SYSTEM")
        }

        checkIdentity()
        updateGreeting()

        bottomNavigation.setOnItemSelectedListener { item ->
            val handled = when (item.itemId) {
                R.id.nav_home -> {
                    showTab(layoutDashboard)
                    loadRecentActivityFromDb()
                    loadPeersFromDb()
                    true
                }
                R.id.nav_messages -> {
                    showTab(layoutConnection)
                    loadPeersFromDb()
                    true
                }
                R.id.nav_alerts -> {
                    showTab(layoutAlerts)
                    loadAlertsFromDb()
                    true
                }
                R.id.nav_settings -> {
                    showTab(layoutSettings)
                    updateSettingsUI()
                    true
                }
                else -> false
            }
            updateBackCallbackState()
            handled
        }

        val addContactClickListener = View.OnClickListener {
            if (isPairingMode) {
                isPairingMode = false
                btnAddContact.text = "+ Add New Contact"
                btnAddContact.setBackgroundColor(Color.parseColor("#E53935"))
                startAutoMode()
            } else {
                showPairingDialog()
            }
        }
        btnAddContact.setOnClickListener(addContactClickListener)

        btnExitChat.setOnClickListener { closeChat() }
        btnSend.setOnClickListener {
            val text = editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
        btnAttach.setOnClickListener {
            showChatAttachmentMenu()
        }
        btnEditIdentity.setOnClickListener { showNameInputDialog() }

        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            startAutoMode()
        }

        loadPeersFromDb()
        loadAlertsFromDb()
        loadRecentActivityFromDb()
        handleChatIntent(intent)
        updateBackCallbackState()

        handler.removeCallbacks(heartbeatWatchdogRunnable)
        handler.postDelayed(heartbeatWatchdogRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun showTab(activeLayout: View) {
        layoutDashboard.visibility = if (activeLayout == layoutDashboard) View.VISIBLE else View.GONE
        layoutConnection.visibility = if (activeLayout == layoutConnection) View.VISIBLE else View.GONE
        layoutAlerts.visibility = if (activeLayout == layoutAlerts) View.VISIBLE else View.GONE
        layoutSettings.visibility = if (activeLayout == layoutSettings) View.VISIBLE else View.GONE
    }

    private fun checkIdentity() {
        val userManager = UserManager(this)
        if (userManager.hasIdentity()) {
            myNickName = userManager.getUsername() ?: ""
            initMeshNetwork()
            updateStatus("WIFI P2P: DISCOVERING")
            updateSettingsUI()
            updateGreeting()
            val myLocProvider: () -> Pair<Double, Double>? = {
                myCurrentCoordinates?.let { Pair(it.latitude, it.longitude) }
            }
            chatAdapter = ChatAdapter(
                myNickName = myNickName,
                onAttachmentClick = { fileName, uri -> openAttachment(fileName, uri) },
                onLocationClick = { lat, lon, title -> showLocationOnDashboardMap(lat, lon, title) },
                myLocationProvider = myLocProvider
            )
            chatRecyclerView.adapter = chatAdapter
            alertThreadAdapter = ChatAdapter(
                myNickName = myNickName,
                onAttachmentClick = { fileName, uri -> openAttachment(fileName, uri) },
                onLocationClick = { lat, lon, title -> showLocationOnDashboardMap(lat, lon, title) },
                myLocationProvider = myLocProvider
            )
            alertThreadRecyclerView.adapter = alertThreadAdapter
            if (hasPermissions()) {
                startAutoMode()
            }
        } else {
            showNameInputDialog()
        }
    }

    private fun showNameInputDialog() {
        if (isFinishing || isDestroyed) return
        try {
            val input = EditText(this).apply {
                hint = "Enter your unique ID/Name"
            }
            AlertDialog.Builder(this)
                .setTitle("Welcome to NEXORA")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        UserManager(this).saveUsername(name)
                        checkIdentity()
                    } else {
                        showNameInputDialog()
                    }
                }
                .create()
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying name dialog", e)
        }
    }

    private fun initMeshNetwork() {
        if (wifiP2pMeshManager != null) return
        neighborTable = NeighborTable()
        connectionManager = ConnectionManager()
        wifiP2pMeshTransport = WifiP2pMeshTransport(connectionManager)
        nearbyMeshTransport = NearbyMeshTransport(this, myNickName)

        blobRepository = BlobRepository(this)
        blobExchange = BlobExchange(
            blobRepository = blobRepository,
            myPeerId = myNickName,
            sendBlobRequest = { targetPeer: String?, hash: String ->
                val reqMsg = ChatMessage(myNickName, SecurityHelper.encrypt("[BLOB_REQ]:$hash"), System.currentTimeMillis())
                val bytes = serialize(reqMsg)
                if (targetPeer != null) {
                    routingEngine?.sendDirectMessage(targetPeer, bytes)
                } else {
                    routingEngine?.broadcastAlert(bytes)
                }
            },
            sendBlobData = { targetPeer: String, hash: String, bytes: ByteArray ->
                val tSendStart = System.currentTimeMillis()
                Log.d(TAG, "TIMING_METRIC: [3] first chunk sending: hash=$hash bytes=${bytes.size} to=$targetPeer")
                if (bytes.size <= 20480) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val dataMsg = ChatMessage(myNickName, SecurityHelper.encrypt("[BLOB_DATA]:$hash|$base64"), System.currentTimeMillis())
                    val payloadBytes = serialize(dataMsg)
                    routingEngine?.sendDirectMessage(targetPeer, payloadBytes)
                    Log.d(TAG, "TIMING_METRIC: [4] single chunk sent: hash=$hash duration=${System.currentTimeMillis() - tSendStart}ms")
                } else {
                    val safeChunkSize = 20480
                    val totalChunks = (bytes.size + safeChunkSize - 1) / safeChunkSize
                    for (i in 0 until totalChunks) {
                        val start = i * safeChunkSize
                        val end = minOf(start + safeChunkSize, bytes.size)
                        val chunk = bytes.copyOfRange(start, end)
                        val chunkB64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        val chunkMsg = ChatMessage(myNickName, SecurityHelper.encrypt("[BLOB_CHUNK]:$hash|$i|$totalChunks|$chunkB64"), System.currentTimeMillis())
                        val chunkBytes = serialize(chunkMsg)
                        routingEngine?.sendDirectMessage(targetPeer, chunkBytes)
                        if (i > 0 && i % 4 == 0) {
                            try { Thread.sleep(2) } catch (_: Exception) {}
                        }
                    }
                    Log.d(TAG, "TIMING_METRIC: [4] chunks transmitted: hash=$hash totalChunks=$totalChunks duration=${System.currentTimeMillis() - tSendStart}ms")
                }
            },
            onBlobObtained = { hash: String ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val blobFile = blobRepository.getFile(hash)
                    val path = blobFile?.absolutePath
                    val length = blobFile?.length() ?: 0L
                    Log.d(TAG, "ATTACHMENT_ON_OBTAINED: hash=$hash bytes=$length")
                    Log.d(TAG, "TIMING_METRIC: [7] blob finalized: hash=$hash bytes=$length")
                    val fileMessages = db.messageDao().getAllFileMessages()
                    for (msg in fileMessages) {
                        val fn = msg.fileName
                        if (msg.text.contains("|$hash|") || msg.text.contains("|$hash") || (fn != null && fn.contains(hash))) {
                            val newText = "[FILE]:${msg.fileName ?: "attachment"}|$path|$hash|$length|SUCCESS"
                            db.messageDao().updateFileDetails(msg.id.toLong(), newText, path ?: "", "SUCCESS", length)
                            Log.d(TAG, "ATTACHMENT_MESSAGE_UPDATED: id=${msg.id} hash=$hash")
                        }
                    }

                    val alerts = db.alertDao().getAllAlerts()
                    for (alert in alerts) {
                        val att = alert.attachmentPath
                        if (att != null && att.contains(hash)) {
                            val clean = att.removePrefix("[FILE]:")
                            val parts = clean.split("|")
                            val fn = parts.getOrNull(0) ?: "attachment"
                            val updatedAttachment = "[FILE]:$fn|$path|$hash|$length|SUCCESS"
                            db.alertDao().updateAlert(alert.copy(attachmentPath = updatedAttachment))
                            Log.d(TAG, "ATTACHMENT_ALERT_UPDATED: alertId=${alert.alertId} hash=$hash")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "TIMING_METRIC: [8] UI becomes available: hash=$hash")
                        if (currentChatPeerName != null) {
                            val history = db.messageDao().getChatHistory(myNickName, currentChatPeerName!!)
                            updateChatUI(history)
                        }
                        loadAlertsFromDb()
                        if (currentAlertThreadId != null) {
                            val currentAlert = db.alertDao().getAlertByAlertId(currentAlertThreadId!!)
                                ?: db.alertDao().getAlertById(currentAlertThreadId!!.toIntOrNull() ?: -1)
                            if (currentAlert != null) {
                                bindAlertAttachment(currentAlert)
                            }
                        }
                    }
                }
            }
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val fileMessages = db.messageDao().getAllFileMessages()
            val missing = fileMessages.filter { it.transferStatus == "RECEIVING" }.mapNotNull { msg ->
                val parts = msg.text.removePrefix("[FILE]:").split("|")
                val hash = parts.getOrNull(2)
                if (!hash.isNullOrBlank() && !blobRepository.has(hash)) hash else null
            }
            if (missing.isNotEmpty()) {
                blobExchange?.resumePendingFetches(missing)
            }

            val alerts = db.alertDao().getAllAlerts()
            for (alert in alerts) {
                val att = alert.attachmentPath
                if (!att.isNullOrBlank() && att.startsWith("[FILE]:")) {
                    val clean = att.removePrefix("[FILE]:")
                    val parts = clean.split("|")
                    val fn = parts.getOrNull(0) ?: "attachment"
                    val hash = parts.firstOrNull { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }
                        ?: parts.getOrNull(2)?.trim()?.lowercase()
                        ?: ""
                    if (hash.isNotBlank()) {
                        if (blobRepository.has(hash)) {
                            val file = blobRepository.getFile(hash)
                            val path = file?.absolutePath ?: ""
                            val length = file?.length() ?: 0L
                            val updated = "[FILE]:$fn|$path|$hash|$length|SUCCESS"
                            if (att != updated) {
                                db.alertDao().updateAlert(alert.copy(attachmentPath = updated))
                            }
                        } else {
                            val peer = alert.originPeerId ?: alert.peerName
                            blobExchange?.want(hash, peer)
                        }
                    }
                }
            }
        }

        routingEngine = RoutingEngine(
            myDeviceId = myNickName,
            connectionManager = connectionManager,
            onApplicationPayloadReceived = { sourcePeerId, messageId, type, payload ->
                handleIncomingApplicationPayload(sourcePeerId, messageId, type, payload)
            },
            onAckReceived = { _, rawPayload, fromPeerId ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val parts = rawPayload.split(":")
                    val (idPart, receiptType) = if (parts.size >= 2) {
                        Pair(parts[0], parts[1].uppercase(Locale.ROOT))
                    } else {
                        Pair(rawPayload, "DELIVERED")
                    }
                    val ids = idPart.split(",").mapNotNull { it.trim().toIntOrNull() }
                    for (idInt in ids) {
                        db.messageDao().updateDeliveryStatusMonotonic(idInt, receiptType)
                        if (currentChatPeerName == fromPeerId) {
                            withContext(Dispatchers.Main) {
                                val updated = chatAdapter.updateMessageStatus(idInt, receiptType)
                                if (!updated && !chatAdapter.hasMessage(idInt)) {
                                    val history = db.messageDao().getChatHistory(myNickName, fromPeerId)
                                    updateChatUI(history)
                                }
                            }
                        }
                    }
                }
            },
            isPeerAuthorized = { peerId ->
                runBlocking(Dispatchers.IO) {
                    db.peerDao().isKnownPeer(peerId)
                }
            },
            onReachabilityChanged = { peerName, isReachable, nextHop, hopDistance ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.peerDao().updateReachability(peerName, isReachable, nextHop, hopDistance, System.currentTimeMillis())
                    withContext(Dispatchers.Main) {
                        loadPeersFromDb()
                        if (currentChatPeerName == peerName) {
                            val isDirect = activeEndpoints.any { pendingConnections[it] == peerName }
                            val live = isDirect || isReachable
                            updateStatus(if (live) "CONNECTED" else "OFFLINE")
                        }
                    }
                    if (isReachable) {
                        blobExchange?.onNeighborAdded(peerName)
                    } else {
                        blobExchange?.onNeighborDisconnected(peerName)
                    }
                }
            },
            onRouteDiscovered = { peerName ->
                flushOfflineMessages(peerName)
            }
        ).apply {
            wifiP2pMeshTransport?.let { registerTransport(it) }
            nearbyMeshTransport?.let { registerTransport(it) }
        }

        wifiP2pMeshManager = WifiP2pMeshManager(
            context = this,
            myDeviceId = myNickName,
            neighborTable = neighborTable,
            connectionManager = connectionManager,
            onMessageReceived = { meshMsg ->
                routingEngine?.processIncomingMessage(meshMsg, meshMsg.sourcePeerId, "WIFI_P2P")
            }
        ).apply {
            neighborTable.addListener {
                runOnUiThread { loadPeersFromDb() }
            }
            addGroupStateListener { groupState ->
                runOnUiThread {
                    val statusStr = if (!groupState.isGroupFormed) {
                        "DISCOVERING"
                    } else if (groupState.isGroupOwner) {
                        "GROUP OWNER (${groupState.clients.size} clients)"
                    } else {
                        "CLIENT (GO: ${groupState.groupOwnerAddress ?: "Unknown"})"
                    }
                    updateStatus(statusStr)
                }
            }
        }
        registerP2pReceiver()
    }

    private fun registerP2pReceiver() {
        val wm = wifiP2pMeshManager ?: return
        if (p2pReceiver == null) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            p2pReceiver = WifiP2pBroadcastReceiver(wm.manager, wm.channel, wm)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(this, p2pReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(p2pReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering P2P receiver", e)
            }
        }
    }

    private fun startAutoMode() {
        if (myNickName.isBlank()) {
            Log.d(TAG, "startAutoMode deferred: myNickName is blank")
            return
        }
        handler.removeCallbacks(roleSwitchRunnable)
        pendingRadioSwitch?.let { handler.removeCallbacks(it) }
        if (hasCapacityForMorePeers()) {
            startAdvertising()
            startDiscovery()
            if (activeEndpoints.isNotEmpty()) {
                updateStatus("CONNECTED")
            }
        }
    }

    private fun switchRoles() {
        startAutoMode()
    }

    private fun resetRadio(forceStopAll: Boolean = false) {
        if (hasPermissions()) {
            if (!forceStopAll && activeEndpoints.isNotEmpty()) {
                Log.d(TAG, "resetRadio skipped because activeEndpoints not empty")
                return
            }
            try {
                Nearby.getConnectionsClient(this).stopAdvertising()
                Nearby.getConnectionsClient(this).stopDiscovery()
                isAdvertising = false
                isDiscovering = false
                Nearby.getConnectionsClient(this).stopAllEndpoints()
                activeEndpoints.clear()
                endpointLastSeen.clear()
                pendingRadioSwitch?.let { handler.removeCallbacks(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error in resetRadio", e)
            }
        }
    }

    private fun startAdvertising() {
        if (myNickName.isBlank() || !hasPermissions() || isAdvertising) return
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
        try {
            Nearby.getConnectionsClient(this).startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener {
                    isAdvertising = true
                    if (activeEndpoints.isEmpty()) updateStatus("AUTO: ADVERTISING")
                }
                .addOnFailureListener { e ->
                    isAdvertising = false
                    Log.e(TAG, "Adv fail", e)
                    handler.postDelayed({
                        if (!isAdvertising && hasCapacityForMorePeers() && !isPairingMode) {
                            startAdvertising()
                        }
                    }, 5000L)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising", e)
        }
    }

    private fun startDiscovery() {
        if (myNickName.isBlank() || !hasPermissions() || isDiscovering) return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnSuccessListener {
                    isDiscovering = true
                    if (activeEndpoints.isEmpty() && !isAdvertising) updateStatus("AUTO: DISCOVERING")
                }
                .addOnFailureListener { e ->
                    isDiscovering = false
                    Log.e(TAG, "Disc fail", e)
                    handler.postDelayed({
                        if (!isDiscovering && hasCapacityForMorePeers() && !isPairingMode) {
                            startDiscovery()
                        }
                    }, 5000L)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting discovery", e)
        }
    }

    private fun handleExplicitDisconnect(endpointId: String) {
        if (pendingConnections.containsKey(endpointId) || activeEndpoints.contains(endpointId)) {
            activeEndpoints.remove(endpointId)
            endpointLastSeen.remove(endpointId)
            nearbyMeshTransport?.onEndpointDisconnected(endpointId)
            val peerName = pendingConnections.remove(endpointId) ?: endpointId
            runOnUiThread {
                if (endpointId == focusedChatEndpointId) {
                    focusedChatEndpointId = activeEndpoints.firstOrNull()
                    if (focusedChatEndpointId == null) {
                        updateStatus("OFFLINE")
                    }
                }
            }
            try {
                Nearby.getConnectionsClient(this).disconnectFromEndpoint(endpointId)
            } catch (_: Exception) {}
            lifecycleScope.launch(Dispatchers.IO) {
                db.peerDao().markPeerOffline(peerName)
                broadcastPresenceUpdate(peerName, PeerStatus.DISCONNECTED)
                withContext(Dispatchers.Main) {
                    loadPeersFromDb()
                    if (currentChatPeerName == peerName) {
                        val isReachable = routingEngine?.isPeerReachable(peerName) == true
                        updateStatus(if (isReachable) "CONNECTED" else "OFFLINE")
                    }
                }
            }
            startAutoMode()
        }
    }

    private fun isConnectedTo(endpointId: String): Boolean = activeEndpoints.contains(endpointId)

    private fun hasCapacityForMorePeers(): Boolean = activeEndpoints.size < MAX_CONCURRENT_PEERS

    private fun openChat(peerName: String, endpointId: String?) {
        if (layoutAlertThread.visibility == View.VISIBLE) {
            closeAlertThread()
        }
        currentChatPeerName = peerName
        val isDirectlyConnected = activeEndpoints.any { pendingConnections[it] == peerName || it == endpointId }
        focusedChatEndpointId = if (isDirectlyConnected) (endpointId ?: activeEndpoints.firstOrNull()) else null
        layoutChat.visibility = View.VISIBLE
        updateBackCallbackState()

        cancelNotificationForPeer(peerName)
        unreadMessageCounts.remove(peerName)

        applyGlobalTopInsets(lastKnownStatusBarInset)
        ViewCompat.requestApplyInsets(layoutChat)

        val isReachable = isDirectlyConnected || (routingEngine?.isPeerReachable(peerName) == true)
        updateStatus(if (isReachable) "CONNECTED" else "OFFLINE")

        findViewById<TextView>(R.id.chatHeader).text = peerName
        chatPeerAvatar.text = peerName.take(1).uppercase(Locale.ROOT)

        lifecycleScope.launch(Dispatchers.IO) {
            val unreadMessages = db.messageDao().getUnreadIncomingMessages(peerName, myNickName)
            if (unreadMessages.isNotEmpty()) {
                db.messageDao().markIncomingMessagesAsRead(peerName, myNickName)
                val batchIds = unreadMessages.mapNotNull { it.senderMessageId }.filter { it.isNotBlank() }.joinToString(",")
                if (batchIds.isNotBlank()) {
                    routingEngine?.sendReceipt(peerName, batchIds, "READ")
                }
            }
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) {
                if (currentChatPeerName == peerName) {
                    updateChatUI(history)
                }
            }
        }
    }

    private fun closeChat() {
        currentChatPeerName = null
        focusedChatEndpointId = null
        layoutChat.visibility = View.GONE
        updateBackCallbackState()
    }

    private fun updateChatUI(history: List<MessageEntity>) {
        val filteredHistory = history.filter { it.alertId == null && !it.text.startsWith("[ALERT]:") }
        chatAdapter.setEntities(filteredHistory)
        if (chatAdapter.itemCount > 0) {
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        }
        messagesEmptyText.visibility = if (filteredHistory.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendMessage(messageText: String) {
        val peerName = currentChatPeerName ?: return
        editMessage.setText("")
        lifecycleScope.launch(Dispatchers.IO) {
            val isReachable = routingEngine?.isPeerReachable(peerName) == true
            val msgEntity = MessageEntity(
                id = 0,
                senderId = myNickName,
                receiverId = peerName,
                text = messageText,
                timestamp = System.currentTimeMillis(),
                isSent = isReachable,
                deliveryStatus = "PENDING"
            )
            val insertedId = db.messageDao().insertMessage(msgEntity)
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) {
                updateChatUI(history)
            }

            try {
                val encrypted = SecurityHelper.encrypt(messageText)
                val chatMsg = ChatMessage(myNickName, encrypted, msgEntity.timestamp)
                val bytes = serialize(chatMsg)
                routingEngine?.sendDirectMessage(peerName, bytes, insertedId.toString())
                db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "SENT")
                withContext(Dispatchers.Main) {
                    chatAdapter.updateMessageStatus(insertedId.toInt(), "SENT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send message error", e)
            }
        }
    }

    private fun flushOfflineMessages(peerName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pendingMessages = db.messageDao().getUnsentMessages(peerName)
            for (msg in pendingMessages) {
                try {
                    val encrypted = SecurityHelper.encrypt(msg.text)
                    val chatMsg = ChatMessage(myNickName, encrypted, msg.timestamp)
                    val bytes = serialize(chatMsg)
                    routingEngine?.sendDirectMessage(peerName, bytes, msg.id.toString())
                    db.messageDao().updateDeliveryStatusMonotonic(msg.id, "SENT")
                } catch (e: Exception) {
                    Log.e(TAG, "Flush message error", e)
                }
            }
            if (currentChatPeerName == peerName) {
                val history = db.messageDao().getChatHistory(myNickName, peerName)
                withContext(Dispatchers.Main) {
                    updateChatUI(history)
                }
            }
        }
    }

    private fun handleIncomingApplicationPayload(
        sourcePeerId: String,
        messageId: String,
        type: MessageType,
        payloadBytes: ByteArray
    ) {
        if (type == MessageType.TOPOLOGY_SYNC) return
        try {
            val msg = deserialize(payloadBytes)
            val decrypted = SecurityHelper.decrypt(msg.messageBody)
            if (decrypted.startsWith("[ALERT]:")) {
                Log.i(TAG, "ALERT_LOCATION_RECEIVED: messageId=$messageId, sourcePeerId=$sourcePeerId, bytes=${payloadBytes.size}")
                Log.i(TAG, "ALERT_LOCATION_DECRYPTED: Decrypted alert message from $sourcePeerId")
            }
            processReceivedDecryptedMessage(msg.senderName, decrypted, msg.time, sourcePeerId, messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed handling application payload", e)
        }
    }

    private fun processReceivedDecryptedMessage(
        senderName: String,
        decryptedBody: String,
        timestamp: Long,
        sourceEndpointOrPeer: String,
        originMessageId: String = ""
    ) {
        if (senderName.isBlank() || senderName == myNickName) return
        val dedupKey = "$senderName|$timestamp|${decryptedBody.hashCode()}"
        if (!recordInboundMessage(dedupKey)) {
            Log.d(TAG, "Dropping duplicate inbound message: $dedupKey")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Alert Reply
            if (decryptedBody.startsWith("[ALERT_REPLY]:")) {
                val clean = decryptedBody.removePrefix("[ALERT_REPLY]:")
                val parts = clean.split("|")
                if (parts.isNotEmpty()) {
                    val alertId = parts[0]
                    val replyText = if (parts.size > 1) parts.subList(1, parts.size).joinToString("|") else ""
                    val msgEntity = MessageEntity(
                        id = 0,
                        senderId = senderName,
                        receiverId = myNickName,
                        text = replyText,
                        timestamp = timestamp,
                        isSent = false,
                        deliveryStatus = "DELIVERED",
                        alertId = alertId
                    )
                    db.messageDao().insertMessage(msgEntity)
                    withContext(Dispatchers.Main) {
                        notifyIncomingMessage(senderName, replyText, timestamp, "TEXT")
                        if (currentAlertThreadId == alertId) {
                            val history = db.messageDao().getAlertThreadMessages(alertId)
                            updateAlertThreadUI(history)
                        }
                    }
                }
                return@launch
            }

            // 2. Blob Request & Data Handling
            if (decryptedBody.startsWith("[BLOB_REQ]:")) {
                val hash = decryptedBody.removePrefix("[BLOB_REQ]:").trim().lowercase()
                Log.d(TAG, "Received [BLOB_REQ] for $hash from $senderName")
                blobExchange?.onRequest(senderName, hash)
                return@launch
            }

            if (decryptedBody.startsWith("[BLOB_DATA]:")) {
                val clean = decryptedBody.removePrefix("[BLOB_DATA]:")
                val parts = clean.split("|")
                val hash = parts[0].trim().lowercase()
                val base64 = if (parts.size > 1) parts[1] else ""
                val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: Exception) { null }
                if (bytes != null) {
                    blobExchange?.onReceivedBytes(hash, bytes, senderName)
                }
                return@launch
            }

            if (decryptedBody.startsWith("[BLOB_CHUNK]:")) {
                val clean = decryptedBody.removePrefix("[BLOB_CHUNK]:")
                val parts = clean.split("|")
                val hash = parts.getOrNull(0)?.trim()?.lowercase() ?: ""
                val chunkIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val totalChunks = parts.getOrNull(2)?.toIntOrNull() ?: 1
                val base64Chunk = parts.getOrNull(3) ?: ""
                val chunkBytes = try { Base64.decode(base64Chunk, Base64.DEFAULT) } catch (_: Exception) { null }
                if (chunkBytes != null && hash.isNotBlank()) {
                    val chunkMap = pendingBlobChunks.computeIfAbsent(hash) { ConcurrentHashMap() }
                    chunkMap[chunkIndex] = chunkBytes
                    val currentReceived = chunkMap.size
                    if (currentReceived == 1) {
                        Log.d(TAG, "TIMING_METRIC: [5] first chunk received: hash=$hash from=$senderName")
                    }
                    val progressPercent = (currentReceived * 100) / totalChunks
                    if (currentReceived % 5 == 0 || currentReceived == totalChunks) {
                        Log.d(TAG, "ATTACHMENT_CHUNKS_PROGRESS: hash=$hash received=$currentReceived/$totalChunks ($progressPercent%)")
                        withContext(Dispatchers.Main) {
                            val msg = db.messageDao().getAllFileMessages().firstOrNull { it.text.contains(hash) }
                            if (msg != null) {
                                chatAdapter.updateTransferProgress(msg.id, progressPercent)
                            }
                        }
                    }

                    if (chunkMap.size == totalChunks) {
                        Log.d(TAG, "TIMING_METRIC: [5] all chunks received: hash=$hash totalChunks=$totalChunks")
                        val totalEstimatedBytes = totalChunks * 20480
                        val baos = java.io.ByteArrayOutputStream(totalEstimatedBytes)
                        for (i in 0 until totalChunks) {
                            chunkMap[i]?.let { baos.write(it) }
                        }
                        pendingBlobChunks.remove(hash)
                        Log.d(TAG, "TIMING_METRIC: [6] blob verifying: hash=$hash bytes=${baos.size()}")
                        blobExchange?.onReceivedBytes(hash, baos.toByteArray(), senderName)
                    }
                }
                return@launch
            }

            // 3. Alert Broadcast (Store in alertDao, DO NOT insert into peer chat)
            if (decryptedBody.startsWith("[ALERT]:")) {
                val payload = AlertPayload.parse(decryptedBody, senderName, timestamp)
                if (payload != null) {
                    val hasLocation = payload.latitude != null && payload.longitude != null
                    Log.i(TAG, "ALERT_LOCATION_PARSED: alertId=${payload.id}, hasLocation=$hasLocation, latitude=${payload.latitude}, longitude=${payload.longitude}, accuracy=${payload.accuracy}")
                    var localAttachment: String? = payload.attachmentPath
                    if (!localAttachment.isNullOrBlank() && localAttachment.startsWith("[FILE]:")) {
                        val clean = localAttachment.removePrefix("[FILE]:")
                        val parts = clean.split("|")
                        val fileName = parts.getOrNull(0) ?: "attachment"
                        val hash = parts.firstOrNull { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }
                            ?: parts.getOrNull(2)?.trim()?.lowercase()
                            ?: parts.getOrNull(1)?.trim()?.lowercase()
                            ?: ""
                        val sizeStr = parts.getOrNull(3) ?: ""
                        val size = sizeStr.toLongOrNull() ?: 0L

                        if (hash.isNotBlank()) {
                            blobExchange?.registerCustodyHash(hash, senderName)
                            if (MeshBlobStore.has(this@MainActivity, hash)) {
                                val resolvedFile = MeshBlobStore.exportReadableFile(this@MainActivity, hash, fileName, null)
                                    ?: MeshBlobStore.getFile(this@MainActivity, hash)
                                val resolvedPath = resolvedFile?.absolutePath ?: ""
                                localAttachment = "[FILE]:$fileName|$resolvedPath|$hash|$size|SUCCESS"
                            } else {
                                localAttachment = "[FILE]:$fileName||$hash|$size|RECEIVING"
                                blobExchange?.want(hash, senderName)
                            }
                        }
                    }
                    val alertEntity = AlertEntity(
                        id = 0,
                        type = payload.alertType,
                        title = payload.title,
                        description = payload.message,
                        message = payload.message,
                        timestamp = payload.sentAt,
                        isRead = false,
                        peerName = payload.senderId,
                        attachmentPath = localAttachment,
                        alertId = payload.id,
                        originPeerId = payload.senderId,
                        isCustodyActive = true,
                        expiresAt = payload.expiresAt,
                        latitude = payload.latitude,
                        longitude = payload.longitude,
                        accuracy = payload.accuracy
                    )
                    db.alertDao().insertAlert(alertEntity)
                    Log.i(TAG, "ALERT_LOCATION_SAVED: alertId=${payload.id}, hasLocation=$hasLocation, latitude=${payload.latitude}, longitude=${payload.longitude}, accuracy=${payload.accuracy}")
                    if (payload.latitude != null && payload.longitude != null) {
                        val distText = myCurrentCoordinates?.let { myLoc ->
                            val meters = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                                myLoc.latitude,
                                myLoc.longitude,
                                payload.latitude,
                                payload.longitude
                            )
                            com.fury.peerconnect.logic.DistanceEngine.formatDistance(meters)
                        }
                        withContext(Dispatchers.Main) {
                            offlineMapManager?.updateAlertLocation(
                                alertId = payload.id,
                                title = payload.title,
                                latitude = payload.latitude,
                                longitude = payload.longitude,
                                distanceText = distText
                            )
                        }
                    }
                    loadAlertsFromDb()
                }
                return@launch
            }

            // 4. Inline File Data (Base64)
            if (decryptedBody.startsWith("[FILE_DATA]:")) {
                val clean = decryptedBody.removePrefix("[FILE_DATA]:")
                val parts = clean.split("|")
                val fileName = parts.getOrNull(0) ?: "attachment"
                val base64Data = if (parts.size > 1) parts[1] else ""
                val isImage = FileStorageManager.isImageFile(fileName)
                val msgType = if (isImage) "IMAGE" else "FILE"
                val bytes = try {
                    Base64.decode(base64Data, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
                val savedFile = if (bytes != null) {
                    FileStorageManager.saveDirectBytes(this@MainActivity, bytes, fileName)
                } else null

                val textBody: String
                val savedPath: String?
                val status: String
                val size: Long
                val hash: String
                if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                    savedPath = savedFile.absolutePath
                    status = "SUCCESS"
                    size = savedFile.length()
                    hash = MeshBlobStore.computeSha256(savedFile)
                    MeshBlobStore.saveIncoming(this@MainActivity, hash, savedFile)
                    textBody = "[FILE]:$fileName|$savedPath|$hash|$size|SUCCESS"
                } else {
                    savedPath = null
                    status = "FAILED"
                    size = 0L
                    hash = ""
                    textBody = "[FILE]:$fileName||||FAILED"
                }

                val msgEntity = MessageEntity(
                    id = 0,
                    senderId = senderName,
                    receiverId = myNickName,
                    text = textBody,
                    timestamp = timestamp,
                    isSent = false,
                    deliveryStatus = "DELIVERED",
                    messageType = msgType,
                    fileName = fileName,
                    localPath = savedPath,
                    fileSize = size,
                    transferStatus = status,
                    senderMessageId = originMessageId.takeIf { it.isNotBlank() }
                )
                val insertedId = db.messageDao().insertMessage(msgEntity)
                if (originMessageId.isNotBlank()) {
                    val isChatActive = isActivityResumed && (currentChatPeerName == senderName)
                    if (isChatActive) {
                        db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "READ")
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                        routingEngine?.sendReceipt(senderName, originMessageId, "READ")
                    } else {
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                    }
                }
                if (status == "SUCCESS") {
                    emitAlert("TRANSFER", "FILE RECEIVED", "$fileName received successfully", senderName)
                }
                loadRecentActivityFromDb()
                withContext(Dispatchers.Main) {
                    notifyIncomingMessage(senderName, if (isImage) "📷 Photo: $fileName" else "📄 File: $fileName", timestamp, msgType, fileName)
                    if (currentChatPeerName == senderName) {
                        val history = db.messageDao().getChatHistory(myNickName, senderName)
                        updateChatUI(history)
                    }
                }
                return@launch
            }

            // 5. File Meta Notification (Content-addressed file transfer)
            if (decryptedBody.startsWith("[FILE]:") || decryptedBody.startsWith("📄 Shared a file:") || decryptedBody.startsWith("Shared a file:")) {
                val clean = when {
                    decryptedBody.startsWith("[FILE]:") -> decryptedBody.removePrefix("[FILE]:")
                    decryptedBody.startsWith("📄 Shared a file: ") -> decryptedBody.removePrefix("📄 Shared a file: ")
                    decryptedBody.startsWith("Shared a file: ") -> decryptedBody.removePrefix("Shared a file: ")
                    else -> decryptedBody
                }
                val parts = clean.split("|")
                val fileName = parts.getOrNull(0) ?: "attachment"
                val incomingPath = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                val hashOrPayloadId = parts.getOrNull(2)?.trim()
                val fileSize = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val wireStatus = parts.getOrNull(4) ?: "RECEIVING"
                val mimeType = parts.getOrNull(5) ?: (if (FileStorageManager.isImageFile(fileName)) "image/*" else "*/*")
                val attachmentKey = parts.getOrNull(6)
                val msgType = if (FileStorageManager.isImageFile(fileName)) "IMAGE" else "FILE"

                val payloadId = hashOrPayloadId?.toLongOrNull()
                val completedFile = if (payloadId != null) pendingCompletedFilesMap.remove(payloadId) else null

                val textBody: String
                val savedPath: String?
                val status: String

                if (completedFile != null && completedFile.file.exists() && completedFile.file.length() > 0) {
                    savedPath = completedFile.file.absolutePath
                    status = "SUCCESS"
                    val finalHash = completedFile.hash.ifBlank { hashOrPayloadId ?: "" }
                    textBody = "[FILE]:$fileName|$savedPath|$finalHash|${completedFile.file.length()}|SUCCESS|$mimeType|$attachmentKey"
                } else if (!hashOrPayloadId.isNullOrBlank() && blobRepository.has(hashOrPayloadId)) {
                    val blobFile = blobRepository.getFile(hashOrPayloadId)
                    savedPath = blobFile?.absolutePath
                    status = "SUCCESS"
                    textBody = "[FILE]:$fileName|$savedPath|$hashOrPayloadId|$fileSize|SUCCESS|$mimeType|$attachmentKey"
                } else {
                    savedPath = incomingPath
                    status = "RECEIVING"
                    textBody = "[FILE]:$fileName||$hashOrPayloadId|$fileSize|RECEIVING|$mimeType|$attachmentKey"
                    if (!hashOrPayloadId.isNullOrBlank() && hashOrPayloadId.length >= 8) {
                        blobExchange?.want(hashOrPayloadId, senderName)
                        blobExchange?.registerCustodyHash(hashOrPayloadId, senderName)
                    }
                }

                val msgEntity = MessageEntity(
                    id = 0,
                    senderId = senderName,
                    receiverId = myNickName,
                    text = textBody,
                    timestamp = timestamp,
                    isSent = false,
                    deliveryStatus = "DELIVERED",
                    messageType = msgType,
                    fileName = fileName,
                    localPath = savedPath,
                    fileSize = fileSize,
                    transferStatus = status,
                    senderMessageId = originMessageId.takeIf { it.isNotBlank() }
                )
                val insertedId = db.messageDao().insertMessage(msgEntity)
                if (originMessageId.isNotBlank()) {
                    val isChatActive = isActivityResumed && (currentChatPeerName == senderName)
                    if (isChatActive) {
                        db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "READ")
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                        routingEngine?.sendReceipt(senderName, originMessageId, "READ")
                    } else {
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                    }
                }
                if (status == "RECEIVING" && payloadId != null) {
                    pendingFileMetadataMap[payloadId] = PendingFileMetadata(
                        messageId = insertedId,
                        alertId = null,
                        fileName = fileName,
                        senderName = senderName,
                        endpointId = sourceEndpointOrPeer
                    )
                } else if (status == "SUCCESS") {
                    emitAlert("TRANSFER", "FILE RECEIVED", "$fileName received successfully", senderName)
                }
                loadRecentActivityFromDb()
                withContext(Dispatchers.Main) {
                    notifyIncomingMessage(senderName, if (msgType == "IMAGE") "📷 Photo: $fileName" else "📄 File: $fileName", timestamp, msgType, fileName)
                    if (currentChatPeerName == senderName) {
                        val history = db.messageDao().getChatHistory(myNickName, senderName)
                        updateChatUI(history)
                    }
                }
                return@launch
            }

            // 5. Location Messages ([LOC]: and [LOC_LIVE]:)
            if (decryptedBody.startsWith("[LOC]:") || decryptedBody.startsWith("[LOC_LIVE]:")) {
                val isLive = decryptedBody.startsWith("[LOC_LIVE]:")
                val clean = if (isLive) decryptedBody.removePrefix("[LOC_LIVE]:") else decryptedBody.removePrefix("[LOC]:")
                val parts = clean.split("|")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                val label = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: (if (isLive) "Live GPS" else "GPS Location")

                if (lat != null && lon != null) {
                    val currentMeters = myCurrentCoordinates?.let { myLoc ->
                        com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                            myLoc.latitude, myLoc.longitude, lat, lon
                        )
                    }
                    if (currentMeters != null && currentMeters > MAX_DIRECT_P2P_DISTANCE_METERS) {
                        Log.w(TAG, "[DISTANCE_EXCEEDED] Peer $senderName is ${currentMeters.toInt()}m away (max direct limit ${MAX_DIRECT_P2P_DISTANCE_METERS.toInt()}m).")
                        val directEp = activeEndpoints.firstOrNull { pendingConnections[it] == senderName }
                        if (directEp != null) {
                            Log.w(TAG, "[DISTANCE_EXCEEDED] Disconnecting direct endpoint $directEp for out-of-range peer $senderName")
                            handleExplicitDisconnect(directEp)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val distanceStr = currentMeters?.let { com.fury.peerconnect.logic.DistanceEngine.formatDistance(it) }
                        val statusLabel = if (distanceStr != null) "Received just now · $distanceStr" else "Received just now"
                        offlineMapManager?.updatePeerLocation(
                            peerId = senderName,
                            peerName = senderName,
                            latitude = lat,
                            longitude = lon,
                            isLive = isLive,
                            lastUpdatedText = statusLabel
                        )
                    }
                }

                val msgEntity = MessageEntity(
                    id = 0,
                    senderId = senderName,
                    receiverId = myNickName,
                    text = decryptedBody,
                    timestamp = timestamp,
                    isSent = false,
                    deliveryStatus = "DELIVERED",
                    messageType = "LOCATION",
                    senderMessageId = originMessageId.takeIf { it.isNotBlank() }
                )
                val insertedId = db.messageDao().insertMessage(msgEntity)
                if (originMessageId.isNotBlank()) {
                    val isChatActive = isActivityResumed && (currentChatPeerName == senderName)
                    if (isChatActive) {
                        db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "READ")
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                        routingEngine?.sendReceipt(senderName, originMessageId, "READ")
                    } else {
                        routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                    }
                }
                loadRecentActivityFromDb()
                withContext(Dispatchers.Main) {
                    val previewText = if (isLive) "🟢 Live location shared: $label" else "📍 Location shared: $label"
                    notifyIncomingMessage(senderName, previewText, timestamp, "LOCATION")
                    if (currentChatPeerName == senderName) {
                        val history = db.messageDao().getChatHistory(myNickName, senderName)
                        updateChatUI(history)
                    }
                }
                return@launch
            }

            // 6. Normal Text Message
            val msgEntity = MessageEntity(
                id = 0,
                senderId = senderName,
                receiverId = myNickName,
                text = decryptedBody,
                timestamp = timestamp,
                isSent = false,
                deliveryStatus = "DELIVERED",
                messageType = "TEXT",
                senderMessageId = originMessageId.takeIf { it.isNotBlank() }
            )
            val insertedId = db.messageDao().insertMessage(msgEntity)
            if (originMessageId.isNotBlank()) {
                val isChatActive = isActivityResumed && (currentChatPeerName == senderName)
                if (isChatActive) {
                    db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "READ")
                    routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                    routingEngine?.sendReceipt(senderName, originMessageId, "READ")
                } else {
                    routingEngine?.sendReceipt(senderName, originMessageId, "DELIVERED")
                }
            }
            loadRecentActivityFromDb()
            withContext(Dispatchers.Main) {
                notifyIncomingMessage(senderName, decryptedBody, timestamp, "TEXT")
                if (currentChatPeerName == senderName) {
                    val history = db.messageDao().getChatHistory(myNickName, senderName)
                    updateChatUI(history)
                }
            }
        }
    }

    private fun handlePresenceUpdate(decryptedBody: String, endpointId: String) {
        Log.d(TAG, "Presence update: $decryptedBody")
    }

    private fun broadcastPresenceUpdate(subjectPeerId: String, status: PeerStatus) {
        lifecycleScope.launch(Dispatchers.IO) {
            val payload = PresencePayload(
                subjectPeerId = subjectPeerId,
                status = status
            )
            routingEngine?.broadcastPresence(payload)
        }
    }

    private fun showPairingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing_mode, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnReceiveHost).setOnClickListener {
            dialog.dismiss()
            startManualHost()
        }
        dialogView.findViewById<View>(R.id.btnSendJoin).setOnClickListener {
            dialog.dismiss()
            startManualJoin()
        }
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            startAutoMode()
        }
        dialog.setOnCancelListener { startAutoMode() }
        dialog.show()
    }

    private fun startManualHost() {
        handler.removeCallbacks(roleSwitchRunnable)
        pendingRadioSwitch?.let { handler.removeCallbacks(it) }
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        isAdvertising = false
        isDiscovering = false
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        activeEndpoints.clear()
        isPairingMode = true
        isHost = true
        updateStatus("PAIRING MODE")
        btnAddContact.text = "Please Wait..."
        btnAddContact.setBackgroundColor(Color.parseColor("#424242"))

        handler.postDelayed({
            updateStatus("PAIRING MODE")
            btnAddContact.text = "Hosting... (Tap to Cancel)"
            btnAddContact.setBackgroundColor(Color.parseColor("#E53935"))
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
            Nearby.getConnectionsClient(this).startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener { isAdvertising = true }
                .addOnFailureListener { e ->
                    isAdvertising = false
                    updateStatus("Error: Radio Failed")
                    btnAddContact.text = "Retry"
                }
        }, 1000L)
    }

    private fun startManualJoin() {
        handler.removeCallbacks(roleSwitchRunnable)
        pendingRadioSwitch?.let { handler.removeCallbacks(it) }
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        isAdvertising = false
        isDiscovering = false
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        activeEndpoints.clear()
        isPairingMode = true
        isHost = false
        discoveredEndpoints.clear()
        updateStatus("PAIRING MODE")
        btnAddContact.text = "Please Wait..."
        btnAddContact.setBackgroundColor(Color.parseColor("#424242"))

        handler.postDelayed({
            updateStatus("PAIRING MODE")
            btnAddContact.text = "Scanning... (Tap to Cancel)"
            btnAddContact.setBackgroundColor(Color.parseColor("#0284C7"))
            val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnSuccessListener { isDiscovering = true }
                .addOnFailureListener { e ->
                    isDiscovering = false
                    updateStatus("Error: Radio Failed")
                    btnAddContact.text = "Retry"
                }
        }, 1000L)
    }

    private fun showDeviceSelectionDialog() {
        if (discoveredEndpoints.isEmpty()) {
            selectionDialog?.dismiss()
            return
        }
        val endpointIds = discoveredEndpoints.keys.toList()
        val names = discoveredEndpoints.values.toTypedArray()

        selectionDialog?.dismiss()
        selectionDialog = AlertDialog.Builder(this)
            .setTitle("Found Devices — Nexora")
            .setItems(names) { _, which ->
                val selectedEndpointId = endpointIds[which]
                val selectedName = names[which]
                Toast.makeText(this, "Connecting to $selectedName...", Toast.LENGTH_SHORT).show()
                Nearby.getConnectionsClient(this).requestConnection(myNickName, selectedEndpointId, connectionLifecycleCallback)
                Nearby.getConnectionsClient(this).stopDiscovery()
                handler.removeCallbacks(roleSwitchRunnable)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                startAutoMode()
            }
            .create()
        selectionDialog?.show()
    }

    private fun performHeartbeatAndLivenessCheck() {
        val now = System.currentTimeMillis()

        // 1. Broadcast periodic presence heartbeat across mesh if there are active endpoints or known routes
        if (myNickName.isNotBlank() && (activeEndpoints.isNotEmpty() || (routingEngine?.routeTable?.getAllActiveRoutes(now)?.isNotEmpty() == true))) {
            broadcastPresenceUpdate(myNickName, PeerStatus.CONNECTED)
        }

        // 2. Check for dead / silent active endpoints (silent departure / large distance)
        val deadEndpoints = mutableListOf<String>()
        for (endpointId in activeEndpoints) {
            val lastSeen = endpointLastSeen[endpointId] ?: now
            if (now - lastSeen > HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "[LIVENESS_TIMEOUT] endpointId=$endpointId silent for ${now - lastSeen}ms (> 30s). Tearing down dead connection.")
                deadEndpoints.add(endpointId)
            }
        }
        for (deadEp in deadEndpoints) {
            handleExplicitDisconnect(deadEp)
        }

        // 3. Purge expired routes and stale neighbors
        routingEngine?.purgeExpiredRoutes()
        neighborTable.purgeStalePeers(HEARTBEAT_TIMEOUT_MS, now)
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            statusText.text = text
            chatStatusText.text = text
            dashConnStatus.text = text
        }
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour in 5..11 -> "Good morning"
            hour in 12..16 -> "Good afternoon"
            hour in 17..21 -> "Good evening"
            else -> "Good night"
        }
        val name = if (myNickName.isNotEmpty()) myNickName else "Peer"
        appGreeting.text = "$timeGreeting, $name"
    }

    private fun updateSettingsUI() {
        settingsDisplayName.text = if (myNickName.isNotEmpty()) myNickName else "Peer"
        settingsAvatar.text = if (myNickName.isNotEmpty()) myNickName.take(1).uppercase(Locale.ROOT) else "N"
        val endpointSuffix = activeEndpoints.firstOrNull()?.let { " · Endpoint: $it" } ?: ""
        settingsPeerId.text = "Local NEXORA Node$endpointSuffix"
        val isLive = activeEndpoints.isNotEmpty()
        settingsStatusBadge.text = if (isLive) "● Direct Mesh Active (${activeEndpoints.size} peers)" else "● Node Online (Autonomous)"
        settingsStatusBadge.setTextColor(Color.parseColor(if (isLive) "#16A34A" else "#0284C7"))
    }

    private fun checkLocationAndRun(action: () -> Unit) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { action() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        val request = IntentSenderRequest.Builder(exception.resolution).build()
                        locationResolutionLauncher.launch(request)
                    } catch (_: Exception) {}
                }
            }
    }

    private fun showCreateAlertDialog() {
        pendingAlertAttachmentUri = null
        pendingAlertAttachmentName = null
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_alert, null)
        val editAlertDesc = dialogView.findViewById<EditText>(R.id.editAlertDesc)
        val btnAttachAlertFile = dialogView.findViewById<View>(R.id.btnAttachAlertFile)
        val textAttachedFileName = dialogView.findViewById<TextView>(R.id.textAttachedFileName)
        val btnSendAlert = dialogView.findViewById<View>(R.id.btnSendAlert)
        val btnCancelCreateAlert = dialogView.findViewById<View>(R.id.btnCancelCreateAlert)
        pendingAlertFileNameText = textAttachedFileName

        val checkAttachLocation = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkAttachLocation)
        val textAlertLocationStatus = dialogView.findViewById<TextView>(R.id.textAlertLocationStatus)
        if (myCurrentCoordinates != null) {
            textAlertLocationStatus.text = "GPS locked (${String.format(Locale.US, "%.4f, %.4f", myCurrentCoordinates!!.latitude, myCurrentCoordinates!!.longitude)})"
        } else {
            textAlertLocationStatus.text = "Acquiring GPS location..."
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnAttachAlertFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            alertFilePickerLauncher.launch(intent)
        }
        btnSendAlert.setOnClickListener {
            val desc = editAlertDesc.text.toString().trim()
            if (desc.isEmpty()) {
                Toast.makeText(this, "Please enter an alert description", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val shouldAttachGps = checkAttachLocation.isChecked
            if (shouldAttachGps) {
                lifecycleScope.launch {
                    val coords = myCurrentCoordinates ?: locationManagerHelper.getCurrentLocation()
                    if (coords == null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("GPS Unavailable")
                            .setMessage("GPS coordinates are currently unavailable. Do you want to broadcast this SOS alert without location coordinates?")
                            .setPositiveButton("Send Without GPS") { _, _ ->
                                dialog.dismiss()
                                createAndBroadcastAlert(desc, pendingAlertAttachmentUri, pendingAlertAttachmentName, attachLocation = false)
                            }
                            .setNegativeButton("Wait for GPS", null)
                            .show()
                    } else {
                        dialog.dismiss()
                        createAndBroadcastAlert(desc, pendingAlertAttachmentUri, pendingAlertAttachmentName, attachLocation = true)
                    }
                }
            } else {
                dialog.dismiss()
                createAndBroadcastAlert(desc, pendingAlertAttachmentUri, pendingAlertAttachmentName, attachLocation = false)
            }
        }
        btnCancelCreateAlert.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun createAndBroadcastAlert(
        desc: String,
        uri: Uri?,
        fileName: String?,
        attachLocation: Boolean = true
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val alertId = "alert_${System.currentTimeMillis()}"
            var localAttachmentStr: String? = null
            var wireAttachmentStr: String? = null
            if (uri != null && fileName != null) {
                val copied = FileStorageManager.copyUriToLocalStorage(this@MainActivity, uri, fileName)
                val localPath = copied?.absolutePath ?: uri.toString()
                val fileSize = copied?.length() ?: 0L
                val hash = if (copied != null) MeshBlobStore.computeSha256(copied) else ""
                if (copied != null && hash.isNotBlank()) {
                    MeshBlobStore.saveIncoming(this@MainActivity, hash, copied)
                    blobRepository?.saveIncoming(hash, copied)
                }
                localAttachmentStr = "[FILE]:$fileName|$localPath|$hash|$fileSize|SUCCESS"
                wireAttachmentStr = "[FILE]:$fileName||$hash|$fileSize|RECEIVING"
            }

            val coords = if (attachLocation) (myCurrentCoordinates ?: locationManagerHelper.getCurrentLocation()) else null
            val lat = coords?.latitude
            val lon = coords?.longitude
            val acc = coords?.accuracy
            val hasLocation = lat != null && lon != null

            Log.i(TAG, "ALERT_LOCATION_GPS: alertId=$alertId, hasLocation=$hasLocation, latitude=$lat, longitude=$lon, accuracy=$acc")

            val alertPayload = AlertPayload(
                id = alertId,
                senderId = myNickName,
                alertType = "SOS",
                title = "SOS Alert",
                message = desc,
                sentAt = System.currentTimeMillis(),
                attachmentPath = wireAttachmentStr,
                expiresAt = System.currentTimeMillis() + 86400000L,
                latitude = lat,
                longitude = lon,
                accuracy = acc
            )
            Log.i(TAG, "ALERT_LOCATION_PAYLOAD: alertId=$alertId, hasLocation=$hasLocation, latitude=$lat, longitude=$lon, accuracy=$acc")

            val newAlert = AlertEntity(
                id = 0,
                type = "SOS",
                title = "SOS Alert",
                description = desc,
                message = desc,
                timestamp = System.currentTimeMillis(),
                isRead = true,
                peerName = myNickName,
                attachmentPath = localAttachmentStr,
                alertId = alertId,
                originPeerId = myNickName,
                isCustodyActive = true,
                expiresAt = System.currentTimeMillis() + 86400000L,
                latitude = lat,
                longitude = lon,
                accuracy = acc
            )
            db.alertDao().insertAlert(newAlert)
            if (lat != null && lon != null) {
                withContext(Dispatchers.Main) {
                    offlineMapManager?.updateAlertLocation(
                        alertId = alertId,
                        title = "SOS Alert (You)",
                        latitude = lat,
                        longitude = lon,
                        distanceText = "At your current location"
                    )
                }
            }

            val alertPayloadStr = alertPayload.toWireString()
            Log.i(TAG, "ALERT_LOCATION_SERIALIZED: alertId=$alertId, hasLocation=$hasLocation, latitude=$lat, longitude=$lon, accuracy=$acc, payloadStr=$alertPayloadStr")

            val alertChatMsg = ChatMessage(myNickName, SecurityHelper.encrypt(alertPayloadStr), System.currentTimeMillis())
            val bytes = serialize(alertChatMsg)
            routingEngine?.broadcastAlert(bytes, alertId)
            Log.i(TAG, "ALERT_LOCATION_SENT: alertId=$alertId, hasLocation=$hasLocation, latitude=$lat, longitude=$lon, accuracy=$acc")

            loadAlertsFromDb()
        }
    }

    private fun openAlertThread(alert: AlertEntity) {
        val alertId = alert.alertId ?: alert.id.toString()
        openAlertThreadById(alertId)
    }

    private fun openAlertThreadById(alertId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val alert = db.alertDao().getAlertByAlertId(alertId)
                ?: db.alertDao().getAlertById(alertId.toIntOrNull() ?: -1)
                ?: return@launch
            val hasLocation = alert.latitude != null && alert.longitude != null
            Log.i(TAG, "ALERT_LOCATION_DB_READ: alertId=${alert.alertId}, hasLocation=$hasLocation, latitude=${alert.latitude}, longitude=${alert.longitude}, accuracy=${alert.accuracy}")

            if (!alert.isRead) {
                db.alertDao().updateAlert(alert.copy(isRead = true))
                loadAlertsFromDb()
            }
            withContext(Dispatchers.Main) {
                currentAlertThreadId = alert.alertId ?: alert.id.toString()
                alertThreadHeader.text = alert.title
                val isFromMe = alert.originPeerId == myNickName || alert.peerName == myNickName
                alertThreadSenderText.text = if (isFromMe) "From: You (Broadcast)" else "From: ${alert.peerName ?: "Unknown"}"
                alertThreadDescription.text = alert.body

                bindAlertAttachment(alert)
                bindAlertLocation(alert)

                layoutAlertThread.visibility = View.VISIBLE
                updateBackCallbackState()
                applyGlobalTopInsets(lastKnownStatusBarInset)
                ViewCompat.requestApplyInsets(layoutAlertThread)
            }
            val history = db.messageDao().getAlertThreadMessages(alert.alertId ?: alertId)
            withContext(Dispatchers.Main) {
                updateAlertThreadUI(history)
            }
        }
    }

    private fun bindAlertLocation(alert: AlertEntity) {
        val lat = alert.latitude
        val lon = alert.longitude
        val acc = alert.accuracy
        val hasLocation = lat != null && lon != null && (lat != 0.0 || lon != 0.0)
        Log.i(TAG, "ALERT_LOCATION_DISPLAYED: alertId=${alert.alertId}, hasLocation=$hasLocation, latitude=$lat, longitude=$lon, accuracy=$acc")

        if (hasLocation && lat != null && lon != null) {
            alertThreadLocationCard.visibility = View.VISIBLE
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(alert.timestamp))
            alertThreadLocationCoordinates.text = String.format(Locale.US, "Latitude: %.5f\nLongitude: %.5f\nTimestamp: %s", lat, lon, timeStr)

            val my = myCurrentCoordinates
            if (my != null) {
                val distMeters = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                    my.latitude,
                    my.longitude,
                    lat,
                    lon
                )
                val distStr = com.fury.peerconnect.logic.DistanceEngine.formatDistance(distMeters)
                alertThreadLocationDistance.text = "Distance: $distStr straight-line away"
            } else {
                alertThreadLocationDistance.text = "📍 Tactical Geo-Tagged Location"
            }

            btnAlertThreadViewOnMap.setOnClickListener {
                showLocationOnDashboardMap(lat, lon, alert.title)
            }
            alertThreadLocationCard.setOnClickListener {
                showLocationOnDashboardMap(lat, lon, alert.title)
            }
        } else {
            alertThreadLocationCard.visibility = View.GONE
        }
    }

    private fun bindAlertAttachment(alert: AlertEntity) {
        val rawAttachment = alert.attachmentPath
        if (rawAttachment.isNullOrBlank() || !rawAttachment.startsWith("[FILE]:")) {
            alertThreadAttachmentCard.visibility = View.GONE
            return
        }
        val clean = rawAttachment.removePrefix("[FILE]:")
        val parts = clean.split("|")
        val fileName = parts.getOrNull(0) ?: "Attachment"
        val pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val hash = parts.firstOrNull { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }
            ?: parts.getOrNull(2)?.trim()?.lowercase()
            ?: ""
        val status = parts.getOrNull(4) ?: "RECEIVING"

        alertThreadAttachmentName.text = fileName
        alertThreadAttachmentCard.visibility = View.VISIBLE

        val localFile = if (!pathOrUri.isNullOrEmpty()) File(pathOrUri) else null
        val isDirectFileAvailable = localFile != null && localFile.exists() && localFile.length() > 0L
        val isBlobStoreAvailable = hash.isNotEmpty() && MeshBlobStore.has(this, hash)

        val isAvailable = isDirectFileAvailable || isBlobStoreAvailable

        if (isAvailable) {
            // AVAILABLE: Tap to open
            alertThreadAttachmentStatus.text = "Tap to open"
            alertThreadAttachmentStatus.setTextColor(Color.parseColor("#16A34A"))
            alertThreadAttachmentCard.isClickable = true
            alertThreadAttachmentCard.setOnClickListener {
                val target = if (isDirectFileAvailable) localFile!!.absolutePath else hash
                openAttachment(fileName, target)
            }
        } else {
            val isReceiving = status == "RECEIVING" || (hash.isNotEmpty() && pendingBlobChunks.containsKey(hash))
            if (isReceiving) {
                // RECEIVING: Receiving attachment...
                alertThreadAttachmentStatus.text = "Receiving attachment..."
                alertThreadAttachmentStatus.setTextColor(Color.parseColor("#EA580C"))
                alertThreadAttachmentCard.isClickable = true
                alertThreadAttachmentCard.setOnClickListener {
                    Toast.makeText(this, "Receiving attachment from mesh...", Toast.LENGTH_SHORT).show()
                }
            } else if (status == "FAILED") {
                // FAILED: Attachment unavailable • Tap to retry
                alertThreadAttachmentStatus.text = "Attachment unavailable • Tap to retry"
                alertThreadAttachmentStatus.setTextColor(Color.parseColor("#DC2626"))
                alertThreadAttachmentCard.isClickable = true
                alertThreadAttachmentCard.setOnClickListener {
                    if (hash.isNotEmpty()) {
                        val peer = alert.peerName ?: alert.originPeerId ?: ""
                        Toast.makeText(this, "Requesting attachment retry...", Toast.LENGTH_SHORT).show()
                        alertThreadAttachmentStatus.text = "Requesting attachment..."
                        alertThreadAttachmentStatus.setTextColor(Color.parseColor("#0284C7"))
                        lifecycleScope.launch(Dispatchers.IO) {
                            blobExchange?.want(hash, peer)
                        }
                    } else {
                        Toast.makeText(this, "Attachment identifier missing", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // MISSING: Requesting attachment... • Tap to retry
                alertThreadAttachmentStatus.text = "Requesting attachment... • Tap to retry"
                alertThreadAttachmentStatus.setTextColor(Color.parseColor("#0284C7"))
                alertThreadAttachmentCard.isClickable = true
                alertThreadAttachmentCard.setOnClickListener {
                    if (hash.isNotEmpty()) {
                        val peer = alert.peerName ?: alert.originPeerId ?: ""
                        Toast.makeText(this, "Requesting attachment from mesh...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            blobExchange?.want(hash, peer)
                        }
                    } else {
                        Toast.makeText(this, "Attachment identifier missing", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Auto-request via existing BlobExchange when viewing if hash is known
            if (hash.isNotEmpty()) {
                val peer = alert.peerName ?: alert.originPeerId ?: ""
                lifecycleScope.launch(Dispatchers.IO) {
                    blobExchange?.want(hash, peer)
                }
            }
        }
    }

    private fun closeAlertThread() {
        currentAlertThreadId = null
        layoutAlertThread.visibility = View.GONE
        updateBackCallbackState()
    }

    private fun updateAlertThreadUI(history: List<MessageEntity>) {
        val chatMessages = history.map {
            ChatMessage(it.senderId, it.text, it.timestamp)
        }
        alertThreadAdapter.setMessages(chatMessages)
        if (alertThreadAdapter.itemCount > 0) {
            alertThreadRecyclerView.scrollToPosition(alertThreadAdapter.itemCount - 1)
        }
    }

    private fun sendAlertThreadReply() {
        val alertId = currentAlertThreadId ?: return
        val replyText = editAlertThreadReply.text.toString().trim()
        if (replyText.isEmpty()) return
        editAlertThreadReply.setText("")

        lifecycleScope.launch(Dispatchers.IO) {
            val alert = db.alertDao().getAlertByAlertId(alertId)
            val receiverName = alert?.peerName ?: "Broadcast"
            val msgEntity = MessageEntity(
                id = 0,
                senderId = myNickName,
                receiverId = receiverName,
                text = replyText,
                timestamp = System.currentTimeMillis(),
                isSent = true,
                deliveryStatus = "FORWARDED",
                alertId = alertId
            )
            db.messageDao().insertMessage(msgEntity)

            val chatMsg = ChatMessage(myNickName, SecurityHelper.encrypt("[ALERT_REPLY]:$alertId|$replyText"), System.currentTimeMillis())
            val bytes = serialize(chatMsg)
            routingEngine?.broadcastAlert(bytes)

            val history = db.messageDao().getAlertThreadMessages(alertId)
            withContext(Dispatchers.Main) {
                updateAlertThreadUI(history)
            }
        }
    }

    private fun loadAlertsFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val alerts = db.alertDao().getAllAlerts()
            withContext(Dispatchers.Main) {
                alertAdapter.setAlerts(alerts)
                textEmptyAlerts.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadRecentActivityFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentMessages = mutableListOf<ChatMessage>()
            val peers = db.peerDao().getAllPeers()
            for (peer in peers) {
                val history = db.messageDao().getChatHistory(myNickName, peer.name)
                val lastMsg = history.filter { it.alertId == null && !it.text.startsWith("[ALERT]:") }.lastOrNull()
                if (lastMsg != null) {
                    val preview = if (lastMsg.text.startsWith("[FILE]:") || lastMsg.text.contains("Shared a file:")) {
                        "Attachment: ${lastMsg.fileName ?: "File"}"
                    } else {
                        lastMsg.text
                    }
                    recentMessages.add(ChatMessage(peer.name, preview, lastMsg.timestamp))
                }
            }
            recentMessages.sortByDescending { it.time }
            withContext(Dispatchers.Main) {
                recentActivityAdapter.setItems(recentMessages.take(5))
                dashEmptyRecentText.visibility = if (recentMessages.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadPeersFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasPhysicalNeighbors = activeEndpoints.isNotEmpty() || (::connectionManager.isInitialized && connectionManager.activeConnectionCount > 0)
            if (!hasPhysicalNeighbors) {
                val staleOnline = db.peerDao().getAllPeers().filter { it.isOnline }
                if (staleOnline.isNotEmpty()) {
                    db.peerDao().setAllOffline()
                }
            }
            val peers = db.peerDao().getAllPeers()
            withContext(Dispatchers.Main) {
                peerAdapter.updateList(peers)
                val livePeers = peers.filter {
                    val isDirect = (it.isOnline && (activeEndpoints.contains(it.endpointId) || activeEndpoints.any { ep -> pendingConnections[ep] == it.name }))
                    val isMesh = (it.isReachable && routingEngine?.isPeerReachable(it.name) == true)
                    isDirect || isMesh
                }
                connectedPeerAdapter.setPeers(livePeers)
                dashActiveCount.text = livePeers.count { it.isOnline }.toString()
                dashKnownCount.text = peers.size.toString()
                dashOfflineCount.text = (peers.size - livePeers.size).toString()
                dashEmptyPeersText.visibility = if (livePeers.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun serialize(message: ChatMessage): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val objectStream = ObjectOutputStream(outputStream)
        objectStream.writeObject(message)
        objectStream.flush()
        return outputStream.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): ChatMessage {
        val inputStream = ByteArrayInputStream(bytes)
        val objectStream = ObjectInputStream(inputStream)
        return objectStream.readObject() as ChatMessage
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("Range")
    private fun getFileNameFromUri(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        return name
    }

    private fun sendFile(uri: Uri, fileName: String) {
        val peerName = currentChatPeerName ?: return
        val startTs = System.currentTimeMillis()
        Log.d(TAG, "TIMING_METRIC: [1] file selected: fileName=$fileName uri=$uri")
        lifecycleScope.launch(Dispatchers.IO) {
            val copied = FileStorageManager.copyUriToLocalStorage(this@MainActivity, uri, fileName)
            val localPath = copied?.absolutePath ?: uri.toString()
            val fileSize = copied?.length() ?: 0L
            val msgType = if (FileStorageManager.isImageFile(fileName)) "IMAGE" else "FILE"
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: (if (msgType == "IMAGE") "image/jpeg" else "*/*")

            val hash = if (copied != null) MeshBlobStore.computeSha256(copied) else java.util.UUID.randomUUID().toString().replace("-", "")
            if (copied != null && hash.isNotBlank()) {
                MeshBlobStore.saveIncoming(this@MainActivity, hash, copied)
                blobRepository?.saveIncoming(hash, copied)
            }
            Log.d(TAG, "TIMING_METRIC: [2] blob prepared: hash=$hash size=$fileSize bytes took=${System.currentTimeMillis() - startTs}ms")

            val endpointId = resolveTargetEndpoint(peerName)
            var payloadId: Long? = null
            if (endpointId != null) {
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val payload = Payload.fromFile(pfd)
                        payloadId = payload.id
                        Nearby.getConnectionsClient(this@MainActivity).sendPayload(endpointId, payload)
                        Log.d(TAG, "ATTACHMENT_TRANSFER_STARTED: sent Nearby file payload ${payload.id} to $endpointId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Nearby payload send failed, fallback to mesh blob exchange", e)
                }
            }

            val localBody = "[FILE]:$fileName|$localPath|$hash|$fileSize|SUCCESS|$mimeType"
            val wireBody = "[FILE]:$fileName||$hash|$fileSize|RECEIVING|$mimeType"
            val msgEntity = MessageEntity(
                id = 0,
                senderId = myNickName,
                receiverId = peerName,
                text = localBody,
                timestamp = System.currentTimeMillis(),
                isSent = true,
                deliveryStatus = "SENT",
                messageType = msgType,
                fileName = fileName,
                localPath = localPath,
                fileSize = fileSize,
                transferStatus = "SUCCESS"
            )
            val insertedId = db.messageDao().insertMessage(msgEntity)
            if (payloadId != null) {
                pendingFileMetadataMap[payloadId] = PendingFileMetadata(
                    messageId = insertedId,
                    alertId = null,
                    fileName = fileName,
                    senderName = myNickName,
                    endpointId = endpointId ?: ""
                )
            }
            val chatMsg = ChatMessage(myNickName, SecurityHelper.encrypt(wireBody), System.currentTimeMillis())
            val bytes = serialize(chatMsg)
            routingEngine?.sendDirectMessage(peerName, bytes, insertedId.toString())
            db.messageDao().updateDeliveryStatusMonotonic(insertedId.toInt(), "SENT")
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) {
                updateChatUI(history)
            }
        }
    }

    private suspend fun resolveTargetEndpoint(peerName: String): String? {
        val activeMatch = activeEndpoints.firstOrNull { pendingConnections[it] == peerName }
        if (activeMatch != null) return activeMatch

        val allPeers = db.peerDao().getAllPeers()
        val dbPeer = allPeers.firstOrNull { it.name == peerName }
        if (dbPeer != null && dbPeer.endpointId.isNotEmpty() && activeEndpoints.contains(dbPeer.endpointId)) {
            return dbPeer.endpointId
        }

        val focused = focusedChatEndpointId
        if (focused != null && activeEndpoints.contains(focused)) {
            if (pendingConnections[focused] == peerName || dbPeer?.endpointId == focused) {
                return focused
            }
        }
        return activeEndpoints.firstOrNull()
    }

    private fun openAttachment(fileName: String, pathOrUri: String) {
        if (pathOrUri.isEmpty()) {
            Toast.makeText(this, "File transfer is still in progress...", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            var targetFile: File? = null
            val uri: Uri = if (pathOrUri.startsWith("content://")) {
                Uri.parse(pathOrUri)
            } else {
                val directFile = File(pathOrUri)
                if (directFile.exists() && directFile.length() > 0L) {
                    targetFile = directFile
                } else {
                    // Check if path is a hash or in blob store
                    val hash = if (pathOrUri.length == 64 && pathOrUri.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                        pathOrUri.lowercase()
                    } else {
                        pathOrUri.substringAfterLast("/").removeSuffix(".blob").trim().lowercase()
                    }
                    if (MeshBlobStore.has(this, hash)) {
                        targetFile = MeshBlobStore.exportReadableFile(this, hash, fileName, null)
                            ?: MeshBlobStore.getFile(this, hash)
                    }
                }
                if (targetFile == null || !targetFile.exists() || targetFile.length() == 0L) {
                    Toast.makeText(this, "Attachment is not yet available. Receiving from mesh...", Toast.LENGTH_SHORT).show()
                    return
                }
                FileStorageManager.getFileUri(this, targetFile)
            }

            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mimeType == null) {
                mimeType = contentResolver.getType(uri)
            }
            if (mimeType == null) {
                mimeType = when {
                    FileStorageManager.isImageFile(fileName) -> "image/*"
                    extension == "pdf" -> "application/pdf"
                    listOf("txt", "log", "json").contains(extension) -> "text/plain"
                    listOf("mp4", "mkv", "avi", "mov").contains(extension) -> "video/*"
                    listOf("mp3", "wav", "m4a", "ogg").contains(extension) -> "audio/*"
                    else -> "*/*"
                }
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveInfoList) {
                grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Open $fileName").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No application found to open $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening attachment", e)
            Toast.makeText(this, "Unable to open file", Toast.LENGTH_SHORT).show()
        }
    }

    fun emitAlert(type: String, title: String, description: String, peerName: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val alert = AlertEntity(
                id = 0,
                type = type,
                title = title,
                description = description,
                timestamp = System.currentTimeMillis(),
                isRead = false,
                peerName = peerName
            )
            db.alertDao().insertAlert(alert)
            loadAlertsFromDb()
        }
    }

    private fun updateBackCallbackState() {
        try {
            val isAlertThreadOpen = ::layoutAlertThread.isInitialized && layoutAlertThread.visibility == View.VISIBLE
            val isChatOpen = ::layoutChat.isInitialized && layoutChat.visibility == View.VISIBLE
            val isNotDashboard = ::bottomNavigation.isInitialized && bottomNavigation.selectedItemId != R.id.nav_home
            backPressedCallback.isEnabled = isAlertThreadOpen || isChatOpen || isNotDashboard
        } catch (e: Exception) {
            Log.e(TAG, "Error updating back callback state", e)
        }
    }

    private fun recordInboundMessage(key: String): Boolean {
        synchronized(processedInboundMessages) {
            if (processedInboundMessages.contains(key)) {
                return false
            }
            if (processedInboundMessages.size >= 1000) {
                val first = processedInboundMessages.iterator().next()
                processedInboundMessages.remove(first)
            }
            processedInboundMessages.add(key)
            return true
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MESSAGES_ID,
                "Nexora Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming peer messages and alerts"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun notifyIncomingMessage(
        senderName: String,
        messageBody: String,
        timestamp: Long,
        messageType: String = "TEXT",
        fileName: String? = null
    ) {
        val isChatOpen = (layoutChat.visibility == View.VISIBLE)
        val shouldNotify = shouldNotifyForInboundMessage(
            isAppResumed = isActivityResumed,
            isChatOpen = isChatOpen,
            currentChatPeerName = currentChatPeerName,
            senderName = senderName,
            myNickName = myNickName,
            isDuplicate = false,
            isInternalProtocol = false,
            hasNotificationPermission = hasNotificationPermission()
        )
        if (!shouldNotify) return

        val preview = buildSafeNotificationPreview(messageBody, messageType, fileName)
        val notificationId = getNotificationIdForPeer(senderName)
        val unreadCount = (unreadMessageCounts[senderName] ?: 0) + 1
        unreadMessageCounts[senderName] = unreadCount

        createNotificationChannelIfNeeded()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_PEER, senderName)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val summaryText = if (unreadCount > 1) "$unreadCount new messages" else null

        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGES_ID)
            .setSmallIcon(R.drawable.ic_nav_messages)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setSubText(summaryText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setWhen(if (timestamp > 0L) timestamp else System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification for $senderName", e)
        }
    }

    private fun cancelNotificationForPeer(peerName: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notificationId = getNotificationIdForPeer(peerName)
            notificationManager?.cancel(notificationId)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling notification for $peerName", e)
        }
    }

    private fun handleChatIntent(intent: Intent?) {
        val targetPeer = parsePeerFromIntent(intent)
        if (!targetPeer.isNullOrEmpty()) {
            intent?.removeExtra(EXTRA_OPEN_CHAT_PEER)
            openChat(targetPeer, null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChatIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        offlineMapManager?.onStart()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        offlineMapManager?.onResume()
        registerP2pReceiver()
        handleChatIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        offlineMapManager?.onPause()
        p2pReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            p2pReceiver = null
        }
    }

    override fun onStop() {
        super.onStop()
        offlineMapManager?.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        offlineMapManager?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        offlineMapManager?.onSaveInstanceState(outState)
    }

    // =========================================================================
    // OFFLINE MAP & LOCATION ENGINE INTEGRATION (Phases 1 - 8)
    // =========================================================================

    private fun startGPSUpdates() {
        lifecycleScope.launch {
            try {
                // Get initial one-time lock
                val initial = locationManagerHelper.getCurrentLocation()
                if (initial != null) {
                    onGPSCoordinatesAcquired(initial)
                }

                // Subscribe to continuous offline GPS flow (throttled 20s or 10m)
                locationManagerHelper.requestLocationUpdates(intervalMillis = 20_000L, minDistanceMeters = 10f)
                    .collect { coords ->
                        onGPSCoordinatesAcquired(coords)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "GPS updates initialization error", e)
            }
        }
    }

    private fun onGPSCoordinatesAcquired(coords: com.fury.peerconnect.logic.LocationManagerHelper.GeoCoordinates) {
        myCurrentCoordinates = coords
        runOnUiThread {
            dashMapCoordinatesText.text = "GPS: ${String.format(Locale.US, "%.5f, %.5f", coords.latitude, coords.longitude)}"
            offlineMapManager?.updateMyGPSLocation(coords.latitude, coords.longitude, animateCamera = false)
            mapStatusText.text = "📍 Offline GPS active (±${coords.accuracy.toInt()}m) • Mesh tracking ready"
        }
    }

    private fun showLocationOnDashboardMap(lat: Double, lon: Double, title: String) {
        runOnUiThread {
            layoutChat.visibility = View.GONE
            layoutAlertThread.visibility = View.GONE
            showTab(layoutDashboard)
            bottomNavigation.selectedItemId = R.id.nav_home
            updateBackCallbackState()

            offlineMapManager?.focusOnLocation(lat, lon, includeMyPosition = true)

            val my = myCurrentCoordinates
            if (my != null) {
                val distMeters = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                    my.latitude,
                    my.longitude,
                    lat,
                    lon
                )
                val distStr = com.fury.peerconnect.logic.DistanceEngine.formatDistance(distMeters)
                mapDistanceMeasureText.text = "Distance: $distStr"
                mapStatusText.text = "📍 Vector to '$title': $distStr straight-line"
            } else {
                mapDistanceMeasureText.text = ""
                mapStatusText.text = "📍 Viewing location: $title"
            }
        }
    }

    private fun showChatAttachmentMenu() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_chat_attachment, null)
        bottomSheet.setContentView(view)

        val actionGallery = view.findViewById<View>(R.id.actionAttachGallery)
        val actionDocument = view.findViewById<View>(R.id.actionAttachDocument)
        val actionLocation = view.findViewById<View>(R.id.actionAttachLocation)
        val actionLiveLocation = view.findViewById<View>(R.id.actionAttachLiveLocation)
        val actionContact = view.findViewById<View>(R.id.actionAttachContact)
        val actionCamera = view.findViewById<View>(R.id.actionAttachCamera)
        val textLiveLocationLabel = view.findViewById<TextView>(R.id.textLiveLocationLabel)
        val containerLiveLocationIcon = view.findViewById<FrameLayout>(R.id.containerLiveLocationIcon)

        // Contact is hidden because no contact sharing protocol is implemented
        actionContact?.visibility = View.GONE

        val isLiveSharing = liveLocationJob != null
        textLiveLocationLabel.text = if (isLiveSharing) "Stop Live" else "Live Location"
        if (isLiveSharing) {
            containerLiveLocationIcon.setBackgroundColor(Color.parseColor("#EF4444"))
        }

        fun animateSelectionAndRun(itemView: View, action: () -> Unit) {
            itemView.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(90)
                .withEndAction {
                    itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(70)
                        .withEndAction {
                            bottomSheet.dismiss()
                            action()
                        }
                }
        }

        actionGallery.setOnClickListener {
            animateSelectionAndRun(actionGallery) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                filePickerLauncher.launch(intent)
            }
        }

        actionDocument.setOnClickListener {
            animateSelectionAndRun(actionDocument) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                filePickerLauncher.launch(intent)
            }
        }

        actionLocation.setOnClickListener {
            animateSelectionAndRun(actionLocation) {
                sendCurrentLocationMessage()
            }
        }

        actionLiveLocation.setOnClickListener {
            animateSelectionAndRun(actionLiveLocation) {
                if (liveLocationJob != null) {
                    stopLiveLocationSharing(notifyPeer = true)
                } else {
                    showLiveLocationDurationDialog()
                }
            }
        }

        actionCamera?.setOnClickListener {
            animateSelectionAndRun(actionCamera) {
                launchCameraCapture()
            }
        }

        bottomSheet.show()
    }

    private fun launchCameraCapture() {
        try {
            val photoFile = File(cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            photoFile.createNewFile()
            val photoUri = FileProvider.getUriForFile(
                this,
                "com.fury.peerconnect.fileprovider",
                photoFile
            )
            pendingCameraCaptureUri = photoUri
            cameraCaptureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching camera capture", e)
            Toast.makeText(this, "Unable to launch camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAttachmentSendPreview(uri: Uri, fileName: String, isImage: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_attachment_preview, null)
        val textTitle = dialogView.findViewById<TextView>(R.id.textPreviewTitle)
        val textFileName = dialogView.findViewById<TextView>(R.id.textPreviewFileName)
        val textFileSize = dialogView.findViewById<TextView>(R.id.textPreviewFileSize)
        val imageThumb = dialogView.findViewById<ImageView>(R.id.imagePreviewThumbnail)
        val iconDoc = dialogView.findViewById<ImageView>(R.id.iconFilePreview)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelPreview)
        val btnSend = dialogView.findViewById<View>(R.id.btnConfirmSend)

        textTitle.text = if (isImage) "Send Image" else "Send File"
        textFileName.text = fileName

        var sizeBytes = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}
        textFileSize.text = if (sizeBytes > 0) formatFileSize(sizeBytes) else "Ready to transmit"

        if (isImage) {
            imageThumb.visibility = View.VISIBLE
            iconDoc.visibility = View.GONE
            try {
                imageThumb.setImageURI(uri)
            } catch (_: Exception) {
                imageThumb.visibility = View.GONE
                iconDoc.visibility = View.VISIBLE
            }
        } else {
            imageThumb.visibility = View.GONE
            iconDoc.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSend.setOnClickListener {
            dialog.dismiss()
            try {
                sendFile(uri, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending file via mesh pipeline", e)
            }
        }

        dialog.show()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun sendCurrentLocationMessage() {
        val peerName = currentChatPeerName ?: return
        lifecycleScope.launch {
            var coords = myCurrentCoordinates
            if (coords == null) {
                Toast.makeText(this@MainActivity, "Acquiring GPS fix...", Toast.LENGTH_SHORT).show()
                coords = locationManagerHelper.getCurrentLocation()
            }
            if (coords == null) {
                Toast.makeText(this@MainActivity, "Unable to get GPS coordinates. Ensure location is enabled.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val payloadStr = "[LOC]:${coords.latitude}|${coords.longitude}|Current Location"
            sendMessage(payloadStr)
        }
    }

    private fun showLiveLocationDurationDialog() {
        val durations = arrayOf("15 Minutes", "30 Minutes", "1 Hour")
        val millis = longArrayOf(15 * 60 * 1000L, 30 * 60 * 1000L, 60 * 60 * 1000L)

        AlertDialog.Builder(this)
            .setTitle("Share Live Location")
            .setItems(durations) { dialog, which ->
                dialog.dismiss()
                startLiveLocationSharing(millis[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLiveLocationSharing(durationMillis: Long) {
        val peerName = currentChatPeerName ?: return
        stopLiveLocationSharing(notifyPeer = false)

        activeLiveSharingDurationMillis = durationMillis
        liveSharingStartTime = System.currentTimeMillis()

        Toast.makeText(this, "Live location sharing started", Toast.LENGTH_SHORT).show()

        liveLocationJob = lifecycleScope.launch {
            var lastSentLat = 0.0
            var lastSentLon = 0.0

            while (isActive) {
                val elapsed = System.currentTimeMillis() - liveSharingStartTime
                if (elapsed >= activeLiveSharingDurationMillis) {
                    stopLiveLocationSharing(notifyPeer = true)
                    break
                }

                val coords = myCurrentCoordinates ?: locationManagerHelper.getCurrentLocation()
                if (coords != null) {
                    val distMoved = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                        lastSentLat,
                        lastSentLon,
                        coords.latitude,
                        coords.longitude
                    )
                    // Send every loop or if significant movement (> 10m)
                    if (distMoved > 10.0 || lastSentLat == 0.0) {
                        lastSentLat = coords.latitude
                        lastSentLon = coords.longitude
                        val payloadStr = "[LOC_LIVE]:${coords.latitude}|${coords.longitude}|Live GPS"
                        sendMessage(payloadStr)
                    }
                }
                delay(20_000L) // Throttled update interval
            }
        }
    }

    private fun stopLiveLocationSharing(notifyPeer: Boolean) {
        liveLocationJob?.cancel()
        liveLocationJob = null
        if (notifyPeer && currentChatPeerName != null) {
            sendMessage("🔴 Live location sharing ended")
        }
    }

    private fun showFoundPersonCoordination(alert: AlertEntity) {
        FoundPersonDialog.show(this, alert) { shareLocation ->
            val origin = alert.originPeerId ?: alert.peerName ?: return@show
            lifecycleScope.launch(Dispatchers.IO) {
                val coords = myCurrentCoordinates
                val replyText = if (shareLocation && coords != null) {
                    "[FOUND_PERSON]:${alert.alertId ?: alert.id}|FOUND|${coords.latitude}|${coords.longitude}"
                } else {
                    "[FOUND_PERSON]:${alert.alertId ?: alert.id}|FOUND|0|0"
                }

                val isReachable = routingEngine?.isPeerReachable(origin) == true
                val msgEntity = MessageEntity(
                    id = 0,
                    senderId = myNickName,
                    receiverId = origin,
                    text = "I found the person from alert '${alert.title}'.",
                    timestamp = System.currentTimeMillis(),
                    isSent = isReachable,
                    deliveryStatus = "PENDING"
                )
                val insertedId = db.messageDao().insertMessage(msgEntity)
                try {
                    val encrypted = SecurityHelper.encrypt(replyText)
                    val chatMsg = ChatMessage(myNickName, encrypted, System.currentTimeMillis())
                    routingEngine?.sendDirectMessage(origin, serialize(chatMsg), insertedId.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sending found person handshake", e)
                }

                if (shareLocation && coords != null) {
                    val locMsg = MessageEntity(
                        id = 0,
                        senderId = myNickName,
                        receiverId = origin,
                        text = "[LOC]:${coords.latitude}|${coords.longitude}|Location of Found Person",
                        timestamp = System.currentTimeMillis() + 10,
                        isSent = isReachable,
                        deliveryStatus = "PENDING",
                        messageType = "LOCATION"
                    )
                    val locId = db.messageDao().insertMessage(locMsg)
                    try {
                        val encLoc = SecurityHelper.encrypt("[LOC]:${coords.latitude}|${coords.longitude}|Location of Found Person")
                        val chatLoc = ChatMessage(myNickName, encLoc, System.currentTimeMillis())
                        routingEngine?.sendDirectMessage(origin, serialize(chatLoc), locId.toString())
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Notification sent to alert origin ($origin)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveLocationSharing(notifyPeer = false)
        offlineMapManager?.onDestroy()
        resetRadio()
        wifiP2pMeshManager?.shutdown()
        p2pReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            p2pReceiver = null
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT_PEER = "com.fury.peerconnect.EXTRA_OPEN_CHAT_PEER"
        const val CHANNEL_MESSAGES_ID = "nexora_chat_messages"

        fun shouldNotifyForInboundMessage(
            isAppResumed: Boolean,
            isChatOpen: Boolean,
            currentChatPeerName: String?,
            senderName: String,
            myNickName: String,
            isDuplicate: Boolean,
            isInternalProtocol: Boolean,
            hasNotificationPermission: Boolean
        ): Boolean {
            if (!hasNotificationPermission) return false
            if (isDuplicate) return false
            if (isInternalProtocol) return false
            if (senderName.isBlank() || senderName == myNickName) return false
            val isActivelyViewing = isAppResumed && isChatOpen && (currentChatPeerName == senderName)
            if (isActivelyViewing) return false
            return true
        }

        fun buildSafeNotificationPreview(
            text: String,
            messageType: String = "TEXT",
            fileName: String? = null
        ): String {
            return when (messageType) {
                "IMAGE" -> if (!fileName.isNullOrBlank()) "📷 $fileName" else "📷 Photo"
                "FILE" -> if (!fileName.isNullOrBlank()) "📄 $fileName" else "📄 Attachment"
                else -> {
                    if (text.startsWith("[FILE]:") || text.startsWith("Shared a file:")) {
                        val fn = fileName ?: text.substringAfter("[FILE]:").split("|").firstOrNull() ?: "Attachment"
                        "📄 $fn"
                    } else if (text.startsWith("[FILE_DATA]:")) {
                        val fn = fileName ?: text.substringAfter("[FILE_DATA]:").split("|").firstOrNull() ?: "Attachment"
                        "📄 $fn"
                    } else {
                        text
                    }
                }
            }
        }

        fun getNotificationIdForPeer(peerName: String): Int {
            return (peerName.hashCode() and 0x7FFFFFFF)
        }

        fun parsePeerFromIntent(intent: Intent?): String? {
            return (intent?.getStringExtra(EXTRA_OPEN_CHAT_PEER)
                ?: intent?.getStringExtra("EXTRA_PEER_NAME")
                ?: intent?.getStringExtra("peerName"))?.takeIf { it.isNotBlank() }
        }
    }
}
