package com.lagradost.cloudstream3.ui.player

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, side-effect free adaptive forward-buffer policy used by [ProactiveLoadControl].
 *
 * Media3's [androidx.media3.exoplayer.DefaultLoadControl] stops loading when the buffered duration
 * reaches `maxBufferMs` and only resumes when it falls below `minBufferMs`. CloudStream used to keep
 * `minBufferMs` at Media3's 50 s default while `maxBufferMs` came from the user's Video Buffer
 * Length (60-1800 s). That made long buffers drain almost completely before refilling.
 *
 * This policy fixes the threshold and continuously adapts to network, memory and playback conditions:
 * - Upper target respects the user's Video Buffer Length, clamped for live streams.
 * - Allocator memory is enforced separately through a device-safe budget with hysteresis.
 * - Refill starts early enough to prevent starvation before new media arrives.
 * - Recovers quickly after seeks, rebuffers and stalls.
 * - Detects unsustainable qualities and advises quality recovery.
 */
object ForwardBufferPolicy {
    /** Lowest adaptive refill point. Faster/stable links stay at or below this floor. */
    const val BASE_REFILL_FRACTION = 0.70

    /** Never start later than this fraction, regardless of how good the network looks. */
    const val MAX_REFILL_FRACTION = 0.95

    /** Minimum hysteresis band so target/refill cannot collapse into the same value. */
    const val MIN_HYSTERESIS_MS = 1_000L

    /** Allocated bytes must fall to this fraction of the budget before loading may restart. */
    const val BYTE_REFILL_FRACTION = 0.85

    /**
     * Hard safety ceiling as a multiple of the allocator budget. Even a critically empty buffer
     * must not allocate past this to prevent OOM.
     */
    const val BYTE_PANIC_FRACTION = 1.5

    /** Forward buffer below this is allowed to ignore the normal byte cap so playback can recover. */
    const val CRITICAL_BUFFER_MS = 2_000L

    /** Media3's default streaming target, used when the user selects "Automatic". */
    const val DEFAULT_MAX_BUFFER_MS = 50_000L

    /** Largest selectable target (up to 30 minutes); guards corrupt preferences. */
    const val MAX_CONFIGURED_BUFFER_MS = 1_800_000L

    /** Live streams must not build an arbitrarily large forward buffer. */
    const val LIVE_MAX_BUFFER_MS = 50_000L

    /** Smallest target we will ever hand to Media3. */
    const val MIN_TARGET_MS = 2_000L

    /** Media3 default video + audio allocator budget (about 137.5 MiB). */
    const val DEFAULT_MEMORY_BUDGET_BYTES = 144_179_200L

    /** Conservative allocator budget for low-RAM devices (48 MiB). */
    const val LOW_RAM_MEMORY_BUDGET_BYTES = 48L * 1024L * 1024L

    /** Never configure an allocator smaller than this. */
    const val MIN_MEMORY_BUDGET_BYTES = 8L * 1024L * 1024L

    /** Upper bound for user-selected RAM on a normal device. */
    const val MAX_MEMORY_BUDGET_BYTES = 384L * 1024L * 1024L

    /** Upper bound for user-selected RAM on a low-RAM device. */
    const val MAX_LOW_RAM_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L

    /** Back buffer retained for instant short rewinds. */
    const val BACK_BUFFER_MS = 30_000L

    /** Fallback media consumption rate when the selected format has no bitrate metadata. */
    const val DEFAULT_CONSUMPTION_BPS = 2_000_000L

    /** Fallback time-to-first-byte when no network sample is available yet. */
    const val DEFAULT_STARTUP_MS = 1_000L

    /** Fallback duration of the next request when no transfer sample is available yet. */
    const val DEFAULT_REQUEST_MS = 3_000L

