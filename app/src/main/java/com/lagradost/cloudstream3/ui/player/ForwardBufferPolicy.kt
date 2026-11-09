package com.lagradost.cloudstream3.ui.player

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, side-effect free forward-buffer policy used by [ProactiveLoadControl].
 *
 * Media3's [androidx.media3.exoplayer.DefaultLoadControl] stops loading when the buffered duration
 * reaches `maxBufferMs` and only resumes when it falls below `minBufferMs`. CloudStream used to keep
 * `minBufferMs` at Media3's 50 s default while `maxBufferMs` came from the user's Video Buffer
 * Length (60-1800 s). That made long buffers drain almost completely before refilling.
 *
 * This policy fixes the threshold, but more importantly it does not treat a fixed percentage as
 * the whole answer:
 *
 * - The user's Video Buffer Length is always the *upper* target. It is never silently reduced by a
 *   nominal-bitrate guess.
 * - Memory is enforced separately through a device/allocator budget. Actual allocator bytes, not
 *   declared bitrate, stop loading when memory would become unsafe.
 * - Refill starts at a dynamic point (70-95% of the target) derived from the measured download
 *   throughput, its volatility and playback speed. On a slow or unstable connection it starts much
 *   earlier; on a fast stable connection it starts at 70%, never at Media3's fixed 50 s.
 * - A predictive calculation raises the refill point when the time needed to download the missing
 *   buffer (including TTFB and a safety margin) approaches the remaining playout runway.
 * - Byte-cap hysteresis, a critical recovery window and a memory-pressure mode prevent load
 *   oscillations and allow recovery after seeks or rebuffers.
 *
 * Everything here is independent of Android and Media3 so it can be unit tested on the JVM.
 */
object ForwardBufferPolicy {
    /** Lowest adaptive refill point. Faster/stable links may stay below the old fixed 80% floor. */
    const val BASE_REFILL_FRACTION = 0.70

    /** Never start later than this fraction, regardless of how good the network looks. */
    const val MAX_REFILL_FRACTION = 0.95

    /** Minimum hysteresis band so target/refill cannot collapse into the same value. */
    const val MIN_HYSTERESIS_MS = 1_000L

    /** Allocated bytes must fall to this fraction of the budget before loading may restart. */
    const val BYTE_REFILL_FRACTION = 0.85

    /**
     * Hard safety ceiling as a multiple of the allocator budget. Even a critically empty buffer
     * must not allocate past this, otherwise a misconfigured budget could OOM the app instead of
     * simply stalling until the renderer frees memory.
     */
    const val BYTE_PANIC_FRACTION = 1.5

    /** Forward buffer below this is allowed to ignore the byte cap so playback can recover. */
    const val CRITICAL_BUFFER_MS = 2_000L

    /** Media3's default streaming target, used when the user selects "Automatic". */
    const val DEFAULT_MAX_BUFFER_MS = 50_000L

    /** Largest selectable target (the UI offers up to 30 minutes); guards corrupt preferences. */
    const val MAX_CONFIGURED_BUFFER_MS = 1_800_000L

    /** Live streams must not build an arbitrarily large forward buffer. */
    const val LIVE_MAX_BUFFER_MS = 50_000L

    /** Smallest target we will ever hand to Media3. */
    const val MIN_TARGET_MS = 2_000L

    /** Media3 1.9.3's default video + audio allocator budget (about 137.5 MiB). */
    const val DEFAULT_MEMORY_BUDGET_BYTES = 144_179_200L

    /** Conservative allocator budget for low-RAM devices (48 MiB). */
    const val LOW_RAM_MEMORY_BUDGET_BYTES = 48L * 1024L * 1024L

    /** Never configure an allocator smaller than this; the UI's smallest explicit value is 10 MiB. */
    const val MIN_MEMORY_BUDGET_BYTES = 8L * 1024L * 1024L

    /** Upper bound for user-selected RAM on a normal device (a safe allocator ceiling). */
    const val MAX_MEMORY_BUDGET_BYTES = 384L * 1024L * 1024L

    /** Upper bound for user-selected RAM on a low-RAM device. */
    const val MAX_LOW_RAM_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L

    /** Back buffer retained for instant short rewinds, matching CloudStream's previous value. */
    const val BACK_BUFFER_MS = 30_000L

    /** Fallback media consumption rate when the selected format has no bitrate metadata. */
    const val DEFAULT_CONSUMPTION_BPS = 2_000_000L

