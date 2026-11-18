package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerManagerTest {

    @Test fun `fallbackAddressKey strips query so re-parse with new auth_key keeps same key`() {
        assertEquals(
            "https://cdn.example.com/v/1/index.m3u8",
            PlayerManager.fallbackAddressKey("https://cdn.example.com/v/1/index.m3u8?auth_key=aaa"),
        )
        assertEquals(
            "https://cdn.example.com/v/1/index.m3u8",
            PlayerManager.fallbackAddressKey("https://cdn.example.com/v/1/index.m3u8?auth_key=bbb&x=1"),
        )
    }

    @Test fun `fallbackAddressKey leaves url without query unchanged`() {
        assertEquals(
            "https://cdn.example.com/v/1/a.mp4",
            PlayerManager.fallbackAddressKey("https://cdn.example.com/v/1/a.mp4"),
        )
    }

    @Test fun `fallbackAddressKey trims surrounding whitespace like libmpvFailed`() {
        assertEquals(
            "https://cdn.example.com/v/1/a.mp4",
            PlayerManager.fallbackAddressKey("  https://cdn.example.com/v/1/a.mp4?auth_key=1  "),
        )
    }

    @Test fun `fallbackAddressKey differs across paths`() {
        assertNotEquals(
            PlayerManager.fallbackAddressKey("https://cdn.example.com/v/1/a.mp4?auth_key=1"),
            PlayerManager.fallbackAddressKey("https://cdn.example.com/v/2/a.mp4?auth_key=1"),
        )
    }
}
