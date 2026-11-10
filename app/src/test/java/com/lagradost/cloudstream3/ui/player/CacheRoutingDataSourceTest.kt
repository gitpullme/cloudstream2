package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CacheRoutingDataSourceTest {

    private class MockDataSource(
        var throwOnOpen: Boolean = false,
        var throwOnRead: Boolean = false,
    ) : DataSource {
        var isOpen = false
        var isClosed = false
        var openedDataSpec: DataSpec? = null
        var readCount = 0
        val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            if (throwOnOpen) {
                throw IOException("Simulated network open failure")
            }
            isOpen = true
            isClosed = false
            openedDataSpec = dataSpec
            return if (dataSpec.length != -1L) dataSpec.length else 2048L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isOpen) throw IOException("read() called before open()")
            if (throwOnRead) throw IOException("Simulated read error")
            readCount++
            return length.coerceAtMost(100)
        }

        override fun getUri(): Uri? = openedDataSpec?.uri

        override fun close() {
            isOpen = false
            isClosed = true
        }
    }

    private class MockFactory(val source: MockDataSource) : DataSource.Factory {
        var createCount = 0
        override fun createDataSource(): DataSource {
            createCount++
            return source
        }
    }

    @Test
    fun `media segments with gzip allowed flag are NOT bypassed from cache`() {
        val cacheSource = MockDataSource()
        val upstreamSource = MockDataSource()
        val cacheFactory = MockFactory(cacheSource)
        val upstreamFactory = MockFactory(upstreamSource)

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheFactory,
            upstreamFactory = upstreamFactory,
            shouldBypass = { uri -> PlaybackCacheManager.isManifest(uri) },
        )

        val ds = routingFactory.createDataSource()

        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example.com/hls/video_1080p_001.ts"))
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()

        ds.open(spec)

        assertEquals(1, cacheFactory.createCount)
        assertEquals(0, upstreamFactory.createCount)
        assertTrue(cacheSource.isOpen)

        ds.close()
        assertTrue(cacheSource.isClosed)
    }

    @Test
    fun `manifests and non-GET requests are bypassed to upstream`() {
        val cacheSource = MockDataSource()
        val upstreamSource = MockDataSource()
        val cacheFactory = MockFactory(cacheSource)
        val upstreamFactory = MockFactory(upstreamSource)

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheFactory,
            upstreamFactory = upstreamFactory,
            shouldBypass = { uri -> PlaybackCacheManager.isManifest(uri) },
        )

        val ds = routingFactory.createDataSource()

        // 1. Manifest URL (.m3u8)
        val manifestSpec = DataSpec(Uri.parse("https://cdn.example.com/master.m3u8"))
        ds.open(manifestSpec)
        assertEquals(0, cacheFactory.createCount)
        assertEquals(1, upstreamFactory.createCount)
        ds.close()

        // 2. Extensionless manifest
        val extensionlessManifest = DataSpec(Uri.parse("https://cdn.example.com/stream/manifest"))
        ds.open(extensionlessManifest)
        assertEquals(0, cacheFactory.createCount)
        assertEquals(2, upstreamFactory.createCount)
        ds.close()

        // 3. POST request (e.g. DRM license exchange)
        val postSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://license.drm.example/acquire"))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(1, 2, 3))
            .build()

        ds.open(postSpec)
        assertEquals(0, cacheFactory.createCount)
        assertEquals(3, upstreamFactory.createCount)
        ds.close()
    }

    @Test
    fun `session lease is acquired on open and released on close`() {
        val cacheSource = MockDataSource()
        val upstreamSource = MockDataSource()
        val cacheFactory = MockFactory(cacheSource)
        val upstreamFactory = MockFactory(upstreamSource)
        val session = PlaybackSessionState()

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheFactory,
            upstreamFactory = upstreamFactory,
            session = session,
        )

        val ds = routingFactory.createDataSource()
        val spec = DataSpec(Uri.parse("https://cdn.example.com/segment_42.ts"))

        assertEquals(0, session.getActiveLeasedKeys().size)

        ds.open(spec)
        assertTrue(session.isKeyLeased("https://cdn.example.com/segment_42.ts"))

        ds.close()
        assertFalse(session.isKeyLeased("https://cdn.example.com/segment_42.ts"))
    }

    @Test
    fun `failed open releases session lease immediately`() {
        val failingSource = MockDataSource(throwOnOpen = true)
        val cacheFactory = MockFactory(failingSource)
        val upstreamFactory = MockFactory(MockDataSource())
        val session = PlaybackSessionState()

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheFactory,
            upstreamFactory = upstreamFactory,
            session = session,
        )

        val ds = routingFactory.createDataSource()
        val spec = DataSpec(Uri.parse("https://cdn.example.com/segment_99.ts"))

        try {
            ds.open(spec)
            fail("Expected open to throw")
        } catch (_: IOException) {
            // Expected
        }

        assertFalse("Lease must not remain active after open throws", session.isKeyLeased("https://cdn.example.com/segment_99.ts"))
        ds.close()
    }

    @Test
    fun `per-session live state causes only the live session to bypass cache`() {
        val cacheSource = MockDataSource()
        val upstreamSource = MockDataSource()
        val cacheFactory = MockFactory(cacheSource)
        val upstreamFactory = MockFactory(upstreamSource)

        val vodSession = PlaybackSessionState(isLive = false)
        val liveSession = PlaybackSessionState(isLive = true)

        val vodRouting = CacheRoutingDataSourceFactory(cacheFactory, upstreamFactory, vodSession)
        val liveRouting = CacheRoutingDataSourceFactory(cacheFactory, upstreamFactory, liveSession)

        val vodDs = vodRouting.createDataSource()
        val liveDs = liveRouting.createDataSource()

        val mediaSpec = DataSpec(Uri.parse("https://cdn.example.com/chunk_001.ts"))

        // VOD session uses cache
        vodDs.open(mediaSpec)
        assertEquals(1, cacheFactory.createCount)
        assertEquals(0, upstreamFactory.createCount)
        vodDs.close()

        // Live session bypasses cache directly to upstream
        liveDs.open(mediaSpec)
        assertEquals(1, cacheFactory.createCount)
        assertEquals(1, upstreamFactory.createCount)
        liveDs.close()
    }

    @Test
    fun `obeys Media3 lifecycle on failed opens, repeated opens, and read exceptions`() {
        val failingCacheSource = MockDataSource(throwOnOpen = true)
        val cacheFactory = MockFactory(failingCacheSource)
        val upstreamFactory = MockFactory(MockDataSource())

        val routingFactory = CacheRoutingDataSourceFactory(
            cacheFactory = cacheFactory,
            upstreamFactory = upstreamFactory,
            shouldBypass = { false },
        )

        val ds = routingFactory.createDataSource()
        val spec = DataSpec(Uri.parse("https://cdn.example.com/video.mp4"))

        try {
            ds.open(spec)
            fail("Expected open to throw IOException")
        } catch (_: IOException) {
            // Expected
        }

        ds.close()

        failingCacheSource.throwOnOpen = false
        failingCacheSource.throwOnRead = true
        val length = ds.open(spec)
        assertTrue(length > 0)
        assertTrue(failingCacheSource.isOpen)

        try {
            val buf = ByteArray(16)
            ds.read(buf, 0, 16)
            fail("Expected read to throw IOException")
        } catch (_: IOException) {
            // Expected
        }

        ds.close()
        assertTrue(failingCacheSource.isClosed)
    }

    @Test
    fun `header fingerprints are hashed rather than stored as plaintext credentials in cache key`() {
        val defaultHeaders = mapOf(
            "Authorization" to "Bearer secret_user_token_12345",
            "Cookie" to "session=secret_session_cookie_abcde",
        )
        val factory = HeaderAwareCacheKeyFactory(defaultHeaders)

        val spec = DataSpec(Uri.parse("https://cdn.example.com/video/seg.ts"))
        val key = factory.buildCacheKey(spec)

        assertFalse(key.contains("secret_user_token_12345"))
        assertFalse(key.contains("secret_session_cookie_abcde"))
        assertTrue(key.contains("|h="))

        val differentTokenHeaders = mapOf(
            "Authorization" to "Bearer different_token_99999",
            "Cookie" to "session=secret_session_cookie_abcde",
        )
        val differentFactory = HeaderAwareCacheKeyFactory(differentTokenHeaders)
        val key2 = differentFactory.buildCacheKey(spec)

        assertNotEquals(key, key2)
    }
}