    /** Cap on the request-startup part of the refill ETA. */
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
        val requestedBufferMs: Long,
        val requestedRamBytes: Long,
        val lowRamDevice: Boolean,
        val memoryClassMb: Int = 0,
        val maxHeapBytes: Long = 0L,
        val systemAvailableBytes: Long = 0L,
    )

    data class NetworkStats(
        val throughputBps: Long = 0L,
        val peakThroughputBps: Long = 0L,
        val throughputVariation: Float = 0f,
        val startupLatencyMs: Long = 0L,
        val lastTransferMs: Long = 0L,
        val samples: Int = 0,
        val state: NetworkState = NetworkState.UNKNOWN,
        val longTermThroughputBps: Long = 0L,
        val confidence: Float = 0f,
        val activeRequests: Int = 0,
        val longestStallMs: Long = 0L,
        val isIdle: Boolean = false,
    )

    data class Plan(
        val requestedBufferMs: Long,
        val targetMs: Long,
        val refillMs: Long,
        val memoryBudgetBytes: Int,
        val lowMemoryWatermarkBytes: Int,
        val isLive: Boolean,
        val lowRamDevice: Boolean,
        val selectedBitrateBps: Long,
        val consumptionBps: Long,
    )

    enum class BufferState {
        INITIALIZING,
        INITIAL_BUFFERING,
        HEALTHY,
        REFILLING,
        DEGRADED_NETWORK,
        STALLED_REQUEST,
        NETWORK_OFFLINE,
        LOW_MEMORY,
        QUALITY_RECOVERY,
        REBUFFER_RECOVERY,
        PAUSED_PREFETCH,
        LIVE_PLAYBACK,
        ENDED,
        RELEASED,
    }

    enum class DepletionRisk {
        NONE,
        LOW,
        MODERATE,
        HIGH,
        CRITICAL,
    }

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
        STALLED_REQUEST,
        NETWORK_OFFLINE,
        QUALITY_RECOVERY,
        PAUSED_PREFETCH,
    }

    data class Decision(
        val isLoading: Boolean,
        val reason: Reason,
        val effectiveTargetMs: Long,
        val refillThresholdMs: Long,
        val runwayMs: Long,
        val timeToRefillMs: Long,
        val throughputBps: Long,
        val consumptionBps: Long,
        val dynamicRefillFraction: Float,
        val critical: Boolean,
        val memoryPressure: Boolean,
        val bufferState: BufferState = BufferState.HEALTHY,
        val depletionRisk: DepletionRisk = DepletionRisk.NONE,
        val networkState: NetworkState = NetworkState.UNKNOWN,
        val sustainableConsumptionBps: Long = 0L,
        val isQualityRecoveryNeeded: Boolean = false,
    )

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

    fun decide(
        plan: Plan,
        isLoading: Boolean,
        bufferedDurationUs: Long,
        allocatedBytes: Long,
        playbackSpeed: Float,
        network: NetworkStats = NetworkStats(),
        playbackState: Int = 3, // Player.STATE_READY
        playWhenReady: Boolean = true,
        rebuffering: Boolean = false,
        memoryPressure: Boolean = false,
        isAdaptiveQualitySupported: Boolean = true,
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

        // Depletion risk evaluation
        val risk = when {
            critical || rebuffering -> DepletionRisk.CRITICAL
            network.state == NetworkState.STALLED || (throughput > 0L && timeToRefillMs > runwayMs) -> DepletionRisk.HIGH
            ratio <= (speed * 1.15) || (throughput > 0L && timeToRefillMs > (runwayMs * 0.75).toLong()) -> DepletionRisk.MODERATE
            bufferedMs <= refillThreshold -> DepletionRisk.LOW
            else -> DepletionRisk.NONE
        }

        // Quality recovery recommendation
        val qualityRecoveryNeeded = isAdaptiveQualitySupported &&
            throughput > 0L &&
            network.samples >= MIN_THROUGHPUT_SAMPLES &&
            network.confidence > 0.40f &&
            ratio < (speed * 0.90) &&
            (bufferedMs < (targetMs * 0.60) || runwayMs < 10_000L)

        val sustainableBps = if (qualityRecoveryNeeded) {
            (throughput * 0.75).toLong()
        } else {
            0L
        }

        fun decision(
            loading: Boolean,
            reason: Reason,
            bufferState: BufferState,
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
            bufferState = bufferState,
            depletionRisk = risk,
            networkState = network.state,
            sustainableConsumptionBps = sustainableBps,
            isQualityRecoveryNeeded = qualityRecoveryNeeded,
        )

        // End of stream guard
        if (playbackState == 4) { // Player.STATE_ENDED
            return decision(false, Reason.NOT_PLAYING, BufferState.ENDED)
        }

        // Offline link detection
        if (network.state == NetworkState.OFFLINE && !critical) {
            return decision(false, Reason.NETWORK_OFFLINE, BufferState.NETWORK_OFFLINE)
        }

        // Paused prefetch policy: bounded up to targetMs
        if (!playWhenReady) {
            if (bufferedMs >= targetMs) {
                return decision(false, Reason.TARGET_REACHED, BufferState.HEALTHY)
            }
            if (bytePanic || byteHigh) {
                return decision(
                    false,
                    if (isLoading) Reason.BYTE_LIMIT_REACHED else Reason.WAITING_FOR_MEMORY,
                    BufferState.LOW_MEMORY
                )
            }
            if (critical || rebuffering) {
                return decision(
                    true,
                    if (rebuffering) Reason.REBUFFER_RECOVERY else Reason.INITIAL_LOAD,
                    BufferState.INITIAL_BUFFERING
                )
            }
            if (isLoading) {
                return decision(true, Reason.CONTINUE, BufferState.PAUSED_PREFETCH)
            }
            if (plan.isLive && bufferedMs >= plan.refillMs) {
                return decision(false, Reason.ABOVE_REFILL, BufferState.LIVE_PLAYBACK)
            }
            if (bufferedMs <= refillThreshold) {
                if (!byteLow) return decision(false, Reason.WAITING_FOR_MEMORY, BufferState.LOW_MEMORY)
                return decision(true, Reason.BELOW_REFILL, BufferState.PAUSED_PREFETCH)
            }
            return decision(false, Reason.ABOVE_REFILL, BufferState.HEALTHY)
        }

        // Loading in progress: keep loading until target or safety limit
        if (isLoading) {
            if (bytePanic) return decision(false, Reason.BYTE_LIMIT_REACHED, BufferState.LOW_MEMORY)
            if (critical) return decision(true, Reason.CONTINUE, BufferState.REBUFFER_RECOVERY)
            if (byteHigh) return decision(false, Reason.BYTE_LIMIT_REACHED, BufferState.LOW_MEMORY)
            if (bufferedMs >= targetMs) return decision(false, Reason.TARGET_REACHED, BufferState.HEALTHY)
            val currentState = when {
                network.state == NetworkState.STALLED -> BufferState.STALLED_REQUEST
                qualityRecoveryNeeded -> BufferState.QUALITY_RECOVERY
                throughput > 0L && ratio < 1.1 -> BufferState.DEGRADED_NETWORK
                plan.isLive -> BufferState.LIVE_PLAYBACK
                else -> BufferState.REFILLING
            }
            return decision(true, Reason.CONTINUE, currentState)
        }

        // Idle state: decide whether to start loading
        if (bytePanic) return decision(false, Reason.WAITING_FOR_MEMORY, BufferState.LOW_MEMORY)
        if (critical) {
            return decision(
                true,
                if (rebuffering) Reason.REBUFFER_RECOVERY else Reason.INITIAL_LOAD,
                if (rebuffering) BufferState.REBUFFER_RECOVERY else BufferState.INITIAL_BUFFERING
            )
        }
        if (byteHigh) return decision(false, Reason.WAITING_FOR_MEMORY, BufferState.LOW_MEMORY)

        if (rebuffering && bufferedMs < targetMs) {
            return decision(true, Reason.REBUFFER_RECOVERY, BufferState.REBUFFER_RECOVERY)
        }

        if (bufferedMs <= refillThreshold) {
            if (!byteLow) return decision(false, Reason.WAITING_FOR_MEMORY, BufferState.LOW_MEMORY)
            val reason = when {
                network.state == NetworkState.STALLED -> Reason.STALLED_REQUEST
                qualityRecoveryNeeded -> Reason.QUALITY_RECOVERY
                throughput > 0L && ratio <= 1.05 -> Reason.LOW_THROUGHPUT
                predictiveRefill > dynamicRefill -> Reason.PREDICTIVE_REFILL
                else -> Reason.BELOW_REFILL
            }
            val state = when {
                network.state == NetworkState.STALLED -> BufferState.STALLED_REQUEST
                qualityRecoveryNeeded -> BufferState.QUALITY_RECOVERY
                throughput > 0L && ratio <= 1.05 -> BufferState.DEGRADED_NETWORK
                plan.isLive -> BufferState.LIVE_PLAYBACK
                else -> BufferState.REFILLING
            }
            return decision(true, reason, state)
        }

        val idleState = when {
            plan.isLive -> BufferState.LIVE_PLAYBACK
            memoryPressure -> BufferState.LOW_MEMORY
            else -> BufferState.HEALTHY
        }
        return decision(false, if (memoryPressure) Reason.MEMORY_PRESSURE else Reason.ABOVE_REFILL, idleState)
    }

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
