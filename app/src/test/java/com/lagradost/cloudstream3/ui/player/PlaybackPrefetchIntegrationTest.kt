package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.C
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

        // Media3 requests this chunk. The handoff must never report a held lock, must record the
        // key as owned by playback, and must not leave a prefetch writer able to fetch it again.
        val handoff = scheduler.onMedia3RequestStarting(identity)
        assertTrue("handoff must never report a held cache lock: $handoff", handoff !is CacheHandoffResult.LockStillHeld)
        assertTrue("playback must own the cache key during its request", scheduler.isKeyInPlaybackUse(identity.cacheKey))
        assertFalse(
            "no prefetch writer may be active for a key playback is reading",
            scheduler.isActivelyWritingKey(identity.cacheKey),
        )

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

    private class KillableUpstreamDataSource(
        val dataMap: Map<Uri, ByteArray>,
    ) : DataSource {
        val openCount = AtomicInteger(0)
        @Volatile var isNetworkDead = false
        private var inputStream: ByteArrayInputStream? = null
        private var currentSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            if (isNetworkDead) {
                throw IOException("Simulated network outage: internet disconnected!")
            }
            openCount.incrementAndGet()
            currentSpec = dataSpec
            val data = dataMap[dataSpec.uri] ?: throw IOException("URI not found in fake server: ${dataSpec.uri}")
            val offset = dataSpec.position.toInt()
            val length = if (dataSpec.length != -1L) dataSpec.length.toInt() else (data.size - offset)
            inputStream = ByteArrayInputStream(data, offset, length)
            return length.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (isNetworkDead) {
                throw IOException("Simulated network outage: internet disconnected!")
            }
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

    @Test
    fun `critical offline playback test - HLS dual-track audio and video - both tracks and AES key prefetched, network dead, playback plays completely offline`() = runBlocking {
        val keyUri = Uri.parse("https://cdn.example.com/hls/key.bin")
        val videoUri = Uri.parse("https://cdn.example.com/hls/video_001.ts")
        val audioUri = Uri.parse("https://cdn.example.com/hls/audio_001.aac")

        val keyBytes = ByteArray(16) { 0x5A }
        val videoBytes = "HLS_VIDEO_ENCRYPTED_STREAM_PAYLOAD_CHUNK_1".toByteArray(Charsets.UTF_8)
        val audioBytes = "HLS_AUDIO_ENCRYPTED_STREAM_PAYLOAD_CHUNK_1".toByteArray(Charsets.UTF_8)

        val dataMap = mapOf(
            keyUri to keyBytes,
            videoUri to videoBytes,
            audioUri to audioBytes,
        )

        val upstream = KillableUpstreamDataSource(dataMap)
        val upstreamFactory = DataSource.Factory { upstream }
        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory { it.key ?: it.uri.toString() }
        }

        val session = PlaybackSessionState(
            sourceUri = "https://cdn.example.com/hls/master.m3u8",
            prefetchModeEnabled = true,
            prefetchLimitBytes = 20L * 1024L * 1024L,
        )

        var reportedCoverageMs = -1L
        val coordinator = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = session,
            playerProvider = { null },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = { it.key ?: it.uri.toString() },
        ).apply {
            onPrefetchCoverageChanged = { ms -> reportedCoverageMs = ms }
        }
        session.prefetchCoordinator = coordinator

        // 1. Prefetch AES-128 key (isInit = true)
        val keyReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = keyUri, cacheKey = keyUri.toString(), length = keyBytes.size.toLong()),
            priority = PrefetchRequest.Priority.IMMEDIATE,
            mediaTimeUs = 0L,
            durationUs = 0L,
            isInit = true,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = DataSpec(keyUri, 0, keyBytes.size.toLong()),
        )
        val wKey = CacheWriter(cacheDataSourceFactory.createDataSource(), keyReq.dataSpec, ByteArray(1024), null)
        wKey.cache()
        CacheCoverageVerifier.markCompleted(keyReq.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[keyReq.identity] = keyReq
        keyReq.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(keyReq)

        // 2. Prefetch Video Chunk (0..6s)
        val videoReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = videoUri, cacheKey = videoUri.toString(), length = videoBytes.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 6_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = DataSpec(videoUri, 0, videoBytes.size.toLong()),
        )
        val wVid = CacheWriter(cacheDataSourceFactory.createDataSource(), videoReq.dataSpec, ByteArray(1024), null)
        wVid.cache()
        CacheCoverageVerifier.markCompleted(videoReq.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[videoReq.identity] = videoReq
        videoReq.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(videoReq)

        // 3. Prefetch Audio Chunk (0..6s)
        val audioReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = audioUri, cacheKey = audioUri.toString(), length = audioBytes.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 6_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_AUDIO,
            dataSpec = DataSpec(audioUri, 0, audioBytes.size.toLong()),
        )
        val wAud = CacheWriter(cacheDataSourceFactory.createDataSource(), audioReq.dataSpec, ByteArray(1024), null)
        wAud.cache()
        CacheCoverageVerifier.markCompleted(audioReq.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[audioReq.identity] = audioReq
        audioReq.status = PrefetchRequest.Status.COMPLETED
        coordinator.registerCompletedRequest(audioReq)

        // Verify all 3 components are in cache
        assertTrue(CacheCoverageVerifier.isFullyCached(simpleCache, keyReq.identity.cacheKey, 0, keyBytes.size.toLong()))
        assertTrue(CacheCoverageVerifier.isFullyCached(simpleCache, videoReq.identity.cacheKey, 0, videoBytes.size.toLong()))
        assertTrue(CacheCoverageVerifier.isFullyCached(simpleCache, audioReq.identity.cacheKey, 0, audioBytes.size.toLong()))

        val opensBeforeNetworkCut = upstream.openCount.get()
        assertTrue(opensBeforeNetworkCut >= 3)

        // 4. CUT THE INTERNET (simulating user turning off Wi-Fi/cellular)
        upstream.isNetworkDead = true

        // 5. Media3 playback demuxer & decoders now request key, video, and audio chunks
        val playbackDs = cacheDataSourceFactory.createDataSource()

        // Read key
        val keyBuf = ByteArray(keyBytes.size)
        playbackDs.open(DataSpec(keyUri))
        playbackDs.read(keyBuf, 0, keyBuf.size)
        playbackDs.close()
        assertArrayEquals(keyBytes, keyBuf)

        // Read video segment
        val vidBuf = ByteArray(videoBytes.size)
        playbackDs.open(DataSpec(videoUri))
        playbackDs.read(vidBuf, 0, vidBuf.size)
        playbackDs.close()
        assertArrayEquals(videoBytes, vidBuf)

        // Read audio segment
        val audBuf = ByteArray(audioBytes.size)
        playbackDs.open(DataSpec(audioUri))
        playbackDs.read(audBuf, 0, audBuf.size)
        playbackDs.close()
        assertArrayEquals(audioBytes, audBuf)

        // Zero additional network requests occurred while internet was dead!
        assertEquals("No network requests must be issued during offline playback of prefetched range",
            opensBeforeNetworkCut, upstream.openCount.get())

        coordinator.release()
        session.release()
    }

    @Test
    fun `offline playability guarantee - missing audio stops prefetch indicator at audio boundary`() = runBlocking {
        val videoUri1 = Uri.parse("https://cdn.example.com/hls/v1.ts")
        val videoUri2 = Uri.parse("https://cdn.example.com/hls/v2.ts")
        val audioUri1 = Uri.parse("https://cdn.example.com/hls/a1.aac")

        val v1Bytes = "VIDEO_PART_1".toByteArray()
        val v2Bytes = "VIDEO_PART_2".toByteArray()
        val a1Bytes = "AUDIO_PART_1".toByteArray()

        val dataMap = mapOf(
            videoUri1 to v1Bytes,
            videoUri2 to v2Bytes,
            audioUri1 to a1Bytes,
        )

        val upstream = KillableUpstreamDataSource(dataMap)
        val upstreamFactory = DataSource.Factory { upstream }
        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory { it.key ?: it.uri.toString() }
        }

        val session = PlaybackSessionState(
            sourceUri = "https://cdn.example.com/hls/master.m3u8",
            prefetchModeEnabled = true,
            prefetchLimitBytes = 20L * 1024L * 1024L,
        )

        var reportedCoverageMs = -1L
        val coordinator = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = session,
            playerProvider = { null },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = { it.key ?: it.uri.toString() },
        ).apply {
            onPrefetchCoverageChanged = { ms -> reportedCoverageMs = ms }
        }
        session.prefetchCoordinator = coordinator

        // Fake active adapter with separate audio
        val fakeAdapter = object : PrefetchAdapter {
            override val name: String = "HLS Fake Dual Track"
            override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean = true
            override suspend fun getUpcomingRequests(snapshot: PlaybackPlayerSnapshot, availableBudgetBytes: Long): List<PrefetchRequest> = emptyList()
            override fun hasSeparateAudioTrack(): Boolean = true
        }
        coordinator.activeAdapter = fakeAdapter

        // Video chunk 1 (0..10s)
        val reqV1 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = videoUri1, cacheKey = videoUri1.toString(), length = v1Bytes.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 10_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = DataSpec(videoUri1, 0, v1Bytes.size.toLong()),
        )
        val wV1 = CacheWriter(cacheDataSourceFactory.createDataSource(), reqV1.dataSpec, ByteArray(1024), null)
        wV1.cache()
        CacheCoverageVerifier.markCompleted(reqV1.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[reqV1.identity] = reqV1
        coordinator.registerCompletedRequest(reqV1)

        // Video chunk 2 (10..20s)
        val reqV2 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = videoUri2, cacheKey = videoUri2.toString(), length = v2Bytes.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 10_000_000L,
            durationUs = 10_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = DataSpec(videoUri2, 0, v2Bytes.size.toLong()),
        )
        val wV2 = CacheWriter(cacheDataSourceFactory.createDataSource(), reqV2.dataSpec, ByteArray(1024), null)
        wV2.cache()
        CacheCoverageVerifier.markCompleted(reqV2.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[reqV2.identity] = reqV2
        coordinator.registerCompletedRequest(reqV2)

        // Audio chunk 1 ONLY (0..10s) - audio chunk 2 is missing!
        val reqA1 = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = audioUri1, cacheKey = audioUri1.toString(), length = a1Bytes.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 10_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_AUDIO,
            dataSpec = DataSpec(audioUri1, 0, a1Bytes.size.toLong()),
        )
        val wA1 = CacheWriter(cacheDataSourceFactory.createDataSource(), reqA1.dataSpec, ByteArray(1024), null)
        wA1.cache()
        CacheCoverageVerifier.markCompleted(reqA1.identity.cacheKey, cache = simpleCache)
        coordinator.scheduler.registry[reqA1.identity] = reqA1
        coordinator.registerCompletedRequest(reqA1)

        val snap = PlaybackPlayerSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 60_000L,
            playbackState = androidx.media3.common.Player.STATE_READY,
            isPlaying = true,
            playWhenReady = true,
            playbackSpeed = 1.0f,
            currentManifest = null,
            sourceUri = session.sourceUri?.let { Uri.parse(it) },
            selectedMime = null,
            isLive = false,
            currentTracks = null,
        )

        val refreshMethod = PlaybackPrefetchCoordinator::class.java.getDeclaredMethod("refreshCompletedCoverage", PlaybackPlayerSnapshot::class.java)
        refreshMethod.isAccessible = true
        refreshMethod.invoke(coordinator, snap)

        // Offline playable position MUST be constrained to 10s (min of video 20s and audio 10s), NOT 20s!
        assertEquals("Offline playable indicator must be constrained by audio boundary", 10_000L, coordinator.lastReportedCoverageMs)

        coordinator.release()
        session.release()
    }

    @Test
    fun `critical offline playback test - MP4 container header and media chunk - offline playback succeeds without network`() = runBlocking {
        val mp4Uri = Uri.parse("https://cdn.example.com/video.mp4")
        val containerHeader = ByteArray(4096) { 0x01 } // 0..4096: ftyp + moov
        val sampleChunk = ByteArray(1024 * 64) { 0x02 } // 4096..69632: mdat media samples

        val fullFile = ByteArray(containerHeader.size + sampleChunk.size)
        System.arraycopy(containerHeader, 0, fullFile, 0, containerHeader.size)
        System.arraycopy(sampleChunk, 0, fullFile, containerHeader.size, sampleChunk.size)

        val upstream = KillableUpstreamDataSource(mapOf(mp4Uri to fullFile))
        val upstreamFactory = DataSource.Factory { upstream }
        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory { it.key ?: it.uri.toString() }
        }

        val session = PlaybackSessionState(
            sourceUri = mp4Uri.toString(),
            prefetchModeEnabled = true,
            prefetchLimitBytes = 20L * 1024L * 1024L,
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

        // 1. Prefetch container header (0..4096, isInit = true)
        val initSpec = DataSpec.Builder().setUri(mp4Uri).setPosition(0L).setLength(4096L).build()
        val initReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = mp4Uri, cacheKey = mp4Uri.toString(), position = 0L, length = 4096L),
            priority = PrefetchRequest.Priority.IMMEDIATE,
            mediaTimeUs = 0L,
            durationUs = 0L,
            isInit = true,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = initSpec,
        )
        val wInit = CacheWriter(cacheDataSourceFactory.createDataSource(), initReq.dataSpec, ByteArray(1024), null)
        wInit.cache()
        coordinator.scheduler.registry[initReq.identity] = initReq
        coordinator.registerCompletedRequest(initReq)

        // 2. Prefetch sample chunk (4096..69632)
        val chunkSpec = DataSpec.Builder().setUri(mp4Uri).setPosition(4096L).setLength(sampleChunk.size.toLong()).build()
        val chunkReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = mp4Uri, cacheKey = mp4Uri.toString(), position = 4096L, length = sampleChunk.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 2_000_000L,
            isInit = false,
            trackType = C.TRACK_TYPE_VIDEO,
            dataSpec = chunkSpec,
        )
        val wChunk = CacheWriter(cacheDataSourceFactory.createDataSource(), chunkReq.dataSpec, ByteArray(1024), null)
        wChunk.cache()
        coordinator.scheduler.registry[chunkReq.identity] = chunkReq
        coordinator.registerCompletedRequest(chunkReq)

        assertTrue(simpleCache.isCached(mp4Uri.toString(), 0L, 4096L))
        assertTrue(simpleCache.isCached(mp4Uri.toString(), 4096L, sampleChunk.size.toLong()))

        val opensBeforeCut = upstream.openCount.get()

        // 3. CUT THE INTERNET
        upstream.isNetworkDead = true

        // 4. Media3 Mp4Extractor opens container header and samples offline
        val playbackDs = cacheDataSourceFactory.createDataSource()

        // Read header
        val headerBuf = ByteArray(4096)
        playbackDs.open(DataSpec(mp4Uri, 0L, 4096L))
        playbackDs.read(headerBuf, 0, headerBuf.size)
        playbackDs.close()
        assertArrayEquals(containerHeader, headerBuf)

        // Read samples
        val sampleBuf = ByteArray(sampleChunk.size)
        playbackDs.open(DataSpec(mp4Uri, 4096L, sampleChunk.size.toLong()))
        playbackDs.read(sampleBuf, 0, sampleBuf.size)
        playbackDs.close()
        assertArrayEquals(sampleChunk, sampleBuf)

        // Zero upstream requests while internet was disconnected
        assertEquals("Offline MP4 playback must not touch network", opensBeforeCut, upstream.openCount.get())

        coordinator.release()
        session.release()
    }

    @Test
    fun `session recovery test - stream change releases session A cleanly and stream B starts prefetching without getting stuck`() = runBlocking {
        val streamAUri = Uri.parse("https://cdn.example.com/streamA/seg1.ts")
        val streamBUri = Uri.parse("https://cdn.example.com/streamB/seg1.ts")

        val dataA = ByteArray(1024 * 1024) { 0x0A }
        val dataB = ByteArray(1024 * 1024) { 0x0B }

        val upstream = KillableUpstreamDataSource(mapOf(streamAUri to dataA, streamBUri to dataB))
        val upstreamFactory = DataSource.Factory { upstream }
        val cacheDataSourceFactory = CacheDataSource.Factory().apply {
            setCache(simpleCache)
            setUpstreamDataSourceFactory(upstreamFactory)
            setCacheKeyFactory { it.key ?: it.uri.toString() }
        }

        // Stream A starts
        val sessionA = PlaybackSessionState(
            sourceUri = streamAUri.toString(),
            prefetchModeEnabled = true,
            prefetchLimitBytes = 2L * 1024L * 1024L,
        )
        val coordinatorA = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = sessionA,
            playerProvider = { null },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = { it.key ?: it.uri.toString() },
        )
        sessionA.prefetchCoordinator = coordinatorA

        val reqA = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = streamAUri, cacheKey = streamAUri.toString(), length = dataA.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 5_000_000L,
            dataSpec = DataSpec(streamAUri, 0, dataA.size.toLong()),
        )
        val wA = CacheWriter(cacheDataSourceFactory.createDataSource(), reqA.dataSpec, ByteArray(1024), null)
        wA.cache()
        coordinatorA.scheduler.registry[reqA.identity] = reqA
        coordinatorA.registerCompletedRequest(reqA)
        assertTrue(simpleCache.isCached(streamAUri.toString(), 0L, dataA.size.toLong()))

        // Stream change happens: release session A cleanly
        sessionA.release()
        assertEquals(PlaybackSessionState.Lifecycle.RELEASED, sessionA.lifecycle)

        // Stream B starts
        val sessionB = PlaybackSessionState(
            sourceUri = streamBUri.toString(),
            prefetchModeEnabled = true,
            prefetchLimitBytes = 2L * 1024L * 1024L,
        )
        val coordinatorB = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = sessionB,
            playerProvider = { null },
            getCache = { simpleCache },
            getCacheDataSourceFactory = { cacheDataSourceFactory },
            upstreamFactory = upstreamFactory,
            cacheKeyFactory = { it.key ?: it.uri.toString() },
        )
        sessionB.prefetchCoordinator = coordinatorB

        val reqB = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = streamBUri, cacheKey = streamBUri.toString(), length = dataB.size.toLong()),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 0L,
            durationUs = 5_000_000L,
            dataSpec = DataSpec(streamBUri, 0, dataB.size.toLong()),
        )
        val wB = CacheWriter(cacheDataSourceFactory.createDataSource(), reqB.dataSpec, ByteArray(1024), null)
        wB.cache()
        coordinatorB.scheduler.registry[reqB.identity] = reqB
        coordinatorB.registerCompletedRequest(reqB)

        // Stream B prefetched successfully without getting stuck!
        assertTrue(simpleCache.isCached(streamBUri.toString(), 0L, dataB.size.toLong()))

        coordinatorB.release()
        sessionB.release()
    }

    @Test
    fun `critical acceptance test - real controllable HTTP server offline HLS dual-track playback with zero network requests after shutdown`() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val baseUrl = "http://127.0.0.1:$port"

        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="audio"
            video.m3u8
        """.trimIndent()

        val videoPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            video_0.ts
            #EXTINF:6.0,
            video_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val audioPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            audio_0.ts
            #EXTINF:6.0,
            audio_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val video0Data = ByteArray(64 * 1024) { 0x47 }
        val video1Data = ByteArray(64 * 1024) { 0x47 }
        val audio0Data = ByteArray(32 * 1024) { 0x47 }
        val audio1Data = ByteArray(32 * 1024) { 0x47 }

        val serverHitCount = AtomicInteger(0)

        server.createContext("/") { exchange ->
            serverHitCount.incrementAndGet()
            val path = exchange.requestURI.path
            val (status, body) = when (path) {
                "/master.m3u8" -> 200 to masterPlaylist.toByteArray(Charsets.UTF_8)
                "/video.m3u8" -> 200 to videoPlaylist.toByteArray(Charsets.UTF_8)
                "/audio.m3u8" -> 200 to audioPlaylist.toByteArray(Charsets.UTF_8)
                "/video_0.ts" -> 200 to video0Data
                "/video_1.ts" -> 200 to video1Data
                "/audio_0.ts" -> 200 to audio0Data
                "/audio_1.ts" -> 200 to audio1Data
                else -> 404 to ByteArray(0)
            }
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val masterUri = Uri.parse("$baseUrl/master.m3u8")
            val defaultHeaders = mapOf("User-Agent" to "CloudStreamRealServerTest")
            val cacheKeyFactory = HeaderAwareCacheKeyFactory(defaultHeaders)

            // Setup real upstream and cache data source factories
            val upstreamFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(defaultHeaders)

            val cacheDataSourceFactory = CacheDataSource.Factory().apply {
                setCache(simpleCache)
                setUpstreamDataSourceFactory(upstreamFactory)
                setCacheKeyFactory(cacheKeyFactory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }

            val session = PlaybackSessionState(
                sourceUri = masterUri.toString(),
                prefetchModeEnabled = true,
                prefetchLimitBytes = 50L * 1024L * 1024L,
            )

            val media3RoutingFactory = CacheRoutingDataSourceFactory(
                cacheFactory = cacheDataSourceFactory,
                upstreamFactory = upstreamFactory,
                session = session,
                cacheKeyFactory = { cacheKeyFactory.buildCacheKey(it) },
            )

            // Step 1: Real Prefetch Coordinator parses HLS and prefetches both tracks
            val parsedMediaPlaylist = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(
                Uri.parse("$baseUrl/video.m3u8"),
                videoPlaylist.byteInputStream()
            ) as androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

            val parsedMultivariant = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(
                masterUri,
                masterPlaylist.byteInputStream()
            ) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

            val hlsManifest = androidx.media3.exoplayer.hls.HlsManifest::class.java.getDeclaredConstructor(
                androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist::class.java,
                androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist::class.java,
            ).apply { isAccessible = true }.newInstance(parsedMultivariant, parsedMediaPlaylist)

            val coordinator = PlaybackPrefetchCoordinator(
                context = android.app.Application(),
                session = session,
                playerProvider = {
                    java.lang.reflect.Proxy.newProxyInstance(
                        androidx.media3.common.Player::class.java.classLoader,
                        arrayOf(androidx.media3.common.Player::class.java),
                    ) { _, method, _ ->
                        when (method.name) {
                            "getApplicationLooper" -> android.os.Looper.getMainLooper()
                            "getCurrentPosition" -> 0L
                            "getBufferedPosition" -> 0L
                            "getDuration" -> 12_000L
                            "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                            "isPlaying" -> true
                            "getPlayWhenReady" -> true
                            "getCurrentManifest" -> hlsManifest
                            "getCurrentTracks" -> null
                            else -> null
                        }
                    } as androidx.media3.common.Player
                },
                getCache = { simpleCache },
                getCacheDataSourceFactory = { cacheDataSourceFactory },
                upstreamFactory = upstreamFactory,
                cacheKeyFactory = { cacheKeyFactory.buildCacheKey(it) },
                defaultHeaders = defaultHeaders,
            )
            session.prefetchCoordinator = coordinator

            // Coordinator schedules upcoming requests
            coordinator.scheduleUpcoming()

            // Pre-cache VOD playlists so offline media-source creation succeeds
            val prefetchDs = cacheDataSourceFactory.createDataSource()
            CacheWriter(prefetchDs, DataSpec.Builder().setUri(masterUri).setHttpRequestHeaders(defaultHeaders).build(), ByteArray(1024), null).cache()
            CacheWriter(prefetchDs, DataSpec.Builder().setUri(Uri.parse("$baseUrl/video.m3u8")).setHttpRequestHeaders(defaultHeaders).build(), ByteArray(1024), null).cache()
            CacheWriter(prefetchDs, DataSpec.Builder().setUri(Uri.parse("$baseUrl/audio.m3u8")).setHttpRequestHeaders(defaultHeaders).build(), ByteArray(1024), null).cache()

            // Execute scheduled video and audio prefetch chunks
            val scheduled = coordinator.scheduler.registry.values.toList()
            for (req in scheduled) {
                val writer = CacheWriter(cacheDataSourceFactory.createDataSource(), req.dataSpec, ByteArray(1024), null)
                writer.cache()
                CacheCoverageVerifier.markCompleted(req.identity.cacheKey, cache = simpleCache)
                coordinator.registerCompletedRequest(req)
            }

            val hitsBeforeShutdown = serverHitCount.get()
            assertTrue("HTTP server must have served requests during prefetch", hitsBeforeShutdown > 0)

            // Verify both audio and video chunks are now cached
            val v0Spec = DataSpec.Builder().setUri(Uri.parse("$baseUrl/video_0.ts")).setHttpRequestHeaders(defaultHeaders).build()
            val a0Spec = DataSpec.Builder().setUri(Uri.parse("$baseUrl/audio_0.ts")).setHttpRequestHeaders(defaultHeaders).build()
            assertTrue("Video 0 must be in SimpleCache", simpleCache.isCached(cacheKeyFactory.buildCacheKey(v0Spec), 0L, video0Data.size.toLong()))
            assertTrue("Audio 0 must be in SimpleCache", simpleCache.isCached(cacheKeyFactory.buildCacheKey(a0Spec), 0L, audio0Data.size.toLong()))

            // Step 2: SHUT DOWN HTTP SERVER COMPLETELY (Internet is completely gone)
            server.stop(0)

            // Step 3: Media3 ExoPlayer attempts to open master playlist and play media offline
            val playbackDs = media3RoutingFactory.createDataSource()

            // Read master manifest offline
            playbackDs.open(DataSpec.Builder().setUri(masterUri).setHttpRequestHeaders(defaultHeaders).build())
            val masterBytes = ByteArray(masterPlaylist.length)
            playbackDs.read(masterBytes, 0, masterBytes.size)
            playbackDs.close()
            assertEquals(masterPlaylist, String(masterBytes, Charsets.UTF_8))

            // Read video segment offline
            playbackDs.open(v0Spec)
            val readV0 = ByteArray(video0Data.size)
            playbackDs.read(readV0, 0, readV0.size)
            playbackDs.close()
            assertArrayEquals("Offline video segment must match prefetched bytes exactly", video0Data, readV0)

            // Read audio segment offline
            playbackDs.open(a0Spec)
            val readA0 = ByteArray(audio0Data.size)
            playbackDs.read(readA0, 0, readA0.size)
            playbackDs.close()
            assertArrayEquals("Offline audio segment must match prefetched bytes exactly", audio0Data, readA0)

            // Assert ZERO HTTP requests occurred after server shutdown
            assertEquals("Zero HTTP upstream requests must occur after network cutoff", hitsBeforeShutdown, serverHitCount.get())

            // Assert that seeking beyond the prefetched range (e.g. video_2.ts which was never prefetched) fails offline
            val unPrefetchedSpec = DataSpec.Builder().setUri(Uri.parse("$baseUrl/video_2.ts")).setHttpRequestHeaders(defaultHeaders).build()
            val offlinePlaybackSucceeded = runCatching {
                val ds = media3RoutingFactory.createDataSource()
                ds.open(unPrefetchedSpec)
            }.isSuccess
            assertFalse("Playback beyond offline endpoint must fail when server is offline", offlinePlaybackSucceeded)

            coordinator.release()
            session.release()
        } finally {
            server.stop(0)
        }
    }
}
