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
 * - Different byte ranges produce different identities.
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

    companion object {
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
    }
}
