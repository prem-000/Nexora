package com.fury.peerconnect.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLayerStyleTest {

    @Test
    fun testMapLayerStyleLabels() {
        assertEquals("Street", OfflineMapManager.MapLayerStyle.STREET.label)
        assertEquals("Satellite", OfflineMapManager.MapLayerStyle.SATELLITE.label)
        assertEquals("Hybrid", OfflineMapManager.MapLayerStyle.HYBRID.label)
    }

    @Test
    fun testAllThreeStylesExist() {
        val styles = OfflineMapManager.MapLayerStyle.values()
        assertEquals(3, styles.size)
    }
}
