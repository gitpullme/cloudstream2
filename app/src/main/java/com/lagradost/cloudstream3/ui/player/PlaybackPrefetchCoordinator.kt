package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-scoped coordinator that inspects player state and schedules upcoming media downloads
 * using bounded parallel workers and the persistent Media3 cache.
 */
@OptIn(UnstableApi::class)
class PlaybackPrefetchCoordinator(
    val context: Context,
    val session: PlaybackSessionState,
    val playerProvider: () -> ExoPlayer?,
    val getCache: () -> SimpleCache?,
    val getCacheDataSourceFactory: () -> CacheDataSource.Factory?,
    val upstreamFactory: DataSource.Factory,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "PrefetchCoordinator"
        const val DEFAULT_FORWARD_WINDOW_US = 120_000_000L // 120s forward runway
        const val LOOP_INTERVAL_MS = 2_000L // poll every 2s
        const val DIAGNOSTICS_INTERVAL_MS = 10_000L // log diagnostics every 10s
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var schedulerJob: Job? = null
    private val started = AtomicBoolean(false)

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

    fun start() {
        if (!session.prefetchModeEnabled) {
            Log.d(TAG, "Prefetch mode disabled for session ${session.sessionId}")
            return
        }
        if (started.compareAndSet(false, true)) {
            Log.i(TAG, "Prefetch coordinator started for session ${session.sessionId}")
            schedulerJob = scope.launch {
                runCoordinatorLoop()
            }
        }
    }

    fun onSeek(newPositionUs: Long) {
        if (!started.get()) return
        scope.launch {
            scheduler.onSeek(newPositionUs)
            activeAdapter?.onSeek(newPositionUs)
            scheduleUpcoming()
        }
    }

    private suspend fun runCoordinatorLoop() {
        while (scope.isActive && session.lifecycle != PlaybackSessionState.Lifecycle.RELEASED) {
            try {
                if (activeAdapter == null) {
                    resolveAdapter()
                }

                scheduleUpcoming()
                maybeLogDiagnostics()
            } catch (t: Throwable) {
                Log.w(TAG, "error in prefetch coordinator cycle: ${t.message}")
            }
            delay(LOOP_INTERVAL_MS)
        }
    }

    private suspend fun resolveAdapter() {
        val player = playerProvider() ?: return
        val uriStr = session.sourceUri ?: return
        val uri = Uri.parse(uriStr)
        val mime = session.selectedMime

        val hlsAdapter = HlsPrefetchAdapter(
            manifestProvider = { player.currentManifest },
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (hlsAdapter.isSupported(uri, mime)) {
            activeAdapter = hlsAdapter
            selectedAdapterName = "HLS"
            Log.i(TAG, "Selected HLS prefetch adapter for $uriStr")
            return
        }

        val dashAdapter = DashPrefetchAdapter(
            manifestProvider = { player.currentManifest },
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (dashAdapter.isSupported(uri, mime)) {
            activeAdapter = dashAdapter
            selectedAdapterName = "DASH"
            Log.i(TAG, "Selected DASH prefetch adapter for $uriStr")
            return
        }

        val mp4Adapter = Mp4RangePrefetchAdapter(
            sourceUri = uri,
            upstreamFactory = upstreamFactory,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = cacheKeyFactory,
        )
        if (mp4Adapter.isSupported(uri, mime)) {
            activeAdapter = mp4Adapter
            selectedAdapterName = "Progressive MP4"
            Log.i(TAG, "Selected progressive MP4 prefetch adapter for $uriStr")
            return
        }

        selectedAdapterName = "Unsupported (Media3 Fallback)"
        Log.i(TAG, "No specialized prefetch adapter supported for $uriStr; falling back to standard Media3 loading")
    }

    private suspend fun scheduleUpcoming() {
        val adapter = activeAdapter ?: return
        val player = playerProvider() ?: return

        val currentPositionUs = player.currentPosition * 1000L
        val bufferedPositionUs = player.bufferedPosition * 1000L
        val durationUs = if (player.duration > 0) player.duration * 1000L else 0L

        // Check if playback has completed
        if (player.playbackState == Player.STATE_ENDED) return

        val upcoming = adapter.getUpcomingRequests(
            currentPositionUs = currentPositionUs,
            bufferedPositionUs = bufferedPositionUs,
            durationUs = durationUs,
            forwardWindowUs = DEFAULT_FORWARD_WINDOW_US,
        )

        for (request in upcoming) {
            scheduler.enqueue(request)
        }
    }

    private fun maybeLogDiagnostics() {
        val now = clock()
        if (now - lastDiagnosticLogTimeMs < DIAGNOSTICS_INTERVAL_MS) return
        lastDiagnosticLogTimeMs = now

        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        val player = playerProvider()
        val posSec = (player?.currentPosition ?: 0L) / 1000L
        val bufSec = (player?.bufferedPosition ?: 0L) / 1000L
        val aheadSec = maxOf(0L, bufSec - posSec)

        val safeUri = session.sourceUri?.let { u ->
            val parsed = Uri.parse(u)
            "${parsed.scheme}://${parsed.host}${parsed.path}"
        } ?: "none"

        Log.d(
            TAG,
            "Diagnostics: [PrefetchMode=${session.prefetchModeEnabled}] " +
                "Adapter=$selectedAdapterName " +
                "Source=$safeUri " +
                "CacheUsage=${PlaybackCacheManager.usageBytes() / (1024 * 1024)}MB / " +
                "Limit=${session.prefetchLimitBytes / (1024 * 1024)}MB " +
                "FreeDisk=${freeDisk / (1024 * 1024)}MB " +
                "Workers=${scheduler.getActiveWorkerCount()}/${scheduler.getTargetWorkers()} " +
                "QueueDepth=${scheduler.getQueueDepth()} " +
                "Prefetched=${scheduler.prefetchedBytes.get() / (1024 * 1024)}MB " +
                "RunwayAhead=${aheadSec}s " +
                "Hits=${scheduler.cacheHitCount.get()} Misses=${scheduler.cacheMissCount.get()} " +
                "Reuses=${scheduler.duplicateReuseCount.get()} Cancels=${scheduler.cancelledCount.get()}"
        )
    }

    fun release() {
        if (started.compareAndSet(true, false)) {
            Log.i(TAG, "releasing prefetch coordinator for session ${session.sessionId}")
            schedulerJob?.cancel()
            scheduler.release()
            activeAdapter?.release()
            activeAdapter = null
            scope.cancel()
        }
    }
}
