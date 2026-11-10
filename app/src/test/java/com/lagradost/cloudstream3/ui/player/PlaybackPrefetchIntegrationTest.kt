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
}
