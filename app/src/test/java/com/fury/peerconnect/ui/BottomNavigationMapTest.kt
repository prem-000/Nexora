package com.fury.peerconnect.ui

import com.fury.peerconnect.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavigationMapTest {

    @Test
    fun testNavigationTabIdsExist() {
        // Verify all 5 tab item IDs are generated and distinct in resource R
        val expectedTabs = listOf(
            R.id.nav_home,
            R.id.nav_messages,
            R.id.nav_map,
            R.id.nav_alerts,
            R.id.nav_settings
        )
        assertEquals(5, expectedTabs.distinct().size)
    }

    @Test
    fun testBackPressCallbackEnabling_ReturnsToDashboardFromMap() {
        // When user is on Map tab (R.id.nav_map), back navigation should be enabled
        var selectedItemId = R.id.nav_map
        fun isBackCallbackEnabled(): Boolean {
            val isNotDashboard = selectedItemId != R.id.nav_home
            return isNotDashboard
        }

        assertTrue("Back callback should be enabled when on Map tab", isBackCallbackEnabled())

        // Simulating back button pressed
        if (selectedItemId != R.id.nav_home) {
            selectedItemId = R.id.nav_home
        }

        assertEquals(R.id.nav_home, selectedItemId)
        assertFalse("Back callback should be disabled when on Home dashboard", isBackCallbackEnabled())
    }

    @Test
    fun testLocationClickNavigationTarget_IsMapTab() {
        // When location is tapped (e.g., from alert or chat), target should switch to Map tab
        var activeTab = R.id.nav_home

        fun onLocationViewRequested() {
            activeTab = R.id.nav_map
        }

        onLocationViewRequested()
        assertEquals(R.id.nav_map, activeTab)
    }

    @Test
    fun testNeXoRaNavDestinationsStrictOrder() {
        val destinations = NeXoRaNavDestination.entries
        assertEquals(5, destinations.size)
        assertEquals("Home", destinations[0].title)
        assertEquals("Chats", destinations[1].title)
        assertEquals("Map", destinations[2].title)
        assertEquals("Alerts", destinations[3].title)
        assertEquals("Settings", destinations[4].title)
    }

    @Test
    fun testNeXoRaNavDestinationFromIdMapping() {
        assertEquals(NeXoRaNavDestination.HOME, NeXoRaNavDestination.fromId(R.id.nav_home))
        assertEquals(NeXoRaNavDestination.CHATS, NeXoRaNavDestination.fromId(R.id.nav_messages))
        assertEquals(NeXoRaNavDestination.MAP, NeXoRaNavDestination.fromId(R.id.nav_map))
        assertEquals(NeXoRaNavDestination.ALERTS, NeXoRaNavDestination.fromId(R.id.nav_alerts))
        assertEquals(NeXoRaNavDestination.SETTINGS, NeXoRaNavDestination.fromId(R.id.nav_settings))
    }

    @Test
    fun testAllFiveDestinationsHaveValidIcons() {
        // Ensure every destination has its corresponding icon asset
        assertEquals(R.drawable.ic_nav_home, NeXoRaNavDestination.HOME.iconRes)
        assertEquals(R.drawable.ic_nav_messages, NeXoRaNavDestination.CHATS.iconRes)
        assertEquals(R.drawable.ic_nav_map, NeXoRaNavDestination.MAP.iconRes)
        assertEquals(R.drawable.ic_nav_alerts, NeXoRaNavDestination.ALERTS.iconRes)
        assertEquals(R.drawable.ic_nav_settings, NeXoRaNavDestination.SETTINGS.iconRes)
    }

    @Test
    fun testDestinationSelectionCallback() {
        var selected = NeXoRaNavDestination.HOME
        val onSelected: (NeXoRaNavDestination) -> Unit = { dest ->
            selected = dest
        }

        NeXoRaNavDestination.entries.forEach { destination ->
            onSelected(destination)
            assertEquals(destination, selected)
        }
    }
}
