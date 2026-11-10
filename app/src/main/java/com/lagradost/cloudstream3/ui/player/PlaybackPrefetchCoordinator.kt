package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
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
 * Session-scoped coordinator that inspects player state through immutable snapshots
 * and schedules upcoming media downloads using bounded parallel workers and persistent cache.
 *
 * Thread Safety:
 * - All ExoPlayer state is read via PlaybackPlayerSnapshot on the player application looper.
 * - All adapter resolution, request scheduling, and network logic run on Dispatchers.IO.
 */
@OptIn(UnstableApi::class)
class PlaybackPrefetchCoordinator(
    val context: Context,
    val session: PlaybackSessionState,
    val playerProvider: () -> androidx.media3.common.Player?,
    val getCache: () -> SimpleCache?,
    val getCacheDataSourceFactory: () -> CacheDataSource.Factory?,
    val upstreamFactory: DataSource.Factory,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "PrefetchCoordinator"
        const val LOOP_INTERVAL_MS = 1_500L
        const val DIAGNOSTICS_INTERVAL_MS = 10_000L
        const val CONTIGUOUS_GAP_TOLERANCE_US = 500_000L // 500ms segment timestamp jitter
    }

    data class PrefetchCoverageRange(
        val mediaTimeStartUs: Long,
        val mediaTimeEndUs: Long,
        val cacheKey: String,
        val byteOffset: Long = 0L,
        val byteLength: Long = -1L,
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

    private var activeAdapter: PrefetchAdapter? = null
    private var selectedAdapterName = "None"
    private var lastDiagnosticLogTimeMs = 0L

    // Completed coverage ranges verified in cache
    private val completedRanges = ConcurrentHashMap.newKeySet<PrefetchCoverageRange>()
    private var lastReportedCoverageMs = -1L

    /**
     * UI callback invoked on Dispatchers.Main with the contiguous prefetched media position in milliseconds.
     */
    var onPrefetchCoverageChanged: ((Long) -> Unit)? = null

    fun start() {
        if (!session.prefetchModeEnabled || isReleased.get()) {
            return
        }
        if (started.compareAndSet(false, true)) {
            Log.i(TAG, "Prefetch coordinator started for session ${session.sessionId}")
            coordinatorJob = scope.launch {
                runCoordinatorLoop()
            }
        }
    }

    fun onSeek(newPositionUs: Long) {
        if (!started.get() || isReleased.get()) return
        scope.launch {
            scheduler.onSeek(newPositionUs)
            activeAdapter?.onSeek(newPositionUs)
            // Recompute coverage immediately around new seek point
            updateCoverage(newPositionUs, newPositionUs)
            scheduleUpcoming()
        }
    }

    private suspend fun runCoordinatorLoop() {
        while (scope.isActive && !isReleased.get() && session.lifecycle != PlaybackSessionState.Lifecycle.RELEASED) {
            try {
                scheduleUpcoming()
            } catch (t: Throwable) {
                Log.w(TAG, "error in prefetch cycle: ${t.message}")
            }
            delay(LOOP_INTERVAL_MS)
        }
    }

    private suspend fun scheduleUpcoming() {
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

        // 2. Resolve adapter if not yet initialized
        var adapter = activeAdapter
        if (adapter == null) {
            adapter = resolveAdapter(snapshot)
            activeAdapter = adapter
        }

        if (adapter == null) return

        // 3. Compute dynamic usable prefetch budget
        val availableBudgetBytes = computeAvailablePrefetchBudget()
        if (availableBudgetBytes <= 0L) {
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
            cacheKeyFactory = cacheKeyFactory,
        )
        if (hlsAdapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = hlsAdapter.name
            Log.i(TAG, "Selected HLS prefetch adapter for $uri")
            return hlsAdapter
        }

        val dashAdapter = DashPrefetchAdapter(
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (dashAdapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = dashAdapter.name
            Log.i(TAG, "Selected DASH prefetch adapter for $uri")
            return dashAdapter
        }

        val mp4Adapter = Mp4RangePrefetchAdapter(
            sourceUri = uri,
            upstreamFactory = upstreamFactory,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (mp4Adapter.isSupported(uri, mime, manifest)) {
            selectedAdapterName = mp4Adapter.name
            Log.i(TAG, "Selected progressive MP4 prefetch adapter for $uri")
            return mp4Adapter
        }

        // If format is not ready (e.g. manifest not loaded yet), do not permanently disable
        if (manifest == null && (uri.path?.endsWith(".m3u8") == true || uri.path?.endsWith(".mpd") == true)) {
            return null
        }

        selectedAdapterName = "Media3 Standard (Fallback)"
        Log.i(TAG, "No specialized prefetch adapter for $uri; using standard Media3 loading")
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
        }.getOrDefault(0L)
        val freeDiskUsable = maxOf(0L, freeDisk - PrefetchRequestScheduler.MIN_DISK_RESERVE_BYTES)

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
        if (!request.isInit && request.durationUs > 0L) {
            completedRanges += PrefetchCoverageRange(
                mediaTimeStartUs = request.mediaTimeUs,
                mediaTimeEndUs = request.mediaTimeUs + request.durationUs,
                cacheKey = request.identity.cacheKey,
                byteOffset = request.identity.position,
                byteLength = request.identity.length,
            )
            latestSnapshot?.let { snapshot ->
                updateCoverage(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
            }
        }
    }

    /**
     * Refreshes completed ranges from SimpleCache, prunes evicted ranges, and computes contiguous coverage.
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
                    )
                }
            }
        }

        // Prune ranges that are no longer fully cached (e.g. evicted old spans)
        completedRanges.removeIf { range ->
            !CacheCoverageVerifier.isFullyCached(cache, range.cacheKey, range.byteOffset, range.byteLength)
        }

        updateCoverage(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
    }

    private fun updateCoverage(currentPositionUs: Long, bufferedPositionUs: Long) {
        val playheadUs = maxOf(currentPositionUs, bufferedPositionUs)
        val sorted = completedRanges
            .filter { it.mediaTimeEndUs >= playheadUs }
            .sortedBy { it.mediaTimeStartUs }

        var contiguousEndUs = playheadUs
        for (range in sorted) {
            if (range.mediaTimeStartUs <= contiguousEndUs + CONTIGUOUS_GAP_TOLERANCE_US) {
                contiguousEndUs = maxOf(contiguousEndUs, range.mediaTimeEndUs)
            } else {
                // Gap detected: stop contiguous coverage indicator here
                break
            }
        }

        val coverageMs = contiguousEndUs / 1000L
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

        val posSec = snapshot.currentPositionMs / 1000L
        val bufSec = snapshot.bufferedPositionMs / 1000L
        val prefetchSec = maxOf(bufSec, lastReportedCoverageMs / 1000L)
        val prefetchAheadSec = maxOf(0L, prefetchSec - posSec)

        val safeUri = session.sourceUri?.let { u ->
            val parsed = Uri.parse(u)
            "${parsed.scheme}://${parsed.host}${parsed.path}"
        } ?: "none"

        Log.d(
            TAG,
            "Diagnostics: [PrefetchMode=${session.prefetchModeEnabled}] " +
                "Adapter=$selectedAdapterName " +
                "Source=$safeUri " +
                "Cache=${PlaybackCacheManager.usageBytes() / (1024 * 1024)}MB / " +
                "Limit=${session.prefetchLimitBytes / (1024 * 1024)}MB " +
                "Reclaimable=${PlaybackCacheManager.reclaimableBytes(session, snapshot) / (1024 * 1024)}MB " +
                "AvailableBudget=${availableBudgetBytes / (1024 * 1024)}MB " +
                "FreeDisk=${freeDisk / (1024 * 1024)}MB " +
                "Workers=${scheduler.getActiveWorkerCount()}/${scheduler.getTargetWorkers()} " +
                "QueueDepth=${scheduler.getQueueDepth()} " +
                "Prefetched=${scheduler.prefetchedBytes.get() / (1024 * 1024)}MB " +
                "PrefetchRunwayAhead=${prefetchAheadSec}s " +
                "Hits=${scheduler.cacheHitCount.get()} Misses=${scheduler.cacheMissCount.get()} " +
                "Reuses=${scheduler.duplicateReuseCount.get()} Cancels=${scheduler.cancelledCount.get()}" +
                (if (reasonStopped != null) " StoppedReason=[$reasonStopped]" else "")
        )
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            Log.i(TAG, "Releasing prefetch coordinator for session ${session.sessionId}")
            coordinatorJob?.cancel()
            scheduler.release()
            activeAdapter?.release()
            activeAdapter = null
            completedRanges.clear()
            onPrefetchCoverageChanged = null
            scope.cancel()
        }
    }
}
