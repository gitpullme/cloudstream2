package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

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

        // Media segment request with FLAG_ALLOW_GZIP set
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example.com/hls/video_1080p_001.ts"))
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()

        ds.open(spec)

        // Must route through cache factory, not upstream!
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

        // 2. POST request (e.g. DRM license exchange)
        val postSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://license.drm.example/acquire"))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(1, 2, 3))
            .build()

        ds.open(postSpec)
        assertEquals(0, cacheFactory.createCount)
        assertEquals(2, upstreamFactory.createCount)
        ds.close()
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

        // 1. Failed open throws IOException
        try {
            ds.open(spec)
            fail("Expected open to throw IOException")
        } catch (_: IOException) {
            // Expected
        }

        // Caller must be able to call close() without crashing even after failed open
        ds.close()

        // 2. Repeated open on the same DataSource works cleanly
        failingCacheSource.throwOnOpen = false
        failingCacheSource.throwOnRead = true
        val length = ds.open(spec)
        assertTrue(length > 0)
        assertTrue(failingCacheSource.isOpen)

        // 3. Exception during read throws and then close() cleans up
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

        // The key must NOT contain the sensitive auth tokens in plaintext
        assertFalse(key.contains("secret_user_token_12345"))
        assertFalse(key.contains("secret_session_cookie_abcde"))
        // It must contain the hashed representation
        assertTrue(key.contains("|h="))

        // Changing the authorization token changes the hash fingerprint
        val differentTokenHeaders = mapOf(
            "Authorization" to "Bearer different_token_99999",
            "Cookie" to "session=secret_session_cookie_abcde",
        )
        val differentFactory = HeaderAwareCacheKeyFactory(differentTokenHeaders)
        val key2 = differentFactory.buildCacheKey(spec)

        assertNotEquals(key, key2)
    }
}
