package com.lagradost.cloudstream3.ui.player

import android.net.Uri

/**
 * Explicit lifecycle state for prefetch adapters.
 * Distinguishes source-format recognition from adapter readiness.
 */
enum class AdapterState {
    UNRESOLVED,
    WAITING_FOR_MANIFEST,
    READY,
    UNSUPPORTED,
    FAILED_RETRYABLE,
    RELEASED
}

/**
 * Strategy interface for media format-specific prefetching (HLS, DASH, Progressive MP4).
 */
interface PrefetchAdapter {
    val name: String

    val state: AdapterState
        get() = AdapterState.READY

    /**
     * Checks if this adapter can handle the given media source.
     */
    suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any? = null): Boolean

    /**
     * Updates adapter readiness using the latest player snapshot.
     * Allows transition from WAITING_FOR_MANIFEST or FAILED_RETRYABLE to READY.
     */
    suspend fun checkReadiness(snapshot: PlaybackPlayerSnapshot): AdapterState = state

    /**
     * Inspects the thread-safe player snapshot and returns upcoming chunks to prefetch
     * within the available storage budget.
     */
    suspend fun getUpcomingRequests(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
    ): List<PrefetchRequest>

    /**
     * Indicates whether the media presentation utilizes separate audio rendition streams
     * (e.g. non-muxed HLS audio or DASH adaptation sets) that must be verified for offline playability.
     */
    fun hasSeparateAudioTrack(): Boolean = false

    /**
     * Called when the user initiates a playback seek.
     */
    fun onSeek(newPositionUs: Long) = Unit

    /**
     * Releases any adapter-scoped resources.
     */
    fun release() = Unit
}
