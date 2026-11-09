package com.lagradost.cloudstream3.ui.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.lagradost.cloudstream3.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * A [LoadControl] that keeps Media3's allocator/back-buffer/playback-start mechanics but replaces
 * only the "should we load more?" decision with [ForwardBufferPolicy].
 *
 * The policy is fed by real measurements from [PlaybackNetworkMonitor] and Media3's own
 * [LoadControl.Parameters] (media position, forward buffer, playback speed, rebuffering state).
 * It therefore starts replenishing well before Media3's fixed threshold when throughput is low or
 * volatile, while still respecting the user's Video Buffer Length as the upper target and the
 * device's allocator budget as the memory ceiling.
 *
 * All calls are expected on ExoPlayer's playback thread, but per-player state is synchronized
 * because preloading and other Media3 components may call a shared control from a different
 * thread in the future.
 */
@OptIn(UnstableApi::class)
class ProactiveLoadControl private constructor(
    private val delegate: DefaultLoadControl,
    private val config: ForwardBufferPolicy.Config,
    private val appContext: Context,
) : LoadControl {

    private class PlayerState(
        @Volatile var plan: ForwardBufferPolicy.Plan,
        var isLoading: Boolean = false,
        var lastReason: ForwardBufferPolicy.Reason? = null,
        var lastLogRealtimeMs: Long = 0L,
        var lastLiveCheckRealtimeMs: Long = 0L,
        var cachedBytes: Long = 0L,
        var cacheReadEvents: Long = 0L,
        var cacheIgnoredCount: Long = 0L,
        var lastCacheSizeBytes: Long = 0L,
        var videoWidth: Int = 0,
        var videoHeight: Int = 0,
        var selectedMime: String? = null,
        var lastForwardMs: Long = 0L,
        var playbackState: Int = Player.STATE_IDLE,
    )

    private val states = ConcurrentHashMap<PlayerId, PlayerState>()
    private val allocators = ConcurrentHashMap<PlayerId, Allocator>()

    /** Receives real upstream request samples. Attach this to the network data source factory. */
    val transferListener = PlaybackNetworkMonitor()

    /**
     * CacheDataSource reports cached bytes and cache-ignore events here. Cached bytes are
     * deliberately not treated as network throughput.
     */
    val cacheEventListener: CacheDataSource.EventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            for (state in states.values) {
                synchronized(state) {
                    state.cachedBytes += cachedBytesRead
                    state.cacheReadEvents += 1
                    state.lastCacheSizeBytes = cacheSizeBytes
                }
            }
        }

        override fun onCacheIgnored(reason: Int) {
            for (state in states.values) {
                synchronized(state) {
                    state.cacheIgnoredCount += 1
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "cache ignored reason=$reason")
            }
        }
    }

    /**
     * Current ExoPlayer playback state, mirrored by CS3IPlayer for diagnostics and for the policy's
     * end-of-stream guard. Isolated per player instance in [PlayerState].
     */
    @Volatile
    var playbackState: Int = Player.STATE_IDLE
        set(value) {
            field = value
            for (s in states.values) {
                synchronized(s) {
                    s.playbackState = value
                }
            }
        }

    fun setPlaybackState(playerId: PlayerId, state: Int) {
        states[playerId]?.let { s ->
            synchronized(s) {
                s.playbackState = state
            }
        }
    }

    fun isLiveStream(): Boolean = states.values.any { it.plan.isLive }

    @Volatile
    private var memoryPressureUntilMs: Long = 0L

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                memoryPressureUntilMs =
                    SystemClock.elapsedRealtime() + ForwardBufferPolicy.MEMORY_PRESSURE_HOLD_MS
                Log.w(TAG, "memory pressure level=$level, reducing buffer target temporarily")
            }
        }

        override fun onLowMemory() {
            memoryPressureUntilMs =
                SystemClock.elapsedRealtime() + ForwardBufferPolicy.MEMORY_PRESSURE_HOLD_MS
            Log.w(TAG, "onLowMemory, reducing buffer target temporarily")
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            // Not relevant to buffer policy.
        }
    }

    init {
        appContext.registerComponentCallbacks(componentCallbacks)
    }

    override fun onPrepared(playerId: PlayerId) {
        delegate.onPrepared(playerId)
        val plan = ForwardBufferPolicy.createPlan(
            config = config,
            isLive = false,
            selectedBitrateBps = 0L,
        )
        states[playerId] = PlayerState(plan)
        allocators[playerId] = delegate.getAllocator(playerId)
        logPlan("prepared", plan)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)

        val state = states[parameters.playerId] ?: PlayerState(
            ForwardBufferPolicy.createPlan(config, isLive = false, selectedBitrateBps = 0L)
        ).also { states[parameters.playerId] = it }

        val isLive = parameters.isLive()
        val trackInfo = trackSelections.trackInfo()
        synchronized(state) {
            state.plan = ForwardBufferPolicy.createPlan(config, isLive, trackInfo.bitrateBps)
            state.videoWidth = trackInfo.width
            state.videoHeight = trackInfo.height
            state.selectedMime = trackInfo.mime
        }
        logPlan("tracks selected", state.plan)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val playerId = parameters.playerId
        val state = states[playerId] ?: PlayerState(
            ForwardBufferPolicy.createPlan(config, isLive = false, selectedBitrateBps = 0L)
        ).also { states[playerId] = it }

        // Live state changes, dynamic sliding playlists and progressive live streams are tracked
        // per player instance without mutating global disk-cache bypass for other players.
        val isLiveNow = parameters.isLive()
        val liveStateChanged = synchronized(state) {
            val now = SystemClock.elapsedRealtime()
            if (isLiveNow != state.plan.isLive ||
                now - state.lastLiveCheckRealtimeMs >= LIVE_CHECK_INTERVAL_MS
            ) {
                state.lastLiveCheckRealtimeMs = now
                if (isLiveNow != state.plan.isLive) {
                    state.plan = ForwardBufferPolicy.createPlan(
                        config = config,
                        isLive = isLiveNow,
                        selectedBitrateBps = state.plan.selectedBitrateBps,
                    )
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        if (liveStateChanged) {
            logPlan("live state changed isLive=$isLiveNow", state.plan)
        }

        val allocatedBytes = try {
            allocatorFor(playerId).totalBytesAllocated.toLong()
        } catch (_: Throwable) {
            // The player may be between onStopped/onPrepared. The time policy can still decide.
            0L
        }
        val network = transferListener.snapshot()
        val memoryPressure = SystemClock.elapsedRealtime() < memoryPressureUntilMs
        // Snapshot cheap, lock-free-by-SimpleCache values before taking the policy state lock.
        // SimpleCache can hold its own lock while delivering cache callbacks, so taking the
        // cache lock while holding the policy lock would be a lock-order inversion. Production
        // builds skip this entirely; it only feeds debug diagnostics.
        val cacheSnapshot = if (BuildConfig.DEBUG) {
            PlaybackCacheManager.snapshot()
        } else {
            PlaybackCacheManager.CacheSnapshot(0L, 0L, false, 0L)
        }
        val cacheBypassed = BuildConfig.DEBUG && PlaybackCacheManager.isBypassingCache()

        val decision = synchronized(state) {
            val decision = ForwardBufferPolicy.decide(
                plan = state.plan,
                isLoading = state.isLoading,
                bufferedDurationUs = parameters.bufferedDurationUs,
                allocatedBytes = allocatedBytes,
                playbackSpeed = parameters.playbackSpeed,
                network = network,
                playbackState = state.playbackState,
                playWhenReady = parameters.playWhenReady,
                rebuffering = parameters.rebuffering,
                memoryPressure = memoryPressure,
            )
            val changed = decision.isLoading != state.isLoading
            val reasonChanged = decision.reason != state.lastReason
            state.isLoading = decision.isLoading
            state.lastReason = decision.reason
            logDecision(
                parameters,
                state,
                allocatedBytes,
                network,
                decision,
                changed,
                reasonChanged,
                cacheSnapshot,
                cacheBypassed,
            )
            decision
        }
        return decision.isLoading
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean {
        return delegate.shouldContinuePreloading(
            playerId,
            timeline,
            mediaPeriodId,
            bufferedDurationUs,
        )
    }

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun onStopped(playerId: PlayerId) {
        delegate.onStopped(playerId)
        states.remove(playerId)
        allocators.remove(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        delegate.onReleased(playerId)
        states.remove(playerId)
        allocators.remove(playerId)
        if (states.isEmpty()) {
            shutdownCallbacks()
        }
    }

    /**
     * Safety net for player-recreation paths where the public [Player] is dropped before Media3
     * delivers [onReleased]. Safe to call more than once.
     */
    fun release() {
        states.clear()
        allocators.clear()
        transferListener.reset()
        shutdownCallbacks()
    }

    @Volatile
    private var callbacksRegistered = true

    private fun shutdownCallbacks() {
        if (!callbacksRegistered) return
        callbacksRegistered = false
        runCatching { appContext.unregisterComponentCallbacks(componentCallbacks) }
    }

    private fun allocatorFor(playerId: PlayerId): Allocator =
        allocators.computeIfAbsent(playerId) { delegate.getAllocator(it) }

    private fun LoadControl.Parameters.isLive(): Boolean {
        if (targetLiveOffsetUs != C.TIME_UNSET) return true
        return try {
            if (timeline.isEmpty) return false
            val period = Timeline.Period()
            timeline.getPeriodByUid(mediaPeriodId.periodUid, period)
            val window = timeline.getWindow(period.windowIndex, Timeline.Window())
            window.isDynamic || window.isLive() || window.durationUs == C.TIME_UNSET
        } catch (_: Throwable) {
            false
        }
    }

    private data class TrackInfo(
        val bitrateBps: Long,
        val width: Int,
        val height: Int,
        val mime: String?,
    )

    private fun Array<ExoTrackSelection?>.trackInfo(): TrackInfo {
        var bitrateBps = 0L
        var width = 0
        var height = 0
        var mime: String? = null
        for (selection in this) {
            val format = try {
                selection?.getSelectedFormat()
            } catch (_: Throwable) {
                null
            } ?: continue
            val bitrate = when {
                format.bitrate != Format.NO_VALUE -> format.bitrate
                format.averageBitrate != Format.NO_VALUE -> format.averageBitrate
                else -> 0
            }
            if (bitrate > 0) bitrateBps += bitrate.toLong()
            if (format.sampleMimeType?.startsWith("video/") == true) {
                if (format.width > width) width = format.width
                if (format.height > height) height = format.height
                mime = format.sampleMimeType
            }
        }
        return TrackInfo(bitrateBps, width, height, mime)
    }

    private fun logPlan(reason: String, plan: ForwardBufferPolicy.Plan?) {
        if (!BuildConfig.DEBUG || plan == null) return
        Log.d(
            TAG,
            "buffer plan ($reason) target=${plan.targetMs}ms refill=${plan.refillMs}ms " +
                "requested=${plan.requestedBufferMs}ms consumption=${plan.consumptionBps}bps " +
                "budget=${plan.memoryBudgetBytes}B lowBudget=${plan.lowMemoryWatermarkBytes}B " +
                "live=${plan.isLive} lowRam=${plan.lowRamDevice} " +
                "bitrate=${plan.selectedBitrateBps}bps"
        )
    }

    private fun logDecision(
        parameters: LoadControl.Parameters,
        state: PlayerState,
        allocatedBytes: Long,
        network: ForwardBufferPolicy.NetworkStats,
        decision: ForwardBufferPolicy.Decision,
        changed: Boolean,
        reasonChanged: Boolean,
        cache: PlaybackCacheManager.CacheSnapshot,
        cacheBypassed: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (!changed && !reasonChanged && now - state.lastLogRealtimeMs < LOG_INTERVAL_MS) return
        state.lastLogRealtimeMs = now

        val action = when {
            changed && decision.isLoading -> "loading START"
            changed -> "loading STOP"
            else -> "loading state"
        }
        val bufferedPositionMs =
            (parameters.playbackPositionUs +
                parameters.bufferedDurationUs.coerceAtLeast(0L)) / 1_000
        val forwardMs = parameters.bufferedDurationUs.coerceAtLeast(0L) / 1_000
        val bufferGrowthMs = forwardMs - state.lastForwardMs
        state.lastForwardMs = forwardMs
        val cacheHitKb = synchronized(state) { state.cachedBytes / 1024L }
        val readEvents = synchronized(state) { state.cacheReadEvents }
        val ignored = synchronized(state) { state.cacheIgnoredCount }
        Log.d(
            TAG,
            "$action reason=${decision.reason} " +
                "pos=${parameters.playbackPositionUs / 1_000}ms " +
                "bufferedPosition=${bufferedPositionMs}ms " +
                "forward=${forwardMs}ms growth=${bufferGrowthMs}ms " +
                "runway=${decision.runwayMs}ms " +
                "target=${decision.effectiveTargetMs}ms refill=${decision.refillThresholdMs}ms " +
                "refillFraction=${"%.3f".format(decision.dynamicRefillFraction)} " +
                "speed=${parameters.playbackSpeed} playWhenReady=${parameters.playWhenReady} " +
                "rebuffering=${parameters.rebuffering} " +
                "state=${playbackStateName(playbackState)} live=${state.plan.isLive} " +
                "bitrate=${state.plan.selectedBitrateBps}bps " +
                "resolution=${state.videoWidth}x${state.videoHeight} " +
                "mime=${state.selectedMime ?: "unknown"} " +
                "consumption=${decision.consumptionBps}bps " +
                "thr=${decision.throughputBps}bps peak=${network.peakThroughputBps}bps " +
                "var=${"%.2f".format(network.throughputVariation)} " +
                "ttfb=${network.startupLatencyMs}ms xfer=${network.lastTransferMs}ms " +
                "etaRefill=${decision.timeToRefillMs}ms " +
                "exhaustsBeforeRefill=${decision.timeToRefillMs > decision.runwayMs} " +
                "bytes=$allocatedBytes/${state.plan.memoryBudgetBytes}B " +
                "cache=${cache.usageBytes / (1024L * 1024L)}MB/" +
                "${cache.maxBytes / (1024L * 1024L)}MB " +
                "cacheRead=${cacheHitKb}KB events=$readEvents " +
                "cacheWrite=${cache.writtenBytes / 1024L}KB ignored=$ignored " +
                "cacheBypass=$cacheBypassed " +
                "memPressure=${decision.memoryPressure} lowRam=${state.plan.lowRamDevice}"
        )
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    companion object {
        private const val TAG = "CS3Buffer"
        private const val LOG_INTERVAL_MS = 5_000L
        private const val LIVE_CHECK_INTERVAL_MS = 5_000L

        /**
         * Builds a load control for one ExoPlayer instance.
         *
         * @param videoBufferMs the user's Video Buffer Length in milliseconds (0 = automatic).
         * @param targetBufferBytes the user's Video Buffer Size (RAM) in bytes (0 = automatic).
         */
        fun create(
            context: Context,
            videoBufferMs: Long,
            targetBufferBytes: Long,
        ): ProactiveLoadControl {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            runCatching { activityManager?.getMemoryInfo(memoryInfo) }

            val config = ForwardBufferPolicy.Config(
                requestedBufferMs = videoBufferMs,
                requestedRamBytes = targetBufferBytes,
                lowRamDevice = activityManager?.isLowRamDevice == true,
                memoryClassMb = activityManager?.memoryClass ?: 0,
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
                systemAvailableBytes = memoryInfo.availMem,
            )

            // At this point no track has been selected, so the plan uses the duration target and
            // the device memory budget. The per-track consumption rate is recalculated in
            // onTracksSelected.
            val initialPlan = ForwardBufferPolicy.createPlan(
                config = config,
                isLive = false,
                selectedBitrateBps = 0L,
            )

            val delegate = DefaultLoadControl.Builder()
                .setTargetBufferBytes(initialPlan.memoryBudgetBytes)
                .setBackBuffer(ForwardBufferPolicy.BACK_BUFFER_MS.toInt(), true)
                .setBufferDurationsMs(
                    initialPlan.refillMs.toInt(),
                    initialPlan.targetMs.toInt(),
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                // The custom policy enforces time and byte hysteresis itself. Keep this false so
                // DefaultLoadControl.shouldStartPlayback() can still start when the allocator
                // budget is reached with little buffered data (important on low-memory devices).
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            Log.i(
                TAG,
                "load control config buffer=${initialPlan.targetMs}ms " +
                    "refill=${initialPlan.refillMs}ms budget=${initialPlan.memoryBudgetBytes}B " +
                    "lowRam=${initialPlan.lowRamDevice} memoryClass=${config.memoryClassMb}MB " +
                    "bitrate=${initialPlan.selectedBitrateBps}bps"
            )

            return ProactiveLoadControl(
                delegate = delegate,
                config = config,
                appContext = context.applicationContext,
            )
        }
    }
}
