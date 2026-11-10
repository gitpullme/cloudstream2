package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the adaptive forward-buffer policy. */
class ForwardBufferPolicyTest {

    private fun createPlan(
        bufferMs: Long = 120_000L,
        ramBytes: Long = 0L,
        lowRam: Boolean = false,
        live: Boolean = false,
        bitrateBps: Long = 0L,
        memoryClassMb: Int = 0,
        maxHeapBytes: Long = 0L,
        availableBytes: Long = 0L,
    ) = ForwardBufferPolicy.createPlan(
        config = ForwardBufferPolicy.Config(
            requestedBufferMs = bufferMs,
            requestedRamBytes = ramBytes,
            lowRamDevice = lowRam,
            memoryClassMb = memoryClassMb,
            maxHeapBytes = maxHeapBytes,
            systemAvailableBytes = availableBytes,
        ),
        isLive = live,
        selectedBitrateBps = bitrateBps,
    )

    private fun decide(
        plan: ForwardBufferPolicy.Plan,
        loading: Boolean,
        bufferedMs: Long,
        allocatedBytes: Long = 0L,
        speed: Float = 1f,
        network: ForwardBufferPolicy.NetworkStats = ForwardBufferPolicy.NetworkStats(),
        playbackState: Int = 3,
        playWhenReady: Boolean = true,
        rebuffering: Boolean = false,
        memoryPressure: Boolean = false,
    ) = ForwardBufferPolicy.decide(
        plan = plan,
        isLoading = loading,
        bufferedDurationUs = if (bufferedMs == Long.MIN_VALUE) Long.MIN_VALUE else bufferedMs * 1_000L,
        allocatedBytes = allocatedBytes,
        playbackSpeed = speed,
        network = network,
        playbackState = playbackState,
        playWhenReady = playWhenReady,
        rebuffering = rebuffering,
        memoryPressure = memoryPressure,
    )

    private fun network(
        throughputBps: Long,
        samples: Int = 5,
        variation: Float = 0f,
        startupMs: Long = 0L,
        transferMs: Long = 0L,
    ) = ForwardBufferPolicy.NetworkStats(
        throughputBps = throughputBps,
        peakThroughputBps = throughputBps,
        throughputVariation = variation,
        startupLatencyMs = startupMs,
        lastTransferMs = transferMs,
        samples = samples,
    )

    @Test
    fun `automatic target keeps the media3 default and starts at 70 percent`() {
        val plan = createPlan(bufferMs = 0L)

        assertEquals(50_000L, plan.targetMs)
        assertEquals(35_000L, plan.refillMs)
        assertEquals(144179200, plan.memoryBudgetBytes)
        assertEquals(122_552_320, plan.lowMemoryWatermarkBytes)
    }

    @Test
    fun `configured target is preserved and baseline is 70 percent`() {
        val plan = createPlan(bufferMs = 120_000L)

        assertEquals(120_000L, plan.targetMs)
        assertEquals(84_000L, plan.refillMs)
    }

    @Test
    fun `loading continues between refill and target`() {
        val decision = decide(createPlan(), loading = true, bufferedMs = 110_000L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.CONTINUE, decision.reason)
    }

    @Test
    fun `full buffer stops loading`() {
        val decision = decide(createPlan(), loading = true, bufferedMs = 120_000L)

        assertFalse(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.TARGET_REACHED, decision.reason)
    }

