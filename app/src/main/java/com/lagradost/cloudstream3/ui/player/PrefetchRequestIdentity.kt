package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

/**
 * Identity of a prefetchable media resource or byte range.
 *
 * Requirements:
 * - Request identity must include all response-affecting properties:
 *   URI, HTTP method, relevant headers / cache key, and byte range.
 * - Same URL with different headers produces different cacheKeys and identities.
 * - Different byte ranges produce different identities, but two ranges that overlap must be
 *   detectable: a player request frequently covers a broader or differently sized range than the
 *   prefetch worker that wrote the same bytes (progressive MP4 / byte-range HLS).
 *
 * Equality stays cache-key + range + method based. That is only sound because the shared
 * HeaderAwareCacheKeyFactory folds every response-affecting header into the cache key; see
 * `CacheKeyParityTest` and `verifyCacheKeyInvariant` below.
 */
@OptIn(UnstableApi::class)
data class PrefetchRequestIdentity(
    val uri: Uri,
    val cacheKey: String,
    val position: Long = 0L,
    val length: Long = -1L,
    val httpMethod: String = "GET",
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrefetchRequestIdentity) return false
        if (cacheKey != other.cacheKey) return false
        if (position != other.position) return false
        if (length != other.length) return false
        if (httpMethod != other.httpMethod) return false
        return true
    }

    override fun hashCode(): Int {
        var result = cacheKey.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + httpMethod.hashCode()
        return result
    }

    /** Exclusive end of the requested interval, or [Long.MAX_VALUE] when the length is unknown. */
    val endExclusive: Long
        get() = if (length > 0L) {
            val end = position + length
            if (end < position) Long.MAX_VALUE else end
        } else {
            Long.MAX_VALUE
        }

    /** Unknown lengths are treated as unbounded for overlap evaluation. */
    val hasKnownLength: Boolean get() = length > 0L

    /**
     * True when both requests target the same cache key, use the same HTTP method, and their byte
     * intervals intersect. An unknown length is unbounded, so it overlaps everything at or after its
     * position.
     */
    fun overlaps(other: PrefetchRequestIdentity): Boolean {
        if (cacheKey != other.cacheKey) return false
        if (httpMethod != other.httpMethod) return false
        return intervalsOverlap(position, length, other.position, other.length)
    }

    fun overlapsRange(rangePosition: Long, rangeLength: Long): Boolean =
        intervalsOverlap(position, length, rangePosition, rangeLength)

    companion object {
        fun intervalsOverlap(
            aPosition: Long,
            aLength: Long,
            bPosition: Long,
            bLength: Long,
        ): Boolean {
            val aStart = aPosition.coerceAtLeast(0L)
            val bStart = bPosition.coerceAtLeast(0L)
            val aEnd = if (aLength > 0L) {
                val end = aStart + aLength
                if (end < aStart) Long.MAX_VALUE else end
            } else {
                Long.MAX_VALUE
            }
            val bEnd = if (bLength > 0L) {
                val end = bStart + bLength
                if (end < bStart) Long.MAX_VALUE else end
            } else {
                Long.MAX_VALUE
            }
            return aStart < bEnd && bStart < aEnd
        }

        fun fromDataSpec(
            dataSpec: DataSpec,
            cacheKeyFactory: (DataSpec) -> String,
            defaultHeaders: Map<String, String> = emptyMap(),
        ): PrefetchRequestIdentity {
            val key = cacheKeyFactory(dataSpec)
            val combinedHeaders = HashMap<String, String>(defaultHeaders).apply {
                putAll(dataSpec.httpRequestHeaders)
            }
            val method = when (dataSpec.httpMethod) {
                DataSpec.HTTP_METHOD_POST -> "POST"
                DataSpec.HTTP_METHOD_HEAD -> "HEAD"
                else -> "GET"
            }
            return PrefetchRequestIdentity(
                uri = dataSpec.uri,
                cacheKey = key,
                position = dataSpec.position,
                length = dataSpec.length,
                httpMethod = method,
                headers = combinedHeaders,
            )
        }

        /**
         * Invariant relied upon by [equals]: the effective cache key must already encode every
         * response-affecting header distinction. Returns a human readable mismatch or null.
         */
        fun verifyCacheKeyInvariant(
            factory: (DataSpec) -> String,
            uri: Uri,
            headersA: Map<String, String>,
            headersB: Map<String, String>,
        ): String? {
            val specA = DataSpec.Builder()
                .setUri(uri)
                .setHttpRequestHeaders(headersA)
                .build()
            val specB = DataSpec.Builder()
                .setUri(uri)
                .setHttpRequestHeaders(headersB)
                .build()
            val keyA = factory(specA)
            val keyB = factory(specB)
            if (headersA != headersB && keyA == keyB) {
                return "cache key ignores response-affecting headers for ${uri.host}${uri.path}"
            }
            return null
        }
    }
}