    /** Fallback time-to-first-byte when no network sample is available yet. */
    const val DEFAULT_STARTUP_MS = 1_000L

    /** Fallback duration of the next request when no transfer sample is available yet. */
    const val DEFAULT_REQUEST_MS = 3_000L

    /** Cap on the request-startup part of the refill ETA; the body is already in throughput. */
    const val MAX_REQUEST_OVERHEAD_MS = 1_500L

    /** Typical gap between sequential requests when nothing has been measured yet. */
    const val DEFAULT_REQUEST_GAP_MS = 300L

    /** A throughput sample is only trusted after this many completed transfers. */
    const val MIN_THROUGHPUT_SAMPLES = 2

    /** Ignore tiny/short samples that would make the estimate noisy. */
    const val MIN_VALID_THROUGHPUT_BPS = 64_000L

    /** How long memory-pressure mode remains active after an onTrimMemory callback. */
    const val MEMORY_PRESSURE_HOLD_MS = 60_000L

    /** Fraction of the target still allowed while the device is under memory pressure. */
    const val MEMORY_PRESSURE_TARGET_FRACTION = 0.50

    data class Config(
        /** Video Buffer Length in milliseconds. Zero means "Automatic". */
        val requestedBufferMs: Long,
        /** Video Buffer Size in bytes. Zero means "Automatic". */
        val requestedRamBytes: Long,
        /** True when ActivityManager reports a low-RAM device. */
        val lowRamDevice: Boolean,
        /** ActivityManager.memoryClass in MiB. Zero when unknown. */
        val memoryClassMb: Int = 0,
        /** Runtime.getRuntime().maxMemory() in bytes. Zero when unknown. */
        val maxHeapBytes: Long = 0L,
        /** System-wide available RAM in bytes. Used only as a soft upper bound. */
        val systemAvailableBytes: Long = 0L,
    )

    /**
     * A snapshot of measured upstream performance. All values are smoothed by [NetworkMonitor];
     * the policy never reacts to a single unusually fast or slow sample.
     */
    data class NetworkStats(
        /** Exponentially weighted transfer rate in bytes/second, or 0 when unknown. */
        val throughputBps: Long = 0L,
        /** Highest recent smoothed rate, for diagnostics only. */
        val peakThroughputBps: Long = 0L,
        /** Coefficient of variation (standard deviation / mean) of recent samples, 0..1. */
        val throughputVariation: Float = 0f,
        /** Exponentially weighted time to first byte in milliseconds. */
        val startupLatencyMs: Long = 0L,
        /** Exponentially weighted duration of the last completed requests, in milliseconds. */
        val lastTransferMs: Long = 0L,
        /** Number of completed, usable samples. */
        val samples: Int = 0,
    )

    /** Calculated thresholds for one player/track selection. */
    data class Plan(
        val requestedBufferMs: Long,
        /** User target after live clamping. This is the upper target, not a bitrate-derived cap. */
        val targetMs: Long,
        /** Baseline refill point; [decide] may raise this based on measured network conditions. */
        val refillMs: Long,
        /** Actual allocator budget; byte pressure is enforced with hysteresis in [decide]. */
        val memoryBudgetBytes: Int,
        val lowMemoryWatermarkBytes: Int,
        val isLive: Boolean,
        val lowRamDevice: Boolean,
        val selectedBitrateBps: Long,
        val consumptionBps: Long,
    )

    /** Why a particular loading decision was made. Used by tests and debug diagnostics. */
    enum class Reason {
        INITIAL_LOAD,
        REBUFFER_RECOVERY,
        PREDICTIVE_REFILL,
        BELOW_REFILL,
        LOW_THROUGHPUT,
        BYTE_REFILL,
        CONTINUE,
        TARGET_REACHED,
        BYTE_LIMIT_REACHED,
        ABOVE_REFILL,
        WAITING_FOR_MEMORY,
        MEMORY_PRESSURE,
        NOT_PLAYING,
    }

    data class Decision(
        val isLoading: Boolean,
        val reason: Reason,
        /** Effective upper target after live/memory-pressure clamping. */
        val effectiveTargetMs: Long,
        /** Dynamic refill point after throughput/volatility/speed adjustment. */
        val refillThresholdMs: Long,
        /** Wall-clock playout runway of the current forward buffer. */
        val runwayMs: Long,
        /** Predicted wall-clock time needed to refill to the target, 0 when unknown. */
        val timeToRefillMs: Long,
        val throughputBps: Long,
        val consumptionBps: Long,
        val dynamicRefillFraction: Float,
        val critical: Boolean,
        val memoryPressure: Boolean,
    )

