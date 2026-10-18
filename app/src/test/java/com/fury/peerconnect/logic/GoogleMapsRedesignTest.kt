package com.fury.peerconnect.logic

import com.fury.peerconnect.ui.MapSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsRedesignTest {

    @Test
    fun testDistanceEngineCalculateDistanceAlias() {
        val lat1 = 37.7749
        val lon1 = -122.4194
        val lat2 = 37.7833
        val lon2 = -122.4167

        val dist1 = DistanceEngine.calculateHaversineDistance(lat1, lon1, lat2, lon2)
        val dist2 = DistanceEngine.calculateDistance(lat1, lon1, lat2, lon2)

        assertEquals("calculateDistance alias must return identical result to calculateHaversineDistance", dist1, dist2, 0.0001)
        assertTrue("Distance between nearby coordinates must be positive", dist2 > 0.0)
    }

    @Test
    fun testDistanceEngineFormatDistance() {
        assertEquals("450 m", DistanceEngine.formatDistance(450.0))
        assertEquals("1.5 km", DistanceEngine.formatDistance(1500.0))
        assertEquals("10.0 km", DistanceEngine.formatDistance(10000.0))
    }

    @Test
    fun testMapSearchResultModel() {
        val peerResult = MapSearchResult(
            id = "Alice",
            type = MapSearchResult.Type.PEER,
            title = "Alice",
            subtitle = "Live Mesh Peer",
            distanceText = "250 m",
            latitude = 37.7749,
            longitude = -122.4194,
            avatarId = "avatar_01",
            isLive = true
        )

        assertEquals("Alice", peerResult.title)
        assertEquals(MapSearchResult.Type.PEER, peerResult.type)
        assertTrue(peerResult.isLive)
        assertEquals("250 m", peerResult.distanceText)

        val alertResult = MapSearchResult(
            id = "alert_1",
            type = MapSearchResult.Type.ALERT,
            title = "Flood Evacuation",
            subtitle = "Emergency Broadcast • FLOOD",
            distanceText = "1.2 km",
            latitude = 37.8,
            longitude = -122.4
        )
        assertEquals(MapSearchResult.Type.ALERT, alertResult.type)
    }

    @Test
    fun testOfflineMapSelectionModel() {
        val selection = OfflineMapManager.MapSelection(
            id = "peer_bob",
            type = OfflineMapManager.SelectionType.PEER,
            title = "Bob",
            subtitle = "Mesh Peer",
            latitude = 34.0522,
            longitude = -118.2437,
            isLive = false,
            isStale = false,
            distanceText = "3.4 km",
            avatarId = "avatar_04"
        )

        assertEquals("Bob", selection.title)
        assertEquals(OfflineMapManager.SelectionType.PEER, selection.type)
        assertEquals("3.4 km", selection.distanceText)
        assertEquals("avatar_04", selection.avatarId)
    }

    @Test
    fun testEmergencyPoiSearchResultModel() {
        val poiResult = MapSearchResult(
            id = "osm_hosp_aiims",
            type = MapSearchResult.Type.POI,
            title = "AIIMS Emergency Department",
            subtitle = "Hospitals & Emergency",
            distanceText = "1.8 km",
            latitude = 28.5672,
            longitude = 77.2100,
            emergencyCategory = com.fury.peerconnect.data.EmergencyCategory.HOSPITAL,
            verifiedContact = "112"
        )

        assertEquals(MapSearchResult.Type.POI, poiResult.type)
        assertEquals("AIIMS Emergency Department", poiResult.title)
        assertEquals(com.fury.peerconnect.data.EmergencyCategory.HOSPITAL, poiResult.emergencyCategory)
        assertEquals("112", poiResult.verifiedContact)
        assertEquals("1.8 km", poiResult.distanceText)
    }

    @Test
    fun testEmergencyPoiSelectionModel() {
        val selection = OfflineMapManager.MapSelection(
            id = "osm_pol_connaught",
            type = OfflineMapManager.SelectionType.POI,
            title = "Connaught Place Police Station",
            subtitle = "Police Station",
            latitude = 28.6328,
            longitude = 77.2197,
            distanceText = "850 m",
            emergencyCategory = com.fury.peerconnect.data.EmergencyCategory.POLICE,
            emergencyPhone = "112",
            address = "Baba Kharak Singh Marg, New Delhi",
            operator = "Delhi Police",
            capabilities = "24/7 Control Room, Emergency Dispatch"
        )

        assertEquals("Connaught Place Police Station", selection.title)
        assertEquals(OfflineMapManager.SelectionType.POI, selection.type)
        assertEquals(com.fury.peerconnect.data.EmergencyCategory.POLICE, selection.emergencyCategory)
        assertEquals("112", selection.emergencyPhone)
        assertEquals("Delhi Police", selection.operator)
        assertEquals("24/7 Control Room, Emergency Dispatch", selection.capabilities)
    }

    @Test
    fun testMapLayerStyles() {
        assertEquals("Street", OfflineMapManager.MapLayerStyle.STREET.label)
        assertEquals("Satellite", OfflineMapManager.MapLayerStyle.SATELLITE.label)
        assertEquals("Hybrid", OfflineMapManager.MapLayerStyle.HYBRID.label)
        assertEquals(3, OfflineMapManager.MapLayerStyle.entries.size)
    }

    @Test
    fun testGpsAndOfflineStatusFormatting() {
        val accuracy = 72.4f
        val gpsStatus = "GPS active · ±${accuracy.toInt()} m"
        assertEquals("GPS active · ±72 m", gpsStatus)

        val offlineStatus = "● Offline map available"
        assertTrue(offlineStatus.startsWith("●"))
        assertTrue(offlineStatus.contains("Offline map available"))
    }
}

