package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.R

/**
 * Pure configuration resolver for player buffer and disk cache settings.
 *
 * Requirements:
 * - When Prefetch Mode is OFF:
 *   - Use the user's manual video_buffer_disk_key, video_buffer_size_key, and video_buffer_length_key unchanged.
 *   - prefetchModeEnabled is false.
 * - When Prefetch Mode is ON:
 *   - prefetch_limit_mb_key controls simpleCacheSize (converted safely to bytes: limitMB * 1024L * 1024L).
 *   - A limit of 0 means Automatic.
 *   - ramBudgetBytes is set to 0L (ForwardBufferPolicy resolves this as automatic device-aware memory budget).
 *   - bufferDurationMs is set to 0L (ForwardBufferPolicy resolves this as safe default playback target).
 *   - prefetchModeEnabled is true.
 * - Legacy preference values are never overwritten or reset.
 */
data class PlayerBufferConfig(
    val prefetchModeEnabled: Boolean,
    val simpleCacheSizeBytes: Long,
    val ramBudgetBytes: Long,
    val bufferDurationMs: Long,
    val prefetchLimitMb: Int,
)

object PlayerBufferConfigResolver {
    fun resolve(
        prefetchModeEnabled: Boolean,
        prefetchLimitMb: Int,
        legacyDiskMb: Int,
        legacyRamMb: Int,
        legacyLengthSec: Int,
    ): PlayerBufferConfig {
        return if (prefetchModeEnabled) {
            val limitMb = prefetchLimitMb.coerceAtLeast(0)
            val limitBytes = limitMb.toLong() * 1024L * 1024L
            PlayerBufferConfig(
                prefetchModeEnabled = true,
                simpleCacheSizeBytes = limitBytes,
                ramBudgetBytes = 0L,
                bufferDurationMs = 0L,
                prefetchLimitMb = limitMb,
            )
        } else {
            val diskMb = legacyDiskMb.coerceAtLeast(0)
            val ramMb = legacyRamMb.coerceAtLeast(0)
            val lengthSec = legacyLengthSec.coerceAtLeast(0)
            PlayerBufferConfig(
                prefetchModeEnabled = false,
                simpleCacheSizeBytes = diskMb.toLong() * 1024L * 1024L,
                ramBudgetBytes = ramMb.toLong() * 1024L * 1024L,
                bufferDurationMs = lengthSec.toLong() * 1000L,
                prefetchLimitMb = prefetchLimitMb.coerceAtLeast(0),
            )
        }
    }

    fun resolveFromPreferences(
        context: Context,
        preferences: SharedPreferences,
    ): PlayerBufferConfig {
        val prefetchModeEnabled = preferences.getBoolean(
            context.getString(R.string.prefetch_mode_enabled_key),
            false
        )
        val prefetchLimitMb = preferences.getInt(
            context.getString(R.string.prefetch_limit_mb_key),
            1024
        )
        val legacyDiskMb = preferences.getInt(
            context.getString(R.string.video_buffer_disk_key),
            0
        )
        val legacyRamMb = preferences.getInt(
            context.getString(R.string.video_buffer_size_key),
            0
        )
        val legacyLengthSec = preferences.getInt(
            context.getString(R.string.video_buffer_length_key),
            0
        )
        return resolve(
            prefetchModeEnabled = prefetchModeEnabled,
            prefetchLimitMb = prefetchLimitMb,
            legacyDiskMb = legacyDiskMb,
            legacyRamMb = legacyRamMb,
            legacyLengthSec = legacyLengthSec,
        )
    }
}
