package com.fury.peerconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyPoiTest {

    @Test
    fun testAllEightEmergencyCategoriesDefined() {
        val categories = EmergencyCategory.values()
        assertEquals("Must support exactly 8 emergency POI categories", 8, categories.size)

        val expectedIds = setOf(
            "hospital",
            "police",
            "fire_station",
            "ambulance",
            "pharmacy",
            "railway_police",
            "railway_help",
            "shelter"
        )
        val actualIds = categories.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)
    }

    @Test
    fun testEmergencyCategoryFromId() {
        assertEquals(EmergencyCategory.HOSPITAL, EmergencyCategory.fromId("hospital"))
        assertEquals(EmergencyCategory.POLICE, EmergencyCategory.fromId("police"))
        assertEquals(EmergencyCategory.FIRE_STATION, EmergencyCategory.fromId("fire_station"))
        assertEquals(EmergencyCategory.AMBULANCE, EmergencyCategory.fromId("ambulance"))
        assertEquals(EmergencyCategory.PHARMACY, EmergencyCategory.fromId("pharmacy"))
        assertEquals(EmergencyCategory.RAILWAY_POLICE, EmergencyCategory.fromId("railway_police"))
        assertEquals(EmergencyCategory.RAILWAY_HELP, EmergencyCategory.fromId("railway_help"))
        assertEquals(EmergencyCategory.SHELTER, EmergencyCategory.fromId("shelter"))
        assertNull(EmergencyCategory.fromId("unknown_category"))
        assertNull(EmergencyCategory.fromId(null))
    }

    @Test
    fun testEmergencyCategoryFromOsmTags() {
        assertEquals(EmergencyCategory.HOSPITAL, EmergencyCategory.fromOsmTags(mapOf("amenity" to "hospital")))
        assertEquals(EmergencyCategory.POLICE, EmergencyCategory.fromOsmTags(mapOf("amenity" to "police")))
        assertEquals(EmergencyCategory.FIRE_STATION, EmergencyCategory.fromOsmTags(mapOf("amenity" to "fire_station")))
        assertEquals(EmergencyCategory.AMBULANCE, EmergencyCategory.fromOsmTags(mapOf("emergency" to "ambulance_station")))
        assertEquals(EmergencyCategory.PHARMACY, EmergencyCategory.fromOsmTags(mapOf("amenity" to "pharmacy")))
        assertEquals(EmergencyCategory.SHELTER, EmergencyCategory.fromOsmTags(mapOf("amenity" to "shelter")))
        assertEquals(EmergencyCategory.RAILWAY_HELP, EmergencyCategory.fromOsmTags(mapOf("railway" to "station", "help_point" to "yes")))
        assertNull(EmergencyCategory.fromOsmTags(mapOf("amenity" to "cafe")))
    }

    @Test
    fun testCategorySpecificHelplineNumbers() {
        // Verified authentic emergency services numbers
        assertEquals("112", EmergencyCategory.HOSPITAL.defaultEmergencyPhone)
        assertEquals("112", EmergencyCategory.POLICE.defaultEmergencyPhone)
        assertEquals("101", EmergencyCategory.FIRE_STATION.defaultEmergencyPhone)
        assertEquals("102", EmergencyCategory.AMBULANCE.defaultEmergencyPhone)
        assertEquals("139", EmergencyCategory.RAILWAY_POLICE.defaultEmergencyPhone)
        assertEquals("139", EmergencyCategory.RAILWAY_HELP.defaultEmergencyPhone)
        assertEquals("1077", EmergencyCategory.SHELTER.defaultEmergencyPhone)
    }

    @Test
    fun testParsePoisFromJson() {
        val sampleJson = """
            [
              {
                "id": "osm_hosp_aiims",
                "name": "AIIMS Emergency Department",
                "category": "hospital",
                "subCategory": "tertiary_trauma",
                "latitude": 28.5672,
                "longitude": 77.2100,
                "phone": "+91-11-26588500",
                "emergencyPhone": "112",
                "address": "Ansari Nagar, New Delhi",
                "operator": "Government of India",
                "capabilities": "Level 1 Trauma, 24/7 ICU, Burn Unit"
              },
              {
                "id": "osm_pol_connaught",
                "name": "Connaught Place Police Station",
                "category": "police",
                "latitude": 28.6328,
                "longitude": 77.2197,
                "emergencyPhone": "112",
                "address": "Baba Kharak Singh Marg, New Delhi",
                "operator": "Delhi Police"
              }
            ]
        """.trimIndent()

        val parsed = EmergencyPoiRepository.parsePoisFromJson(sampleJson)
        assertEquals(2, parsed.size)

        val aiims = parsed[0]
        assertEquals("osm_hosp_aiims", aiims.id)
        assertEquals("AIIMS Emergency Department", aiims.name)
        assertEquals(EmergencyCategory.HOSPITAL, aiims.getEmergencyCategory())
        assertEquals(28.5672, aiims.latitude, 0.0001)
        assertEquals(77.2100, aiims.longitude, 0.0001)
        assertEquals("112", aiims.emergencyPhone)
        assertEquals("+91-11-26588500", aiims.phone)
        assertEquals("Ansari Nagar, New Delhi", aiims.address)
        assertEquals("Level 1 Trauma, 24/7 ICU, Burn Unit", aiims.capabilities)

        val cpPolice = parsed[1]
        assertEquals("Connaught Place Police Station", cpPolice.name)
        assertEquals(EmergencyCategory.POLICE, cpPolice.getEmergencyCategory())
        assertNull(cpPolice.phone)
        assertEquals("112", cpPolice.getVerifiedEmergencyHelpline())
    }

    @Test
    fun testParsePoisFromJsonWithEmptyAndMalformedData() {
        assertTrue(EmergencyPoiRepository.parsePoisFromJson("").isEmpty())
        assertTrue(EmergencyPoiRepository.parsePoisFromJson("[]").isEmpty())
        assertTrue(EmergencyPoiRepository.parsePoisFromJson("invalid json string").isEmpty())

        // Incomplete records missing required fields (id, name, or coordinates)
        val incompleteJson = """
            [
              {"id": "", "name": "No ID", "category": "hospital", "latitude": 28.5, "longitude": 77.2},
              {"id": "valid_id", "name": "", "category": "police", "latitude": 28.5, "longitude": 77.2},
              {"id": "no_coords", "name": "Facility", "category": "fire_station"}
            ]
        """.trimIndent()
        val parsed = EmergencyPoiRepository.parsePoisFromJson(incompleteJson)
        assertTrue("Incomplete or invalid records must be safely filtered out", parsed.isEmpty())
    }

    @Test
    fun testEmergencyPoiEntityHelplineResolution() {
        val poiWithSpecificEmergencyPhone = EmergencyPoiEntity(
            id = "p1",
            name = "Test Police",
            category = "police",
            latitude = 28.0,
            longitude = 77.0,
            phone = "011-123456",
            emergencyPhone = "112"
        )
        assertEquals("112", poiWithSpecificEmergencyPhone.getVerifiedEmergencyHelpline())

        val poiWithGeneralPhoneOnly = EmergencyPoiEntity(
            id = "p2",
            name = "Test Pharmacy",
            category = "pharmacy",
            latitude = 28.0,
            longitude = 77.0,
            phone = "011-987654"
        )
        assertEquals("011-987654", poiWithGeneralPhoneOnly.getVerifiedEmergencyHelpline())

        val poiWithNoPhone = EmergencyPoiEntity(
            id = "p3",
            name = "Test Shelter",
            category = "shelter",
            latitude = 28.0,
            longitude = 77.0
        )
        assertEquals("1077", poiWithNoPhone.getVerifiedEmergencyHelpline())
    }
}
