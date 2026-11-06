package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure forward-buffer threshold policy. */
class ForwardBufferPolicyTest {

    private fun createPlan(
        bufferMs: Long = 120_000L,
        ramBytes: Long = 0L,
        lowRam: Boolean = false,
        live: Boolean = false,
        bitrateBps: Long = 0L,
    ) = ForwardBufferPolicy.createPlan(
        config = ForwardBufferPolicy.Config(
            requestedBufferMs = bufferMs,
            requestedRamBytes = ramBytes,
            lowRamDevice = lowRam,
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
    ) = ForwardBufferPolicy.decide(
        plan = plan,
        isLoading = loading,
        bufferedDurationUs = bufferedMs * 1_000L,
        allocatedBytes = allocatedBytes,
        playbackSpeed = speed,
    )

    @Test
    fun `automatic target matches media3 default and refills at 80 percent`() {
        val plan = createPlan(bufferMs = 0L)

        assertEquals(50_000L, plan.targetMs)
        assertEquals(40_000L, plan.refillMs)
        assertEquals(ForwardBufferPolicy.AUTO_MEMORY_BUDGET_BYTES, plan.memoryBudgetBytes)
        assertEquals(115_343_360, plan.lowMemoryWatermarkBytes)
    }

    @Test
    fun `configured target is preserved and refill starts at 80 percent`() {
        val plan = createPlan(bufferMs = 120_000L)

        assertEquals(120_000L, plan.targetMs)
        assertEquals(96_000L, plan.refillMs)
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
    fun `buffer just above refill stays idle`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 96_001L)

        assertFalse(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.ABOVE_REFILL, decision.reason)
    }

    @Test
    fun `buffer at refill starts loading`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 96_000L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decision.reason)
    }

    @Test
    fun `buffer below refill starts loading`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 60_000L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decision.reason)
    }

    @Test
    fun `empty and unknown buffer durations start loading`() {
        val empty = decide(createPlan(), loading = false, bufferedMs = 0L)
        assertTrue(empty.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, empty.reason)

        val unknown = ForwardBufferPolicy.decide(
            plan = createPlan(),
            isLoading = false,
            bufferedDurationUs = Long.MIN_VALUE,
            allocatedBytes = 0L,
            playbackSpeed = 1f,
        )
        assertTrue(unknown.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.INITIAL_LOAD, unknown.reason)
    }

    @Test
    fun `buffer below playback start threshold starts loading`() {
        val decision = decide(createPlan(), loading = false, bufferedMs = 1_000L)

        assertTrue(decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decision.reason)
    }

    @Test
    fun `live target is capped and still refills at 80 percent`() {
        val livePlan = createPlan(bufferMs = 480_000L, live = true)

        assertEquals(50_000L, livePlan.targetMs)
        assertEquals(40_000L, livePlan.refillMs)
        assertTrue(livePlan.isLive)

        assertFalse(decide(livePlan, loading = false, bufferedMs = 45_000L).isLoading)
        assertTrue(decide(livePlan, loading = false, bufferedMs = 40_000L).isLoading)
    }

    @Test
    fun `paused prefetch keeps the same safe target behavior`() {
        // Media3 still asks the LoadControl whether it may buffer while paused. The policy fills
        // up to the target and then stops, so a pause cannot create an endless download.
        val plan = createPlan()

        assertTrue(decide(plan, loading = true, bufferedMs = 60_000L).isLoading)
        assertFalse(decide(plan, loading = true, bufferedMs = 120_000L).isLoading)
        assertTrue(decide(plan, loading = false, bufferedMs = 60_000L).isLoading)
    }

    @Test
    fun `low ram caps the target using the selected bitrate`() {
        val plan = createPlan(bufferMs = 120_000L, lowRam = true, bitrateBps = 8_000_000L)

        assertEquals(ForwardBufferPolicy.LOW_RAM_MEMORY_BUDGET_BYTES, plan.memoryBudgetBytes)
        assertEquals(47_815L, plan.targetMs)
        assertEquals(38_252L, plan.refillMs)
        assertTrue(plan.targetMs < plan.timeTargetMs)
    }

    @Test
    fun `explicit ram budget wins over low ram automatic budget`() {
        val tenMb = 10L * 1024L * 1024L
        val plan = createPlan(
            bufferMs = 120_000L,
            ramBytes = tenMb,
            lowRam = true,
            bitrateBps = 8_000_000L,
        )

        assertEquals(tenMb.toInt(), plan.memoryBudgetBytes)
        assertEquals(9_961L, plan.targetMs)
        assertEquals(7_968L, plan.refillMs)
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
        val lowWatermark = plan.lowMemoryWatermarkBytes

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
            allocatedBytes = lowWatermark.toLong(),
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
    fun `playback speed scales the refill watermark up to the target`() {
        val plan = createPlan(bufferMs = 120_000L)

        // 1.5x needs 1.5 * 96 s = 144 s of media to protect the same wall-clock duration, capped
        // at the 120 s target, so loading is already required at 110 s.
        assertTrue(decide(plan, loading = false, bufferedMs = 110_000L, speed = 1.5f).isLoading)

        // At normal speed 110 s is still above the 96 s watermark.
        assertFalse(decide(plan, loading = false, bufferedMs = 110_000L, speed = 1f).isLoading)

        // Slower playback consumes buffer more slowly and must not lower the watermark.
        assertFalse(decide(plan, loading = false, bufferedMs = 110_000L, speed = 0.5f).isLoading)
    }

    @Test
    fun `simulated playback refills long targets far above the legacy 50 second threshold`() {
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

            assertEquals((targetMs * 0.8).toLong(), refillAtMs)
            assertTrue(refillAtMs > 50_000L)
        }
    }

    @Test
    fun `oversized legacy setting is clamped to a safe maximum`() {
        val plan = createPlan(bufferMs = 3_600_000L)

        assertEquals(ForwardBufferPolicy.MAX_CONFIGURED_BUFFER_MS, plan.targetMs)
        assertEquals(480_000L, plan.refillMs)
    }

    @Test
    fun `short target still has a safe refill threshold`() {
        val plan = createPlan(bufferMs = 5_000L)

        assertEquals(5_000L, plan.targetMs)
        assertEquals(4_000L, plan.refillMs)
    }
}
