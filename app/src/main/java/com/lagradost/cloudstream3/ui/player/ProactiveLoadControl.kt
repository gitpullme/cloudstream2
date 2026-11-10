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
 * A [LoadControl] that coordinates Media3's allocator, back-buffer, and playback-start mechanics
 * with [ForwardBufferPolicy] and [PlaybackSessionState].
 *
 * Requirements:
 * - Session-scoped state: each player has its own independent session.
 * - Robust throughput and volatility inputs from [PlaybackNetworkMonitor].
 * - Quality adaptation advice when throughput is unsustainable.
 * - Dynamic predictive refill starting before buffer depletion risk.
 * - Safe memory budget with hysteresis.
 * - Sanitized rate-limited debug logging.
 */
@OptIn(UnstableApi::class)
class ProactiveLoadControl private constructor(
    private val delegate: DefaultLoadControl,
    private val config: ForwardBufferPolicy.Config,
    private val appContext: Context?,
    val session: PlaybackSessionState = PlaybackSessionState(),
    var onQualityDegradationAdvised: ((sustainableBps: Long) -> Unit)? = null,
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

    /** Dedicated session network monitor receiving real upstream request samples. */
    val transferListener: PlaybackNetworkMonitor get() = session.networkMonitor

    /**
     * CacheDataSource reports cached bytes and cache-ignore events here. Cached bytes are
     * deliberately not treated as network throughput.
     */
    val cacheEventListener: CacheDataSource.EventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
            session.cachedBytesRead += cachedBytesRead
            session.cacheReadEvents += 1
            session.lastCacheSizeBytes = cacheSizeBytes

            for (state in states.values) {
                synchronized(state) {
                    state.cachedBytes += cachedBytesRead
                    state.cacheReadEvents += 1
                    state.lastCacheSizeBytes = cacheSizeBytes
                }
            }
        }

        override fun onCacheIgnored(reason: Int) {
            session.cacheIgnoredCount += 1
            for (state in states.values) {
                synchronized(state) {
                    state.cacheIgnoredCount += 1
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "cache ignored reason=$reason [session=${session.sessionId}]")
            }
        }
    }

    @Volatile
    var playbackState: Int = Player.STATE_IDLE
        set(value) {
            field = value
            session.playbackState = value
            for (s in states.values) {
                synchronized(s) {
                    s.playbackState = value
                }
            }
        }

    fun setPlaybackState(playerId: PlayerId, state: Int) {
        session.playbackState = state
        states[playerId]?.let { s ->
            synchronized(s) {
                s.playbackState = state
            }
        }
    }

    fun isLiveStream(): Boolean = session.isLive || states.values.any { it.plan.isLive }

    @Volatile
    private var memoryPressureUntilMs: Long = 0L

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
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

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    init {
        appContext?.registerComponentCallbacks(componentCallbacks)
    }

    override fun onPrepared(playerId: PlayerId) {
        delegate.onPrepared(playerId)
        session.onPrepared(playerId)
        val plan = ForwardBufferPolicy.createPlan(
            config = config,
            isLive = session.isLive,
            selectedBitrateBps = session.selectedBitrateBps,
        )
        session.memoryBudgetBytes = plan.memoryBudgetBytes
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
            ForwardBufferPolicy.createPlan(config, isLive = session.isLive, selectedBitrateBps = 0L)
        ).also { states[parameters.playerId] = it }

        val isLive = parameters.isLive() || session.isLive
        session.isLive = isLive
        val trackInfo = trackSelections.trackInfo()

        session.selectedBitrateBps = trackInfo.bitrateBps
        session.videoWidth = trackInfo.width
        session.videoHeight = trackInfo.height
        session.selectedMime = trackInfo.mime

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
            ForwardBufferPolicy.createPlan(config, isLive = session.isLive, selectedBitrateBps = session.selectedBitrateBps)
        ).also { states[playerId] = it }

        val isLiveNow = parameters.isLive() || session.isLive
        session.isLive = isLiveNow
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
            0L
        }

        // Update session metrics
        session.allocatedBytes = allocatedBytes
        session.bufferedDurationUs = parameters.bufferedDurationUs
        session.playbackPositionUs = parameters.playbackPositionUs
        session.forwardBufferMs = parameters.bufferedDurationUs.coerceAtLeast(0L) / 1_000L
        session.bufferedEndPositionMs = (parameters.playbackPositionUs + parameters.bufferedDurationUs.coerceAtLeast(0L)) / 1_000L
        session.playbackSpeed = parameters.playbackSpeed
        session.playWhenReady = parameters.playWhenReady
        session.onRebufferingChanged(parameters.rebuffering)

        val network = transferListener.snapshot()
        val memoryPressure = SystemClock.elapsedRealtime() < memoryPressureUntilMs
        val cacheSnapshot = if (BuildConfig.DEBUG) {
            PlaybackCacheManager.snapshot()
        } else {
            PlaybackCacheManager.CacheSnapshot(0L, 0L, false, 0L)
        }
        val cacheBypassed = BuildConfig.DEBUG && PlaybackCacheManager.isBypassingCache()

        val decision = synchronized(state) {
            val dec = ForwardBufferPolicy.decide(
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
                isAdaptiveQualitySupported = true,
            )

            // Advise quality adaptation if network is sustainably below current quality
            if (dec.isQualityRecoveryNeeded && dec.sustainableConsumptionBps > 0L) {
                session.isQualityRecoveryActive = true
                session.sustainableBitrateBps = dec.sustainableConsumptionBps
                onQualityDegradationAdvised?.invoke(dec.sustainableConsumptionBps)
            }

            val changed = dec.isLoading != state.isLoading
            val reasonChanged = dec.reason != state.lastReason
            state.isLoading = dec.isLoading
            state.lastReason = dec.reason
            logDecision(
                parameters,
                state,
                allocatedBytes,
                network,
                dec,
                changed,
                reasonChanged,
                cacheSnapshot,
                cacheBypassed,
            )
            dec
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
        session.release()
        if (states.isEmpty()) {
            shutdownCallbacks()
        }
    }

    fun release() {
        states.clear()
        allocators.clear()
        session.release()
        shutdownCallbacks()
    }

    @Volatile
    private var callbacksRegistered = appContext != null

    private fun shutdownCallbacks() {
        if (!callbacksRegistered) return
        callbacksRegistered = false
        runCatching { appContext?.unregisterComponentCallbacks(componentCallbacks) }
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
            "buffer plan [${session.sessionId}] ($reason) target=${plan.targetMs}ms refill=${plan.refillMs}ms " +
                "consumption=${plan.consumptionBps}bps budget=${plan.memoryBudgetBytes}B live=${plan.isLive}"
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
            (parameters.playbackPositionUs + parameters.bufferedDurationUs.coerceAtLeast(0L)) / 1_000
        val forwardMs = parameters.bufferedDurationUs.coerceAtLeast(0L) / 1_000
        val bufferGrowthMs = forwardMs - state.lastForwardMs
        state.lastForwardMs = forwardMs
        val cacheHitKb = synchronized(state) { state.cachedBytes / 1024L }

        Log.d(
            TAG,
            "$action [${session.sessionId}] reason=${decision.reason} state=${decision.bufferState} " +
                "forward=${forwardMs}ms runway=${decision.runwayMs}ms " +
                "target=${decision.effectiveTargetMs}ms refill=${decision.refillThresholdMs}ms " +
                "speed=${parameters.playbackSpeed} rebuffer=${parameters.rebuffering} " +
                "thr=${decision.throughputBps}bps net=${decision.networkState} risk=${decision.depletionRisk} " +
                "bytes=$allocatedBytes/${state.plan.memoryBudgetBytes}B " +
                "cacheHit=${cacheHitKb}KB"
        )
    }

    companion object {
        private const val TAG = "CS3Buffer"
        private const val LOG_INTERVAL_MS = 5_000L
        private const val LIVE_CHECK_INTERVAL_MS = 5_000L

        fun create(
            context: Context,
            videoBufferMs: Long,
            targetBufferBytes: Long,
            session: PlaybackSessionState = PlaybackSessionState(),
            onQualityDegradationAdvised: ((sustainableBps: Long) -> Unit)? = null,
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

            val initialPlan = ForwardBufferPolicy.createPlan(
                config = config,
                isLive = session.isLive,
                selectedBitrateBps = session.selectedBitrateBps,
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
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            PlaybackCacheManager.registerSession(session)

            return ProactiveLoadControl(
                delegate = delegate,
                config = config,
                appContext = context.applicationContext,
                session = session,
                onQualityDegradationAdvised = onQualityDegradationAdvised,
            )
        }

        fun createForTest(
            config: ForwardBufferPolicy.Config,
            session: PlaybackSessionState = PlaybackSessionState(),
            onQualityDegradationAdvised: ((sustainableBps: Long) -> Unit)? = null,
        ): ProactiveLoadControl {
            val initialPlan = ForwardBufferPolicy.createPlan(
                config = config,
                isLive = session.isLive,
                selectedBitrateBps = session.selectedBitrateBps,
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
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            PlaybackCacheManager.registerSession(session)

            return ProactiveLoadControl(
                delegate = delegate,
                config = config,
                appContext = null,
                session = session,
                onQualityDegradationAdvised = onQualityDegradationAdvised,
            )
        }
    }
}
