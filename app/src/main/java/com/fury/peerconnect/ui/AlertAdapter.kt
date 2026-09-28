package com.fury.peerconnect.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.AlertEntity
import com.fury.peerconnect.logic.AvatarManager

class AlertAdapter(
    private val onAlertClick: ((AlertEntity) -> Unit)? = null,
    private val onLocationClick: ((Double, Double, String) -> Unit)? = null,
    private val onFoundPersonClick: ((AlertEntity) -> Unit)? = null,
    private val myLocationProvider: (() -> Pair<Double, Double>?)? = null,
    private val avatarIdProvider: ((String) -> String?)? = null,
    private val onPeerAvatarClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    private val allAlerts = mutableListOf<AlertEntity>()
    private val displayedAlerts = mutableListOf<AlertEntity>()
    private var currentFilter: String = "ALL"
    var onFilterChanged: ((filter: String, count: Int) -> Unit)? = null

    fun getCurrentFilter(): String = currentFilter

    fun getDisplayedCount(): Int = displayedAlerts.size

    fun isCriticalAlert(alert: AlertEntity): Boolean {
        return alert.type.equals("CRITICAL", ignoreCase = true) ||
                alert.type.equals("CRITICAL_EMERGENCY", ignoreCase = true) ||
                alert.severity.equals("CRITICAL", ignoreCase = true)
    }

    fun setAlerts(newAlerts: List<AlertEntity>) {
        allAlerts.clear()
        allAlerts.addAll(newAlerts)
        applyFilter(currentFilter)
    }

    fun applyFilter(filter: String) {
        currentFilter = filter
        displayedAlerts.clear()
        when (filter) {
            "CRITICAL" -> {
                displayedAlerts.addAll(allAlerts.filter { isCriticalAlert(it) })
            }
            "GROUPS" -> {
                displayedAlerts.addAll(allAlerts.filter {
                    !isCriticalAlert(it) && (it.type == "SOS" || it.type == "GROUP" || it.type == "BROADCAST")
                })
            }
            "SYSTEM" -> {
                displayedAlerts.addAll(allAlerts.filter {
                    !isCriticalAlert(it) && it.type != "SOS" && it.type != "GROUP" && it.type != "BROADCAST"
                })
            }
            else -> {
                // ALL: show standard SOS, groups, system, and critical
                displayedAlerts.addAll(allAlerts)
            }
        }
        try {
            notifyDataSetChanged()
        } catch (_: Throwable) {}
        onFilterChanged?.invoke(currentFilter, displayedAlerts.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(displayedAlerts[position], onAlertClick, onLocationClick, onFoundPersonClick, myLocationProvider, avatarIdProvider, onPeerAvatarClick)
    }

    override fun getItemCount(): Int = displayedAlerts.size

    class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val alertPeerAvatar: ImageView? = itemView.findViewById(R.id.alertPeerAvatar)
        private val badgeType: TextView = itemView.findViewById(R.id.badgeType)
        private val textAlertTime: TextView = itemView.findViewById(R.id.textAlertTime)
        private val textAlertTitle: TextView = itemView.findViewById(R.id.textAlertTitle)
        private val textAlertDescription: TextView = itemView.findViewById(R.id.textAlertDescription)
        private val accentStripe: View? = itemView.findViewById(R.id.alertAccentStripe)

        private val alertLocationContainer: View? = itemView.findViewById(R.id.alertLocationContainer)
        private val textAlertCoordinates: TextView? = itemView.findViewById(R.id.textAlertCoordinates)
        private val btnAlertViewOnMap: com.google.android.material.button.MaterialButton? = itemView.findViewById(R.id.btnAlertViewOnMap)
        private val btnAlertFoundPerson: com.google.android.material.button.MaterialButton? = itemView.findViewById(R.id.btnAlertFoundPerson)

        fun bind(
            alert: AlertEntity,
            onAlertClick: ((AlertEntity) -> Unit)?,
            onLocationClick: ((Double, Double, String) -> Unit)?,
            onFoundPersonClick: ((AlertEntity) -> Unit)?,
            myLocationProvider: (() -> Pair<Double, Double>?)?,
            avatarProvider: ((String) -> String?)? = null,
            onAvatarClick: ((String) -> Unit)? = null
        ) {
            if (!alert.peerName.isNullOrBlank()) {
                alertPeerAvatar?.visibility = View.VISIBLE
                val avatarId = avatarProvider?.invoke(alert.peerName)
                alertPeerAvatar?.let {
                    AvatarManager.loadAvatar(it, avatarId)
                    it.setOnClickListener { onAvatarClick?.invoke(alert.peerName) }
                }
            } else {
                alertPeerAvatar?.visibility = View.GONE
            }

            val isCritical = alert.type.equals("CRITICAL", ignoreCase = true) ||
                    alert.type.equals("CRITICAL_EMERGENCY", ignoreCase = true) ||
                    alert.severity.equals("CRITICAL", ignoreCase = true)

            val cardView = itemView as? com.google.android.material.card.MaterialCardView
            if (isCritical) {
                cardView?.strokeColor = Color.parseColor("#EF4444")
                cardView?.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt()
            } else {
                cardView?.strokeColor = androidx.core.content.ContextCompat.getColor(itemView.context, R.color.nexora_border)
                cardView?.strokeWidth = (1 * itemView.resources.displayMetrics.density).toInt()
            }

            badgeType.text = if (isCritical) "🚨 CRITICAL ALERT" else "● ${alert.type}"
            val categoryTag = if (isCritical && !alert.category.isNullOrBlank()) " [${alert.category}]" else ""
            textAlertTitle.text = "${alert.title}$categoryTag"
            val senderPrefix = if (!alert.peerName.isNullOrBlank()) "From: ${alert.peerName}\n" else ""
            textAlertDescription.text = "$senderPrefix${alert.body}".trim()
            val timeFormatted = DateFormat.format("hh:mm a", alert.timestamp).toString()
            textAlertTime.text = timeFormatted

            val color = when {
                isCritical -> Color.parseColor("#DC2626")
                alert.type == "QUEUED" -> Color.parseColor("#F59E0B")
                alert.type == "DISCOVERY" -> Color.parseColor("#6B7280")
                alert.type == "DISCONNECTION" -> Color.parseColor("#B71C1C")
                alert.type == "CONNECTION" -> Color.parseColor("#16A34A")
                alert.type == "SOS" || alert.type == "EMERGENCY" -> Color.parseColor("#E53935")
                alert.type == "TRANSFER" -> Color.parseColor("#0284C7")
                else -> Color.parseColor("#E53935")
            }

            accentStripe?.setBackgroundColor(color)
            accentStripe?.layoutParams?.width = if (isCritical) (6 * itemView.resources.displayMetrics.density).toInt() else (4 * itemView.resources.displayMetrics.density).toInt()
            accentStripe?.requestLayout()

            val gradientDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.0f
                setColor(if (isCritical) Color.parseColor("#FEE2E2") else Color.parseColor("#FEF2F2"))
                setStroke(if (isCritical) 3 else 2, if (isCritical) Color.parseColor("#EF4444") else Color.parseColor("#FCA5A5"))
            }
            badgeType.background = gradientDrawable
            badgeType.setTextColor(if (isCritical) Color.parseColor("#B91C1C") else color)

            // Tactical Location Actions
            val lat = alert.latitude
            val lon = alert.longitude
            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                alertLocationContainer?.visibility = View.VISIBLE

                val myCoords = myLocationProvider?.invoke()
                val distStr = if (myCoords != null) {
                    val dist = com.fury.peerconnect.logic.DistanceEngine.calculateHaversineDistance(
                        myCoords.first,
                        myCoords.second,
                        lat,
                        lon
                    )
                    " • ${com.fury.peerconnect.logic.DistanceEngine.formatDistance(dist)} away"
                } else ""

                val radiusSuffix = if (alert.radiusMeters != null && alert.radiusMeters > 0) {
                    val rMeters = alert.radiusMeters.toInt()
                    val rText = if (rMeters >= 1000) "${rMeters / 1000}km" else "${rMeters}m"
                    " (Radius: $rText)"
                } else ""

                textAlertCoordinates?.text = "📍 Geo-tagged (${String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)})$distStr$radiusSuffix"

                btnAlertViewOnMap?.setOnClickListener {
                    onLocationClick?.invoke(lat, lon, alert.title)
                }

                val isMissing = alert.title.contains("Missing", ignoreCase = true) ||
                        alert.body.contains("missing", ignoreCase = true) ||
                        alert.type.equals("MISSING", ignoreCase = true)
                btnAlertFoundPerson?.visibility = if (isMissing) View.VISIBLE else View.GONE
                btnAlertFoundPerson?.setOnClickListener {
                    onFoundPersonClick?.invoke(alert)
                }
            } else {
                alertLocationContainer?.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onAlertClick?.invoke(alert)
            }
        }
    }
}
