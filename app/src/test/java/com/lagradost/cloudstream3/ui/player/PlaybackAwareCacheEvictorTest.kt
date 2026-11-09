package com.lagradost.cloudstream3.ui.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.NavigableSet
import java.util.TreeSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Unit tests for the playback-aware disk cache eviction order. */
class PlaybackAwareCacheEvictorTest {

    private class FakeCache : Cache {
        val removed = mutableListOf<CacheSpan>()
        val removedResources = mutableListOf<String>()
        val spans = mutableMapOf<String, TreeSet<CacheSpan>>()
        var throwOnRemove: Boolean = false

        override fun getUid(): Long = 1L
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> =
            TreeSet()

        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
            spans[key] ?: TreeSet()

        override fun getKeys(): Set<String> = spans.keys
        override fun getCacheSpace(): Long = spans.values.sumOf { set -> set.sumOf { it.length } }

        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
            throw UnsupportedOperationException()

        override fun startReadWriteNonBlocking(
            key: String,
            position: Long,
            length: Long,
        ): CacheSpan? = null

        override fun startFile(key: String, position: Long, length: Long): File =
            throw UnsupportedOperationException()

        override fun commitFile(file: File, length: Long) = Unit
        override fun releaseHoleSpan(holeSpan: CacheSpan) = Unit

        override fun removeResource(key: String) {
            removedResources += key
            spans.remove(key)
        }

        override fun removeSpan(span: CacheSpan) {
            if (throwOnRemove) {
                throw IOException("Simulated disk error during span removal")
            }
            removed += span
            spans[span.key]?.remove(span)
        }

        fun putSpan(span: CacheSpan) {
            spans.computeIfAbsent(span.key) { TreeSet() }.add(span)
        }

        override fun isCached(key: String, position: Long, length: Long): Boolean =
            spans[key]?.any { it.position == position && it.length == length } ?: false

        override fun getCachedLength(key: String, position: Long, length: Long): Long = -length
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = 0L
        override fun applyContentMetadataMutations(
            key: String,
            mutations: ContentMetadataMutations,
        ) = Unit

        override fun getContentMetadata(key: String): ContentMetadata = object : ContentMetadata {
            override fun get(key: String, defaultValue: ByteArray?): ByteArray? = defaultValue
            override fun get(key: String, defaultValue: String?): String? = defaultValue
            override fun get(key: String, defaultValue: Long): Long = defaultValue
            override fun contains(key: String): Boolean = false
        }
    }

    private fun span(
        key: String,
        length: Long = 400L,
        ageMs: Long = 0L,
        position: Long = 0L,
    ) = CacheSpan(
        key,
        position,
        length,
        System.currentTimeMillis() - ageMs,
        File.createTempFile("cache-test", ".bin").apply { deleteOnExit() },
    )

    @Test
    fun `old unrelated spans are evicted before the active source`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()
        evictor.setActivePrefix("https://cdn.example/active/")

        val oldUnrelated = span("https://cdn.example/other/old.ts", ageMs = 60_000L)
        val active = span("https://cdn.example/active/segment.ts", ageMs = 60_000L)
        evictor.onSpanAdded(cache, oldUnrelated)
        evictor.onSpanAdded(cache, active)

        // Adding another 400 bytes pushes the cache 200 bytes over its limit.
        val newUnrelated = span("https://cdn.example/other/new.ts")
        evictor.onSpanAdded(cache, newUnrelated)

