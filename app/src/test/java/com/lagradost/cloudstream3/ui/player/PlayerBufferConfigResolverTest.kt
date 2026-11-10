package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferConfigResolverTest {

    @Test
    fun `prefetch mode off preserves all legacy buffer and cache settings`() {
        val config = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = false,
            prefetchLimitMb = 1024,
            legacyDiskMb = 250,
            legacyRamMb = 60,
            legacyLengthSec = 120,
        )

        assertFalse(config.prefetchModeEnabled)
        assertEquals(250L * 1024L * 1024L, config.simpleCacheSizeBytes)
        assertEquals(60L * 1024L * 1024L, config.ramBudgetBytes)
        assertEquals(120_000L, config.bufferDurationMs)
        assertEquals(1024, config.prefetchLimitMb)
    }

    @Test
    fun `prefetch mode on applies prefetch storage limit and sets ram and duration to automatic`() {
        val config = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = true,
            prefetchLimitMb = 512,
            legacyDiskMb = 200,
            legacyRamMb = 100,
            legacyLengthSec = 180,
        )

        assertTrue(config.prefetchModeEnabled)
        assertEquals(512L * 1024L * 1024L, config.simpleCacheSizeBytes)
        // 0L indicates automatic device-aware memory budget and default buffer target in ForwardBufferPolicy
        assertEquals(0L, config.ramBudgetBytes)
        assertEquals(0L, config.bufferDurationMs)
        assertEquals(512, config.prefetchLimitMb)
    }

    @Test
    fun `prefetch mode on with automatic storage limit resolves to zero bytes`() {
        val config = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = true,
            prefetchLimitMb = 0,
            legacyDiskMb = 100,
            legacyRamMb = 50,
            legacyLengthSec = 90,
        )

        assertTrue(config.prefetchModeEnabled)
        assertEquals(0L, config.simpleCacheSizeBytes)
        assertEquals(0L, config.ramBudgetBytes)
        assertEquals(0L, config.bufferDurationMs)
        assertEquals(0, config.prefetchLimitMb)
    }

    @Test
    fun `disabling prefetch mode restores original legacy preferences without data loss`() {
        // Step 1: User had custom settings
        val legacyDisk = 300
        val legacyRam = 80
        val legacyLength = 240

        // Step 2: Enabled prefetch mode
        val activeConfig = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = true,
            prefetchLimitMb = 2048,
            legacyDiskMb = legacyDisk,
            legacyRamMb = legacyRam,
            legacyLengthSec = legacyLength,
        )
        assertTrue(activeConfig.prefetchModeEnabled)
        assertEquals(2048L * 1024L * 1024L, activeConfig.simpleCacheSizeBytes)

        // Step 3: Turned prefetch mode off again
        val restoredConfig = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = false,
            prefetchLimitMb = 2048,
            legacyDiskMb = legacyDisk,
            legacyRamMb = legacyRam,
            legacyLengthSec = legacyLength,
        )
        assertFalse(restoredConfig.prefetchModeEnabled)
        assertEquals(legacyDisk.toLong() * 1024L * 1024L, restoredConfig.simpleCacheSizeBytes)
        assertEquals(legacyRam.toLong() * 1024L * 1024L, restoredConfig.ramBudgetBytes)
        assertEquals(legacyLength.toLong() * 1000L, restoredConfig.bufferDurationMs)
    }

    @Test
    fun `negative inputs are clamped to zero safely`() {
        val config = PlayerBufferConfigResolver.resolve(
            prefetchModeEnabled = true,
            prefetchLimitMb = -10,
            legacyDiskMb = -5,
            legacyRamMb = -2,
            legacyLengthSec = -1,
        )

        assertTrue(config.prefetchModeEnabled)
        assertEquals(0L, config.simpleCacheSizeBytes)
        assertEquals(0, config.prefetchLimitMb)
    }
}
