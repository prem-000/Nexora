package com.fury.peerconnect.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.ChatMessage
import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.logic.FileStorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val myNickName: String = "",
    private val onAttachmentClick: ((String, String) -> Unit)? = null,
    private val onLocationClick: ((Double, Double, String) -> Unit)? = null,
    private val myLocationProvider: (() -> Pair<Double, Double>?)? = null
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
            TYPE_FILE_ME -> FileViewHolder(inflater.inflate(R.layout.item_message_file_me, parent, false), onAttachmentClick, true)
            TYPE_FILE_OTHER -> FileViewHolder(inflater.inflate(R.layout.item_message_file_other, parent, false), onAttachmentClick, false)
            TYPE_IMAGE_ME -> ImageViewHolder(inflater.inflate(R.layout.item_message_image_me, parent, false), onAttachmentClick, true)
            TYPE_IMAGE_OTHER -> ImageViewHolder(inflater.inflate(R.layout.item_message_image_other, parent, false), onAttachmentClick, false)
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
        private val isMe: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        val textFileName: TextView = itemView.findViewById(R.id.textFileName)
        val textSender: TextView = itemView.findViewById(R.id.textSender)
        val textTime: TextView? = itemView.findViewById(R.id.textTime)
        val imagePreview: ImageView = itemView.findViewById(R.id.imagePreview)
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
            val fileName = parts.getOrNull(0) ?: "image.jpg"
            var pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val hash = parts.getOrNull(2)?.trim()?.lowercase() ?: ""
            var status = parts.getOrNull(4) ?: if (pathOrUri != null) "SUCCESS" else "RECEIVING"
            val attachmentKey = parts.getOrNull(6)?.takeIf { it.isNotBlank() }

            val context = itemView.context
            if (hash.isNotBlank() && com.fury.peerconnect.logic.MeshBlobStore.has(context, hash)) {
                status = "SUCCESS"
                if (pathOrUri == null || !File(pathOrUri).exists()) {
                    val exported = com.fury.peerconnect.logic.MeshBlobStore.exportReadableFile(context, hash, fileName, attachmentKey)
                    pathOrUri = exported?.absolutePath ?: com.fury.peerconnect.logic.MeshBlobStore.getFile(context, hash)?.absolutePath
                }
            }

            textFileName.text = fileName
            val sender = if (isMe) "You" else msg.senderName
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, isMe, msg.deliveryStatus, msg.alertId)

            if (!pathOrUri.isNullOrEmpty()) {
                val file = File(pathOrUri)
                if (file.exists() && file.length() > 0L) {
                    val bmp = loadSampledBitmap(file.absolutePath, 400, 300)
                    if (bmp != null) {
                        imagePreview.setImageBitmap(bmp)
                    } else {
                        imagePreview.setImageURI(Uri.fromFile(file))
                    }
                    textSender.text = "$sender • Tap to view"
                } else if (pathOrUri.startsWith("content://")) {
                    imagePreview.setImageURI(Uri.parse(pathOrUri))
                    textSender.text = "$sender • Tap to view"
                } else {
                    imagePreview.setImageResource(R.drawable.ic_image)
                    textSender.text = if (status == "FAILED") "$sender • Transfer failed" else "$sender • File unavailable"
                }
            } else {
                imagePreview.setImageResource(R.drawable.ic_image)
                val statusText = when (status) {
                    "FAILED" -> "Transfer failed"
                    "RECEIVING" -> if (msg.transferProgress > 0) "Receiving image (${msg.transferProgress}%)..." else "Receiving image..."
                    else -> "Pending..."
                }
                textSender.text = "$sender • $statusText"
            }

            val finalPath = pathOrUri
            itemView.setOnClickListener {
                onAttachmentClick?.invoke(fileName, finalPath ?: "")
            }
        }
    }

    class FileViewHolder(
        itemView: View,
        private val onAttachmentClick: ((String, String) -> Unit)?,
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
            var pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val hash = parts.getOrNull(2)?.trim()?.lowercase() ?: ""
            val sizeStr = parts.getOrNull(3)
            val fileSize = sizeStr?.toLongOrNull() ?: 0L
            var status = parts.getOrNull(4) ?: if (pathOrUri != null) "SUCCESS" else "RECEIVING"
            val attachmentKey = parts.getOrNull(6)?.takeIf { it.isNotBlank() }

            val context = itemView.context
            if (hash.isNotBlank() && com.fury.peerconnect.logic.MeshBlobStore.has(context, hash)) {
                status = "SUCCESS"
                if (pathOrUri == null || !File(pathOrUri).exists()) {
                    val exported = com.fury.peerconnect.logic.MeshBlobStore.exportReadableFile(context, hash, fileName, attachmentKey)
                    pathOrUri = exported?.absolutePath ?: com.fury.peerconnect.logic.MeshBlobStore.getFile(context, hash)?.absolutePath
                }
            }

            textFileName.text = fileName
            textSender.text = if (isMe) "You" else msg.senderName
            textTime?.text = formatMessageTime(msg.time)
            bindStatus(imageStatus, isMe, msg.deliveryStatus, msg.alertId)

            val sizePrefix = if (fileSize > 0L) "${FileStorageManager.formatFileSize(fileSize)} • " else ""
            val metaStatus = if (pathOrUri != null && (File(pathOrUri).exists() || pathOrUri.startsWith("content://"))) {
                "Tap to open"
            } else if (status == "FAILED") {
                "Transfer failed"
            } else if (status == "RECEIVING") {
                if (msg.transferProgress > 0) "Receiving (${msg.transferProgress}%)..." else "Receiving..."
            } else {
                "Tap to open"
            }
            textFileMeta.text = "$sizePrefix$metaStatus"

            val finalPath = pathOrUri
            itemView.setOnClickListener {
                onAttachmentClick?.invoke(fileName, finalPath ?: "")
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

            val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val label = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: (if (isLive) "Live GPS" else "GPS Location")

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
