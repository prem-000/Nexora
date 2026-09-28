package com.fury.peerconnect.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.MessageEntity
import com.fury.peerconnect.logic.FileStorageManager
import com.fury.peerconnect.logic.MeshBlobStore
import java.io.File

class PeerSharedMediaAdapter(
    private val onItemClick: (fileName: String, pathOrUri: String) -> Unit
) : RecyclerView.Adapter<PeerSharedMediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MessageEntity>()

    fun setItems(newItems: List<MessageEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer_shared_media, parent, false)
        return MediaViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class MediaViewHolder(
        itemView: View,
        private val onItemClick: (fileName: String, pathOrUri: String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val imgThumb: ImageView = itemView.findViewById(R.id.imgSharedMediaThumb)
        private val iconFile: ImageView = itemView.findViewById(R.id.iconSharedMediaFile)
        private val textName: TextView = itemView.findViewById(R.id.textSharedMediaName)
        private val textSize: TextView = itemView.findViewById(R.id.textSharedMediaSize)

        fun bind(msg: MessageEntity) {
            val body = msg.text
            val cleanBody = when {
                body.startsWith("[FILE]:") -> body.removePrefix("[FILE]:")
                body.startsWith("📄 Shared a file: ") -> body.removePrefix("📄 Shared a file: ")
                body.startsWith("Shared a file: ") -> body.removePrefix("Shared a file: ")
                body.contains("Shared a file: ") -> body.substringAfter("Shared a file: ")
                else -> body
            }
            val parts = cleanBody.split("|")
            val rawName = msg.fileName ?: parts.getOrNull(0) ?: "Attachment"
            val fileName = rawName.ifBlank { "Attachment" }
            var pathOrUri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: msg.localPath?.takeIf { it.isNotEmpty() }
            val hash = parts.getOrNull(2)?.trim()?.lowercase() ?: ""
            val sizeStr = parts.getOrNull(3)
            val fileSize = if (msg.fileSize > 0L) msg.fileSize else (sizeStr?.toLongOrNull() ?: 0L)
            val attachmentKey = parts.getOrNull(6)?.takeIf { it.isNotBlank() && it != "null" }

            val context = itemView.context
            if (hash.isNotBlank() && MeshBlobStore.has(context, hash)) {
                if (pathOrUri == null || !File(pathOrUri).exists()) {
                    val exported = MeshBlobStore.exportReadableFile(context, hash, fileName, attachmentKey)
                    pathOrUri = exported?.absolutePath ?: MeshBlobStore.getFile(context, hash)?.absolutePath
                }
            } else if (pathOrUri == null || !File(pathOrUri).exists()) {
                val candidate = FileStorageManager.getStorageDir(context).listFiles()
                    ?.firstOrNull { it.name.endsWith(fileName) && it.length() > 0L }
                if (candidate != null) {
                    pathOrUri = candidate.absolutePath
                }
            }

            textName.text = fileName
            textSize.text = if (fileSize > 0L) FileStorageManager.formatFileSize(fileSize) else "File"

            val isImage = msg.messageType.equals("IMAGE", ignoreCase = true) ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".gif").any { fileName.lowercase().endsWith(it) }

            if (isImage) {
                iconFile.setImageResource(R.drawable.ic_image)
                if (pathOrUri != null && File(pathOrUri).exists()) {
                    try {
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(pathOrUri, options)
                        var sampleSize = 1
                        if (options.outHeight > 120 || options.outWidth > 120) {
                            val halfH = options.outHeight / 2
                            val halfW = options.outWidth / 2
                            while ((halfH / sampleSize) >= 120 && (halfW / sampleSize) >= 120) {
                                sampleSize *= 2
                            }
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        }
                        val bitmap = BitmapFactory.decodeFile(pathOrUri, decodeOpts)
                        if (bitmap != null) {
                            imgThumb.setImageBitmap(bitmap)
                            imgThumb.visibility = View.VISIBLE
                            iconFile.visibility = View.GONE
                        } else {
                            imgThumb.visibility = View.GONE
                            iconFile.visibility = View.VISIBLE
                        }
                    } catch (_: Exception) {
                        imgThumb.visibility = View.GONE
                        iconFile.visibility = View.VISIBLE
                    }
                } else {
                    imgThumb.visibility = View.GONE
                    iconFile.visibility = View.VISIBLE
                }
            } else {
                imgThumb.visibility = View.GONE
                iconFile.visibility = View.VISIBLE
                iconFile.setImageResource(R.drawable.ic_document)
            }

            val finalPath = pathOrUri ?: ""
            itemView.setOnClickListener {
                onItemClick(fileName, finalPath)
            }
        }
    }
}
