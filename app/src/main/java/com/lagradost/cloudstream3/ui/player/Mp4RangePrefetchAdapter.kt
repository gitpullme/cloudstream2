package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Byte-range prefetch adapter for progressive MP4 and container formats, based on FastStream.
 *
 * Requirements:
 * - Only use independent byte-range downloads when:
 *   - Actual range support is verified via probe.
 *   - Server returns valid 206 Partial Content and consistent Content-Range.
 *   - Total resource length is known.
 *   - Source is not live or DRM-protected.
 * - If server ignores Range (returns 200) or fails validation, stop parallel range requests
 *   and let normal Media3 loading continue without duplicate full-file downloads.
 * - Standard chunk size: 1 MB.
 */
@OptIn(UnstableApi::class)
class Mp4RangePrefetchAdapter(
    val sourceUri: Uri,
    val upstreamFactory: DataSource.Factory,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
    val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : PrefetchAdapter {

    companion object {
        private const val TAG = "Mp4RangePrefetch"
        const val DEFAULT_CHUNK_SIZE = 1024L * 1024L // 1 MB (FastStream standard)
        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
    }

    private var verified = false
    private var isRangeSupported = false
    private var totalContentLength = -1L

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?): Boolean {
        if (mimeType == MimeTypes.APPLICATION_M3U8 || mimeType == MimeTypes.APPLICATION_MPD) {
            return false
        }
        val path = sourceUri.path?.lowercase().orEmpty()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            return false
        }
        return verifyRangeSupport()
    }

    private suspend fun verifyRangeSupport(): Boolean = withContext(Dispatchers.IO) {
        if (verified) return@withContext isRangeSupported

        // Issue a 2-byte probe request: bytes 0-1
        val probeSpec = DataSpec.Builder()
            .setUri(sourceUri)
            .setPosition(0L)
            .setLength(2L)
            .setHttpRequestHeaders(defaultHeaders)
            .build()

        val upstream = upstreamFactory.createDataSource()
        try {
            val openedLength = upstream.open(probeSpec)
            val responseHeaders = upstream.responseHeaders

            // Content-Range header inspection
            val contentRange = responseHeaders["Content-Range"]?.firstOrNull()
                ?: responseHeaders["content-range"]?.firstOrNull()

            if (contentRange == null) {
                Log.w(TAG, "server ignored Range header (no Content-Range header present); range prefetch disabled for $sourceUri")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val match = CONTENT_RANGE_REGEX.find(contentRange)
            if (match == null) {
                Log.w(TAG, "invalid Content-Range header format: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val (startStr, endStr, totalStr) = match.destructured
            val start = startStr.toLongOrNull() ?: -1L
            val end = endStr.toLongOrNull() ?: -1L
            val total = totalStr.toLongOrNull() ?: -1L

            if (start != 0L || end < 1L || total <= 0L) {
                Log.w(TAG, "inconsistent Content-Range values: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            totalContentLength = total
            isRangeSupported = true
            verified = true
            Log.i(TAG, "range support verified for $sourceUri; totalLength=${total / (1024 * 1024)}MB")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "range probe request failed: ${e.message}; range prefetch disabled")
            isRangeSupported = false
            verified = true
            false
        } finally {
            runCatching { upstream.close() }
        }
    }

    override suspend fun getUpcomingRequests(
        currentPositionUs: Long,
        bufferedPositionUs: Long,
        durationUs: Long,
        forwardWindowUs: Long,
    ): List<PrefetchRequest> {
        if (!verifyRangeSupport() || totalContentLength <= 0L) {
            return emptyList()
        }

        val totalBytes = totalContentLength
        val playheadByte = if (durationUs > 0L) {
            val fraction = (currentPositionUs.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
            (fraction * totalBytes).toLong()
        } else {
            0L
        }

        // Forward window in bytes
        val forwardBytes = if (durationUs > 0L) {
            val fraction = (forwardWindowUs.toDouble() / durationUs.toDouble()).coerceIn(0.0, 1.0)
            maxOf(chunkSize * 4, (fraction * totalBytes).toLong())
        } else {
            chunkSize * 8
        }

        val targetEndByte = minOf(totalBytes, playheadByte + forwardBytes)
        val alignedStart = (playheadByte / chunkSize) * chunkSize

        val requests = mutableListOf<PrefetchRequest>()
        var offset = alignedStart
        var chunkIndex = 0

        while (offset < targetEndByte) {
            val length = minOf(chunkSize, totalBytes - offset)
            val spec = DataSpec.Builder()
                .setUri(sourceUri)
                .setPosition(offset)
                .setLength(length)
                .setHttpRequestHeaders(defaultHeaders)
                .build()

            val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
            val priority = when {
                chunkIndex == 0 -> PrefetchRequest.Priority.NEXT_MEDIA
                chunkIndex <= 2 -> PrefetchRequest.Priority.FORWARD_MEDIA
                else -> PrefetchRequest.Priority.USEFUL_PREFETCH
            }

            val estimatedTimeUs = if (totalBytes > 0L && durationUs > 0L) {
                ((offset.toDouble() / totalBytes.toDouble()) * durationUs).toLong()
            } else {
                0L
            }

            requests += PrefetchRequest(
                identity = identity,
                priority = priority,
                mediaTimeUs = estimatedTimeUs,
                durationUs = 0L,
                isInit = (offset == 0L),
                trackType = C.TRACK_TYPE_DEFAULT,
                dataSpec = spec,
            )

            offset += length
            chunkIndex++
        }

        return requests
    }
}