    /**
     * Builds the duration/allocator target for the current player.
     *
     * The bitrate is used only to estimate consumption, never to shrink the user's duration
     * target. Memory is bounded by the allocator budget instead.
     */
    fun createPlan(
        config: Config,
        isLive: Boolean,
        selectedBitrateBps: Long,
    ): Plan {
        val requested = if (config.requestedBufferMs > 0) {
            config.requestedBufferMs
        } else {
            DEFAULT_MAX_BUFFER_MS
        }

        val timeTarget = requested.coerceIn(MIN_TARGET_MS, MAX_CONFIGURED_BUFFER_MS)
        val target = if (isLive) {
            timeTarget.coerceAtMost(LIVE_MAX_BUFFER_MS).coerceAtLeast(MIN_TARGET_MS)
        } else {
            timeTarget
        }

        val consumption = if (selectedBitrateBps > 0) {
            selectedBitrateBps
        } else {
            DEFAULT_CONSUMPTION_BPS
        }

        val budget = resolveMemoryBudget(config)
        val lowWatermark = (budget.toDouble() * BYTE_REFILL_FRACTION).toLong().toInt()
        val band = max(MIN_HYSTERESIS_MS, (target * 0.05).toLong())
        val baseline = (target * BASE_REFILL_FRACTION).toLong()
            .coerceAtLeast(MIN_TARGET_MS)
            .coerceAtMost(max(MIN_TARGET_MS, target - band))

        return Plan(
            requestedBufferMs = config.requestedBufferMs,
            targetMs = target,
            refillMs = baseline,
            memoryBudgetBytes = budget,
            lowMemoryWatermarkBytes = lowWatermark,
            isLive = isLive,
            lowRamDevice = config.lowRamDevice,
            selectedBitrateBps = selectedBitrateBps.coerceAtLeast(0L),
            consumptionBps = consumption,
        )
    }

