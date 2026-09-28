package com.fury.peerconnect.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarManagerTest {

    @Test
    fun testPredefinedAvatarCountAndIds() {
        assertEquals(10, AvatarManager.AVATAR_IDS.size)
        assertEquals("avatar_01", AvatarManager.DEFAULT_AVATAR_ID)

        for (i in 1..10) {
            val expected = String.format("avatar_%02d", i)
            assertTrue("Expected list to contain $expected", AvatarManager.AVATAR_IDS.contains(expected))
        }
    }

    @Test
    fun testIsValidAvatar() {
        assertTrue(AvatarManager.isValidAvatar("avatar_01"))
        assertTrue(AvatarManager.isValidAvatar("avatar_10"))
        assertTrue(AvatarManager.isValidAvatar(" avatar_05 "))

        assertFalse(AvatarManager.isValidAvatar(null))
        assertFalse(AvatarManager.isValidAvatar(""))
        assertFalse(AvatarManager.isValidAvatar("avatar_00"))
        assertFalse(AvatarManager.isValidAvatar("avatar_11"))
        assertFalse(AvatarManager.isValidAvatar("custom_photo.png"))
        assertFalse(AvatarManager.isValidAvatar("http://example.com/avatar.jpg"))
    }

    @Test
    fun testGetDrawableIdFallback() {
        val defaultDrawable = AvatarManager.getDrawableId(AvatarManager.DEFAULT_AVATAR_ID)
        val nullFallback = AvatarManager.getDrawableId(null)
        val invalidFallback = AvatarManager.getDrawableId("non_existent_avatar")
        val emptyFallback = AvatarManager.getDrawableId("")

        assertEquals(defaultDrawable, nullFallback)
        assertEquals(defaultDrawable, invalidFallback)
        assertEquals(defaultDrawable, emptyFallback)
    }
}
