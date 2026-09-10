package com.fury.peerconnect.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.fury.peerconnect.R
import com.fury.peerconnect.data.AlertEntity
import com.google.android.material.button.MaterialButton

/**
 * Dialog to coordinate found-person response with two-way consent handshake.
 * If accepted by both parties, temporary live tracking is enabled between finder and reporter.
 */
object FoundPersonDialog {

    fun show(
        context: Context,
        alert: AlertEntity,
        onConfirmFound: (shareLocationWithOrigin: Boolean) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_found_person, null)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val textTitle = view.findViewById<TextView>(R.id.foundDialogTitle)
        val textBody = view.findViewById<TextView>(R.id.foundDialogBody)
        val btnShareLocation = view.findViewById<MaterialButton>(R.id.btnShareLocationWithFamily)
        val btnNotifyOnly = view.findViewById<MaterialButton>(R.id.btnNotifyOnly)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelFound)

        textTitle.text = "Coordinate: ${alert.title}"
        textBody.text = "You are reporting that you have found the subject of alert '${alert.title}'.\n\n" +
                "To help the reporter locate the person, you can establish temporary location sharing over the NeXoRa offline mesh."

        btnShareLocation.setOnClickListener {
            dialog.dismiss()
            onConfirmFound(true)
        }

        btnNotifyOnly.setOnClickListener {
            dialog.dismiss()
            onConfirmFound(false)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
