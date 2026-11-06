package com.lagradost.cloudstream3.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
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
 * A thin [LoadControl] wrapper that replaces only the "should we load more?" decision while
 * leaving all ExoPlayer mechanics (allocator, back buffer, start/rebuffer thresholds, lifecycle)
 * to a carefully configured [DefaultLoadControl].
 *
 * The delegate is built with the user's Video Buffer Length as `maxBufferMs` and
 * [ForwardBufferPolicy]'s low watermark as `minBufferMs`, so the effective Media3 defaults are
 * correct even if some other component ever queries them. Actual loading decisions come from
 * [ForwardBufferPolicy], which:
 *
 * - restarts loading at 80% of the configured target instead of at Media3's fixed 50 s,
 * - keeps the time target from exceeding the user's RAM/allocator budget,
 * - applies byte hysteresis so the byte cap cannot cause rapid on/off flapping,
 * - always guarantees enough headroom to recover from seeks and rebuffers,
 * - uses a smaller target for live streams and scales the low watermark with playback speed.
 *
 * All callbacks are expected on ExoPlayer's playback thread, but the per-player state is
 * synchronized anyway because preloading/other Media3 components may call a shared LoadControl
 * from a different thread in the future.
 */
@OptIn(UnstableApi::class)
class ProactiveLoadControl private constructor(
    private val delegate: DefaultLoadControl,
    private val config: ForwardBufferPolicy.Config,
) : LoadControl {

    private class PlayerState(
        @Volatile var plan: ForwardBufferPolicy.Plan,
        var isLoading: Boolean = false,
        var lastLogRealtimeMs: Long = 0L,
        var lastLiveCheckRealtimeMs: Long = 0L,
    )

    private val states = ConcurrentHashMap<PlayerId, PlayerState>()
    private val allocators = ConcurrentHashMap<PlayerId, Allocator>()

    /**
     * Current ExoPlayer playback state, mirrored here only for debug diagnostics. Updated by
     * CS3IPlayer's player listener on the application thread.
     */
    @Volatile
    var playbackState: Int = Player.STATE_IDLE

    override fun onPrepared(playerId: PlayerId) {
        delegate.onPrepared(playerId)
        states[playerId] = PlayerState(
            ForwardBufferPolicy.createPlan(
                config = config,
                isLive = false,
                selectedBitrateBps = 0L,
            )
        )
        allocators[playerId] = delegate.getAllocator(playerId)
        logPlan("prepared", states[playerId]?.plan)
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
        val bitrate = trackSelections.sumSelectedBitrate()
        synchronized(state) {
            state.plan = ForwardBufferPolicy.createPlan(config, isLive, bitrate)
        }
        logPlan("tracks selected", state.plan)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val playerId = parameters.playerId
        val state = states[playerId] ?: PlayerState(
            ForwardBufferPolicy.createPlan(config, isLive = false, selectedBitrateBps = 0L)
        ).also { states[playerId] = it }

        // Progressive live streams may only be identified after preparation, when the extractor
        // discovers an unknown duration. This check is throttled because shouldContinueLoading is
        // called on every playback-loop tick while the buffer is full.
        val liveDiscovered = synchronized(state) {
            val now = SystemClock.elapsedRealtime()
            if (!state.plan.isLive &&
                now - state.lastLiveCheckRealtimeMs >= LIVE_CHECK_INTERVAL_MS
            ) {
                state.lastLiveCheckRealtimeMs = now
                if (parameters.isLive()) {
                    state.plan = ForwardBufferPolicy.createPlan(
                        config = config,
                        isLive = true,
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
        if (liveDiscovered) {
            logPlan("live discovered", state.plan)
        }

        val allocatedBytes = try {
            allocatorFor(playerId).totalBytesAllocated.toLong()
        } catch (_: Throwable) {
            // The player may be between onStopped/onPrepared. Treat it as an empty allocator;
            // the time policy can still make a safe decision.
            0L
        }
        val decision = synchronized(state) {
            val decision = ForwardBufferPolicy.decide(
                plan = state.plan,
                isLoading = state.isLoading,
                bufferedDurationUs = parameters.bufferedDurationUs,
                allocatedBytes = allocatedBytes,
                playbackSpeed = parameters.playbackSpeed,
            )
            val changed = decision.isLoading != state.isLoading
            state.isLoading = decision.isLoading
            logDecision(parameters, state, allocatedBytes, decision, changed)
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
    }

    private fun allocatorFor(playerId: PlayerId): Allocator =
        allocators.computeIfAbsent(playerId) { delegate.getAllocator(it) }

    private fun LoadControl.Parameters.isLive(): Boolean {
        if (targetLiveOffsetUs != C.TIME_UNSET) return true
        return try {
            if (timeline.isEmpty) return false
            val period = Timeline.Period()
            timeline.getPeriodByUid(mediaPeriodId.periodUid, period)
            timeline.getWindow(period.windowIndex, Timeline.Window()).isDynamic
        } catch (_: Throwable) {
            false
        }
    }

    private fun Array<ExoTrackSelection?>.sumSelectedBitrate(): Long {
        var total = 0L
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
            if (bitrate > 0) total += bitrate.toLong()
        }
        return total
    }

    private fun logPlan(reason: String, plan: ForwardBufferPolicy.Plan?) {
        if (!BuildConfig.DEBUG || plan == null) return
        Log.d(
            TAG,
            "buffer plan ($reason) target=${plan.targetMs}ms refill=${plan.refillMs}ms " +
                "requested=${plan.requestedBufferMs}ms timeTarget=${plan.timeTargetMs}ms " +
                "byteTarget=${plan.byteLimitedTargetMs ?: "unknown"}ms " +
                "budget=${plan.memoryBudgetBytes}B lowBudget=${plan.lowMemoryWatermarkBytes}B " +
                "live=${plan.isLive} lowRam=${plan.lowRamDevice} " +
                "bitrate=${plan.selectedBitrateBps}bps"
        )
    }

    private fun logDecision(
        parameters: LoadControl.Parameters,
        state: PlayerState,
        allocatedBytes: Long,
        decision: ForwardBufferPolicy.Decision,
        changed: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (!changed && now - state.lastLogRealtimeMs < LOG_INTERVAL_MS) return
        state.lastLogRealtimeMs = now

        val action = when {
            changed && decision.isLoading -> "loading START"
            changed -> "loading STOP"
            else -> "loading state"
        }
        val bufferedPositionMs =
            (parameters.playbackPositionUs +
                parameters.bufferedDurationUs.coerceAtLeast(0L)) / 1_000
        Log.d(
            TAG,
            "$action reason=${decision.reason} " +
                "pos=${parameters.playbackPositionUs / 1_000}ms " +
                "bufferedPosition=${bufferedPositionMs}ms " +
                "forwardBuffer=${parameters.bufferedDurationUs / 1_000}ms " +
                "target=${state.plan.targetMs}ms refill=${state.plan.refillMs}ms " +
                "bytes=$allocatedBytes/${state.plan.memoryBudgetBytes}B " +
                "speed=${parameters.playbackSpeed} playWhenReady=${parameters.playWhenReady} " +
                "rebuffering=${parameters.rebuffering} " +
                "playbackState=${playbackStateName(playbackState)} " +
                "live=${state.plan.isLive} bitrate=${state.plan.selectedBitrateBps}bps " +
                "lowRam=${state.plan.lowRamDevice}"
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
        private const val TAG = "CS3LoadControl"
        private const val LOG_INTERVAL_MS = 10_000L
        private const val LIVE_CHECK_INTERVAL_MS = 5_000L
        private const val BACK_BUFFER_MS = 30_000

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
            val config = ForwardBufferPolicy.Config(
                requestedBufferMs = videoBufferMs,
                requestedRamBytes = targetBufferBytes,
                lowRamDevice = activityManager?.isLowRamDevice == true,
            )

            // At this point no track has been selected, so the plan uses the time target and the
            // memory budget. The per-track byte-limited target is recalculated in onTracksSelected.
            val initialPlan = ForwardBufferPolicy.createPlan(
                config = config,
                isLive = false,
                selectedBitrateBps = 0L,
            )

            val delegate = DefaultLoadControl.Builder()
                .setTargetBufferBytes(initialPlan.memoryBudgetBytes)
                .setBackBuffer(BACK_BUFFER_MS, true)
                .setBufferDurationsMs(
                    initialPlan.refillMs.toInt(),
                    initialPlan.targetMs.toInt(),
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                // The custom policy already prioritises time up to the byte budget and treats the
                // byte cap as a hard memory safeguard. Keep DefaultLoadControl's default here so
                // its shouldStartPlayback() retains the ability to start when the byte target is
                // reached with very little buffered data (important on low-memory devices).
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            return ProactiveLoadControl(delegate, config)
        }
    }
}
