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
 * Audio dependency topology of a presentation.
 *
 * These are four distinct situations and must never be collapsed into a boolean:
 *  1. [MUXED] - audio and video share one stream.
 *  2. [SEPARATE_READY] - separate audio is required and its selected rendition playlist is usable.
 *  3. [SEPARATE_UNAVAILABLE] - separate audio is required but the playlist/rendition could not be
 *     resolved. Offline coverage must not extend, because unknown audio bytes are missing.
 *  4. [NONE] - the presentation has no audio track.
 *
 * [UNKNOWN] means the topology has not been determined yet. It must never be treated as "muxed".
 */
enum class AudioDependency {
    UNKNOWN,
    MUXED,
    NONE,
    SEPARATE_READY,
    SEPARATE_UNAVAILABLE
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
     * Reporting true does NOT mean the adapter is ready to schedule requests; see [checkReadiness].
     */
    suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any? = null): Boolean

    /**
     * True when the adapter can be selected for this source at all (format recognition only).
     */
    fun canHandleSource(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean = true

    /**
     * True when the adapter has everything it needs (e.g. a parsed manifest) to produce requests.
     */
    fun isManifestReady(snapshot: PlaybackPlayerSnapshot): Boolean = state == AdapterState.READY

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
     * Full audio dependency topology. Defaults to [AudioDependency.SEPARATE_READY] when the legacy
     * boolean says separate audio is required, so existing adapters keep their behaviour, and
     * [AudioDependency.MUXED] otherwise.
     */
    val audioDependency: AudioDependency
        get() = if (hasSeparateAudioTrack()) AudioDependency.SEPARATE_READY else AudioDependency.MUXED

    /**
     * Called when the user initiates a playback seek.
     */
    fun onSeek(newPositionUs: Long) = Unit

    /**
     * Releases any adapter-scoped resources.
     */
    fun release() = Unit
}
