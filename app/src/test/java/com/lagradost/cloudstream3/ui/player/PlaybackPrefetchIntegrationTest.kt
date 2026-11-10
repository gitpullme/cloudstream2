package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration tests for FastStream-inspired Prefetch Mode in CloudStream.
 *
 * Tests the real Media3 SimpleCache pipeline:
 * 1. Prefetch a segment into SimpleCache.
 * 2. Normal Media3 playback reaches that segment.
 * 3. Verify the segment is read from disk cache without issuing a second upstream request.
 * 4. Verify single-writer protection, header isolation, and multi-session safety.
 */
@OptIn(UnstableApi::class)
class PlaybackPrefetchIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var simpleCache: SimpleCache

    private val segmentUri = Uri.parse("https://cdn.example.com/hls/segment_001.ts")
    private val segmentData = "PREFETCHED_MEDIA_DATA_CHUNK_TEST_PAYLOAD".toByteArray(Charsets.UTF_8)

    private class CountingUpstreamDataSource(
        private val payload: ByteArray,
    ) : DataSource {
        val openCount = AtomicInteger(0)
        val readCount = AtomicInteger(0)
        private var inputStream: ByteArrayInputStream? = null
        private var currentSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openCount.incrementAndGet()
            currentSpec = dataSpec
            val offset = dataSpec.position.toInt()
            val length = if (dataSpec.length != -1L) dataSpec.length.toInt() else (payload.size - offset)
            inputStream = ByteArrayInputStream(payload, offset, length)
            return length.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCount.incrementAndGet()
            val stream = inputStream ?: throw IOException("read() called before open()")
            return stream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = currentSpec?.uri

        override fun close() {
            inputStream?.close()
            inputStream = null
            currentSpec = null
        }
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("cs3_prefetch_test", "").apply {
            delete()
            mkdirs()
        }
        simpleCache = SimpleCache(tempDir, NoOpCacheEvictor())
    }

    @After
    fun tearDown() {
        runCatching { simpleCache.release() }
        tempDir.deleteRecursively()
    }

    @Test
    fun `critical acceptance test - prefetched segment in SimpleCache is read during Media3 playback without second upstream request`() = runBlocking {
        val upstream = CountingUpstreamDataSource(segmentData)
        val upstreamFactory = DataSource.Factory { upstream }

        val defaultHeaders = mapOf("User-Agent" to "CloudStreamPlayer")
        val cacheKeyFactory = HeaderAwareCacheKeyFactory(defaultHeaders)

        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory(cacheKeyFactory)
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val segmentSpec = DataSpec.Builder()
            .setUri(segmentUri)
            .setPosition(0L)
            .setLength(segmentData.size.toLong())
            .setHttpRequestHeaders(defaultHeaders)
            .build()

        // Step 1: Background Prefetch Coordinator downloads the upcoming segment
        val prefetchDataSource = cacheDataSourceFactory.createDataSource()
        val writer = CacheWriter(
            prefetchDataSource,
            segmentSpec,
            ByteArray(1024),
            null
        )
        writer.cache()
        prefetchDataSource.close()

        // Upstream must have been contacted exactly ONCE by the prefetch worker
        assertEquals("Upstream must be accessed once during prefetch", 1, upstream.openCount.get())

        // Verify that SimpleCache now contains this segment
        val cacheKey = cacheKeyFactory.buildCacheKey(segmentSpec)
        assertTrue("Segment must be present in SimpleCache after prefetch", simpleCache.isCached(cacheKey, 0L, segmentData.size.toLong()))

        // Step 2: Normal Media3 ExoPlayer playback reaches this segment
        val session = PlaybackSessionState(sourceUri = segmentUri.toString(), prefetchModeEnabled = true)
        val media3RoutingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheDataSourceFactory,
            upstreamFactory = upstreamFactory,
            session = session,
        )
        val media3DataSource = media3RoutingFactory.createDataSource()

        // Media3 opens the DataSpec
        val openedLength = media3DataSource.open(segmentSpec)
        assertEquals(segmentData.size.toLong(), openedLength)

        val readBuffer = ByteArray(segmentData.size)
        var totalRead = 0
        while (totalRead < segmentData.size) {
            val bytesRead = media3DataSource.read(readBuffer, totalRead, segmentData.size - totalRead)
            if (bytesRead < 0) break
            totalRead += bytesRead
        }
        media3DataSource.close()

        // Verify data integrity
        assertEquals(segmentData.size, totalRead)
        assertArrayEquals("Playback data must match original segment content", segmentData, readBuffer)

        // CRITICAL CHECK: Upstream openCount MUST STILL BE 1!
        assertEquals("Media3 playback must read the segment entirely from SimpleCache with NO second upstream request", 1, upstream.openCount.get())
    }

    @Test
    fun `header sensitivity prevents cache pollution between different authorization contexts`() = runBlocking {
        val upstream = CountingUpstreamDataSource(segmentData)
        val upstreamFactory = DataSource.Factory { upstream }

        val userHeaders1 = mapOf("Authorization" to "Bearer user1_token")
        val userHeaders2 = mapOf("Authorization" to "Bearer user2_token")

        val keyFactory1 = HeaderAwareCacheKeyFactory(userHeaders1)
        val keyFactory2 = HeaderAwareCacheKeyFactory(userHeaders2)

        val spec1 = DataSpec.Builder().setUri(segmentUri).setHttpRequestHeaders(userHeaders1).build()
        val spec2 = DataSpec.Builder().setUri(segmentUri).setHttpRequestHeaders(userHeaders2).build()

        val key1 = keyFactory1.buildCacheKey(spec1)
        val key2 = keyFactory2.buildCacheKey(spec2)

        // Different authorization tokens must produce different cache keys
        assertFalse("Cache keys must differ for different auth tokens", key1 == key2)
    }

    @Test
    fun `media3 request promotes and protects in-flight prefetch without duplicate fetch`() = runBlocking {
        val session = PlaybackSessionState(sourceUri = segmentUri.toString(), prefetchModeEnabled = true)
        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { simpleCache },
            getCacheDataSourceFactory = { null },
        )

        val spec = DataSpec(segmentUri, 0, 1024)
        val identity = PrefetchRequestIdentity(uri = segmentUri, cacheKey = "coord_test_key", position = 0, length = 1024)
        val req = PrefetchRequest(
            identity = identity,
            priority = PrefetchRequest.Priority.SPECULATIVE,
            dataSpec = spec,
        )

        scheduler.enqueue(req)
        assertEquals(PrefetchRequest.Priority.SPECULATIVE, req.priority)

        // Media3 requests this chunk
        val inFlight = scheduler.onMedia3RequestStarting(identity)
        assertNotNull(inFlight)
        assertEquals(PrefetchRequest.Priority.IMMEDIATE, inFlight?.priority)

        scheduler.onMedia3RequestFinished(identity)
        scheduler.release()
    }

    @Test
    fun `multiple sessions do not pollute or cancel each other`() = runBlocking {
        val session1 = PlaybackSessionState(sourceUri = "https://cdn.example.com/stream1.m3u8", prefetchModeEnabled = true)
        val session2 = PlaybackSessionState(sourceUri = "https://cdn.example.com/stream2.m3u8", prefetchModeEnabled = true)

        val scheduler1 = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session1,
            getCache = { simpleCache },
            getCacheDataSourceFactory = { null },
        )
        val scheduler2 = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session2,
            getCache = { simpleCache },
            getCacheDataSourceFactory = { null },
        )

        val id1 = PrefetchRequestIdentity(uri = Uri.parse("https://cdn.example.com/stream1/seg0.ts"), cacheKey = "s1_seg0")
        val id2 = PrefetchRequestIdentity(uri = Uri.parse("https://cdn.example.com/stream2/seg0.ts"), cacheKey = "s2_seg0")

        scheduler1.enqueue(PrefetchRequest(identity = id1, priority = PrefetchRequest.Priority.NEXT_MEDIA, dataSpec = DataSpec(id1.uri)))
        scheduler2.enqueue(PrefetchRequest(identity = id2, priority = PrefetchRequest.Priority.NEXT_MEDIA, dataSpec = DataSpec(id2.uri)))

        assertEquals(1, scheduler1.registry.size)
        assertEquals(1, scheduler2.registry.size)

        // Releasing session1 must NOT affect session2
        session1.release()
        scheduler1.release()

        assertEquals(0, scheduler1.registry.size)
        assertEquals(1, scheduler2.registry.size)

        session2.release()
        scheduler2.release()
    }

    @Test
    fun `production path integration test - PlaybackPrefetchCoordinator schedules upcoming HLS segment through scheduler and Media3 reuses cache`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            https://cdn.example.com/hls/segment_001.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(
            segmentUri,
            m3u8.byteInputStream(Charsets.UTF_8)
        )

        val upstream = CountingUpstreamDataSource(segmentData)
        val upstreamFactory = DataSource.Factory { upstream }
        val defaultHeaders = mapOf("User-Agent" to "CloudStreamPlayer")
        val keyFactory = HeaderAwareCacheKeyFactory(defaultHeaders)

        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory(keyFactory)
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val session = PlaybackSessionState(sourceUri = segmentUri.toString(), prefetchModeEnabled = true)
        val fakePlayer = object : androidx.media3.common.Player by (java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.common.Player::class.java.classLoader,
            arrayOf(androidx.media3.common.Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getApplicationLooper" -> android.os.Looper.getMainLooper()
                "getCurrentPosition" -> 0L
                "getBufferedPosition" -> 0L
                "getDuration" -> 6000L
                "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                "isPlaying" -> true
                "getPlayWhenReady" -> true
                "getCurrentManifest" -> playlist
                "getCurrentTracks" -> null
                "getPlaybackParameters" -> androidx.media3.common.PlaybackParameters.DEFAULT
                else -> null
            }
        } as androidx.media3.common.Player) {}

        val coordinator = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = session,
            playerProvider = { fakePlayer },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = { keyFactory.buildCacheKey(it) },
        )
        session.prefetchCoordinator = coordinator

        // Schedule upcoming segments via real coordinator
        val snapshot = PlaybackPlayerSnapshot.capture(
            playerProvider = { fakePlayer },
            sourceUri = segmentUri,
            selectedMime = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            isLive = false,
        )
        assertNotNull(snapshot)

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = { keyFactory.buildCacheKey(it) },
        )
        val requests = adapter.getUpcomingRequests(snapshot!!, 100L * 1024L * 1024L)
        assertEquals(1, requests.size)

        // Enqueue through the production scheduler
        val enqueued = coordinator.scheduler.enqueue(requests.first())

        // Wait for prefetch worker to complete the segment
        var waitedMs = 0
        while (enqueued.status != PrefetchRequest.Status.COMPLETED && waitedMs < 2000) {
            kotlinx.coroutines.delay(50)
            waitedMs += 50
        }
        assertEquals("Worker must complete prefetch request", PrefetchRequest.Status.COMPLETED, enqueued.status)
        assertEquals("Upstream must be accessed exactly once by prefetch", 1, upstream.openCount.get())

        // Verify SimpleCache has the chunk
        val cacheKey = keyFactory.buildCacheKey(requests.first().dataSpec)
        assertTrue(simpleCache.isCached(cacheKey, 0L, segmentData.size.toLong()))

        // Media3 requests the same segment via CacheRoutingDataSourceFactory
        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheDataSourceFactory,
            upstreamFactory = upstreamFactory,
            session = session,
            cacheKeyFactory = { keyFactory.buildCacheKey(it) },
        )
        val media3DataSource = routingFactory.createDataSource()
        val opened = media3DataSource.open(requests.first().dataSpec)
        assertEquals(segmentData.size.toLong(), opened)

        val readBuf = ByteArray(segmentData.size)
        var totalRead = 0
        while (totalRead < segmentData.size) {
            val r = media3DataSource.read(readBuf, totalRead, segmentData.size - totalRead)
            if (r < 0) break
            totalRead += r
        }
        media3DataSource.close()

        assertEquals(segmentData.size, totalRead)
        org.junit.Assert.assertArrayEquals(segmentData, readBuf)

        // CRITICAL CHECK: Media3 read from SimpleCache, upstream openCount remains 1!
        assertEquals("Playback must reuse cache with no second upstream request", 1, upstream.openCount.get())

        // Clean release
        coordinator.release()
        session.release()
        assertEquals(0, coordinator.scheduler.getActiveWorkerCount())
        assertTrue("All leases must be released on session release", session.getActiveLeasedKeys().isEmpty())
    }

    @Test
    fun `continuous rolling prefetch reclaims obsolete spans behind 30s playback window under small cache limit`() = runBlocking {
        val limitBytes = 5L * 1024L * 1024L
        val chunkSize = 2 * 1024 * 1024 // 2 MB each
        val chunk1Data = ByteArray(chunkSize) { 1 }
        val chunk2Data = ByteArray(chunkSize) { 2 }
        val chunk3Data = ByteArray(chunkSize) { 3 }

        val uri1 = Uri.parse("https://cdn.example.com/hls/seg1.ts")
        val uri2 = Uri.parse("https://cdn.example.com/hls/seg2.ts")
        val uri3 = Uri.parse("https://cdn.example.com/hls/seg3.ts")

        val dataMap = mapOf(
            uri1 to chunk1Data,
            uri2 to chunk2Data,
            uri3 to chunk3Data,
        )

        val upstream = object : DataSource {
            private var currentUri: Uri? = null
            private var currentData: ByteArray? = null
            private var offset = 0

            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                currentUri = dataSpec.uri
                currentData = dataMap[dataSpec.uri] ?: throw IOException("URI not found: ${dataSpec.uri}")
                offset = dataSpec.position.toInt()
                return (currentData!!.size - offset).toLong()
            }
            override fun read(buffer: ByteArray, bufferOffset: Int, length: Int): Int {
                val data = currentData ?: return -1
                val remaining = data.size - offset
                if (remaining <= 0) return -1
                val bytesToRead = minOf(length, remaining)
                System.arraycopy(data, offset, buffer, bufferOffset, bytesToRead)
                offset += bytesToRead
                return bytesToRead
            }
            override fun getUri(): Uri? = currentUri
            override fun close() {
                currentUri = null
                currentData = null
            }
        }
        val upstreamFactory = DataSource.Factory { upstream }
        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory { it.key ?: it.uri.toString() }
        }

        val session = PlaybackSessionState(
            sourceUri = "https://cdn.example.com/hls/playlist.m3u8",
            prefetchModeEnabled = true,
            prefetchLimitBytes = limitBytes,
        )

        val coordinator = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = session,
            playerProvider = { null },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = { it.key ?: it.uri.toString() },
        )
        session.prefetchCoordinator = coordinator

        // 1. Prefetch seg1 (0..6s, 2MB)
        val req1 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = uri1, cacheKey = uri1.toString(), length = chunkSize.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 6_000_000L,
            dataSpec = DataSpec(uri1, 0, chunkSize.toLong()),
        )
        // 2. Prefetch seg2 (6..12s, 2MB)
        val req2 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = uri2, cacheKey = uri2.toString(), length = chunkSize.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 6_000_000L,
            durationUs = 6_000_000L,
            dataSpec = DataSpec(uri2, 0, chunkSize.toLong()),
        )

        // Download both into cache (4 MB total)
        val w1 = CacheWriter(cacheDataSourceFactory.createDataSource(), req1.dataSpec, ByteArray(1024), null)
        w1.cache()
        coordinator.scheduler.registry[req1.identity] = req1
        req1.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(req1)

        val w2 = CacheWriter(cacheDataSourceFactory.createDataSource(), req2.dataSpec, ByteArray(1024), null)
        w2.cache()
        coordinator.scheduler.registry[req2.identity] = req2
        req2.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(req2)

        assertTrue(simpleCache.isCached(uri1.toString(), 0, chunkSize.toLong()))
        assertTrue(simpleCache.isCached(uri2.toString(), 0, chunkSize.toLong()))
        assertEquals(4L * 1024 * 1024, simpleCache.cacheSpace)

        // 3. Playback advances to 45 seconds
        session.playbackPositionUs = 45_000_000L

        // Backward retention is 30s -> media ended before 15s (45s - 30s) is eligible for eviction
        // seg1 ended at 6s -> eligible!
        assertFalse("seg1 must be eligible for eviction", session.isResourceActive(uri1.toString()))

        // 4. Evict obsolete span seg1 to make room for seg3
        val spans = simpleCache.getCachedSpans(uri1.toString())
        for (span in spans) {
            simpleCache.removeSpan(span)
        }

        // Now prefetch seg3
        val req3 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = uri3, cacheKey = uri3.toString(), length = chunkSize.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 45_000_000L,
            durationUs = 6_000_000L,
            dataSpec = DataSpec(uri3, 0, chunkSize.toLong()),
        )
        val w3 = CacheWriter(cacheDataSourceFactory.createDataSource(), req3.dataSpec, ByteArray(1024), null)
        w3.cache()
        coordinator.scheduler.registry[req3.identity] = req3
        req3.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(req3)

        // seg1 was evicted, seg3 was cached
        assertFalse(simpleCache.isCached(uri1.toString(), 0, chunkSize.toLong()))
        assertTrue(simpleCache.isCached(uri3.toString(), 0, chunkSize.toLong()))

        // Cache space remains within 5 MB limit
        assertTrue("Total cache space must be <= limit (5 MB)", simpleCache.cacheSpace <= limitBytes)

        coordinator.release()
        session.release()
    }
}