    @Test
    fun `buffer just above the adaptive refill stays idle`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 84_001L)

        assertFalse(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.ABOVE_REFILL, decision.reason)
    }

    @Test
    fun `buffer at the adaptive refill starts loading`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 84_000L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decision.reason)
    }

    @Test
    fun `empty buffer starts loading`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 0L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, decision.reason)
    }

    @Test
    fun `unknown negative buffer is treated as empty`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = Long.MIN_VALUE)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, decision.reason)
    }

    @Test
    fun `state idle still allows the initial load during prepare`() {
        // Media3 can ask for data before the public state has advanced from IDLE. Blocking that
        // would deadlock prepare, which was a real risk in the previous implementation.
        val decision = decide(
            plan = createPlan(),
            loading = false,
            bufferedMs = 0L,
            playbackState = 1, // Player.STATE_IDLE
        )

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, decision.reason)
    }

    @Test
    fun `state ended stops loading`() {
        val decision = decide(
            plan = createPlan(),
            loading = false,
            bufferedMs = 0L,
            playbackState = 4, // Player.STATE_ENDED
        )

        assertFalse(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.NOT_PLAYING, decision.reason)
    }

    @Test
    fun `live target is capped and still uses the adaptive threshold`() {
        val livePlan = createPlan(bufferMs = 480_000L, live = true)

        assertEquals(50_000L, livePlan.targetMs)
        assertEquals(35_000L, livePlan.refillMs)
        assertTrue(livePlan.isLive)

        assertFalse(decide(livePlan, loading = false, bufferedMs = 40_000L).isLoading)
        assertTrue(decide(livePlan, loading = false, bufferedMs = 35_000L).isLoading)
    }

    @Test
    fun `bitrate no longer shrinks a low ram time target`() {
        // The old implementation converted nominal bitrate into a byte cap and reduced the target
        // from 120 s to about 48 s. Only actual allocator bytes may stop loading now.
        val plan = createPlan(bufferMs = 120_000L, lowRam = true, bitrateBps = 8_000_000L)

        assertEquals(ForwardBufferPolicy.LOW_RAM_MEMORY_BUDGET_BYTES.toInt(), plan.memoryBudgetBytes)
        assertEquals(120_000L, plan.targetMs)
        assertEquals(84_000L, plan.refillMs)
        assertEquals(8_000_000L, plan.consumptionBps)
    }

    @Test
    fun `small explicit ram setting is honoured`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(bufferMs = 120_000L, ramBytes = tenMb, lowRam = true)

        assertEquals(tenMb.toInt(), plan.memoryBudgetBytes)
        assertEquals((tenMb * 0.85).toLong().toInt(), plan.lowMemoryWatermarkBytes)
        assertEquals(120_000L, plan.targetMs)
    }

    @Test
    fun `explicit ram setting is clamped to a safe device budget`() {
        // 35% of the 100 MiB heap, not the user's 400 MiB request or the nominal bitrate.
        val plan = createPlan(
            bufferMs = 120_000L,
            ramBytes = 400L * 1024L * 1024L,
            memoryClassMb = 100,
        )

        assertEquals((100L * 1024L * 1024L * 0.35).toInt(), plan.memoryBudgetBytes)
        assertEquals(120_000L, plan.targetMs)
    }

    @Test
    fun `runtime max heap takes precedence over the memory class estimate`() {
        val heap = 200L * 1024L * 1024L
        val plan = createPlan(memoryClassMb = 100, maxHeapBytes = heap)

        assertEquals((heap * 0.35).toInt(), plan.memoryBudgetBytes)
    }

    @Test
    fun `system available memory is a soft ceiling for automatic and explicit budgets`() {        val available = 256L * 1024L * 1024L
        val auto = createPlan(availableBytes = available)
        val explicit = createPlan(ramBytes = 200L * 1024L * 1024L, availableBytes = available)

        assertEquals((available / 4L).toInt(), auto.memoryBudgetBytes)
        assertEquals((available / 4L).toInt(), explicit.memoryBudgetBytes)
    }

    @Test
    fun `byte cap stops loading before the time target`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(bufferMs = 120_000L, ramBytes = tenMb)

        val decision = decide(
            plan = plan,
            loading = true,
            bufferedMs = 60_000L,
            allocatedBytes = tenMb,
        )

        assertFalse(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BYTE_LIMIT_REACHED, decision.reason)
    }

    @Test
    fun `byte hysteresis waits for the low watermark before restarting`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(bufferMs = 120_000L, ramBytes = tenMb)
        val lowWatermark = plan.lowMemoryWatermarkBytes.toLong()

        val waiting = decide(
            plan = plan,
            loading = false,
            bufferedMs = 60_000L,
            allocatedBytes = lowWatermark + 1L,
        )
        assertFalse(waiting.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.WAITING_FOR_MEMORY, waiting.reason)

        val restarting = decide(
            plan = plan,
            loading = false,
            bufferedMs = 60_000L,
            allocatedBytes = lowWatermark,
        )
        assertTrue(restarting.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, restarting.reason)
    }

    @Test
    fun `critically low buffer ignores the byte cap so playback can recover`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(bufferMs = 120_000L, ramBytes = tenMb)

        val restart = decide(
            plan = plan,
            loading = false,
            bufferedMs = 1_000L,
            allocatedBytes = tenMb,
        )
        assertTrue(restart.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, restart.reason)

        val keepLoading = decide(
            plan = plan,
            loading = true,
            bufferedMs = 1_000L,
            allocatedBytes = tenMb,
        )
        assertTrue(keepLoading.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.CONTINUE, keepLoading.reason)
    }

    @Test
    fun `panic byte ceiling stops even a critical buffer from over-allocating`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(bufferMs = 120_000L, ramBytes = tenMb)
        val panicBytes = (tenMb * ForwardBufferPolicy.BYTE_PANIC_FRACTION).toLong()

        val idle = decide(
            plan = plan,
            loading = false,
            bufferedMs = 1_000L,
            allocatedBytes = panicBytes,
        )
        assertFalse(idle.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.WAITING_FOR_MEMORY, idle.reason)

        val loading = decide(
            plan = plan,
            loading = true,
            bufferedMs = 1_000L,
            allocatedBytes = panicBytes + 1L,
        )
        assertFalse(loading.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BYTE_LIMIT_REACHED, loading.reason)
    }

    @Test
    fun `rebuffering restarts loading below the target`() {
        val decision = decide(
            plan = createPlan(),
            loading = false,
            bufferedMs = 30_000L,
            rebuffering = true,
        )

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.REBUFFER_RECOVERY, decision.reason)
    }

    @Test
    fun `a seek that empties the buffer immediately requests data`() {
        val plan = createPlan(bufferMs = 480_000L)

        // Before the seek the buffer was full and the loader was idle.
        assertFalse(decide(plan, loading = false, bufferedMs = 480_000L).isLoading)

        // ExoPlayer drops the forward buffer on seek; the policy must not wait for 70%.
        val afterSeek = decide(plan, loading = false, bufferedMs = 0L)
        assertTrue(afterSeek.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, afterSeek.reason)
    }

    @Test
    fun `higher playback speed raises the refill point`() {
        val plan = createPlan(bufferMs = 120_000L)

        // 2x needs more media in the bank for the same wall-clock runway.
        val normal = decide(plan, loading = false, bufferedMs = 86_000L, speed = 1f)
        val fast = decide(plan, loading = false, bufferedMs = 86_000L, speed = 2f)

        assertFalse(normal.isLoading)
        assertTrue(fast.isLoading)
        assertTrue(fast.refillThresholdMs > normal.refillThresholdMs)
    }

    @Test
    fun `slow measured throughput starts refilling earlier`() {
        val plan = createPlan(bufferMs = 120_000L, bitrateBps = 1_000_000L)

        val fast = decide(
            plan = plan,
            loading = false,
            bufferedMs = 90_000L,
            network = network(4_000_000L),
        )
        val slow = decide(
            plan = plan,
            loading = false,
            bufferedMs = 90_000L,
            network = network(1_200_000L),
        )

        assertFalse(fast.isLoading)
        assertTrue(slow.isLoading)
        assertTrue(slow.refillThresholdMs > fast.refillThresholdMs)
    }

    @Test
    fun `throughput below consumption starts at the maximum safe refill`() {
        val plan = createPlan(bufferMs = 120_000L, bitrateBps = 1_000_000L)
        val decision = decide(
            plan = plan,
            loading = false,
            bufferedMs = 100_000L,
            network = network(800_000L),
        )

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.LOW_THROUGHPUT, decision.reason)
        assertTrue(decision.refillThresholdMs >= 110_000L)
    }

    @Test
    fun `volatile throughput starts refilling earlier`() {
        val plan = createPlan(bufferMs = 120_000L, bitrateBps = 1_000_000L)

        val stable = decide(
            plan = plan,
            loading = false,
            bufferedMs = 87_000L,
            network = network(4_000_000L, variation = 0f),
        )
        val volatile = decide(
            plan = plan,
            loading = false,
            bufferedMs = 87_000L,
            network = network(4_000_000L, variation = 1f),
        )

        assertFalse(stable.isLoading)
        assertTrue(volatile.isLoading)
    }

    @Test
    fun `predicted exhaustion raises the refill point above the heuristic floor`() {
        // A short clip with a slow-ish link and a long TTFB: starting only at the heuristic 76%
        // would leave less runway than the measured download + startup time.
        val plan = createPlan(bufferMs = 20_000L, bitrateBps = 1_000_000L)
        val stats = network(
            throughputBps = 1_200_000L,
            startupMs = 10_000L,
            transferMs = 1_000L,
        )
        val decision = decide(
            plan = plan,
            loading = false,
            bufferedMs = 15_500L,
            network = stats,
        )

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.PREDICTIVE_REFILL, decision.reason)
        assertTrue(decision.refillThresholdMs > 15_200L)
        assertTrue(decision.timeToRefillMs > decision.runwayMs)
    }

    @Test
    fun `no throughput samples fall back to the safe heuristic without prediction`() {
        val plan = createPlan()
        val decision = decide(plan, loading = false, bufferedMs = 84_000L)

        assertTrue(decision.isLoading)
        assertEquals(84_000L, decision.refillThresholdMs)
        assertEquals(0L, decision.throughputBps)
        assertEquals(0L, decision.timeToRefillMs)
    }

    @Test
    fun `memory pressure halves the effective target and can hold loading off`() {
        val plan = createPlan(bufferMs = 120_000L)

        val normal = decide(plan, loading = false, bufferedMs = 50_000L)
        val pressure = decide(
            plan = plan,
            loading = false,
            bufferedMs = 50_000L,
            memoryPressure = true,
        )

        assertTrue(normal.isLoading)
        assertFalse(pressure.isLoading)
        assertEquals(60_000L, pressure.effectiveTargetMs)
        assertEquals(ForwardBufferPolicy.Reason.MEMORY_PRESSURE, pressure.reason)
        assertTrue(pressure.memoryPressure)
    }

    @Test
    fun `memory pressure still allows refill below its reduced watermark`() {
        val plan = createPlan(bufferMs = 120_000L)
        val decision = decide(
            plan = plan,
            loading = false,
            bufferedMs = 42_000L,
            memoryPressure = true,
        )

        assertTrue(decision.isLoading)
        assertEquals(60_000L, decision.effectiveTargetMs)
    }

    @Test
    fun `paused prefetch fills to the same target but never beyond it`() {
        val plan = createPlan()

        assertTrue(decide(plan, loading = true, bufferedMs = 60_000L, playWhenReady = false).isLoading)
        assertFalse(decide(plan, loading = true, bufferedMs = 120_000L, playWhenReady = false).isLoading)
        assertTrue(decide(plan, loading = false, bufferedMs = 60_000L, playWhenReady = false).isLoading)
        assertFalse(decide(plan, loading = false, bufferedMs = 95_000L, playWhenReady = false).isLoading)
    }

    @Test
    fun `paused live streams cap forward buffer and stop loading at live limit`() {
        val livePlan = createPlan(live = true)
        assertEquals(ForwardBufferPolicy.LIVE_MAX_BUFFER_MS, livePlan.targetMs)

        // Below live target while loading -> continues
        assertTrue(decide(livePlan, loading = true, bufferedMs = 30_000L, playWhenReady = false).isLoading)

        // At live target while loading -> stops
        assertFalse(decide(livePlan, loading = true, bufferedMs = 50_000L, playWhenReady = false).isLoading)

        // Above live target -> stops
        assertFalse(decide(livePlan, loading = false, bufferedMs = 50_001L, playWhenReady = false).isLoading)
    }

    @Test
    fun `live state changes and dynamic progressive streams adjust plan targets`() {
        val vodPlan = createPlan(bufferMs = 120_000L, live = false)
        assertEquals(120_000L, vodPlan.targetMs)

        // Transition to live caps target
        val livePlan = ForwardBufferPolicy.createPlan(
            config = ForwardBufferPolicy.Config(requestedBufferMs = 120_000L, requestedRamBytes = 0L, lowRamDevice = false),
            isLive = true,
            selectedBitrateBps = vodPlan.selectedBitrateBps,
        )
        assertEquals(ForwardBufferPolicy.LIVE_MAX_BUFFER_MS, livePlan.targetMs)

        // Transition back from live to VOD restores target
        val restoredPlan = ForwardBufferPolicy.createPlan(
            config = ForwardBufferPolicy.Config(requestedBufferMs = 120_000L, requestedRamBytes = 0L, lowRamDevice = false),
            isLive = false,
            selectedBitrateBps = livePlan.selectedBitrateBps,
        )
        assertEquals(120_000L, restoredPlan.targetMs)
    }

    @Test
    fun `simulated playback refills well above the legacy 50 second threshold`() {
        for (targetMs in listOf(120_000L, 480_000L)) {
            val plan = createPlan(bufferMs = targetMs)
            var bufferedMs = targetMs
            var loading = false
            var refillAtMs = -1L

            while (bufferedMs > 0L && refillAtMs < 0L) {
                val decision = decide(plan, loading, bufferedMs)
                if (decision.isLoading) {
                    refillAtMs = bufferedMs
                } else {
                    loading = false
                    bufferedMs -= 1_000L
                }
            }

            assertEquals((targetMs * 0.7).toLong(), refillAtMs)
            assertTrue(refillAtMs > 50_000L)
        }
    }

    @Test
    fun `simulated slow link refills before the buffer is dangerous`() {
        val plan = createPlan(bufferMs = 480_000L, bitrateBps = 1_000_000L)
        var bufferedMs = 480_000L
        var loading = false
        var refillAtMs = -1L

        while (bufferedMs > 0L && refillAtMs < 0L) {
            val decision = decide(
                plan = plan,
                loading = loading,
                bufferedMs = bufferedMs,
                network = network(1_200_000L),
            )
            if (decision.isLoading) {
                refillAtMs = bufferedMs
            } else {
                loading = false
                bufferedMs -= 1_000L
            }
        }

        assertTrue(refillAtMs >= 360_000L)
        assertTrue(refillAtMs > 50_000L)
    }

    @Test
    fun `oversized legacy setting is clamped and keeps hysteresis`() {
        val plan = createPlan(bufferMs = 3_600_000L)

        assertEquals(ForwardBufferPolicy.MAX_CONFIGURED_BUFFER_MS, plan.targetMs)
        assertEquals(1_260_000L, plan.refillMs)
        assertTrue(plan.refillMs < plan.targetMs)
    }

    @Test
    fun `the largest ui buffer setting is preserved`() {
        val plan = createPlan(bufferMs = 1_800_000L)

        assertEquals(1_800_000L, plan.targetMs)
        assertEquals(1_260_000L, plan.refillMs)
    }

    @Test
    fun `short target still has a safe refill threshold`() {
        val plan = createPlan(bufferMs = 5_000L)

        assertEquals(5_000L, plan.targetMs)
        assertEquals(3_500L, plan.refillMs)
        assertEquals(3_500L, decide(plan, loading = false, bufferedMs = 3_500L).refillThresholdMs)
        assertEquals(3_000L, decide(plan, loading = false, bufferedMs = 3_000L).runwayMs)
    }

    @Test
    fun `stalled network request sets STALLED_REQUEST state and HIGH depletion risk`() {
        val plan = createPlan(bufferMs = 60_000L, bitrateBps = 2_000_000L)
        val netStalled = ForwardBufferPolicy.NetworkStats(
            throughputBps = 500_000L,
            samples = 3,
            state = NetworkState.STALLED,
            longestStallMs = 4_000L,
        )

        val decision = decide(plan, loading = false, bufferedMs = 30_000L, network = netStalled)
        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.STALLED_REQUEST, decision.reason)
        assertEquals(ForwardBufferPolicy.BufferState.STALLED_REQUEST, decision.bufferState)
        assertEquals(ForwardBufferPolicy.DepletionRisk.HIGH, decision.depletionRisk)
    }

    @Test
    fun `offline network suppresses loading unless buffer is critical`() {
        val plan = createPlan(bufferMs = 60_000L)
        val netOffline = ForwardBufferPolicy.NetworkStats(
            throughputBps = 0L,
            samples = 2,
            state = NetworkState.OFFLINE,
        )

        // Non-critical buffer offline: do not schedule futile requests
        val decisionNormal = decide(plan, loading = false, bufferedMs = 20_000L, network = netOffline)
        assertFalse(decisionNormal.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.NETWORK_OFFLINE, decisionNormal.reason)
        assertEquals(ForwardBufferPolicy.BufferState.NETWORK_OFFLINE, decisionNormal.bufferState)

        // Critical buffer (< 2000ms): must attempt recovery
        val decisionCritical = decide(plan, loading = false, bufferedMs = 1_000L, network = netOffline)
        assertTrue(decisionCritical.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, decisionCritical.reason)
    }

    @Test
    fun `quality recovery advised when throughput is sustainably below consumption`() {
        val plan = createPlan(bufferMs = 60_000L, bitrateBps = 5_000_000L)
        val netLow = ForwardBufferPolicy.NetworkStats(
            throughputBps = 2_000_000L, // 2 Mbps vs 5 Mbps consumption
            samples = 4,
            confidence = 0.8f,
            state = NetworkState.DEGRADED,
        )

        val decision = decide(plan, loading = false, bufferedMs = 25_000L, network = netLow)
        assertTrue("Must flag quality recovery needed", decision.isQualityRecoveryNeeded)
        assertTrue("Sustainable consumption must be advised", decision.sustainableConsumptionBps in 1_000_000L..2_000_000L)
        assertEquals(ForwardBufferPolicy.BufferState.QUALITY_RECOVERY, decision.bufferState)
    }

    @Test
    fun `paused prefetch loads bounded to target without starvation panic`() {
        val plan = createPlan(bufferMs = 60_000L)

        // Below target: loads up to targetMs
        val decisionLoading = decide(plan, loading = false, bufferedMs = 30_000L, playWhenReady = false)
        assertTrue(decisionLoading.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decisionLoading.reason)
        assertEquals(ForwardBufferPolicy.BufferState.PAUSED_PREFETCH, decisionLoading.bufferState)

        // Target reached: stops loading
        val decisionFull = decide(plan, loading = false, bufferedMs = 60_000L, playWhenReady = false)
        assertFalse(decisionFull.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.TARGET_REACHED, decisionFull.reason)
    }

    @Test
    fun `playback speed 1x vs 1_5x vs 2x increases refill threshold`() {
        val plan = createPlan(bufferMs = 120_000L, bitrateBps = 2_000_000L)
        val net = network(throughputBps = 3_000_000L)

        val d1x = decide(plan, loading = false, bufferedMs = 80_000L, speed = 1.0f, network = net)
        val d15x = decide(plan, loading = false, bufferedMs = 80_000L, speed = 1.5f, network = net)
        val d2x = decide(plan, loading = false, bufferedMs = 80_000L, speed = 2.0f, network = net)

        assertTrue(d15x.refillThresholdMs >= d1x.refillThresholdMs)
        assertTrue(d2x.refillThresholdMs >= d15x.refillThresholdMs)
    }
}
