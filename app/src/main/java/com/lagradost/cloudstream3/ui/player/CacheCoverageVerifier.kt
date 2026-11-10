package com.lagradost.cloudstream3.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies complete, contiguous cache coverage for media requests.
 * Prevents treating a 1-byte or partially cached HLS/MP4 segment as fully complete.
 */
@OptIn(UnstableApi::class)
object CacheCoverageVerifier {

    // Cache keys confirmed to have completed EOF downloads by CacheWriter or Media3 loader
    private val completedKeys = ConcurrentHashMap.newKeySet<String>()

    fun markCompleted(cacheKey: String) {
        completedKeys.add(cacheKey)
    }

    fun unmarkCompleted(cacheKey: String) {
        completedKeys.remove(cacheKey)
    }

    fun isFullyCached(
        cache: Cache?,
        identity: PrefetchRequestIdentity,
    ): Boolean = isFullyCached(
        cache = cache,
        cacheKey = identity.cacheKey,
        position = identity.position,
        length = identity.length,
    )

    fun isFullyCached(
        cache: Cache?,
        cacheKey: String,
        position: Long,
        length: Long,
    ): Boolean {
        if (cache == null) return false

        // 1. Explicit known byte-range request
        if (length > 0L) {
            return cache.isCached(cacheKey, position, length)
        }

        // 2. Unknown length request (e.g. HLS media segment without byte-range)
        val metadata = runCatching { cache.getContentMetadata(cacheKey) }.getOrNull()
        val contentLength = if (metadata != null) {
            ContentMetadata.getContentLength(metadata)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        if (contentLength > 0L && contentLength != C.LENGTH_UNSET.toLong()) {
            val rangeLen = contentLength - position
            if (rangeLen <= 0L) return false
            return cache.isCached(cacheKey, position, rangeLen)
        }

        // 3. If content length is unset in metadata, verify via completed EOF registration and spans
        if (completedKeys.contains(cacheKey)) {
            val spans = runCatching { cache.getCachedSpans(cacheKey) }.getOrNull()
            if (!spans.isNullOrEmpty()) {
                val totalCached = spans.sumOf { it.length }
                return totalCached > 0L
            }
        }

        // Partial or unverified unknown-length resources are never reported as fully cached
        return false
    }
}
