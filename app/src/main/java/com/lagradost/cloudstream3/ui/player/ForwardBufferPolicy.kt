package com.lagradost.cloudstream3.ui.player

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, side-effect free forward-buffer policy used by [ProactiveLoadControl].
 *
 * Why this exists:
 * Media3's [androidx.media3.exoplayer.DefaultLoadControl] stops loading as soon as the buffered
 * duration reaches `maxBufferMs`, and it only starts loading again when the buffered duration
 * falls below `minBufferMs`. CloudStream used to keep `minBufferMs` at Media3's default of 50 s
 * while `maxBufferMs` was the user's Video Buffer Length (60-480 s). For a 4 minute target this
 * means the player had to consume 3m 10s of buffer before it considered loading again, which is
 * exactly the delayed replenishment this class fixes.
 *
 * The policy keeps the user's configured duration as the *upper* target, restarts loading at 80%
 * of the effective target, and adds a byte watermark so that large time targets cannot turn into
 * unbounded memory use. It is intentionally independent of Android/Media3 so that all threshold
 * decisions can be unit tested on the JVM.
 */
object ForwardBufferPolicy {
    /** Loading restarts when the forward buffer drops to this fraction of the target. */
    const val REFILL_FRACTION = 0.80

    /** Loading restarts after a byte-limited stop when allocated bytes drop to this fraction. */
    const val BYTE_REFILL_FRACTION = 0.80

    /** Media3's default maximum buffer duration for streaming playback. */
    const val DEFAULT_MAX_BUFFER_MS = 50_000L

    /** Do not let a corrupt/legacy setting create an effectively unbounded buffer. */
    const val MAX_CONFIGURED_BUFFER_MS = 600_000L

    /**
     * Live playback must not buffer arbitrarily far into the future. Media3 uses 50 s as its
     * default maximum for streaming, so that is a safe upper bound for live streams too.
     */
    const val LIVE_MAX_BUFFER_MS = 50_000L

    /**
     * Smallest effective target. This matches Media3's default after-rebuffer start threshold, so
     * even a very small RAM budget cannot produce a target that makes playback unable to resume.
     */
    const val MIN_TARGET_MS = 2_000L

    /**
     * Media3 1.9.3's default target buffer for a video + audio stream:
     * `DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE` (about 137.5 MiB).
     * Used instead of an arbitrary huge allocator when the user keeps the RAM setting on Auto.
     */
    const val AUTO_MEMORY_BUDGET_BYTES = 144_179_200

    /** Conservative allocator budget for low-RAM devices (48 MiB). */
    const val LOW_RAM_MEMORY_BUDGET_BYTES = 48 * 1024 * 1024

    /**
     * Keep a small amount of headroom in the byte budget for container/allocator overhead.
     * Media3's bitrates describe media payload; the allocator also stores sample metadata.
     */
    const val BYTE_SAFETY_FACTOR = 0.95

    /**
     * Below this forward-buffer duration the byte cap is ignored. This makes recovery from a seek
     * or a rebuffer possible even if the back buffer temporarily keeps the allocator near its
     * target. The overshoot is bounded by two seconds of media.
     */
    const val CRITICAL_BUFFER_MS = 2_000L

    /** Inputs that come from the user's settings and the current device. */
    data class Config(
        /** Video Buffer Length in milliseconds. Zero means "automatic". */
        val requestedBufferMs: Long,
        /** Video Buffer Size in bytes. Zero means "automatic". */
        val requestedRamBytes: Long,
        /** True when ActivityManager reports a low-RAM device. */
        val lowRamDevice: Boolean,
    )

    /** Calculated thresholds for one player/track selection. */
    data class Plan(
        val requestedBufferMs: Long,
        /** User target after live clamping. */
        val timeTargetMs: Long,
        /** Target that fits the memory budget at the selected bitrate, if the bitrate is known. */
        val byteLimitedTargetMs: Long?,
        /** Effective upper target = min(time target, byte-limited target). */
        val targetMs: Long,
        /** Low watermark at which loading starts again. */
        val refillMs: Long,
        val memoryBudgetBytes: Int,
        val lowMemoryWatermarkBytes: Int,
        val isLive: Boolean,
        val lowRamDevice: Boolean,
        val selectedBitrateBps: Long,
    )

    /** Why a particular loading decision was made. Used by tests and debug diagnostics. */
    enum class Reason {
        INITIAL_LOAD,
        BELOW_REFILL,
        BYTE_REFILL,
        CONTINUE,
        TARGET_REACHED,
        BYTE_LIMIT_REACHED,
        ABOVE_REFILL,
        WAITING_FOR_MEMORY,
    }

    data class Decision(
        val isLoading: Boolean,
        val reason: Reason,
    )

