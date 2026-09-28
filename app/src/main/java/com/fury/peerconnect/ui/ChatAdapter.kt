package com.fury.peerconnect.ui

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.ChatMessage
import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.logic.FileStorageManager
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val myNickName: String = "",
    private val onAttachmentClick: ((String, String) -> Unit)? = null,
    private val onLocationClick: ((Double, Double, String) -> Unit)? = null,
    private val myLocationProvider: (() -> Pair<Double, Double>?)? = null,
    var onAttachmentRetry: ((messageId: Long, hash: String, fileName: String, senderPeer: String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: ArrayList<ChatItem> = ArrayList()

    companion object {
        const val TYPE_TEXT_ME: Int = 1
        const val TYPE_TEXT_OTHER: Int = 2
        const val TYPE_FILE_ME: Int = 3
        const val TYPE_FILE_OTHER: Int = 4
        const val TYPE_IMAGE_ME: Int = 5
        const val TYPE_IMAGE_OTHER: Int = 6
        const val TYPE_DATE_SEPARATOR: Int = 7
        const val TYPE_LOCATION_ME: Int = 8
        const val TYPE_LOCATION_OTHER: Int = 9

        fun formatMessageTime(timeMillis: Long, locale: Locale = Locale.getDefault()): String {
            val validTime = if (timeMillis > 0L) timeMillis else System.currentTimeMillis()
            val sdf = SimpleDateFormat("hh:mm a", locale)
            return sdf.format(Date(validTime))
        }

        fun getStatusRank(status: String): Int {
            return when (status.uppercase()) {
                "PENDING", "SENDING" -> 0
                "SENT", "FORWARDED" -> 1
                "DELIVERED" -> 2
                "READ" -> 3
                else -> 1
            }
        }

        fun bindStatus(imageStatus: ImageView?, isMe: Boolean, deliveryStatus: String, alertId: String?) {
            if (imageStatus == null) return
            if (!isMe) {
                imageStatus.visibility = View.GONE
                return
            }
            imageStatus.visibility = View.VISIBLE
            val context = imageStatus.context
            val defaultColor = ContextCompat.getColor(context, R.color.nexora_tick_default)
            val readColor = ContextCompat.getColor(context, R.color.nexora_tick_read)

            if (alertId != null) {
                // Alert thread replies / broadcasts: keep UI honest, single tick only
                val isPending = deliveryStatus.equals("PENDING", ignoreCase = true) || deliveryStatus.equals("SENDING", ignoreCase = true)
                imageStatus.setImageResource(if (isPending) R.drawable.ic_msg_clock else R.drawable.ic_msg_single_tick)
                imageStatus.setColorFilter(defaultColor)
                return
            }

            when (deliveryStatus.uppercase()) {
                "PENDING", "SENDING" -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_clock)
                    imageStatus.setColorFilter(defaultColor)
                }
                "SENT", "FORWARDED" -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_single_tick)
                    imageStatus.setColorFilter(defaultColor)
                }
                "DELIVERED" -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_double_tick)
                    imageStatus.setColorFilter(defaultColor)
                }
                "READ" -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_double_tick)
                    imageStatus.setColorFilter(readColor)
                }
                "FAILED" -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_failed)
                    imageStatus.setColorFilter(ContextCompat.getColor(context, R.color.nexora_danger))
                }
                else -> {
                    imageStatus.setImageResource(R.drawable.ic_msg_single_tick)
                    imageStatus.setColorFilter(defaultColor)
                }
            }
        }

        fun loadSampledBitmap(filePath: String, reqWidth: Int = 400, reqHeight: Int = 400): Bitmap? {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            var inSampleSize = 1
            val height = options.outHeight
            val width = options.outWidth
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            return BitmapFactory.decodeFile(filePath, decodeOptions)
        }
    }

    fun setEntities(history: List<MessageEntity>) {
        val grouped = ChatDateFormatter.groupMessagesWithDateSeparators(history, myNickName)
        items.clear()
        items.addAll(grouped)
        try {
            notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    fun setMessages(history: List<ChatMessage>) {
        val fakeEntities = history.mapIndexed { index, msg ->
            MessageEntity(
                id = index + 1,
                senderId = msg.senderName,
                receiverId = "",
                text = msg.messageBody,
                timestamp = msg.time,
                isSent = true,
                deliveryStatus = "SENT"
            )
        }
        setEntities(fakeEntities)
    }

    fun addMessage(msg: ChatMessage) {
        val lastTimestamp = items.lastOrNull { it is ChatItem.Message }?.let { (it as ChatItem.Message).time }
        val msgTime = if (msg.time > 0L) msg.time else System.currentTimeMillis()
        if (lastTimestamp == null || !ChatDateFormatter.isSameCalendarDay(lastTimestamp, msgTime)) {
            val dateText = ChatDateFormatter.formatDateSeparator(msgTime)
            items.add(ChatItem.DateSeparator(dateText, msgTime))
            try {
                notifyItemInserted(items.size - 1)
            } catch (_: Exception) {}
        }
        items.add(
            ChatItem.Message(
                id = 0,
                senderName = msg.senderName,
                messageBody = msg.messageBody,
                time = msgTime,
                isMe = msg.senderName == myNickName,
                deliveryStatus = "SENT"
            )
        )
        try {
            notifyItemInserted(items.size - 1)
        } catch (_: Exception) {}
    }

    fun updateMessageStatus(messageId: Int, newStatus: String): Boolean {
        val index = items.indexOfFirst { it is ChatItem.Message && it.id == messageId }
        if (index != -1) {
            val current = items[index] as ChatItem.Message
            // Enforce monotonic state progression: never downgrade (e.g. READ to DELIVERED)
            if (getStatusRank(newStatus) < getStatusRank(current.deliveryStatus)) {
                return false
            }
            items[index] = current.copy(deliveryStatus = newStatus)
            try {
                notifyItemChanged(index)
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    fun updateTransferProgress(messageId: Int, percent: Int): Boolean {
        val index = items.indexOfFirst { it is ChatItem.Message && it.id == messageId }
        if (index != -1) {
            val current = items[index] as ChatItem.Message
            items[index] = current.copy(transferProgress = percent)
            try {
                notifyItemChanged(index)
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    fun updateAttachmentStatus(messageId: Long, newText: String, localPath: String, status: String): Boolean {
        val index = items.indexOfFirst { it is ChatItem.Message && it.id.toLong() == messageId }
        if (index != -1) {
            val current = items[index] as ChatItem.Message
            val newProgress = if (status == "SUCCESS") 100 else current.transferProgress
            items[index] = current.copy(
                messageBody = newText,
                localPath = localPath,
                transferStatus = status,
                transferProgress = newProgress
            )
            try {
                notifyItemChanged(index)
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    fun hasMessage(messageId: Int): Boolean {
        return items.any { it is ChatItem.Message && it.id == messageId }
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        val item = items[position]
        if (item is ChatItem.DateSeparator) {
            return TYPE_DATE_SEPARATOR
        }
        val msg = item as ChatItem.Message
        val isMe = msg.isMe
        val body = msg.messageBody
        val isLoc = body.startsWith("[LOC]:") || body.startsWith("[LOC_LIVE]:")
        if (isLoc) {
            return if (isMe) TYPE_LOCATION_ME else TYPE_LOCATION_OTHER
        }

        val isFile = body.startsWith("[FILE]:") || body.contains("Shared a file:")
        val cleanBody = when {
            body.startsWith("[FILE]:") -> body.removePrefix("[FILE]:")
            body.startsWith("📄 Shared a file: ") -> body.removePrefix("📄 Shared a file: ")
            body.startsWith("Shared a file: ") -> body.removePrefix("Shared a file: ")
            body.contains("Shared a file: ") -> body.substringAfter("Shared a file: ")
            else -> ""
        }
        val fileName = cleanBody.split("|").firstOrNull() ?: ""
        val isImage = isFile && FileStorageManager.isImageFile(fileName)
        return when {
            isImage -> if (isMe) TYPE_IMAGE_ME else TYPE_IMAGE_OTHER
            isFile -> if (isMe) TYPE_FILE_ME else TYPE_FILE_OTHER
            else -> if (isMe) TYPE_TEXT_ME else TYPE_TEXT_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DATE_SEPARATOR -> DateSeparatorViewHolder(inflater.inflate(R.layout.item_chat_date_separator, parent, false))
            TYPE_TEXT_ME -> TextViewHolder(inflater.inflate(R.layout.item_message_me, parent, false))
            TYPE_TEXT_OTHER -> TextViewHolder(inflater.inflate(R.layout.item_message_other, parent, false))
            TYPE_FILE_ME -> FileViewHolder(inflater.inflate(R.layout.item_message_file_me, parent, false), onAttachmentClick, onAttachmentRetry, true)
            TYPE_FILE_OTHER -> FileViewHolder(inflater.inflate(R.layout.item_message_file_other, parent, false), onAttachmentClick, onAttachmentRetry, false)
            TYPE_IMAGE_ME -> ImageViewHolder(inflater.inflate(R.layout.item_message_image_me, parent, false), onAttachmentClick, onAttachmentRetry, true)
            TYPE_IMAGE_OTHER -> ImageViewHolder(inflater.inflate(R.layout.item_message_image_other, parent, false), onAttachmentClick, onAttachmentRetry, false)
            TYPE_LOCATION_ME -> LocationViewHolder(inflater.inflate(R.layout.item_message_location_me, parent, false), onLocationClick, myLocationProvider, true)
            TYPE_LOCATION_OTHER -> LocationViewHolder(inflater.inflate(R.layout.item_message_location_other, parent, false), onLocationClick, myLocationProvider, false)
            else -> TextViewHolder(inflater.inflate(R.layout.item_message_me, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is DateSeparatorViewHolder -> holder.bind(item as ChatItem.DateSeparator)
            is TextViewHolder -> holder.bind(item as ChatItem.Message)
            is ImageViewHolder -> holder.bind(item as ChatItem.Message)
            is FileViewHolder -> holder.bind(item as ChatItem.Message)
            is LocationViewHolder -> holder.bind(item as ChatItem.Message)
        }
    }

    class DateSeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textDateSeparator: TextView = itemView.findViewById(R.id.textDateSeparator)

        fun bind(item: ChatItem.DateSeparator) {
            textDateSeparator.text = item.dateText
        }
    }

    class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textMessage: TextView = itemView.findViewById(R.id.textMessage)
        val textSender: TextView = itemView.findViewById(R.id.textSender)
        val textTime: TextView? = itemView.findViewById(R.id.textTime)
        val imageStatus: ImageView? = itemView.findViewById(R.id.imageStatus)

        fun bind(msg: ChatItem.Message) {
            textMessage.text = msg.messageBody
            textSender.text = msg.senderName
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, msg.isMe, msg.deliveryStatus, msg.alertId)
        }
    }

    class ImageViewHolder(
        itemView: View,
        private val onAttachmentClick: ((String, String) -> Unit)?,
        private val onAttachmentRetry: ((Long, String, String, String) -> Unit)?,
        private val isMe: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        val textFileName: TextView = itemView.findViewById(R.id.textFileName)
        val textSender: TextView = itemView.findViewById(R.id.textSender)
        val textTime: TextView? = itemView.findViewById(R.id.textTime)
        val imagePreview: ImageView = itemView.findViewById(R.id.imagePreview)
        val imageStatus: ImageView? = itemView.findViewById(R.id.imageStatus)

        // New progress UI views (may be null on old layouts for safety)
        val transferPlaceholder: FrameLayout? = itemView.findViewById(R.id.transferPlaceholder)
        val transferProgressIndicator: CircularProgressIndicator? = itemView.findViewById(R.id.transferProgressIndicator)
        val transferSpinner: CircularProgressIndicator? = itemView.findViewById(R.id.transferSpinner)
        val transferProgressText: TextView? = itemView.findViewById(R.id.transferProgressText)

        fun bind(msg: ChatItem.Message) {
            val body = msg.messageBody
            val cleanBody = when {
                body.startsWith("[FILE]:") -> body.removePrefix("[FILE]:")
                body.startsWith("📄 Shared a file: ") -> body.removePrefix("📄 Shared a file: ")
                body.startsWith("Shared a file: ") -> body.removePrefix("Shared a file: ")
                body.contains("Shared a file: ") -> body.substringAfter("Shared a file: ")
                else -> body
            }
            val parts = cleanBody.split("|")
            val fileName = parts.getOrNull(0) ?: "image.jpg"
            var pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: msg.localPath?.takeIf { it.isNotEmpty() }
            val hash = parts.getOrNull(2)?.trim()?.lowercase() ?: ""
            var status = parts.getOrNull(4) ?: if (pathOrUri != null) "SUCCESS" else msg.transferStatus
            val attachmentKey = parts.getOrNull(6)?.takeIf { it.isNotBlank() && it != "null" }

            val context = itemView.context
            if (hash.isNotBlank() && com.fury.peerconnect.logic.MeshBlobStore.has(context, hash)) {
                status = "SUCCESS"
                if (pathOrUri == null || !File(pathOrUri).exists()) {
                    val exported = com.fury.peerconnect.logic.MeshBlobStore.exportReadableFile(context, hash, fileName, attachmentKey)
                    pathOrUri = exported?.absolutePath ?: com.fury.peerconnect.logic.MeshBlobStore.getFile(context, hash)?.absolutePath
                }
            } else if (pathOrUri == null || !File(pathOrUri).exists()) {
                val candidate = com.fury.peerconnect.logic.FileStorageManager.getStorageDir(context).listFiles()
                    ?.firstOrNull { it.name.endsWith(fileName) && it.length() > 0L }
                if (candidate != null && (hash.isBlank() || com.fury.peerconnect.logic.MeshBlobStore.computeSha256(candidate).equals(hash, ignoreCase = true))) {
                    pathOrUri = candidate.absolutePath
                    status = "SUCCESS"
                }
            }

            textFileName.text = fileName
            val sender = if (isMe) "You" else msg.senderName
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, isMe, msg.deliveryStatus, msg.alertId)

            val path = pathOrUri
            val hasFile = !path.isNullOrEmpty() &&
                    (path.startsWith("content://") || File(path).let { it.exists() && it.length() > 0L })

            if (hasFile && path != null) {
                // ── Image is available: reveal with crossfade ──────────────────
                showImageWithCrossfade(path)
                textSender.text = "$sender • Tap to view"
                itemView.setOnClickListener {
                    onAttachmentClick?.invoke(fileName, path)
                }
            } else {
                // ── Image is still transferring or failed ──────────────────────
                imagePreview.visibility = View.GONE
                transferPlaceholder?.visibility = View.VISIBLE

                val progress = msg.transferProgress
                if (status == "FAILED") {
                    transferSpinner?.visibility = View.GONE
                    transferProgressIndicator?.visibility = View.INVISIBLE
                    transferProgressText?.visibility = View.GONE
                    textSender.text = "$sender • Transfer failed • Tap to retry"
                    itemView.setOnClickListener {
                        if (onAttachmentRetry != null && hash.isNotBlank()) {
                            onAttachmentRetry.invoke(msg.id.toLong(), hash, fileName, msg.senderName)
                        } else {
                            onAttachmentClick?.invoke(fileName, path ?: "")
                        }
                    }
                } else if (progress > 0) {
                    transferSpinner?.visibility = View.GONE
                    transferProgressIndicator?.let { ind ->
                        ind.visibility = View.VISIBLE
                        ind.isIndeterminate = false
                        val anim = ObjectAnimator.ofInt(ind, "progress", ind.progress, progress).apply {
                            duration = 300
                            interpolator = DecelerateInterpolator()
                        }
                        anim.start()
                    }
                    transferProgressText?.let {
                        it.visibility = View.VISIBLE
                        it.text = "$progress%"
                    }
                    val statusText = if (isMe) "$sender • Sending ($progress%)…" else "$sender • Receiving ($progress%)…"
                    textSender.text = statusText
                    itemView.setOnClickListener {
                        onAttachmentClick?.invoke(fileName, path ?: "")
                    }
                } else {
                    // Indeterminate
                    transferSpinner?.visibility = View.VISIBLE
                    transferProgressIndicator?.visibility = View.INVISIBLE
                    transferProgressText?.visibility = View.GONE
                    val statusText = if (isMe) "$sender • Sending…" else "$sender • Receiving…"
                    textSender.text = statusText
                    itemView.setOnClickListener {
                        if (!isMe && onAttachmentRetry != null && hash.isNotBlank()) {
                            onAttachmentRetry.invoke(msg.id.toLong(), hash, fileName, msg.senderName)
                        } else {
                            onAttachmentClick?.invoke(fileName, path ?: "")
                        }
                    }
                }
            }
        }

        private fun showImageWithCrossfade(pathOrUri: String) {
            val bmp: Bitmap? = if (pathOrUri.startsWith("content://")) {
                null // URI-based — set via setImageURI below
            } else {
                loadSampledBitmap(pathOrUri, 400, 300)
            }

            // Prepare final image (hidden at alpha=0)
            if (bmp != null) {
                imagePreview.setImageBitmap(bmp)
            } else {
                imagePreview.setImageURI(Uri.parse(pathOrUri))
            }

            // If placeholder is showing, crossfade
            if (transferPlaceholder?.visibility == View.VISIBLE) {
                imagePreview.alpha = 0f
                imagePreview.visibility = View.VISIBLE
                imagePreview.animate()
                    .alpha(1f)
                    .setDuration(280)
                    .withEndAction {
                        transferPlaceholder.visibility = View.GONE
                    }
                    .start()
            } else {
                imagePreview.visibility = View.VISIBLE
                imagePreview.alpha = 1f
                transferPlaceholder?.visibility = View.GONE
            }
        }
    }

    class FileViewHolder(
        itemView: View,
        private val onAttachmentClick: ((String, String) -> Unit)?,
        private val onAttachmentRetry: ((Long, String, String, String) -> Unit)?,
        private val isMe: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        val textFileName: TextView = itemView.findViewById(R.id.textFileName)
        val textSender: TextView = itemView.findViewById(R.id.textSender)
        val textFileMeta: TextView = itemView.findViewById(R.id.textFileMeta)
        val textTime: TextView? = itemView.findViewById(R.id.textTime)
        val imageStatus: ImageView? = itemView.findViewById(R.id.imageStatus)

        fun bind(msg: ChatItem.Message) {
            val body = msg.messageBody
            val cleanBody = when {
                body.startsWith("[FILE]:") -> body.removePrefix("[FILE]:")
                body.startsWith("📄 Shared a file: ") -> body.removePrefix("📄 Shared a file: ")
                body.startsWith("Shared a file: ") -> body.removePrefix("Shared a file: ")
                body.contains("Shared a file: ") -> body.substringAfter("Shared a file: ")
                else -> body
            }
            val parts = cleanBody.split("|")
            val fileName = parts.getOrNull(0) ?: "attachment"
            var pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: msg.localPath?.takeIf { it.isNotEmpty() }
            val hash = parts.getOrNull(2)?.trim()?.lowercase() ?: ""
            val sizeStr = parts.getOrNull(3)
            val fileSize = sizeStr?.toLongOrNull() ?: 0L
            var status = parts.getOrNull(4) ?: if (pathOrUri != null) "SUCCESS" else msg.transferStatus
            val attachmentKey = parts.getOrNull(6)?.takeIf { it.isNotBlank() && it != "null" }

            val context = itemView.context
            if (hash.isNotBlank() && com.fury.peerconnect.logic.MeshBlobStore.has(context, hash)) {
                status = "SUCCESS"
                if (pathOrUri == null || !File(pathOrUri).exists()) {
                    val exported = com.fury.peerconnect.logic.MeshBlobStore.exportReadableFile(context, hash, fileName, attachmentKey)
                    pathOrUri = exported?.absolutePath ?: com.fury.peerconnect.logic.MeshBlobStore.getFile(context, hash)?.absolutePath
                }
            } else if (pathOrUri == null || !File(pathOrUri).exists()) {
                val candidate = com.fury.peerconnect.logic.FileStorageManager.getStorageDir(context).listFiles()
                    ?.firstOrNull { it.name.endsWith(fileName) && it.length() > 0L }
                if (candidate != null && (hash.isBlank() || com.fury.peerconnect.logic.MeshBlobStore.computeSha256(candidate).equals(hash, ignoreCase = true))) {
                    pathOrUri = candidate.absolutePath
                    status = "SUCCESS"
                }
            }

            textFileName.text = fileName
            textSender.text = if (isMe) "You" else msg.senderName
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, isMe, msg.deliveryStatus, msg.alertId)

            val sizePrefix = if (fileSize > 0L) "${FileStorageManager.formatFileSize(fileSize)} • " else ""
            val hasFile = pathOrUri != null && (File(pathOrUri).exists() || pathOrUri.startsWith("content://"))
            val metaStatus = if (hasFile) {
                "Tap to open"
            } else if (status == "FAILED") {
                "Transfer failed • Tap to retry"
            } else if (status == "RECEIVING") {
                if (msg.transferProgress > 0) "Receiving (${msg.transferProgress}%)..." else "Receiving..."
            } else {
                "Tap to open"
            }
            textFileMeta.text = "$sizePrefix$metaStatus"

            itemView.setOnClickListener {
                if (hasFile && pathOrUri != null) {
                    onAttachmentClick?.invoke(fileName, pathOrUri)
                } else if (status == "FAILED" || (!isMe && !hasFile)) {
                    if (onAttachmentRetry != null && hash.isNotBlank()) {
                        onAttachmentRetry.invoke(msg.id.toLong(), hash, fileName, msg.senderName)
                    } else {
                        onAttachmentClick?.invoke(fileName, pathOrUri ?: "")
                    }
                } else {
                    onAttachmentClick?.invoke(fileName, pathOrUri ?: "")
                }
            }
        }
    }

    class LocationViewHolder(
        itemView: View,
        private val onLocationClick: ((Double, Double, String) -> Unit)?,
        private val myLocationProvider: (() -> Pair<Double, Double>?)?,
        private val isMe: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        val badgeLocationHeader: TextView = itemView.findViewById(R.id.badgeLocationHeader)
        val textCoordinates: TextView = itemView.findViewById(R.id.textCoordinates)
        val textSender: TextView = itemView.findViewById(R.id.textSender)
        val textDistance: TextView = itemView.findViewById(R.id.textDistance)
        val btnViewOnMap: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnViewOnMap)
        val textTime: TextView? = itemView.findViewById(R.id.textTime)
        val imageStatus: ImageView? = itemView.findViewById(R.id.imageStatus)
        val imageMapPreview: ImageView? = itemView.findViewById(R.id.imageMapPreview)

        fun bind(msg: ChatItem.Message) {
            val body = msg.messageBody
            val isLive = body.startsWith("[LOC_LIVE]:")
            val clean = if (isLive) body.removePrefix("[LOC_LIVE]:") else body.removePrefix("[LOC]:")
            val parts = clean.split("|")

            val lat: Double
            val lon: Double
            val label: String
            if (isLive) {
                lat = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                lon = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                label = "Live GPS"
            } else {
                lat = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                lon = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                label = if (parts.size >= 4 && parts[3].toFloatOrNull() != null) {
                    parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "Current Location"
                } else {
                    parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "GPS Location"
                }
            }

            badgeLocationHeader.text = if (isLive) "🟢 LIVE LOCATION" else "📍 SHARED LOCATION"
            textCoordinates.text = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            textSender.text = if (isMe) "You • $label" else "${msg.senderName} • $label"
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, isMe, msg.deliveryStatus, msg.alertId)

            if (imageMapPreview != null && (lat != 0.0 || lon != 0.0)) {
                com.fury.peerconnect.logic.TacticalMapPreviewHelper.loadLocationPreview(
                    itemView.context,
                    lat,
                    lon,
                    imageMapPreview
                )
            }

            // Calculate offline straight-line distance if my location is known
            val myCoords = myLocationProvider?.invoke()
            if (myCoords != null && (lat != 0.0 || lon != 0.0)) {
                val distMeters = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                    myCoords.first,
                    myCoords.second,
                    lat,
                    lon
                )
                textDistance.text = "${com.fury.peerconnect.logic.DistanceEngine.formatDistance(distMeters)} away"
                textDistance.visibility = View.VISIBLE
            } else {
                textDistance.visibility = View.GONE
            }

            btnViewOnMap.setOnClickListener {
                onLocationClick?.invoke(lat, lon, if (isMe) "You" else msg.senderName)
            }
            itemView.setOnClickListener {
                onLocationClick?.invoke(lat, lon, if (isMe) "You" else msg.senderName)
            }
        }
    }
}
