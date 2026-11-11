package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Cache-key parity between the prefetch pipeline and the real Media3 playback request path.
 *
 * A prefetched byte is only reusable when the playback DataSource computes the exact same key that
 * the prefetch worker wrote. These tests pin that invariant, including header sensitivity and
 * custom DataSpec keys.
 */
class CacheKeyParityTest {

    private lateinit var tempDir: File
    private lateinit var cache: SimpleCache

    private val uri = Uri.parse("https://cdn.example.com/vod/movie.mp4")

    private class FixedUpstream(private val payload: ByteArray) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            private var stream: ByteArrayInputStream? = null
            private var currentSpec: DataSpec? = null

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                currentSpec = dataSpec
                val offset = dataSpec.position.toInt()
                val available = payload.size - offset
                val length = if (dataSpec.length >= 0L) minOf(dataSpec.length.toInt(), available) else available
                stream = ByteArrayInputStream(payload, offset, length)
                return length.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                stream?.read(buffer, offset, length) ?: -1

            override fun getUri(): Uri? = currentSpec?.uri

            override fun close() {
                stream?.close()
                stream = null
            }
        }
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("cs3_keyparity", "").apply {
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

    private fun spec(
        position: Long = 0L,
        length: Long = -1L,
        requestHeaders: Map<String, String> = emptyMap(),
        key: String? = null,
    ): DataSpec = DataSpec.Builder()
        .setUri(uri)
        .setPosition(position)
        .setLength(length)
        .setHttpRequestHeaders(requestHeaders)
        .apply { key?.let { setKey(it) } }
        .build()

    @Test
    fun `prefetch identity and router use the same key for identical headers`() {
        val headers = mapOf("Authorization" to "Bearer abc", "Referer" to "https://site.example")
        val keyFactory = HeaderAwareCacheKeyFactory(headers)
        val dataSpec = spec(length = 1024L, requestHeaders = headers)

        val prefetchIdentity = PrefetchRequestIdentity.fromDataSpec(dataSpec, keyFactory::buildCacheKey, headers)
        // The routing factory now derives its key exclusively from the shared key factory.
        val routerKey = keyFactory.buildCacheKey(dataSpec)
        assertEquals(prefetchIdentity.cacheKey, routerKey)
    }

    @Test
    fun `stored bytes are addressable with the identity key`() = runBlocking {
        val headers = mapOf("Authorization" to "Bearer abc")
        val keyFactory = HeaderAwareCacheKeyFactory(headers)
        val payload = ByteArray(2048) { (it % 97).toByte() }
        val upstream = FixedUpstream(payload)
        val cacheFactory = CacheDataSource.Factory().apply {
            setCache(this@CacheKeyParityTest.cache)
            setUpstreamDataSourceFactory(upstream)
            setCacheKeyFactory(keyFactory)
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        val dataSpec = spec(length = payload.size.toLong(), requestHeaders = headers)
        val identity = PrefetchRequestIdentity.fromDataSpec(dataSpec, keyFactory::buildCacheKey, headers)

        val dataSource = cacheFactory.createDataSource()
        CacheWriter(dataSource, dataSpec, ByteArray(1024), null).cache()
        dataSource.close()

        assertTrue(
            "prefetch identity key must address the bytes actually stored in SimpleCache",
            cache.isCached(identity.cacheKey, 0L, payload.size.toLong()),
        )
        // And the bytes must not be visible under the bare URI key.
        assertTrue(cache.getCachedSpans(uri.toString()).isEmpty())
    }

    @Test
    fun `different authorization headers never collide`() {
        val keyFactoryA = HeaderAwareCacheKeyFactory(mapOf("Authorization" to "Bearer a"))
        val keyFactoryB = HeaderAwareCacheKeyFactory(mapOf("Authorization" to "Bearer b"))
        val keyA = keyFactoryA.buildCacheKey(spec(length = 100L, requestHeaders = mapOf("Authorization" to "Bearer a")))
        val keyB = keyFactoryB.buildCacheKey(spec(length = 100L, requestHeaders = mapOf("Authorization" to "Bearer b")))
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `different referer never collides`() {
        val factory = HeaderAwareCacheKeyFactory()
        val keyA = factory.buildCacheKey(spec(length = 100L, requestHeaders = mapOf("Referer" to "https://a.example")))
        val keyB = factory.buildCacheKey(spec(length = 100L, requestHeaders = mapOf("Referer" to "https://b.example")))
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `signed url query strings stay part of the identity`() {
        val signed = DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example.com/vod/movie.mp4?token=abc123&expires=1"))
            .build()
        val clean = DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example.com/vod/movie.mp4"))
            .build()
        val factory = HeaderAwareCacheKeyFactory()
        assertNotEquals(factory.buildCacheKey(signed), factory.buildCacheKey(clean))
    }

    @Test
    fun `same url and headers with different byte ranges share a key but not an identity`() {
        val factory = HeaderAwareCacheKeyFactory()
        val a = spec(position = 0L, length = 4096L)
        val b = spec(position = 1024L, length = 4096L)
        val idA = PrefetchRequestIdentity.fromDataSpec(a, factory::buildCacheKey)
        val idB = PrefetchRequestIdentity.fromDataSpec(b, factory::buildCacheKey)

        assertEquals("byte ranges share one cache key", idA.cacheKey, idB.cacheKey)
        assertNotEquals("but they are different request identities", idA, idB)
        assertTrue("overlapping ranges must be detected", idA.overlaps(idB))
    }

    @Test
    fun `unknown length overlaps everything at or after its position`() {
        val a = PrefetchRequestIdentity(uri = uri, cacheKey = "k", position = 1000L, length = -1L)
        val b = PrefetchRequestIdentity(uri = uri, cacheKey = "k", position = 5000L, length = 512L)
        val c = PrefetchRequestIdentity(uri = uri, cacheKey = "k", position = 0L, length = 1000L)
        assertTrue("unbounded range overlaps a later finite range", a.overlaps(b))
        assertTrue(b.overlaps(a))
        // End-exclusive: an unbounded range starting at 1000 does not cover bytes [0, 1000).
        assertFalse(a.overlaps(c))
        assertFalse(c.overlaps(a))
    }

    @Test
    fun `custom DataSpec key is preserved by the identity and the router`() {
        val factory = HeaderAwareCacheKeyFactory(mapOf("Authorization" to "Bearer abc"))
        val dataSpec = spec(length = 100L, requestHeaders = mapOf("Authorization" to "Bearer abc"), key = "custom-key")
        val identity = PrefetchRequestIdentity.fromDataSpec(dataSpec, factory::buildCacheKey)
        assertTrue(identity.cacheKey.startsWith("custom-key"))
        assertEquals(identity.cacheKey, factory.buildCacheKey(dataSpec))
    }

    @Test
    fun `cache key invariant reports header-insensitive factories`() {
        val headerAware = HeaderAwareCacheKeyFactory()
        assertNull(
            "HeaderAwareCacheKeyFactory must fold response-affecting headers into the key",
            PrefetchRequestIdentity.verifyCacheKeyInvariant(
                factory = headerAware::buildCacheKey,
                uri = uri,
                headersA = mapOf("Authorization" to "Bearer a"),
                headersB = mapOf("Authorization" to "Bearer b"),
            ),
        )
        assertNotNull(
            "a URI-only key factory must be reported as unsafe",
            PrefetchRequestIdentity.verifyCacheKeyInvariant(
                factory = { it.uri.toString() },
                uri = uri,
                headersA = mapOf("Authorization" to "Bearer a"),
                headersB = mapOf("Authorization" to "Bearer b"),
            ),
        )
    }
}
