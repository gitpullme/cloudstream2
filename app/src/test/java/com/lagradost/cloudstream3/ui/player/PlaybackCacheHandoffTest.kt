package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Playback-side acceptance tests for the prefetch repair.
 *
 * These exercise the real Media3 SimpleCache / CacheDataSource / CacheWriter classes together with
 * the production routing factory, scheduler and audit. They answer the question the previous test
 * suite could not: did the *playback* DataSource read the prefetched bytes from the shared cache,
 * and how much did it fetch from upstream instead?
 */
class PlaybackCacheHandoffTest {

    private lateinit var tempDir: File
    private lateinit var cache: SimpleCache

    private val headers = mapOf("User-Agent" to "CloudStreamPlayer", "Authorization" to "Bearer t0ken")

    /** Upstream that dispatches on the URI path and can simulate a slow or stuck server. */
    private class MapUpstream(
        private val payloads: Map<String, ByteArray>,
        private val readChunkSize: Int = Int.MAX_VALUE,
        private val readDelayMs: Long = 0L,
        private val blockFirstReadMs: Long = 0L,
    ) : DataSource.Factory {
        val openCounts = ConcurrentHashMap<String, AtomicInteger>()
        val totalOpens = AtomicInteger(0)

        /** Signalled once the prefetch writer has actually entered its blocking read. */
        val firstReadStarted = java.util.concurrent.CountDownLatch(1)
        private val sourceCounter = AtomicInteger(0)

        fun opens(path: String): Int = openCounts[path]?.get() ?: 0

        override fun createDataSource(): DataSource = object : DataSource {
            private var stream: ByteArrayInputStream? = null
            private var currentSpec: DataSpec? = null
            private var blockedAlready = false
            private val sourceIndex = sourceCounter.getAndIncrement()
            private val listeners = mutableListOf<TransferListener>()

            override fun addTransferListener(transferListener: TransferListener) {
                listeners += transferListener
            }

            override fun open(dataSpec: DataSpec): Long {
                currentSpec = dataSpec
                totalOpens.incrementAndGet()
                openCounts.getOrPut(dataSpec.uri.path.orEmpty()) { AtomicInteger(0) }.incrementAndGet()
                val payload = payloads[dataSpec.uri.path.orEmpty()]
                    ?: throw IOException("no fixture for ${dataSpec.uri.path}")
                val offset = dataSpec.position.toInt()
                val available = (payload.size - offset).coerceAtLeast(0)
                val length = if (dataSpec.length >= 0L) {
                    minOf(dataSpec.length.toInt(), available)
                } else {
                    available
                }
                if (length <= 0) throw IOException("range outside fixture")
                stream = ByteArrayInputStream(payload, offset, length)
                return length.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Signal as soon as the read is entered, before any simulated blocking, so callers
                // can prove the writer really is inside a read.
                firstReadStarted.countDown()
                if (blockFirstReadMs > 0L && !blockedAlready && sourceIndex == 0) {
                    blockedAlready = true
                    // Simulates a writer that cannot observe cancellation until its blocking read
                    // returns - longer than the handoff deadline.
                    Thread.sleep(blockFirstReadMs)
                }
                if (readDelayMs > 0L) Thread.sleep(readDelayMs)
                val read = stream?.read(buffer, offset, minOf(length, readChunkSize)) ?: -1
                if (read > 0) {
                    for (listener in listeners) {
                        listener.onBytesTransferred(this, currentSpec!!, false, read)
                    }
                }
                return read
            }

            override fun getUri(): Uri? = currentSpec?.uri

            override fun close() {
                stream?.close()
                stream = null
                currentSpec = null
            }
        }
    }

    private fun keyFactory() = HeaderAwareCacheKeyFactory(headers)

