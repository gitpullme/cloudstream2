package com.lagradost.cloudstream3.ui.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

/** Unit tests for the playback-aware disk cache eviction order. */
@OptIn(UnstableApi::class)
class PlaybackAwareCacheEvictorTest {

    private class FakeCache : Cache {
        val removed = mutableListOf<CacheSpan>()
        val removedResources = mutableListOf<String>()

        override fun getUid(): Long = 1L
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> =
            TreeSet()

        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TreeSet()
        override fun getKeys(): Set<String> = emptySet()
        override fun getCacheSpace(): Long = 0L

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
        }

        override fun removeSpan(span: CacheSpan) {
            removed += span
        }

        override fun isCached(key: String, position: Long, length: Long): Boolean = false
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
}