    /**
     * Decides whether the loader should continue.
     *
     * The state machine has hysteresis: while loading it keeps going until the effective target or
     * the byte ceiling is reached; while idle it starts again at the predictive/dynamic refill
     * point, or immediately when the buffer is critically low or rebuffering.
     *
     * @param bufferedDurationUs Media3's total forward-buffered duration in media time.
     * @param allocatedBytes total allocator bytes for this player, including the back buffer.
     * @param playbackSpeed current playback speed; values above 1x increase wall-clock protection.
     * @param network measured upstream performance. With no samples the policy falls back to the
     *     adaptive baseline and Media3-safe defaults.
     * @param playbackState Player.STATE_* mirrored by CS3IPlayer.
     * @param playWhenReady whether playback is intended to advance.
     * @param rebuffering Media3's LoadControl.Parameters.rebuffering flag.
     * @param memoryPressure true while the app has received a memory-pressure callback; the
     *     effective target is temporarily halved and the byte ceiling is enforced earlier.
     */
    fun decide(
        plan: Plan,
        isLoading: Boolean,
        bufferedDurationUs: Long,
        allocatedBytes: Long,
        playbackSpeed: Float,
        network: NetworkStats = NetworkStats(),
        playbackState: Int = 3, // Player.STATE_READY, kept as Int so this file stays pure JVM code.
        playWhenReady: Boolean = true,
        rebuffering: Boolean = false,
        memoryPressure: Boolean = false,
    ): Decision {
        val speed = if (playbackSpeed.isFinite() && playbackSpeed > 0.1f) playbackSpeed else 1f
        val rawBufferedMs = if (bufferedDurationUs > 0L) bufferedDurationUs / 1_000L else 0L
        val bufferedMs = rawBufferedMs.coerceAtLeast(0L)

        val pressureTarget = if (memoryPressure) {
            (plan.targetMs * MEMORY_PRESSURE_TARGET_FRACTION).toLong()
                .coerceAtLeast(MIN_TARGET_MS)
                .coerceAtMost(plan.targetMs)
        } else {
            plan.targetMs
        }
        val band = max(MIN_HYSTERESIS_MS, (pressureTarget * 0.05).toLong())
        val targetMs = pressureTarget.coerceAtLeast(MIN_TARGET_MS)
        val maxRefill = max(MIN_TARGET_MS, targetMs - band)

        val throughput = if (
            network.samples >= MIN_THROUGHPUT_SAMPLES &&
            network.throughputBps >= MIN_VALID_THROUGHPUT_BPS
        ) {
            network.throughputBps
        } else {
            0L
        }

        val consumption = plan.consumptionBps.coerceAtLeast(1L)
        val ratio = if (throughput > 0L) {
            throughput.toDouble() / consumption.toDouble()
        } else {
            1.0
        }
        val volatility = if (network.samples >= 3) {
            network.throughputVariation.coerceIn(0f, 1f)
        } else {
            0f
        }

        var fraction = BASE_REFILL_FRACTION
        if (throughput > 0L) {
            // Downloading at barely above the media rate leaves almost no net fill rate, so start
            // much earlier. At >= 1.5x the bitrate the baseline is safe.
            val slowLink = ((1.5 - ratio).coerceIn(0.0, 1.0) / 1.0).toFloat()
            fraction += 0.20f * slowLink
        }
        fraction += 0.05f * volatility
        fraction += (speed - 1f).coerceIn(0f, 2f) * 0.03f
        fraction = fraction.coerceIn(BASE_REFILL_FRACTION, MAX_REFILL_FRACTION)

        val dynamicRefill = (targetMs * fraction).toLong()
            .coerceIn(MIN_TARGET_MS, maxRefill)

        val startupMs = if (network.startupLatencyMs > 0L) {
            network.startupLatencyMs
        } else {
            DEFAULT_STARTUP_MS
        }
        val requestGapMs = if (network.lastTransferMs > 0L) {
            // Only the scheduling/connection part belongs here. The body transfer is already
            // represented by throughput, so cap this to avoid double counting large downloads.
            network.lastTransferMs.coerceIn(0L, MAX_REQUEST_OVERHEAD_MS)
        } else {
            DEFAULT_REQUEST_GAP_MS
        }
        val safetyMs = max(2_000L, (targetMs * 0.05).toLong()) +
            (volatility * targetMs * 0.10f).toLong()
        val overheadMs = startupMs + requestGapMs + safetyMs

        val missingMs = (targetMs - bufferedMs).coerceAtLeast(0L)
        val timeToRefillMs = if (throughput > 0L) {
            (missingMs.toDouble() / ratio).toLong() + overheadMs
        } else {
            0L
        }

        // Predictive threshold. The buffer must survive the request startup and the time needed to
        // fetch the missing media at the measured rate. With ratio = throughput / consumption and
        // runway = B / speed, solving B / speed >= overhead + (target - B) / ratio gives:
        //
        //   B >= speed * (target + overhead * ratio) / (ratio + speed)
        //
        // If the link cannot even keep up with consumption (ratio <= speed), no threshold can make
        // the buffer recover, so start as early as possible.
        val predictiveRefill = when {
            throughput <= 0L -> dynamicRefill
            ratio <= speed.toDouble() -> maxRefill
            else -> {
                val requiredForward = speed.toDouble() *
                    (targetMs + overheadMs * ratio) / (ratio + speed.toDouble())
                requiredForward.toLong().coerceIn(dynamicRefill, maxRefill)
            }
        }

        val refillThreshold = max(dynamicRefill, predictiveRefill)
            .coerceIn(MIN_TARGET_MS, maxRefill)
        val critical = bufferedMs < CRITICAL_BUFFER_MS
        val runwayMs = (bufferedMs.toDouble() / speed.toDouble()).toLong()
        val effectiveBytesBudget = if (memoryPressure) {
            (plan.memoryBudgetBytes.toDouble() * 0.80).toLong()
        } else {
            plan.memoryBudgetBytes.toLong()
        }
        val bytes = allocatedBytes.coerceAtLeast(0L)
        val byteHigh = effectiveBytesBudget > 0L && bytes >= effectiveBytesBudget
        val bytePanic = effectiveBytesBudget > 0L &&
            bytes >= (effectiveBytesBudget * BYTE_PANIC_FRACTION).toLong()
        val byteLow = effectiveBytesBudget <= 0L ||
            bytes <= (effectiveBytesBudget * BYTE_REFILL_FRACTION).toLong()

        fun decision(
            loading: Boolean,
            reason: Reason,
        ) = Decision(
            isLoading = loading,
            reason = reason,
            effectiveTargetMs = targetMs,
            refillThresholdMs = refillThreshold,
            runwayMs = runwayMs,
            timeToRefillMs = timeToRefillMs,
            throughputBps = throughput,
            consumptionBps = consumption,
            dynamicRefillFraction = fraction.toFloat(),
            critical = critical,
            memoryPressure = memoryPressure,
        )

        // Only ENDED means "do not load". STATE_IDLE must still be allowed: during prepare Media3
        // can ask for the initial load before the public playback state has advanced to BUFFERING.
        if (playbackState == 4) {
            return decision(false, Reason.NOT_PLAYING)
        }

        if (isLoading) {
            if (bytePanic) return decision(false, Reason.BYTE_LIMIT_REACHED)
            if (critical) return decision(true, Reason.CONTINUE)
            if (byteHigh) return decision(false, Reason.BYTE_LIMIT_REACHED)
            if (bufferedMs >= targetMs) return decision(false, Reason.TARGET_REACHED)
            return decision(true, Reason.CONTINUE)
        }

        if (bytePanic) return decision(false, Reason.WAITING_FOR_MEMORY)
        if (critical) {
            return decision(
                true,
                if (rebuffering) Reason.REBUFFER_RECOVERY else Reason.INITIAL_LOAD
            )
        }
        if (byteHigh) return decision(false, Reason.WAITING_FOR_MEMORY)

        if (rebuffering && bufferedMs < targetMs) {
            return decision(true, Reason.REBUFFER_RECOVERY)
        }

        if (bufferedMs <= refillThreshold) {
            // Byte hysteresis: once the allocator is near its budget, only restart after enough
            // media has been consumed to fall below the low watermark. The critical-buffer path
            // above is the exception so a depleted player can still recover.
            if (!byteLow) return decision(false, Reason.WAITING_FOR_MEMORY)
            val reason = when {
                throughput > 0L && ratio <= 1.05 -> Reason.LOW_THROUGHPUT
                predictiveRefill > dynamicRefill -> Reason.PREDICTIVE_REFILL
                else -> Reason.BELOW_REFILL
            }
            return decision(true, reason)
        }

        return decision(false, if (memoryPressure) Reason.MEMORY_PRESSURE else Reason.ABOVE_REFILL)
    }