        assertEquals(listOf(oldUnrelated), cache.removed)
        assertEquals(800L, evictor.usageBytes())
    }

    @Test
    fun `recently touched spans are protected while an older span exists`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 60_000L,
        )
        evictor.onCacheInitialized()
        evictor.setActivePrefix("https://cdn.example/active/")

        val old = span("https://cdn.example/other/old.ts", ageMs = 120_000L)
        val recent = span("https://cdn.example/other/recent.ts", ageMs = 1_000L)
        evictor.onSpanAdded(cache, old)
        evictor.onSpanAdded(cache, recent)

        evictor.onSpanAdded(cache, span("https://cdn.example/active/new.ts"))

        assertEquals(listOf(old), cache.removed)
    }

    @Test
    fun `eviction still happens when only the active source is cached`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 60_000L,
        )
        evictor.onCacheInitialized()
        evictor.setActivePrefix("https://cdn.example/active/")

        val first = span("https://cdn.example/active/1.ts")
        evictor.onSpanAdded(cache, first)
        val second = span("https://cdn.example/active/2.ts")
        evictor.onSpanAdded(cache, second)
        evictor.onSpanAdded(cache, span("https://cdn.example/active/3.ts"))

        assertEquals(1, cache.removed.size)
        assertEquals(800L, evictor.usageBytes())
    }

    @Test
    fun `touching a span moves it to the most recently used position`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()
        evictor.setActivePrefix("https://cdn.example/active/")

        val first = span("https://cdn.example/active/1.ts", ageMs = 60_000L)
        val second = span("https://cdn.example/active/2.ts", ageMs = 60_000L)
        evictor.onSpanAdded(cache, first)
        evictor.onSpanAdded(cache, second)
        // Simulate a read of the first span, which makes it newer than the second.
        evictor.onSpanTouched(cache, first, span(first.key, ageMs = 0L))

        evictor.onSpanAdded(cache, span("https://cdn.example/active/3.ts"))

        assertEquals(listOf(second), cache.removed)
        assertTrue(cache.removed.none { it.key == first.key })
    }

    @Test
    fun `cache initialized from disk is sorted by persisted touch time`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 0L,
        )
        evictor.setActivePrefix("https://cdn.example/active/")

        val older = span("https://cdn.example/active/old.ts", ageMs = 60_000L)
        val newer = span("https://cdn.example/active/new.ts", ageMs = 1_000L)
        // Directory scan order is arbitrary (and here reversed).
        evictor.onSpanAdded(cache, newer)
        evictor.onSpanAdded(cache, older)
        evictor.onCacheInitialized()

        evictor.onSpanAdded(cache, span("https://cdn.example/active/third.ts"))

        assertEquals(listOf(older), cache.removed)
        assertEquals(800L, evictor.usageBytes())
    }

    @Test
    fun `failed removals do not permanently remove span from accounting`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_000L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()

        val span1 = span("https://cdn.example/1.ts", length = 400L)
        val span2 = span("https://cdn.example/2.ts", length = 400L)
        val span3 = span("https://cdn.example/3.ts", length = 400L)

        cache.putSpan(span1)
        cache.putSpan(span2)
        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)
        assertEquals(800L, evictor.usageBytes())

        // Now simulate a failure when deleting spans
        cache.throwOnRemove = true
        cache.putSpan(span3)
        evictor.onSpanAdded(cache, span3)

        // Removal failed, so the victim span must NOT be permanently removed from local accounting.
        // Total bytes must reflect all 3 spans (1200L) since none could be removed from disk.
        assertEquals(1200L, evictor.usageBytes())
        assertTrue(cache.removed.isEmpty())
    }

    @Test
    fun `reducing cache size enforces immediate eviction without writing new spans`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 2_000L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()

        val span1 = span("https://cdn.example/1.ts", length = 400L, ageMs = 30_000L)
        val span2 = span("https://cdn.example/2.ts", length = 400L, ageMs = 20_000L)
        val span3 = span("https://cdn.example/3.ts", length = 400L, ageMs = 10_000L)

        cache.putSpan(span1)
        cache.putSpan(span2)
        cache.putSpan(span3)
        evictor.onSpanAdded(cache, span1)
        evictor.onSpanAdded(cache, span2)
        evictor.onSpanAdded(cache, span3)

        assertEquals(1200L, evictor.usageBytes())
        assertTrue(cache.removed.isEmpty())

        // User reduces cache size preference to 700 bytes.
        // Eviction must happen immediately even though no new span is written!
        evictor.setMaxBytes(700L, cache)

        assertEquals(2, cache.removed.size)
        assertEquals(listOf(span1, span2), cache.removed)
        assertEquals(400L, evictor.usageBytes())
    }

    @Test
    fun `concurrent cache touches maintain consistent accounting without race conditions`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 100_000L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()

        val numThreads = 8
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)

        val initialSpans = (0 until numThreads).map { i ->
            span("https://cdn.example/item_$i.ts", length = 100L).also {
                evictor.onSpanAdded(cache, it)
            }
        }

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    var current = initialSpans[t]
                    for (j in 0 until iterationsPerThread) {
                        val next = span("https://cdn.example/item_$t.ts", length = 100L, position = j.toLong())
                        evictor.onSpanTouched(cache, current, next)
                        current = next
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Exactly numThreads items of 100L bytes each = 800L
        assertEquals((numThreads * 100L), evictor.usageBytes())
    }

    @Test
    fun `active source protects multiple CDN domains, track variants and signed URLs`() {
        val cache = FakeCache()
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 1_500L,
            protectRecentMs = 0L,
        )
        evictor.onCacheInitialized()

        // Active media stream has segments on different CDN domains and track variants
        val videoVariantA = span("https://cdn1.fastly.net/hls/1080p/seg1.m4s?token=abc123", length = 400L)
        val videoVariantB = span("https://cdn2.akamai.com/hls/720p/seg1.m4s?sig=xyz789", length = 400L)
        val audioTrack = span("https://audio-cdn.cf.net/audio/eng/seg1.m4a?expires=9999", length = 400L)
        val unrelatedOld = span("https://other-movie.com/video/seg9.ts", length = 400L, ageMs = 120_000L)

        // Register active ownership
        evictor.registerActiveKey(videoVariantA.key)
        evictor.registerActiveKey(videoVariantB.key)
        evictor.registerActiveKey(audioTrack.key)

        evictor.onSpanAdded(cache, unrelatedOld)
        evictor.onSpanAdded(cache, videoVariantA)
        evictor.onSpanAdded(cache, videoVariantB)
        assertEquals(1200L, evictor.usageBytes())

        // Adding audio pushes over 1500L max (1600L total). Unrelated old span must be evicted!
        evictor.onSpanAdded(cache, audioTrack)

        assertEquals(listOf(unrelatedOld), cache.removed)
        assertTrue(evictor.isSpanActive(videoVariantA.key))
        assertTrue(evictor.isSpanActive(videoVariantB.key))
        assertTrue(evictor.isSpanActive(audioTrack.key))
        assertFalse(evictor.isSpanActive(unrelatedOld.key))
        assertEquals(1200L, evictor.usageBytes())
    }

    @Test
    fun `signed segment URLs with changing query tokens still match normalized active source`() {
        val evictor = PlaybackCacheManager.PlaybackAwareCacheEvictor(
            initialMaxBytes = 10_000L,
        )
        evictor.registerActiveUri(PlaybackCacheManager.normalizeUri("https://cdn.video.com/hls/stream/seg01.ts?initial_token=123"))

        // Key with updated query token and sensitive header hash fingerprint
        val keyWithNewToken = "https://cdn.video.com/hls/stream/seg01.ts?new_token=456&ts=789|h=a1b2c3d4"

        assertTrue(evictor.isSpanActive(keyWithNewToken))
        assertFalse(evictor.isSpanActive("https://cdn.video.com/hls/stream/other_episode.ts?token=123"))
    }
}
