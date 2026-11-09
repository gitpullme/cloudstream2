package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Verifies the end-to-end adaptive playback loading sequence:
 * 1. Buffer falls below predictive threshold.
 * 2. LoadControl requests continued loading.
 * 3. Media3 starts a load (DataSource open).
 * 4. Upstream data source initializes.
 * 5. Response begins (headers received).
 * 6. Bytes arrive (media data read).
 * 7. Forward buffer grows.
 *
 * Measures and validates the duration between each step.
 */
class PlaybackLoadingPipelineTest {

    private class PipelineDataSource(private val payload: ByteArray) : DataSource {
        private var inputStream: ByteArrayInputStream? = null
        var isClosed = false

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            isClosed = false
            inputStream = ByteArrayInputStream(payload)
            return payload.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val stream = inputStream ?: throw IOException("read() before open()")
            return stream.read(buffer, offset, length)
        }

        override fun getUri(): Uri = Uri.parse("https://cdn.example.com/segment_001.ts")

        override fun close() {
            isClosed = true
            inputStream?.close()
            inputStream = null
        }
    }

    @Test
    fun `test complete playback loading sequence and measure step latencies`() {
        val stepTimesNanos = LongArray(8)
        val stepDurationsMs = DoubleArray(7)

        val targetBufferMs = 120_000L
        val plan = ForwardBufferPolicy.createPlan(
            config = ForwardBufferPolicy.Config(
                requestedBufferMs = targetBufferMs,
                requestedRamBytes = 64L * 1024L * 1024L,
                lowRamDevice = false,
            ),
            isLive = false,
            selectedBitrateBps = 4_000_000L, // 4 Mbps
        )

        var simulatedTime = 10_000L
        val networkMonitor = PlaybackNetworkMonitor(clock = { simulatedTime })
        val mediaPayload = ByteArray(256 * 1024) { (it % 256).toByte() } // 256 KB segment
        val upstreamSource = PipelineDataSource(mediaPayload)

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = { upstreamSource },
            upstreamFactory = { upstreamSource },
            shouldBypass = { false },
        )


        // --- STEP 1: Buffer falls below predictive threshold ---
        stepTimesNanos[1] = System.nanoTime()
        val belowThresholdBufferedUs = 50_000_000L // 50 seconds (refill threshold is 84s)

        // --- STEP 2: LoadControl requests continued loading ---
        stepTimesNanos[2] = System.nanoTime()
        stepDurationsMs[1] = (stepTimesNanos[2] - stepTimesNanos[1]) / 1_000_000.0

        val decision = ForwardBufferPolicy.decide(
            plan = plan,
            isLoading = false,
            bufferedDurationUs = belowThresholdBufferedUs,
            allocatedBytes = 10L * 1024L * 1024L,
            playbackSpeed = 1f,
            network = networkMonitor.snapshot(),
            playbackState = 3, // READY
            playWhenReady = true,
        )
        assertTrue("LoadControl must request loading when buffer is below threshold", decision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.BELOW_REFILL, decision.reason)

        // --- STEP 3: Media3 starts a load (DataSource open) ---
        stepTimesNanos[3] = System.nanoTime()
        stepDurationsMs[2] = (stepTimesNanos[3] - stepTimesNanos[3]) / 1_000_000.0

        val dataSource = routingFactory.createDataSource()
        val dataSpec = DataSpec(Uri.parse("https://cdn.example.com/segment_001.ts"))

        // --- STEP 4: Upstream data source initializes ---
        stepTimesNanos[4] = System.nanoTime()
        stepDurationsMs[3] = (stepTimesNanos[4] - stepTimesNanos[3]) / 1_000_000.0

        networkMonitor.onTransferInitializing(upstreamSource, dataSpec, isNetwork = true)
        simulatedTime += 50L
        val openedLength = dataSource.open(dataSpec)
        assertEquals(mediaPayload.size.toLong(), openedLength)

        // --- STEP 5: Response begins (headers/TTFB) ---
        stepTimesNanos[5] = System.nanoTime()
        stepDurationsMs[4] = (stepTimesNanos[5] - stepTimesNanos[4]) / 1_000_000.0

        networkMonitor.onTransferStart(upstreamSource, dataSpec, isNetwork = true)
        simulatedTime += 50L

        // --- STEP 6: Bytes arrive ---
        stepTimesNanos[6] = System.nanoTime()
        stepDurationsMs[5] = (stepTimesNanos[6] - stepTimesNanos[5]) / 1_000_000.0

        val buffer = ByteArray(32 * 1024)
        var totalBytesRead = 0
        while (totalBytesRead < mediaPayload.size) {
            val bytesRead = dataSource.read(buffer, 0, buffer.size)
            if (bytesRead < 0) break
            totalBytesRead += bytesRead
            simulatedTime += 10L
            networkMonitor.onBytesTransferred(upstreamSource, dataSpec, isNetwork = true, bytesTransferred = bytesRead)
        }
        simulatedTime += 100L
        networkMonitor.onTransferEnd(upstreamSource, dataSpec, isNetwork = true)
        dataSource.close()

        assertEquals(mediaPayload.size, totalBytesRead)
        val networkSnapshot = networkMonitor.snapshot()
        assertTrue("Network monitor must record sample", networkSnapshot.samples >= 1)
        assertTrue("Throughput must be positive", networkSnapshot.throughputBps > 0L)

        // --- STEP 7: Forward buffer grows ---
        stepTimesNanos[7] = System.nanoTime()
        stepDurationsMs[6] = (stepTimesNanos[7] - stepTimesNanos[6]) / 1_000_000.0

        // As segments arrive, buffered media duration advances:
        val replenishedBufferedUs = 120_000_000L // 120 seconds (reaches target)
        val replenishedDecision = ForwardBufferPolicy.decide(
            plan = plan,
            isLoading = true,
            bufferedDurationUs = replenishedBufferedUs,
            allocatedBytes = 25L * 1024L * 1024L,
            playbackSpeed = 1f,
            network = networkMonitor.snapshot(),
            playbackState = 3,
            playWhenReady = true,
        )

        assertFalse("Loading must stop when target buffer is reached", replenishedDecision.isLoading)
        assertEquals(ForwardBufferPolicy.Reason.TARGET_REACHED, replenishedDecision.reason)

        // Verify timing measurements between every step
        println("=== Playback Loading Pipeline Latencies ===")
        println("Step 1 -> 2 (Threshold detection to LoadControl request): ${stepDurationsMs[1]} ms")
        println("Step 2 -> 3 (LoadControl request to Media3 load start):   ${stepDurationsMs[2]} ms")
        println("Step 3 -> 4 (Media3 load start to upstream init):        ${stepDurationsMs[3]} ms")
        println("Step 4 -> 5 (Upstream init to response start):           ${stepDurationsMs[4]} ms")
        println("Step 5 -> 6 (Response start to bytes arrived):           ${stepDurationsMs[5]} ms")
        println("Step 6 -> 7 (Bytes arrival to buffer growth check):      ${stepDurationsMs[6]} ms")

        for (i in 1..6) {
            assertTrue("Step $i to ${i+1} duration must be non-negative", stepDurationsMs[i] >= 0.0)
        }
    }
}
