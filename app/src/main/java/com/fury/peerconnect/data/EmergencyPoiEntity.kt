package com.fury.peerconnect.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local Room entity for verified offline Emergency Points of Interest.
 * Data is locally provisioned from OpenStreetMap regional extracts.
 *
 * Strict policy: Never fabricate facility records, phone numbers, opening hours,
 * or emergency capabilities. Unlisted fields remain null.
 */
@Entity(
    tableName = "emergency_pois",
    indices = [
        Index(value = ["category"]),
        Index(value = ["latitude", "longitude"])
    ]
)
data class EmergencyPoiEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val category: String,
    val subCategory: String? = null,
    val latitude: Double,
    val longitude: Double,
    val phone: String? = null,
    val emergencyPhone: String? = null,
    val address: String? = null,
    val operator: String? = null,
    val capabilities: String? = null,
    val source: String = "OpenStreetMap Regional Extract • ODbL",
    val isVerified: Boolean = true
) {
    fun getEmergencyCategory(): EmergencyCategory? = EmergencyCategory.fromId(category)

    fun getVerifiedEmergencyHelpline(): String? {
        return emergencyPhone ?: phone ?: getEmergencyCategory()?.defaultEmergencyPhone
    }
}