    /**
     * Builds the thresholds for the current player.
     *
     * @param config user/device configuration.
     * @param isLive whether the current period is a live stream.
     * @param selectedBitrateBps combined bitrate of the selected audio/video tracks, or 0 if
     *     Media3 has not reported a usable bitrate yet.
     */
    fun createPlan(
        config: Config,
        isLive: Boolean,
        selectedBitrateBps: Long,
    ): Plan {
        val requested =
            if (config.requestedBufferMs > 0) config.requestedBufferMs else DEFAULT_MAX_BUFFER_MS
        val timeTarget = requested
            .coerceIn(MIN_TARGET_MS, MAX_CONFIGURED_BUFFER_MS)
            .let { if (isLive) min(it, LIVE_MAX_BUFFER_MS) else it }
            .coerceAtLeast(MIN_TARGET_MS)

        val budgetBytes = resolveMemoryBudget(config)
        val lowWatermark = (budgetBytes.toLong() * BYTE_REFILL_FRACTION).toLong().toInt()
        val bitrate = selectedBitrateBps.coerceAtLeast(0L)

        val byteLimitedTarget = if (bitrate > 0) {
            // bytes * 8 / (bits per second) -> seconds -> milliseconds
            val millis = budgetBytes.toDouble() * 8.0 * BYTE_SAFETY_FACTOR /
                bitrate.toDouble() * 1_000.0
            millis.toLong().coerceAtLeast(MIN_TARGET_MS)
        } else {
            null
        }

        val target = min(timeTarget, byteLimitedTarget ?: Long.MAX_VALUE)
        val refill = max(MIN_TARGET_MS, (target * REFILL_FRACTION).toLong())
            .coerceAtMost(target)

        return Plan(
            requestedBufferMs = config.requestedBufferMs,
            timeTargetMs = timeTarget,
            byteLimitedTargetMs = byteLimitedTarget,
            targetMs = target,
            refillMs = refill,
            memoryBudgetBytes = budgetBytes,
            lowMemoryWatermarkBytes = lowWatermark,
            isLive = isLive,
            lowRamDevice = config.lowRamDevice,
            selectedBitrateBps = bitrate,
        )
    }

    /**
     * Decides whether the loader should continue.
     *
     * The state machine has hysteresis: when loading it keeps going until the effective target or
     * the byte cap is reached; when idle it only starts again at/below [Plan.refillMs] (and below
     * the byte low watermark, unless the buffer is critically low).
     *
     * @param bufferedDurationUs Media3's total forward-buffered duration. Values <= 0 (including
     *     `C.TIME_UNSET`) are treated as an empty buffer, which safely starts loading.
     * @param allocatedBytes total allocator bytes for this player, including the back buffer.
     * @param playbackSpeed current playback speed. Above 1x the low watermark is scaled so the
     *     same amount of *wall-clock* protection remains.
     */
    fun decide(
        plan: Plan,
        isLoading: Boolean,
        bufferedDurationUs: Long,
        allocatedBytes: Long,
        playbackSpeed: Float,
    ): Decision {
        val targetUs = plan.targetMs * 1_000L
        val refillUs = plan.refillMs * 1_000L
        val speed =
            if (playbackSpeed.isFinite() && playbackSpeed > 1f) playbackSpeed else 1f
        val scaledRefillUs =
            if (speed > 1f) min((refillUs * speed).toLong(), targetUs) else refillUs

        val bufferedUs = if (bufferedDurationUs > 0L) bufferedDurationUs else 0L
        val critical = bufferedUs < CRITICAL_BUFFER_MS * 1_000L
        val atTarget = bufferedUs >= targetUs
        val belowRefill = bufferedUs <= scaledRefillUs

        val bytes = allocatedBytes.coerceAtLeast(0L)
        val byteHigh =
            plan.memoryBudgetBytes > 0 && bytes >= plan.memoryBudgetBytes.toLong()
        val byteLow =
            plan.memoryBudgetBytes <= 0 || bytes <= plan.lowMemoryWatermarkBytes.toLong()

        return when {
            isLoading && atTarget -> Decision(false, Reason.TARGET_REACHED)
            // The byte cap is a hard memory safeguard, but never let it starve a nearly empty
            // buffer (seeking/rebuffering must be able to recover).
            isLoading && byteHigh && !critical ->
                Decision(false, Reason.BYTE_LIMIT_REACHED)

            isLoading -> Decision(true, Reason.CONTINUE)

            belowRefill && (byteLow || critical) -> Decision(
                true,
                when {
                    bufferedUs == 0L -> Reason.INITIAL_LOAD
                    !byteLow -> Reason.BYTE_REFILL
                    else -> Reason.BELOW_REFILL
                }
            )

            belowRefill -> Decision(false, Reason.WAITING_FOR_MEMORY)
            else -> Decision(false, Reason.ABOVE_REFILL)
        }
    }

    private fun resolveMemoryBudget(config: Config): Int {
        val requested = config.requestedRamBytes
        return when {
            requested > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            requested > 0L -> requested.toInt()
            config.lowRamDevice -> LOW_RAM_MEMORY_BUDGET_BYTES
            else -> AUTO_MEMORY_BUDGET_BYTES
        }
    }
}
