package com.fury.peerconnect.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.data.AlertEntity

class AlertAdapter(
    private val onAlertClick: ((AlertEntity) -> Unit)? = null,
    private val onLocationClick: ((Double, Double, String) -> Unit)? = null,
    private val onFoundPersonClick: ((AlertEntity) -> Unit)? = null,
    private val myLocationProvider: (() -> Pair<Double, Double>?)? = null
) : RecyclerView.Adapter<AlertAdapter.AlertViewHolder>() {

    private val allAlerts = mutableListOf<AlertEntity>()
    private val displayedAlerts = mutableListOf<AlertEntity>()
    private var currentFilter: String = "ALL"

    fun setAlerts(newAlerts: List<AlertEntity>) {
        allAlerts.clear()
        allAlerts.addAll(newAlerts)
        applyFilter(currentFilter)
    }

    fun applyFilter(filter: String) {
        currentFilter = filter
        displayedAlerts.clear()
        when (filter) {
            "GROUPS" -> {
                displayedAlerts.addAll(allAlerts.filter {
                    it.type == "SOS" || it.type == "GROUP" || it.type == "BROADCAST"
                })
            }
            "SYSTEM" -> {
                displayedAlerts.addAll(allAlerts.filter {
                    it.type != "SOS" && it.type != "GROUP" && it.type != "BROADCAST"
                })
            }
            else -> {
                displayedAlerts.addAll(allAlerts)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(displayedAlerts[position], onAlertClick, onLocationClick, onFoundPersonClick, myLocationProvider)
    }

    override fun getItemCount(): Int = displayedAlerts.size

    class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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
            myLocationProvider: (() -> Pair<Double, Double>?)?
        ) {
            badgeType.text = "● ${alert.type}"
            textAlertTitle.text = alert.title
            val senderPrefix = if (!alert.peerName.isNullOrBlank()) "From: ${alert.peerName}\n" else ""
            textAlertDescription.text = "$senderPrefix${alert.body}".trim()
            val timeFormatted = DateFormat.format("hh:mm a", alert.timestamp).toString()
            textAlertTime.text = timeFormatted

            val color = when (alert.type) {
                "QUEUED" -> Color.parseColor("#F59E0B")
                "DISCOVERY" -> Color.parseColor("#6B7280")
                "DISCONNECTION" -> Color.parseColor("#B71C1C")
                "CONNECTION" -> Color.parseColor("#16A34A")
                "SOS", "EMERGENCY" -> Color.parseColor("#E53935")
                "TRANSFER" -> Color.parseColor("#0284C7")
                else -> Color.parseColor("#E53935")
            }

            accentStripe?.setBackgroundColor(color)

            val gradientDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24.0f
                setColor(Color.parseColor("#FEF2F2"))
                setStroke(2, Color.parseColor("#FCA5A5"))
            }
            badgeType.background = gradientDrawable
            badgeType.setTextColor(color)

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

                textAlertCoordinates?.text = "📍 Geo-tagged (${String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)})$distStr"

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
