package com.lagradost.cloudstream3.ui.player

import android.net.Uri

/**
 * Strategy interface for media format-specific prefetching (HLS, DASH, Progressive MP4).
 */
interface PrefetchAdapter {
    val name: String

    /**
     * Checks if this adapter can handle the given media source.
     */
    suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any? = null): Boolean

    /**
     * Inspects the thread-safe player snapshot and returns upcoming chunks to prefetch
     * within the available storage budget.
     */
    suspend fun getUpcomingRequests(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
    ): List<PrefetchRequest>

    /**
     * Called when the user initiates a playback seek.
     */
    fun onSeek(newPositionUs: Long) = Unit

    /**
     * Releases any adapter-scoped resources.
     */
    fun release() = Unit
}