    private fun prefetchFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().apply {
            setCache(this@PlaybackCacheHandoffTest.cache)
            setUpstreamDataSourceFactory(upstream)
            setCacheKeyFactory(keyFactory())
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

    private fun specFor(uri: Uri, length: Long, headers: Map<String, String> = this.headers): DataSpec =
        DataSpec.Builder()
            .setUri(uri)
            .setPosition(0L)
            .setLength(length)
            .setHttpRequestHeaders(headers)
            .build()

    private fun newSession(uri: Uri): PlaybackSessionState {
        val session = PlaybackSessionState(sourceUri = uri.toString(), prefetchModeEnabled = true)
        session.cacheAudit = PlaybackCacheAudit(session.sessionId)
        return session
    }

    private fun newCoordinator(
        session: PlaybackSessionState,
        prefetchFactory: CacheDataSource.Factory,
        upstream: DataSource.Factory,
    ): PlaybackPrefetchCoordinator = PlaybackPrefetchCoordinator(
        context = android.app.Application(),
        session = session,
        playerProvider = { null },
        getCache = { cache },
        getCacheDataSourceFactory = { prefetchFactory },
        upstreamFactory = upstream,
        defaultHeaders = headers,
        cacheKeyFactory = { spec -> keyFactory().buildCacheKey(spec) },
    ).also { session.prefetchCoordinator = it }

    private fun playbackFactory(
        upstream: DataSource.Factory,
        session: PlaybackSessionState,
        audit: PlaybackCacheAudit,
    ): CacheRoutingDataSourceFactory {
        val auditedUpstream = PlaybackCacheAudit.UpstreamFactory(upstream, audit)
        val playbackCacheFactory = CacheDataSource.Factory().apply {
            setCache(this@PlaybackCacheHandoffTest.cache)
            setUpstreamDataSourceFactory(auditedUpstream)
            setCacheKeyFactory(keyFactory())
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
            setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    audit.onPlaybackCachedBytes(cachedBytesRead)
                }

                override fun onCacheIgnored(reason: Int) {
                    audit.onPlaybackCacheIgnored(reason)
                }
            })
        }
        return CacheRoutingDataSourceFactory(
            cacheFactory = playbackCacheFactory,
            upstreamFactory = auditedUpstream,
            session = session,
            audit = audit,
            cacheKeyFactory = { spec -> keyFactory().buildCacheKey(spec) },
            shouldBypass = { false },
        )
    }

    private fun readAll(dataSource: DataSource, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var total = 0
        while (total < expected) {
            val read = dataSource.read(out, total, expected - total)
            if (read < 0) break
            total += read
        }
        return if (total == expected) out else out.copyOf(total)
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("cs3_handoff_test", "").apply {
            delete()
            mkdirs()
        }
        cache = SimpleCache(tempDir, NoOpCacheEvictor())
        CacheCoverageVerifier.clear()
    }

    @After
    fun tearDown() {
        CacheCoverageVerifier.clear()
        runCatching { cache.release() }
        tempDir.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------------
    // G: the actual playback DataSource must consume prefetched bytes from SimpleCache
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `prefetched resource is served by the playback cache with zero upstream bytes`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/hls/video_001.ts")
        val payload = ByteArray(96 * 1024) { (it % 251).toByte() }
        val upstream = MapUpstream(mapOf(uri.path!! to payload))
        val session = newSession(uri)
        val audit = session.cacheAudit!!
        val coordinator = newCoordinator(session, prefetchFactory(upstream), upstream)
        val spec = specFor(uri, payload.size.toLong())
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, headers)

        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                durationUs = 6_000_000L,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            assertTrue(
                "prefetch worker must complete the segment",
                waitUntil { request.status == PrefetchRequest.Status.COMPLETED },
            )
            assertEquals("prefetch must fetch the segment exactly once", 1, upstream.opens(uri.path!!))
            assertTrue(
                "exact completion evidence must be recorded",
                CacheCoverageVerifier.isFullyCached(cache, identity.cacheKey, 0L, payload.size.toLong()),
            )

            // ---- Actual playback path ----
            val router = playbackFactory(upstream, session, audit)
            val playback = router.createDataSource()
            val opened = playback.open(spec)
            assertEquals(payload.size.toLong(), opened)
            val bytes = readAll(playback, payload.size)
            assertEquals(-1, playback.read(ByteArray(8), 0, 8))
            playback.close()

            assertTrue("playback must have read bytes", bytes.isNotEmpty())
            assertTrue(
                "playback must read the prefetched bytes from SimpleCache, got ${audit.playbackCacheBytesRead.get()}",
                audit.playbackCacheBytesRead.get() >= payload.size.toLong(),
            )
            assertEquals(
                "playback must not fetch prefetched bytes from upstream",
                0L,
                audit.playbackUpstreamBytesRead.get(),
            )
            assertEquals(
                "no second HTTP request may be issued for a fully prefetched resource",
                1,
                upstream.opens(uri.path!!),
            )
            assertEquals(1L, audit.playbackCacheHitRequestCount.get())
            assertEquals(0L, audit.playbackCacheMissRequestCount.get())
            assertEquals(0L, audit.playbackCacheLockBypassCount.get())
            assertEquals(0L, audit.playbackCacheIgnoredCount.get())
            assertTrue("prefetch bytes must be attributed to prefetch only", audit.prefetchBytesWritten.get() > 0L)
        } finally {
            coordinator.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A/2: handoff must be explicit and must actually release the writer span lock
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `handoff cancels the running writer and releases its cache span lock`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/hls/video_slow.ts")
        val payload = ByteArray(512 * 1024) { (it % 253).toByte() }
        val upstream = MapUpstream(
            payloads = mapOf(uri.path!! to payload),
            readChunkSize = 4 * 1024,
            readDelayMs = 4L,
        )
        val session = newSession(uri)
        val coordinator = newCoordinator(session, prefetchFactory(upstream), upstream)
        val spec = specFor(uri, payload.size.toLong())
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, headers)

        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            assertTrue(
                "writer must be actively writing",
                waitUntil { request.status == PrefetchRequest.Status.RUNNING },
            )
            // Make sure the writer is inside a read, not cancelled before it started.
            assertTrue(upstream.firstReadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(coordinator.scheduler.isActivelyWritingKey(identity.cacheKey))

            val handoff = coordinator.scheduler.onMedia3RequestStarting(identity)
            assertTrue(
                "handoff must prove the writer released its lock, got $handoff",
                handoff is CacheHandoffResult.WriterCancelledAndUnlocked,
            )
            assertFalse(
                "no writer may still hold the lock after a successful handoff",
                coordinator.scheduler.isActivelyWritingKey(identity.cacheKey),
            )
            assertEquals(PrefetchRequest.Status.CANCELLED, request.status)
            assertTrue(coordinator.scheduler.isKeyInPlaybackUse(identity.cacheKey))

            coordinator.scheduler.onMedia3RequestFinished(identity)
            assertFalse(coordinator.scheduler.isKeyInPlaybackUse(identity.cacheKey))
        } finally {
            coordinator.release()
        }
    }

    @Test
    fun `overlapping but non-identical ranges are coordinated, disjoint ranges are not`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/mp4/movie.mp4")
        val payload = ByteArray(64 * 1024) { 7 }
        val upstream = MapUpstream(mapOf(uri.path!! to payload))
        val session = newSession(uri)
        val coordinator = newCoordinator(session, prefetchFactory(upstream), upstream)
        val key = keyFactory().buildCacheKey(specFor(uri, -1L))

        try {
            val prefetchSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0L)
                .setLength(4096L)
                .setHttpRequestHeaders(headers)
                .build()
            val prefetchIdentity = PrefetchRequestIdentity.fromDataSpec(prefetchSpec, keyFactory()::buildCacheKey, headers)
            assertEquals(key, prefetchIdentity.cacheKey)
            val request = PrefetchRequest(
                identity = prefetchIdentity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = prefetchSpec,
            )
            coordinator.scheduler.enqueue(request)

            // A narrower, non-identical range that overlaps [0, 4096)
            val overlapping = PrefetchRequestIdentity(
                uri = uri,
                cacheKey = prefetchIdentity.cacheKey,
                position = 1024L,
                length = 1024L,
                headers = headers,
            )
            val handoff = coordinator.scheduler.onMedia3RequestStarting(overlapping)
            assertEquals(
                CacheHandoffResult.WriterCancelledAndUnlocked(cancelledQueuedRequests = 1, releasedRunningRequests = 0),
                handoff,
            )
            assertEquals(PrefetchRequest.Status.CANCELLED, request.status)
            coordinator.scheduler.onMedia3RequestFinished(overlapping)

            // A disjoint range must not disturb anything
            val disjoint = PrefetchRequestIdentity(
                uri = uri,
                cacheKey = prefetchIdentity.cacheKey,
                position = 8192L,
                length = 1024L,
                headers = headers,
            )
            assertEquals(CacheHandoffResult.NoMatchingWriter, coordinator.scheduler.onMedia3RequestStarting(disjoint))
            coordinator.scheduler.onMedia3RequestFinished(disjoint)
        } finally {
            coordinator.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A/3: a writer that cannot release its lock must produce a measured upstream fallback
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `writer that exceeds the handoff deadline forces a recorded upstream fallback`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/hls/video_stuck.ts")
        val payload = ByteArray(256 * 1024) { 11 }
        val upstream = MapUpstream(
            payloads = mapOf(uri.path!! to payload),
            readChunkSize = 8 * 1024,
            // Long enough that both the direct handoff and the routing factory's own handoff
            // observe a genuinely held cache span lock.
            blockFirstReadMs = 2 * PrefetchRequestScheduler.HANDOFF_TIMEOUT_MS + 2_000L,
        )
        val session = newSession(uri)
        val audit = session.cacheAudit!!
        val coordinator = newCoordinator(session, prefetchFactory(upstream), upstream)
        val spec = specFor(uri, payload.size.toLong())
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, headers)

        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            assertTrue(waitUntil { request.status == PrefetchRequest.Status.RUNNING })
            // The writer must already be inside its blocking read when the handoff deadline starts.
            assertTrue(upstream.firstReadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))

            val handoff = coordinator.scheduler.onMedia3RequestStarting(identity)
            assertTrue("handoff must time out, got $handoff", handoff is CacheHandoffResult.LockStillHeld)

            val router = playbackFactory(upstream, session, audit)
            val playback = router.createDataSource()
            playback.open(spec)
            val opened = playback.read(ByteArray(4096), 0, 4096)
            playback.close()

            assertTrue("explicit fallback must still deliver bytes", opened > 0)
            assertEquals(
                "locked-cache fallback must be recorded, not silent",
                1L,
                audit.playbackCacheLockBypassCount.get(),
            )
            assertEquals(1L, audit.playbackUpstreamFallbackWhileLockedCount.get())
            assertEquals(0L, audit.playbackCacheHitRequestCount.get())
            assertTrue(audit.playbackUpstreamBytesRead.get() > 0L)
            assertTrue(handoff is CacheHandoffResult.LockStillHeld)
        } finally {
            // Let the stuck writer observe cancellation before tearing the cache down.
            waitUntil(15_000L) { !coordinator.scheduler.isActivelyWritingKey(identity.cacheKey) }
            coordinator.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Baseline measurement: reproduces the pre-repair Media3 configuration to quantify the silent
    // cache-lock bypass that made prefetched bytes useless during playback.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `legacy non-blocking cache configuration silently bypasses a locked span to upstream`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/hls/video_legacy.ts")
        val payload = ByteArray(512 * 1024) { 13 }
        val upstream = MapUpstream(
            payloads = mapOf(uri.path!! to payload),
            readChunkSize = 8 * 1024,
            blockFirstReadMs = 5_000L,
        )
        val keyFactory = keyFactory()
        // Exactly the old factory: FLAG_IGNORE_CACHE_ON_ERROR only, i.e. Media3's non-blocking
        // span acquisition. No handoff, no BLOCK_ON_CACHE.
        val legacyCachedBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val legacyCacheFactory = CacheDataSource.Factory().apply {
            setCache(this@PlaybackCacheHandoffTest.cache)
            setUpstreamDataSourceFactory(upstream)
            setCacheKeyFactory(keyFactory)
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    legacyCachedBytes.addAndGet(cachedBytesRead)
                }

                override fun onCacheIgnored(reason: Int) = Unit
            })
        }
        val fullSpec = specFor(uri, payload.size.toLong())
        val identity = PrefetchRequestIdentity.fromDataSpec(fullSpec, keyFactory::buildCacheKey, headers)

        val writerSource = legacyCacheFactory.createDataSource()
        val writer = androidx.media3.datasource.cache.CacheWriter(writerSource, fullSpec, ByteArray(32 * 1024), null)
        val writerThread = Thread { runCatching { writer.cache() } }.also {
            it.isDaemon = true
            it.start()
        }
        try {
            assertTrue(upstream.firstReadStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("the resource must not be complete while the writer is mid-span", cache.isCached(identity.cacheKey, 0L, payload.size.toLong()))

            val playbackSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0L)
                .setLength(4096L)
                .setHttpRequestHeaders(headers)
                .build()
            val playbackSource = legacyCacheFactory.createDataSource()
            playbackSource.open(playbackSpec)
            val read = playbackSource.read(ByteArray(4096), 0, 4096)
            playbackSource.close()

            assertTrue("legacy playback still obtains bytes", read > 0)
            assertEquals(
                "legacy configuration issues a SECOND upstream request for bytes a writer already holds",
                2,
                upstream.opens(uri.path!!),
            )
            assertEquals(
                "legacy playback gets ZERO cache bytes for the locked range (all bytes came from upstream)",
                0L,
                legacyCachedBytes.get(),
            )
        } finally {
            writer.cancel()
            writerThread.join(8_000L)
            runCatching { writerSource.close() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // C/1: EOF must not leak from one open to the next on the same RoutedDataSource
    // ---------------------------------------------------------------------------------------------

    private fun twoRequestEofScenario(firstFullRead: Boolean) = runBlocking {
        val fullUri = Uri.parse("https://cdn.example.com/mp4/full.mp4")
        val partialUri = Uri.parse("https://cdn.example.com/mp4/partial.mp4")
        val payload = ByteArray(4096) { 3 }
        val upstream = MapUpstream(
            mapOf(
                fullUri.path!! to payload,
                partialUri.path!! to payload,
            )
        )
        val session = newSession(fullUri)
        val audit = session.cacheAudit!!
        val router = playbackFactory(upstream, session, audit)
        val dataSource = router.createDataSource()

        val fullSpec = specFor(fullUri, payload.size.toLong())
        val partialSpec = specFor(partialUri, payload.size.toLong())
        val fullKey = keyFactory().buildCacheKey(fullSpec)
        val partialKey = keyFactory().buildCacheKey(partialSpec)

        fun consumeToEof(spec: DataSpec) {
            dataSource.open(spec)
            val buffer = ByteArray(1024)
            while (dataSource.read(buffer, 0, buffer.size) >= 0) {
                // drain
            }
            dataSource.close()
        }

        fun consumePartially(spec: DataSpec) {
            dataSource.open(spec)
            val buffer = ByteArray(16)
            assertTrue(dataSource.read(buffer, 0, buffer.size) > 0)
            dataSource.close()
        }

        try {
            if (firstFullRead) {
                consumeToEof(fullSpec)
                consumePartially(partialSpec)
            } else {
                consumePartially(partialSpec)
                consumeToEof(fullSpec)
            }

            assertTrue(
                "request A reached EOF and must be recorded complete",
                CacheCoverageVerifier.isFullyCached(cache, fullKey, 0L, payload.size.toLong()),
            )
            assertFalse(
                "request B was partially consumed and must NOT be marked complete",
                CacheCoverageVerifier.isFullyCached(cache, partialKey, 0L, -1L),
            )
            assertFalse(
                "no completion evidence may be invented for the partial request",
                CacheCoverageVerifier.completionEvidence(partialKey).any { it.reachedEof && it.endByteExclusive > 0 },
            )
        } finally {
            session.prefetchCoordinator?.release()
        }
    }

    @Test
    fun `RoutedDataSource does not leak EOF from a previous open (full then partial)`() {
        twoRequestEofScenario(firstFullRead = true)
    }

    @Test
    fun `RoutedDataSource does not leak EOF from a previous open (partial then full)`() {
        twoRequestEofScenario(firstFullRead = false)
    }

    // ---------------------------------------------------------------------------------------------
    // B: prefetch activity must never be reported as a playback cache hit
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `prefetch cache reads are not counted as playback cache hits`() = runBlocking {
        val uri = Uri.parse("https://cdn.example.com/hls/video_metrics.ts")
        val payload = ByteArray(64 * 1024) { 5 }
        val upstream = MapUpstream(mapOf(uri.path!! to payload))
        val session = newSession(uri)
        val audit = session.cacheAudit!!
        val coordinator = newCoordinator(session, prefetchFactory(upstream), upstream)
        val spec = specFor(uri, payload.size.toLong())
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, headers)

        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            assertTrue(waitUntil { request.status == PrefetchRequest.Status.COMPLETED })

            assertEquals("prefetch must not produce playback cache reads", 0L, audit.playbackCacheBytesRead.get())
            assertEquals("prefetch must not produce playback requests", 0L, audit.playbackRequestCount.get())
            assertTrue(audit.prefetchBytesWritten.get() > 0L)
            assertTrue(audit.prefetchRequestCount.get() >= 1L)
        } finally {
            coordinator.release()
        }
    }
}
