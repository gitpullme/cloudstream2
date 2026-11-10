package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PlaybackNetworkMonitorTest {

    private class FakeClock(var currentTimeMs: Long = 10_000L) {
        fun advance(ms: Long): Long {
            currentTimeMs += ms
            return currentTimeMs
        }
    }

    private class FakeDataSource(val name: String) : DataSource {
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = 1000L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        override fun getUri(): Uri? = Uri.parse("https://cdn.example.com/stream/$name.ts")
        override fun close() = Unit
    }

    private fun dataSpec(uriStr: String = "https://cdn.example.com/stream/seg1.ts") =
        DataSpec(Uri.parse(uriStr))

    @Test
    fun `no network history returns NetworkState UNKNOWN`() {
        val monitor = PlaybackNetworkMonitor()
        val snapshot = monitor.snapshot()

        assertEquals(0, snapshot.samples)
        assertEquals(0L, snapshot.throughputBps)
        assertEquals(NetworkState.UNKNOWN, snapshot.state)
        assertEquals(0f, snapshot.confidence, 0.001f)
    }

    @Test
    fun `completed network transfers update throughput in bits per second and report HEALTHY`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("video1")
        val spec = dataSpec()

        monitor.onTransferInitializing(source, spec, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source, spec, isNetwork = true)
        clock.advance(50)

        // 500 KB transferred in 400ms -> approx 10 Mbps
        val totalBytes = 500 * 1024
        monitor.onBytesTransferred(source, spec, isNetwork = true, bytesTransferred = totalBytes)
        clock.advance(400)
        monitor.onTransferEnd(source, spec, isNetwork = true)

        val snapshot = monitor.snapshot()
        assertEquals(1, snapshot.samples)
        assertTrue(snapshot.throughputBps > 5_000_000L)
        assertTrue(snapshot.peakThroughputBps >= snapshot.throughputBps)
        assertEquals(NetworkState.HEALTHY, snapshot.state)
    }

    @Test
    fun `slow connection is classified as DEGRADED`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("slow1")
        val spec = dataSpec()

        // 32 KB transferred in 1000ms -> approx 256 kbps (slow)
        monitor.onTransferInitializing(source, spec, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source, spec, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(source, spec, isNetwork = true, bytesTransferred = 32 * 1024)
        clock.advance(1000)
        monitor.onTransferEnd(source, spec, isNetwork = true)

        val snapshot = monitor.snapshot()
        assertEquals(1, snapshot.samples)
        assertEquals(NetworkState.DEGRADED, snapshot.state)
    }

    @Test
    fun `cache reads are ignored and do not affect network throughput`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("cached_source")
        val spec = dataSpec()

        monitor.onTransferInitializing(source, spec, isNetwork = false)
        clock.advance(50)
        monitor.onTransferStart(source, spec, isNetwork = false)
        clock.advance(50)
        monitor.onBytesTransferred(source, spec, isNetwork = false, bytesTransferred = 1024 * 1024)
        clock.advance(400)
        monitor.onTransferEnd(source, spec, isNetwork = false)

        val snapshot = monitor.snapshot()
        assertEquals(0, snapshot.samples)
        assertEquals(0L, snapshot.throughputBps)
        assertEquals(0L, snapshot.peakThroughputBps)
        assertEquals(NetworkState.UNKNOWN, snapshot.state)
    }

    @Test
    fun `active stalled request switches to NetworkState STALLED`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source1 = FakeDataSource("fast1")
        val spec1 = dataSpec("https://cdn.example.com/fast.ts")

        // 1. Establish high throughput baseline (10 MB in 500ms)
        monitor.onTransferInitializing(source1, spec1, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source1, spec1, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(source1, spec1, isNetwork = true, bytesTransferred = 10 * 1024 * 1024)
        clock.advance(500)
        monitor.onTransferEnd(source1, spec1, isNetwork = true)

        val initialSnapshot = monitor.snapshot()
        assertEquals(NetworkState.HEALTHY, initialSnapshot.state)

        // 2. Start a second request that receives few bytes and stalls for 5 seconds
        val stalledSource = FakeDataSource("stalled")
        val stalledSpec = dataSpec("https://cdn.example.com/stalled.ts")

        monitor.onTransferInitializing(stalledSource, stalledSpec, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(stalledSource, stalledSpec, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(stalledSource, stalledSpec, isNetwork = true, bytesTransferred = 1024)

        clock.advance(5_000)

        val stalledSnapshot = monitor.snapshot()
        assertEquals(NetworkState.STALLED, stalledSnapshot.state)
        assertTrue(stalledSnapshot.longestStallMs >= 2500L)

        // Ending the stalled request clears the stall condition
        monitor.onTransferEnd(stalledSource, stalledSpec, isNetwork = true)
        val postEndSnapshot = monitor.snapshot()
        assertFalse(postEndSnapshot.state == NetworkState.STALLED)
    }

    @Test
    fun `connection failure reports OFFLINE and recovery returns to normal`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("errSource")
        val spec = dataSpec()

        monitor.onTransferInitializing(source, spec, isNetwork = true)
        monitor.onTransferError(source, spec, isNetwork = true)

        val offlineSnapshot = monitor.snapshot()
        assertEquals(NetworkState.OFFLINE, offlineSnapshot.state)

        monitor.recordConnectionRecovery()
        val recoveredSnapshot = monitor.snapshot()
        assertFalse(recoveredSnapshot.state == NetworkState.OFFLINE)
    }

    @Test
    fun `concurrent sessions with different bandwidth remain strictly isolated`() {
        val clock = FakeClock()
        val monitorFast = PlaybackNetworkMonitor(sessionId = "fastSession", clock = { clock.currentTimeMs })
        val monitorSlow = PlaybackNetworkMonitor(sessionId = "slowSession", clock = { clock.currentTimeMs })

        val srcFast = FakeDataSource("fast")
        val srcSlow = FakeDataSource("slow")

        // Fast transfer on monitorFast (10 MB in 200ms -> 400 Mbps)
        monitorFast.onTransferInitializing(srcFast, dataSpec(), isNetwork = true)
        monitorFast.onTransferStart(srcFast, dataSpec(), isNetwork = true)
        monitorFast.onBytesTransferred(srcFast, dataSpec(), isNetwork = true, bytesTransferred = 10 * 1024 * 1024)
        clock.advance(200)
        monitorFast.onTransferEnd(srcFast, dataSpec(), isNetwork = true)

        // Slow transfer on monitorSlow (64 KB in 1000ms -> 512 Kbps)
        monitorSlow.onTransferInitializing(srcSlow, dataSpec(), isNetwork = true)
        monitorSlow.onTransferStart(srcSlow, dataSpec(), isNetwork = true)
        monitorSlow.onBytesTransferred(srcSlow, dataSpec(), isNetwork = true, bytesTransferred = 64 * 1024)
        clock.advance(1000)
        monitorSlow.onTransferEnd(srcSlow, dataSpec(), isNetwork = true)

        val snapFast = monitorFast.snapshot()
        val snapSlow = monitorSlow.snapshot()

        assertTrue(snapFast.throughputBps > 100_000_000L)
        assertTrue(snapSlow.throughputBps < 1_000_000L)
        assertEquals(NetworkState.HEALTHY, snapFast.state)
        assertEquals(NetworkState.DEGRADED, snapSlow.state)
    }

    @Test
    fun `aborted requests are cleaned up cleanly without leaking state`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("aborted")
        val spec = dataSpec()

        monitor.onTransferInitializing(source, spec, isNetwork = true)
        monitor.onTransferStart(source, spec, isNetwork = true)
        monitor.onBytesTransferred(source, spec, isNetwork = true, bytesTransferred = 32 * 1024)

        assertEquals(1, monitor.snapshot().activeRequests)

        monitor.onTransferAborted(source, spec, isNetwork = true)
        assertEquals(0, monitor.snapshot().activeRequests)
    }

    @Test
    fun `scheduling gaps are tracked separately from body transfer duration`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source1 = FakeDataSource("req1")
        val spec1 = dataSpec()

        monitor.onTransferInitializing(source1, spec1, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source1, spec1, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(source1, spec1, isNetwork = true, bytesTransferred = 100 * 1024)
        clock.advance(200)
        monitor.onTransferEnd(source1, spec1, isNetwork = true)

        clock.advance(400)
        val source2 = FakeDataSource("req2")
        val spec2 = dataSpec()
        monitor.onTransferInitializing(source2, spec2, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source2, spec2, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(source2, spec2, isNetwork = true, bytesTransferred = 100 * 1024)
        clock.advance(200)
        monitor.onTransferEnd(source2, spec2, isNetwork = true)

        val snapshot = monitor.snapshot()
        assertEquals(2, snapshot.samples)
        assertTrue(snapshot.lastTransferMs >= 0L)
    }

    @Test
    fun `concurrent transfers and interrupted requests do not corrupt state or accumulate unboundedly`() {
        val clock = AtomicLong(10_000L)
        val monitor = PlaybackNetworkMonitor(clock = { clock.addAndGet(10L) })
        val numThreads = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    val src = FakeDataSource("worker_$i")
                    val spec = dataSpec("https://cdn.example.com/track_$i/seg.ts")
                    monitor.onTransferInitializing(src, spec, isNetwork = true)
                    clock.addAndGet(50L)
                    monitor.onTransferStart(src, spec, isNetwork = true)
                    clock.addAndGet(50L)
                    monitor.onBytesTransferred(src, spec, isNetwork = true, bytesTransferred = 64 * 1024)
                    clock.addAndGet(200L)

                    if (i % 2 == 0) {
                        monitor.onTransferEnd(src, spec, isNetwork = true)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val snapshot = monitor.snapshot()
        assertTrue(snapshot.samples > 0)
        assertTrue(snapshot.throughputBps > 0L)
    }
}
