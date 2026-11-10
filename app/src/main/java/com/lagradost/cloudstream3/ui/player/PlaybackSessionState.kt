package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates the independent state of one actual playback session / player instance.
 *
 * Requirements:
 * - Never let one player overwrite another player's active URI, cache ownership, network status,
 *   or live-stream state.
 * - Do not let one live player force all players to bypass disk cache.
 * - Release all session-owned resources on player release, failure, replacement, or cancellation.
 * - Do not clear another player's state.
 * - Support intended transitions for PiP/background audio/reload without prematurely clearing leases.
 * - Make repeated release safe and idempotent.
 */
@OptIn(UnstableApi::class)
class PlaybackSessionState(
    val sessionId: String = UUID.randomUUID().toString(),
    var sourceUri: String? = null,
    var isLive: Boolean = false,
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
    val prefetchModeEnabled: Boolean = false,
    val prefetchLimitBytes: Long = 0L,
) {
    enum class MediaType {
        PROGRESSIVE,
        HLS,
        DASH,
        LIVE,
        OFFLINE,
        TORRENT,
        UNKNOWN
    }

    enum class Lifecycle {
        INITIALIZING,
        PREPARED,
        ACTIVE,
        PAUSED,
        RELEASING,
        RELEASED
    }

    data class CacheLease(
        val leaseId: String,
        val key: String,
        val uri: Uri?,
        val rangeStart: Long = 0L,
        val rangeLength: Long = -1L,
        val createdAtMs: Long = System.currentTimeMillis(),
        val expiresAtMs: Long = System.currentTimeMillis() + DEFAULT_LEASE_DURATION_MS,
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAtMs
    }

    var playerId: PlayerId? = null
    var normalizedSourceUri: String? = sourceUri?.let { PlaybackCacheManager.normalizeUri(it) }
    var isPlaylist: Boolean = false
    var mediaType: MediaType = if (isLive) MediaType.LIVE else MediaType.UNKNOWN

    // Selected track and media formats
    @Volatile var selectedBitrateBps: Long = 0L
    @Volatile var selectedAudioBitrateBps: Long = 0L
    @Volatile var videoWidth: Int = 0
    @Volatile var videoHeight: Int = 0
    @Volatile var selectedMime: String? = null

    // Playout metrics
    @Volatile var playbackSpeed: Float = 1.0f
    @Volatile var forwardBufferMs: Long = 0L
    @Volatile var bufferedDurationUs: Long = 0L
    @Volatile var playbackPositionUs: Long = 0L
    @Volatile var bufferedEndPositionMs: Long = 0L
    @Volatile var playbackState: Int = Player.STATE_IDLE
    @Volatile var playWhenReady: Boolean = true

    // Allocator and memory budget
    @Volatile var allocatedBytes: Long = 0L
    @Volatile var memoryBudgetBytes: Int = 0

    // Rebuffering and starvation statistics
    @Volatile var rebuffering: Boolean = false
    @Volatile var rebufferCount: Int = 0
    @Volatile var totalRebufferDurationMs: Long = 0L
    @Volatile var lastRebufferStartRealtimeMs: Long = 0L
    @Volatile var starvationCount: Int = 0

    // Quality adaptation state
    @Volatile var isQualityRecoveryActive: Boolean = false
    @Volatile var originalMaxVideoBitrate: Int = Int.MAX_VALUE
    @Volatile var currentMaxVideoBitrate: Int = Int.MAX_VALUE
    @Volatile var lastQualitySwitchRealtimeMs: Long = 0L
    @Volatile var sustainableBitrateBps: Long = 0L

    // Cache diagnostics
    @Volatile var cachedBytesRead: Long = 0L
    @Volatile var cacheReadEvents: Long = 0L
    @Volatile var cacheIgnoredCount: Long = 0L
    @Volatile var lastCacheSizeBytes: Long = 0L

    // Session lifecycle
    @Volatile var lifecycle: Lifecycle = Lifecycle.INITIALIZING
        private set

    // Active resource leases
    private val activeLeases = ConcurrentHashMap<String, CacheLease>()

    // Optional prefetch coordinator for this session
    var prefetchCoordinator: PlaybackPrefetchCoordinator? = null

    // Dedicated network monitor for this session
    val networkMonitor: PlaybackNetworkMonitor = PlaybackNetworkMonitor(sessionId, clock)

    init {
        updateMediaTypeFromUri(sourceUri)
    }

    fun setSource(uri: String?, isPlaylistSource: Boolean = false, live: Boolean = false) {
        sourceUri = uri
        normalizedSourceUri = uri?.let { PlaybackCacheManager.normalizeUri(it) }
        isPlaylist = isPlaylistSource
        isLive = live
        updateMediaTypeFromUri(uri)
    }

    private fun updateMediaTypeFromUri(uri: String?) {
        if (isLive) {
            mediaType = MediaType.LIVE
            return
        }
        if (uri == null) {
            mediaType = MediaType.UNKNOWN
            return
        }
        val clean = uri.substringBefore('?').substringBefore('#').lowercase()
        mediaType = when {
            clean.endsWith(".m3u8") || isPlaylist -> MediaType.HLS
            clean.endsWith(".mpd") -> MediaType.DASH
            clean.startsWith("content://") || clean.startsWith("file://") -> MediaType.OFFLINE
            clean.startsWith("magnet:") || clean.startsWith("torrent:") -> MediaType.TORRENT
            else -> MediaType.PROGRESSIVE
        }
    }

    fun onPrepared(id: PlayerId?) {
        playerId = id
        lifecycle = Lifecycle.PREPARED
    }

    fun onPlaying() {
        if (lifecycle != Lifecycle.RELEASED) {
            lifecycle = Lifecycle.ACTIVE
        }
    }

    fun onPaused() {
        if (lifecycle != Lifecycle.RELEASED) {
            lifecycle = Lifecycle.PAUSED
        }
    }

    fun onRebufferingChanged(isRebuffering: Boolean, nowRealtimeMs: Long = SystemClock.elapsedRealtime()) {
        if (isRebuffering == rebuffering) return
        rebuffering = isRebuffering
        if (isRebuffering) {
            rebufferCount++
            lastRebufferStartRealtimeMs = nowRealtimeMs
        } else {
            if (lastRebufferStartRealtimeMs > 0L) {
                val duration = (nowRealtimeMs - lastRebufferStartRealtimeMs).coerceAtLeast(0L)
                totalRebufferDurationMs += duration
                lastRebufferStartRealtimeMs = 0L
            }
        }
    }

    fun acquireLease(
        key: String,
        uri: Uri? = null,
        rangeStart: Long = 0L,
        rangeLength: Long = -1L,
        ttlMs: Long = DEFAULT_LEASE_DURATION_MS,
    ): CacheLease {
        val leaseId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val lease = CacheLease(
            leaseId = leaseId,
            key = key,
            uri = uri,
            rangeStart = rangeStart,
            rangeLength = rangeLength,
            createdAtMs = now,
            expiresAtMs = now + ttlMs,
        )
        activeLeases[leaseId] = lease
        return lease
    }

    fun releaseLease(lease: CacheLease?) {
        if (lease != null) {
            activeLeases.remove(lease.leaseId)
        }
    }

    fun releaseLeaseById(leaseId: String) {
        activeLeases.remove(leaseId)
    }

    fun isKeyLeased(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        pruneExpiredLeases(nowMs)
        return activeLeases.values.any { it.key == key && !it.isExpired(nowMs) }
    }

    fun isUriLeased(uriString: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        pruneExpiredLeases(nowMs)
        val norm = PlaybackCacheManager.normalizeUri(uriString)
        return activeLeases.values.any { lease ->
            if (lease.isExpired(nowMs)) return@any false
            val lUri = lease.uri?.toString() ?: return@any false
            PlaybackCacheManager.normalizeUri(lUri) == norm
        }
    }

    fun getActiveLeasedKeys(nowMs: Long = System.currentTimeMillis()): Set<String> {
        pruneExpiredLeases(nowMs)
        return activeLeases.values.mapNotNull { if (!it.isExpired(nowMs)) it.key else null }.toSet()
    }

    fun pruneExpiredLeases(nowMs: Long = System.currentTimeMillis()) {
        val iterator = activeLeases.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(nowMs)) {
                iterator.remove()
            }
        }
    }

    fun clearLeases() {
        activeLeases.clear()
    }

    companion object {
        const val DEFAULT_LEASE_DURATION_MS = 120_000L
        const val BACKWARD_RETENTION_US = 30_000_000L // 30s backward seek recovery window
    }

    fun isResourceActive(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lifecycle == Lifecycle.RELEASED) return false
        if (isKeyLeased(key, nowMs)) return true

        // If prefetch mode is enabled, evaluate rolling media-time retention window
        if (prefetchModeEnabled) {
            val coordinator = prefetchCoordinator
            if (coordinator != null) {
                // If it's currently being downloaded by prefetch worker or requested by Media3
                if (coordinator.scheduler.media3ActiveRequests.any { it.cacheKey == key }) return true
                if (coordinator.scheduler.isActivelyWritingKey(key)) return true

                // Check request registry for media time metadata
                val req = coordinator.scheduler.registry.values.firstOrNull { it.identity.cacheKey == key }
                if (req != null) {
                    if (req.isInit) return true // Initialization segment is always protected
                    val currentPosUs = coordinator.latestSnapshot?.currentPositionUs ?: playbackPositionUs
                    val backwardProtectUs = maxOf(0L, currentPosUs - BACKWARD_RETENTION_US)
                    // If segment end is at or after backward protect window, it is protected
                    if (req.mediaTimeUs + req.durationUs >= backwardProtectUs) {
                        return true
                    }
                    // Segment is older than backward retention window: eligible for eviction!
                    return false
                }
            }
            // For prefetch mode: do not fall through to global prefix match which protects all spans.
            // Spans not actively leased or within retention window are eligible for eviction.
            return false
        }

        // Check against active media URI
        val src = sourceUri ?: return false
        if (key.startsWith(src)) return true

        // Clean prefix check
        val rawUrl = key.substringBefore('|')
        if (rawUrl.startsWith(src)) return true

        val norm = normalizedSourceUri
        if (norm != null && PlaybackCacheManager.normalizeUri(rawUrl) == norm) return true

        // If directory scope (HLS/DASH), check directory prefix when prefetch mode is not active
        if (isPlaylist && !prefetchModeEnabled) {
            val prefix = PlaybackCacheManager.keyPrefix(src, directoryScope = true)
            if (prefix.isNotBlank() && (key.startsWith(prefix) || rawUrl.startsWith(prefix))) {
                return true
            }
        }

        return false
    }

    fun release() {
        if (lifecycle == Lifecycle.RELEASED) return
        lifecycle = Lifecycle.RELEASING
        prefetchCoordinator?.release()
        prefetchCoordinator = null
        clearLeases()
        networkMonitor.reset()
        lifecycle = Lifecycle.RELEASED
        PlaybackCacheManager.unregisterSession(sessionId)
    }
}
