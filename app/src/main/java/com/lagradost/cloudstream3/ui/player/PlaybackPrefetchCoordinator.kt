package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central coordinator for Prefetch Mode.
 *
 * Coordinates:
 * - Thread confinement: captures immutable Player snapshots on ExoPlayer's looper.
 * - Dynamic format adapter resolution (HLS, DASH, MP4) with explicit lifecycle states.
 * - Usable storage-budget calculation with strict limit enforcement and atomic reservations.
 * - Per-interval dependency tracking (init segments, AES-128 keys) without global seekbar freeze.
 * - Verified offline playability coverage, which is what the blue seekbar paints.
 */
@OptIn(UnstableApi::class)
class PlaybackPrefetchCoordinator(
    val context: Context,
    val session: PlaybackSessionState,
    val playerProvider: () -> Player?,
    val getCache: () -> SimpleCache?,
    val getCacheDataSourceFactory: () -> CacheDataSource.Factory?,
    val upstreamFactory: DataSource.Factory? = null,
    val cacheKeyFactory: (DataSpec) -> String = { it.key ?: it.uri.toString() },
    val defaultHeaders: Map<String, String> = emptyMap(),
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "PrefetchCoordinator"
        const val LOOP_INTERVAL_MS = 1_500L
        const val DIAGNOSTICS_INTERVAL_MS = 10_000L
        const val CONTIGUOUS_GAP_TOLERANCE_US = 500_000L // 500ms segment timestamp jitter
        const val WATCHDOG_TIMEOUT_MS = 30_000L
    }

    data class PrefetchCoverageRange(
        val mediaTimeStartUs: Long,
        val mediaTimeEndUs: Long,
        val cacheKey: String,
        val byteOffset: Long = 0L,
        val byteLength: Long = -1L,
        val trackType: Int = C.TRACK_TYPE_VIDEO,
        val dependencyKeys: List<String> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var coordinatorJob: Job? = null
    private val started = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    @Volatile
    var latestSnapshot: PlaybackPlayerSnapshot? = null
        private set

    val scheduler = PrefetchRequestScheduler(
        context = context,
        session = session,
        getCache = getCache,
        getCacheDataSourceFactory = getCacheDataSourceFactory,
        clock = clock,
    )

    internal var activeAdapter: PrefetchAdapter? = null
    private var selectedAdapterName = "None"
    private var lastDiagnosticLogTimeMs = 0L
    private var lastProgressTimeMs = clock()
    private var lastSourceUri: Uri? = null

    // Completed coverage ranges verified in cache
    private val completedRanges = ConcurrentHashMap.newKeySet<PrefetchCoverageRange>()
    internal var lastReportedCoverageMs = -1L

    /**
     * UI callback invoked on Dispatchers.Main with the contiguous *offline-playable* media position
     * in milliseconds.
     */
    var onPrefetchCoverageChanged: ((Long) -> Unit)? = null

    private val audit: PlaybackCacheAudit?
        get() = session.cacheAudit

    fun start() {
        if (!session.prefetchModeEnabled || isReleased.get()) {
            return
        }
        if (started.compareAndSet(false, true)) {
            Log.i(TAG, "Prefetch coordinator started for session ${session.sessionId}")
            audit?.let { a ->
                a.coverageOracle = { key, position, length -> isWithinClaimedCoverage(key, position, length) }
                a.onClaimedRangeMissed = { key -> invalidateCoverageFor(key) }
            }
            coordinatorJob = scope.launch {
                runCoordinatorLoop()
            }
        }
    }

    fun onSeek(newPositionUs: Long) {
        if (isReleased.get()) return
        scope.launch {
            scheduler.onSeek(newPositionUs)
            activeAdapter?.onSeek(newPositionUs)
            updateCoverage(newPositionUs, newPositionUs)
            scheduleUpcoming()
        }
    }

    private suspend fun runCoordinatorLoop() {
        while (scope.isActive && !isReleased.get() && session.lifecycle != PlaybackSessionState.Lifecycle.RELEASED) {
            try {
                scheduler.ensureWorkerPool()
                scheduleUpcoming()
                checkWatchdog()
            } catch (t: Throwable) {
                Log.w(TAG, "error in prefetch cycle: ${t.message}")
            }
            delay(LOOP_INTERVAL_MS)
        }
    }

    private fun checkWatchdog() {
        val now = clock()
        if (scheduler.getQueueDepth() > 0 && scheduler.getActiveWorkerCount() == 0) {
            if (now - lastProgressTimeMs > WATCHDOG_TIMEOUT_MS) {
                Log.w(TAG, "Prefetch watchdog: worker pool stalled with queue depth ${scheduler.getQueueDepth()}; reviving workers")
                scheduler.ensureWorkerPool()
                lastProgressTimeMs = now
            }
        }
    }

    internal suspend fun scheduleUpcoming() {
        val uriStr = session.sourceUri ?: return
        val parsedUri = Uri.parse(uriStr)

        // 1. Capture thread-safe immutable player snapshot on player looper
        val snapshot = PlaybackPlayerSnapshot.capture(
            playerProvider = playerProvider,
            sourceUri = parsedUri,
            selectedMime = session.selectedMime,
            isLive = session.isLive,
        ) ?: return

        latestSnapshot = snapshot
        if (snapshot.isEnded) return

        // Check for stream / media item transition
        if (lastSourceUri != null && lastSourceUri != snapshot.sourceUri) {
            Log.i(TAG, "Stream change detected; resetting coordinator state")
            activeAdapter?.release()
            activeAdapter = null
            completedRanges.clear()
            scheduler.onSeek(0L)
            lastReportedCoverageMs = -1L
        }
        lastSourceUri = snapshot.sourceUri

        // 2. Resolve adapter if not yet initialized. Resolution may return an adapter that is only
        //    WAITING_FOR_MANIFEST; readiness is retried from every future snapshot.
        var adapter = activeAdapter
        if (adapter == null) {
            adapter = resolveAdapter(snapshot)
            activeAdapter = adapter
        }

        if (adapter == null) return

        val currentState = adapter.checkReadiness(snapshot)
        if (currentState != AdapterState.READY) {
            maybeLogDiagnostics(snapshot, 0L, reasonStopped = "Adapter waiting: $currentState")
            if (currentState == AdapterState.RELEASED || currentState == AdapterState.UNSUPPORTED) {
                activeAdapter = null
            }
            return
        }

        // 3. Compute dynamic usable prefetch budget
        val availableBudgetBytes = computeAvailablePrefetchBudget()
        if (availableBudgetBytes <= 0L) {
            PlaybackCacheManager.ensureRoomForPrefetch(
                session = session,
                incomingBytes = 1024 * 1024L,
                reservedBytes = scheduler.reservedBytes.get(),
                snapshot = snapshot,
            )
            refreshCompletedCoverage(snapshot)
            maybeLogDiagnostics(snapshot, availableBudgetBytes, reasonStopped = "Storage/Cache limit reached")
            return
        }

        // 4. Query upcoming requests from the adapter
        val upcoming = adapter.getUpcomingRequests(snapshot, availableBudgetBytes)

        for (request in upcoming) {
            scheduler.enqueue(request)
        }

        // 5. Update completed coverage from cache and notify UI
        refreshCompletedCoverage(snapshot)
        maybeLogDiagnostics(snapshot, availableBudgetBytes)
    }

    private suspend fun resolveAdapter(snapshot: PlaybackPlayerSnapshot): PrefetchAdapter? {
        val uri = snapshot.sourceUri ?: return null
        val mime = snapshot.selectedMime
        val manifest = snapshot.currentManifest

        val hlsAdapter = HlsPrefetchAdapter(
            defaultHeaders = defaultHeaders,
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (hlsAdapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = hlsAdapter.name
            Log.i(TAG, "Selected HLS prefetch adapter (state=${hlsAdapter.state})")
            return hlsAdapter
        }

        val dashAdapter = DashPrefetchAdapter(
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (dashAdapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = dashAdapter.name
            Log.i(TAG, "Selected DASH prefetch adapter (state=${dashAdapter.state})")
            return dashAdapter
        }

        val mp4Adapter = Mp4RangePrefetchAdapter(
            sourceUri = uri,
            upstreamFactory = upstreamFactory ?: return null,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (mp4Adapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = mp4Adapter.name
            Log.i(TAG, "Selected progressive MP4 prefetch adapter (state=${mp4Adapter.state})")
            return mp4Adapter
        }

        selectedAdapterName = "Media3 Standard (Fallback)"
        Log.i(TAG, "No specialized prefetch adapter; using standard Media3 loading")
        return null
    }

    /**
     * Calculates the dynamic byte budget available for prefetching based on user limit,
     * cache occupancy, device free space, active worker reservations, and rolling reclaimable bytes.
     */
    fun computeAvailablePrefetchBudget(): Long {
        val limitBytes = if (session.prefetchLimitBytes > 0L) {
            session.prefetchLimitBytes
        } else {
            PlaybackCacheManager.maxBytes()
        }

        val currentUsage = PlaybackCacheManager.usageBytes()
        val reserved = scheduler.reservedBytes.get()
        val reclaimable = PlaybackCacheManager.reclaimableBytes(session, latestSnapshot)

        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrNull()
        val freeDiskUsable = if (freeDisk != null) {
            maxOf(0L, freeDisk - PrefetchRequestScheduler.MIN_DISK_RESERVE_BYTES)
        } else {
            Long.MAX_VALUE
        }

        val cacheHeadroom = if (limitBytes > 0L) {
            maxOf(0L, (limitBytes - currentUsage) + reclaimable - reserved)
        } else {
            Long.MAX_VALUE
        }
        return minOf(cacheHeadroom, freeDiskUsable)
    }

    /**
     * Registers a completed prefetch request to immediately extend coverage if verified.
     */
    fun registerCompletedRequest(request: PrefetchRequest) {
        lastProgressTimeMs = clock()
        if (!request.isInit && request.durationUs > 0L) {
            completedRanges += PrefetchCoverageRange(
                mediaTimeStartUs = request.mediaTimeUs,
                mediaTimeEndUs = request.mediaTimeUs + request.durationUs,
                cacheKey = request.identity.cacheKey,
                byteOffset = request.identity.position,
                byteLength = request.identity.length,
                trackType = request.trackType,
                dependencyKeys = request.dependencyKeys,
            )
            latestSnapshot?.let { snapshot ->
                updateCoverage(snapshot)
            }
        }
    }

    /**
     * Refreshes completed ranges from SimpleCache, prunes evicted ranges, and computes
     * true verified OfflinePlayableCoverage across all dependencies.
     */
    private fun refreshCompletedCoverage(snapshot: PlaybackPlayerSnapshot) {
        val cache = getCache() ?: return

        // Verify completed requests in registry
        for ((identity, request) in scheduler.registry) {
            if (!request.isInit && request.durationUs > 0L) {
                if (CacheCoverageVerifier.isFullyCached(cache, identity.cacheKey, identity.position, identity.length)) {
                    completedRanges += PrefetchCoverageRange(
                        mediaTimeStartUs = request.mediaTimeUs,
                        mediaTimeEndUs = request.mediaTimeUs + request.durationUs,
                        cacheKey = identity.cacheKey,
                        byteOffset = identity.position,
                        byteLength = identity.length,
                        trackType = request.trackType,
                        dependencyKeys = request.dependencyKeys,
                    )
                }
            }
        }

        // Prune ranges that are no longer fully cached (e.g. evicted old spans)
        completedRanges.removeIf { range ->
            !CacheCoverageVerifier.isFullyCached(cache, range.cacheKey, range.byteOffset, range.byteLength)
        }

        updateCoverage(snapshot)
    }

    /** Drops coverage claims for a cache key that playback proved was not actually readable. */
    internal fun invalidateCoverageFor(cacheKey: String) {
        val removed = completedRanges.removeIf { it.cacheKey == cacheKey }
        if (removed) {
            Log.w(TAG, "invalidating unverified coverage for keyHash=${PlaybackCacheAudit.safeKeyHash(cacheKey)}")
            latestSnapshot?.let { updateCoverage(it) }
        }
    }

    private fun isWithinClaimedCoverage(cacheKey: String, position: Long, length: Long): Boolean {
        if (lastReportedCoverageMs <= 0L) return false
        return completedRanges.any { range ->
            range.cacheKey == cacheKey &&
                range.mediaTimeEndUs <= lastReportedCoverageMs * 1000L + CONTIGUOUS_GAP_TOLERANCE_US
        }
    }

    private fun dependenciesSatisfied(cache: Cache?, range: PrefetchCoverageRange): Boolean {
        if (range.dependencyKeys.isEmpty()) return true
        return range.dependencyKeys.all { depKey ->
            CacheCoverageVerifier.isResourceComplete(cache, depKey)
        }
    }

    private fun computeContiguousSpanEnd(
        cache: Cache?,
        ranges: List<PrefetchCoverageRange>,
        playheadUs: Long,
    ): Long {
        if (cache == null) return playheadUs

        // Filter ranges that have ALL per-interval dependencies (init segment, AES key) verified.
        // A missing key for a FUTURE interval must not block an earlier interval.
        val validRanges = ranges.filter { dependenciesSatisfied(cache, it) }

        val sorted = validRanges
            .filter { it.mediaTimeEndUs >= playheadUs }
            .sortedBy { it.mediaTimeStartUs }

        var contiguousEndUs = playheadUs
        for (range in sorted) {
            if (range.mediaTimeStartUs <= contiguousEndUs + CONTIGUOUS_GAP_TOLERANCE_US) {
                contiguousEndUs = maxOf(contiguousEndUs, range.mediaTimeEndUs)
            } else {
                break
            }
        }
        return contiguousEndUs
    }

    private fun updateCoverage(snapshot: PlaybackPlayerSnapshot) {
        updateCoverage(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
    }

    private fun updateCoverage(currentPositionUs: Long, bufferedPositionUs: Long) {
        val cache = getCache()
        val playheadUs = maxOf(currentPositionUs, bufferedPositionUs)

        val videoRanges = completedRanges.filter { it.trackType == C.TRACK_TYPE_VIDEO }
        val audioRanges = completedRanges.filter { it.trackType == C.TRACK_TYPE_AUDIO }

        val contiguousVideoEndUs = computeContiguousSpanEnd(cache, videoRanges, playheadUs)
        val contiguousAudioEndUs = computeContiguousSpanEnd(cache, audioRanges, playheadUs)
        val dependency = activeAdapter?.audioDependency ?: AudioDependency.MUXED

        // The blue line means "verified offline-playable", not "some bytes downloaded".
        val offlinePlayableUs = when (dependency) {
            AudioDependency.MUXED, AudioDependency.NONE -> contiguousVideoEndUs
            AudioDependency.SEPARATE_READY -> minOf(contiguousVideoEndUs, contiguousAudioEndUs)
            // Separate audio required but unverifiable: never advance past the playhead.
            AudioDependency.SEPARATE_UNAVAILABLE, AudioDependency.UNKNOWN -> playheadUs
        }

        audit?.let { a ->
            a.offlinePlayableEndMs = offlinePlayableUs / 1000L
            a.missingAudioDependency = when {
                dependency == AudioDependency.SEPARATE_UNAVAILABLE ->
                    (activeAdapter as? HlsPrefetchAdapter)?.audioFailureReason ?: "separate audio unverifiable"
                dependency == AudioDependency.SEPARATE_READY && contiguousAudioEndUs < contiguousVideoEndUs ->
                    "audio coverage stops at ${contiguousAudioEndUs / 1000L}ms"
                else -> null
            }
            a.missingVideoDependency = if (contiguousVideoEndUs <= playheadUs && videoRanges.isNotEmpty()) {
                "video coverage exhausted at ${contiguousVideoEndUs / 1000L}ms"
            } else {
                null
            }
            a.missingInitializationDependency = completedRanges
                .firstOrNull { !dependenciesSatisfied(cache, it) }
                ?.dependencyKeys
                ?.firstOrNull { !CacheCoverageVerifier.isResourceComplete(cache, it) }
                ?.let { "init/key missing for range at ${it.take(24)}" }
        }

        notifyCoverageChanged(offlinePlayableUs / 1000L)
    }

    private fun notifyCoverageChanged(coverageMs: Long) {
        if (coverageMs != lastReportedCoverageMs) {
            lastReportedCoverageMs = coverageMs
            val listener = onPrefetchCoverageChanged
            if (listener != null) {
                scope.launch {
                    val mainLooper = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
                    if (mainLooper != null) {
                        val dispatched = runCatching {
                            withContext(Dispatchers.Main.immediate) {
                                listener(coverageMs)
                            }
                        }.isSuccess
                        if (!dispatched) {
                            android.os.Handler(mainLooper).post { listener(coverageMs) }
                        }
                    } else {
                        listener(coverageMs)
                    }
                }
            }
        }
    }

    private fun maybeLogDiagnostics(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
        reasonStopped: String? = null,
    ) {
        val now = clock()
        if (now - lastDiagnosticLogTimeMs < DIAGNOSTICS_INTERVAL_MS) return
        lastDiagnosticLogTimeMs = now

        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        val adapterStateStr = activeAdapter?.state?.name ?: "NONE"
        val a = audit

        val block = if (a != null) {
            a.diagnostics(
                cacheUsageBytes = PlaybackCacheManager.usageBytes(),
                workersActive = scheduler.getActiveWorkerCount(),
                workersTarget = scheduler.getTargetWorkers(),
                queued = scheduler.getQueueDepth(),
            ) + "\nOFFLINE:" +
                "\n  verifiedEndpoint=" + formatPosition(a.offlinePlayableEndMs) +
                "\n  missingVideo=" + (a.missingVideoDependency ?: "none") +
                "\n  missingAudio=" + (a.missingAudioDependency ?: "none") +
                "\n  missingInitialization=" + (a.missingInitializationDependency ?: "none")
        } else {
            "PREFETCH: downloaded=${PlaybackCacheAudit.formatBytes(scheduler.prefetchedBytes.get())} " +
                "workers=${scheduler.getActiveWorkerCount()}/${scheduler.getTargetWorkers()} " +
                "queued=${scheduler.getQueueDepth()}"
        }

        Log.d(
            TAG,
            "session=${session.sessionId} adapter=$selectedAdapterName($adapterStateStr) " +
                "availableBudget=${PlaybackCacheAudit.formatBytes(availableBudgetBytes)} " +
                "freeDisk=${PlaybackCacheAudit.formatBytes(freeDisk)} " +
                (if (reasonStopped != null) "stopped=[$reasonStopped]\n" else "\n") + block,
        )
    }

    private fun formatPosition(ms: Long): String {
        if (ms < 0L) return "none"
        val totalSeconds = ms / 1000L
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            Log.i(TAG, "Releasing prefetch coordinator for session ${session.sessionId}")
            coordinatorJob?.cancel()
            scheduler.release()
            activeAdapter?.release()
            activeAdapter = null
            completedRanges.clear()
            audit?.let {
                it.coverageOracle = null
                it.onClaimedRangeMissed = null
            }
            onPrefetchCoverageChanged = null
            scope.cancel()
        }
    }
}
