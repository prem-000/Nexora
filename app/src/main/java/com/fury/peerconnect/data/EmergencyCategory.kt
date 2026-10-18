package com.fury.peerconnect.data

import java.util.Locale

/**
 * Supported emergency POI categories for offline tactical operations.
 *
 * Supported categories:
 *  - Hospitals & emergency departments
 *  - Police stations
 *  - Fire & rescue services
 *  - Ambulance facilities
 *  - Pharmacies
 *  - Railway Protection Force (RPF) & Government Railway Police (GRP)
 *  - Railway station help points
 *  - Emergency shelters & relief centres
 */
enum class EmergencyCategory(
    val id: String,
    val displayName: String,
    val iconSymbol: String,
    val badgeColorHex: String,
    val defaultEmergencyPhone: String?
) {
    HOSPITAL(
        id = "hospital",
        displayName = "Hospitals & ED",
        iconSymbol = "🏥",
        badgeColorHex = "#EF4444", // Red
        defaultEmergencyPhone = "112"
    ),
    POLICE(
        id = "police",
        displayName = "Police Stations",
        iconSymbol = "👮",
        badgeColorHex = "#3B82F6", // Blue
        defaultEmergencyPhone = "112"
    ),
    FIRE_STATION(
        id = "fire_station",
        displayName = "Fire & Rescue",
        iconSymbol = "🚒",
        badgeColorHex = "#F97316", // Orange
        defaultEmergencyPhone = "101"
    ),
    AMBULANCE(
        id = "ambulance",
        displayName = "Ambulance Facilities",
        iconSymbol = "🚑",
        badgeColorHex = "#10B981", // Emerald
        defaultEmergencyPhone = "102"
    ),
    PHARMACY(
        id = "pharmacy",
        displayName = "Pharmacies",
        iconSymbol = "💊",
        badgeColorHex = "#14B8A6", // Teal
        defaultEmergencyPhone = null
    ),
    RAILWAY_POLICE(
        id = "railway_police",
        displayName = "RPF & GRP",
        iconSymbol = "🛡️",
        badgeColorHex = "#8B5CF6", // Violet
        defaultEmergencyPhone = "139"
    ),
    RAILWAY_HELP(
        id = "railway_help",
        displayName = "Railway Help Points",
        iconSymbol = "🚉",
        badgeColorHex = "#6366F1", // Indigo
        defaultEmergencyPhone = "139"
    ),
    SHELTER(
        id = "shelter",
        displayName = "Emergency Shelters",
        iconSymbol = "⛺",
        badgeColorHex = "#EC4899", // Rose / Pink
        defaultEmergencyPhone = "1077"
    );

    companion object {
        fun fromId(rawId: String?): EmergencyCategory? {
            if (rawId.isNullOrBlank()) return null
            val normalized = rawId.trim().lowercase(Locale.US)
            return values().firstOrNull {
                it.id == normalized || it.name.lowercase(Locale.US) == normalized
            }
        }

        fun fromOsmTags(tags: Map<String, String>): EmergencyCategory? {
            val amenity = tags["amenity"]?.lowercase(Locale.US)
            val emergency = tags["emergency"]?.lowercase(Locale.US)
            val railway = tags["railway"]?.lowercase(Locale.US)
            val operator = tags["operator"]?.lowercase(Locale.US) ?: ""

            return when {
                amenity == "hospital" || amenity == "clinic" || emergency == "emergency_ward_entrance" -> HOSPITAL
                amenity == "police" && (operator.contains("railway") || operator.contains("rpf") || operator.contains("grp")) -> RAILWAY_POLICE
                amenity == "police" -> POLICE
                amenity == "fire_station" || emergency == "fire_station" -> FIRE_STATION
                amenity == "ambulance_station" || emergency == "ambulance_station" -> AMBULANCE
                amenity == "pharmacy" -> PHARMACY
                railway == "station" && (tags.containsKey("help_point") || tags.containsKey("information")) -> RAILWAY_HELP
                amenity == "shelter" || tags["social_facility"] == "shelter" || emergency == "shelter" -> SHELTER
                else -> null
            }
        }
    }
}