    /**
     * Resolves the allocator budget from device class and the user's RAM setting.
     *
     * ExoPlayer's allocator stores media payload in Java byte arrays, so the budget is bounded by a
     * fraction of the app heap rather than the nominal stream bitrate. A user request for a huge
     * buffer on a small-heap device is clamped rather than allowed to OOM the app.
     */
    fun resolveMemoryBudget(config: Config): Int {
        val heapCeiling = when {
            config.maxHeapBytes > 0L -> (config.maxHeapBytes.toDouble() * 0.35).toLong()
            config.memoryClassMb > 0 -> (config.memoryClassMb.toLong() * 1024L * 1024L * 0.35).toLong()
            else -> Long.MAX_VALUE
        }
        val systemCeiling = if (config.systemAvailableBytes > 0L) {
            (config.systemAvailableBytes / 4L).coerceAtLeast(MIN_MEMORY_BUDGET_BYTES)
        } else {
            Long.MAX_VALUE
        }

        val autoBudget = if (config.lowRamDevice) {
            min(LOW_RAM_MEMORY_BUDGET_BYTES, heapCeiling)
                .coerceAtLeast(MIN_MEMORY_BUDGET_BYTES.coerceAtMost(LOW_RAM_MEMORY_BUDGET_BYTES))
        } else {
            min(DEFAULT_MEMORY_BUDGET_BYTES, heapCeiling)
                .coerceAtLeast(min(64L * 1024L * 1024L, heapCeiling))
                .coerceAtLeast(MIN_MEMORY_BUDGET_BYTES)
        }.coerceAtMost(systemCeiling)

        val hardCeiling = if (config.lowRamDevice) {
            MAX_LOW_RAM_MEMORY_BUDGET_BYTES
        } else {
            MAX_MEMORY_BUDGET_BYTES
        }

        val requested = if (config.requestedRamBytes > 0L) {
            min(config.requestedRamBytes, min(hardCeiling, heapCeiling))
                .coerceAtMost(systemCeiling)
        } else {
            autoBudget
        }

        return requested
            .coerceAtLeast(MIN_MEMORY_BUDGET_BYTES)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}
