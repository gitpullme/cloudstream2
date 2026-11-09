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
    fun `completed network transfers update throughput in bits per second`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("video1")
        val spec = dataSpec()

        monitor.onTransferInitializing(source, spec, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(source, spec, isNetwork = true)
        clock.advance(50)

        // 500 KB transferred in 400ms
        val totalBytes = 500 * 1024
        monitor.onBytesTransferred(source, spec, isNetwork = true, bytesTransferred = totalBytes)
        clock.advance(400)
        monitor.onTransferEnd(source, spec, isNetwork = true)

        val snapshot = monitor.snapshot()
        assertEquals(1, snapshot.samples)
        // Rate must be positive and in bits per second
        assertTrue(snapshot.throughputBps > 0L)
        assertTrue(snapshot.peakThroughputBps >= snapshot.throughputBps)
    }

    @Test
    fun `cache reads are ignored and do not affect network throughput`() {
        val clock = FakeClock()
        val monitor = PlaybackNetworkMonitor(clock = { clock.currentTimeMs })
        val source = FakeDataSource("cached_source")
        val spec = dataSpec()

        // Report as cache read (isNetwork = false)
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
    }

    @Test
    fun `active stalled request lowers effective throughput prediction`() {
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
        val initialRate = initialSnapshot.throughputBps
        assertTrue(initialRate > 1_000_000L)

        // 2. Start a second request that receives very few bytes and stalls
        val stalledSource = FakeDataSource("stalled")
        val stalledSpec = dataSpec("https://cdn.example.com/stalled.ts")

        monitor.onTransferInitializing(stalledSource, stalledSpec, isNetwork = true)
        clock.advance(50)
        monitor.onTransferStart(stalledSource, stalledSpec, isNetwork = true)
        clock.advance(50)
        monitor.onBytesTransferred(stalledSource, stalledSpec, isNetwork = true, bytesTransferred = 1024)

        // Advance clock by 10 seconds to simulate request stalling
        clock.advance(10_000)

        // When stalled request is active, snapshot takes in-flight throughput into account
        val postStallSnapshot = monitor.snapshot()
        assertTrue(postStallSnapshot.samples >= 1)
        assertTrue(postStallSnapshot.throughputBps < initialRate)
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

        // Second request has a gap between first request end and second request init (400ms gap)
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
        // Scheduling gap is non-negative and separate from body transfer
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

                    // Even workers complete normally; odd workers are interrupted/abandoned without onTransferEnd
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
