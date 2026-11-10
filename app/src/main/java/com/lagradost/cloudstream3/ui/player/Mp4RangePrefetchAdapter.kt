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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validated progressive MP4 range prefetch adapter, based on FastStream.
 *
 * Requirements:
 * - Only selected when positively identified as supported progressive MP4.
 * - HTTP 206 Partial Content validation probe.
 * - Parses container index (sidx / segment index) to establish true byte-to-time mapping.
 * - If reliable mapping or range support is not present, falls back safely to normal Media3 loading.
 */
@OptIn(UnstableApi::class)
class Mp4RangePrefetchAdapter(
    val sourceUri: Uri,
    val upstreamFactory: DataSource.Factory,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
    val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : PrefetchAdapter {

    override val name: String = "Progressive MP4"

    companion object {
        private const val TAG = "Mp4RangePrefetch"
        const val DEFAULT_CHUNK_SIZE = 1024L * 1024L // 1 MB
        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")
        private const val SIDX_FOURCC = 0x73696478 // "sidx"
    }

    data class Mp4Segment(
        val byteOffset: Long,
        val byteLength: Long,
        val startTimeUs: Long,
        val durationUs: Long,
    )

    private var verified = false
    private var isRangeSupported = false
    private var totalContentLength = -1L
    private val parsedSegments = mutableListOf<Mp4Segment>()

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean {
        // Strict positive MP4 check: do not treat arbitrary formats (MKV, WebM, TS) as MP4
        val isMp4Mime = mimeType == MimeTypes.VIDEO_MP4 || mimeType == "video/mp4"
        val path = sourceUri.path?.lowercase().orEmpty()
        val isMp4Ext = path.endsWith(".mp4") || path.contains(".mp4?")

        if (!isMp4Mime && !isMp4Ext) {
            return false
        }

        // Live / playlist formats are not progressive MP4
        if (mimeType == MimeTypes.APPLICATION_M3U8 || mimeType == MimeTypes.APPLICATION_MPD ||
            path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            return false
        }

        return verifyRangeAndIndexSupport()
    }

    private suspend fun verifyRangeAndIndexSupport(): Boolean = withContext(Dispatchers.IO) {
        if (verified) return@withContext (isRangeSupported && parsedSegments.isNotEmpty())

        // 1. Send range probe for the first 32 KB to verify 206 Partial Content and inspect headers
        val probeLength = 32L * 1024L
        val probeSpec = DataSpec.Builder()
            .setUri(sourceUri)
            .setPosition(0L)
            .setLength(probeLength)
            .setHttpRequestHeaders(defaultHeaders)
            .build()

        val upstream = upstreamFactory.createDataSource()
        val probeBuffer = ByteArray(probeLength.toInt())
        var bytesReadTotal = 0

        try {
            val openedLength = upstream.open(probeSpec)
            val responseHeaders = upstream.responseHeaders

            val contentRange = responseHeaders["Content-Range"]?.firstOrNull()
                ?: responseHeaders["content-range"]?.firstOrNull()

            if (contentRange == null) {
                Log.w(TAG, "server returned no Content-Range header (200 OK); range prefetch disabled for $sourceUri")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val match = CONTENT_RANGE_REGEX.find(contentRange)
            if (match == null) {
                Log.w(TAG, "malformed Content-Range header: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val (startStr, endStr, totalStr) = match.destructured
            val start = startStr.toLongOrNull() ?: -1L
            val end = endStr.toLongOrNull() ?: -1L
            val total = totalStr.toLongOrNull() ?: -1L

            if (start != 0L || total <= 0L) {
                Log.w(TAG, "inconsistent Content-Range values: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            totalContentLength = total
            isRangeSupported = true

            // Read the initial probe bytes to check for index box (sidx)
            while (bytesReadTotal < probeBuffer.size) {
                val read = upstream.read(probeBuffer, bytesReadTotal, probeBuffer.size - bytesReadTotal)
                if (read < 0) break
                bytesReadTotal += read
            }

            // Attempt to parse MP4 sidx box to establish true byte-to-time mapping
            parseSidxBox(probeBuffer, bytesReadTotal)

            verified = true
            if (parsedSegments.isNotEmpty()) {
                Log.i(TAG, "MP4 range prefetch verified with ${parsedSegments.size} indexed segments for $sourceUri")
                true
            } else {
                Log.i(TAG, "MP4 range supported but no verified container index found; falling back safely to standard Media3 progressive loading")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "range probe request failed: ${e.message}; range prefetch disabled")
            isRangeSupported = false
            verified = true
            false
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * Parses standard ISO MP4 sidx box from initial bytes to establish exact byte-to-time intervals.
     */
    private fun parseSidxBox(data: ByteArray, length: Int) {
        if (length < 16) return
        var offset = 0
        while (offset + 8 <= length) {
            val bb = ByteBuffer.wrap(data, offset, length - offset).order(ByteOrder.BIG_ENDIAN)
            val boxSize = bb.int.toLong() and 0xFFFFFFFFL
            val boxType = bb.int

            if (boxSize < 8 || offset + boxSize > length) {
                // If box is sidx and spans beyond probe buffer, or invalid
                if (boxType == SIDX_FOURCC && offset + 12 <= length) {
                    parseSidxPayload(bb, offset + 8, totalContentLength)
                }
                break
            }

            if (boxType == SIDX_FOURCC) {
                parseSidxPayload(bb, offset + 8, totalContentLength)
                break
            }

            offset += boxSize.toInt()
        }
    }

    private fun parseSidxPayload(bb: ByteBuffer, payloadOffset: Int, totalBytes: Long) {
        try {
            val version = bb.get().toInt() and 0xFF
            bb.get() // flags (3 bytes)
            bb.get()
            bb.get()
            bb.int // referenceId
            val timescale = bb.int.toLong() and 0xFFFFFFFFL
            if (timescale <= 0L) return

            val earliestPresentationTime = if (version == 0) {
                bb.int.toLong() and 0xFFFFFFFFL
            } else {
                bb.long
            }
            val firstOffset = if (version == 0) {
                bb.int.toLong() and 0xFFFFFFFFL
            } else {
                bb.long
            }

            bb.short // reserved
            val referenceCount = bb.short.toInt() and 0xFFFF
            if (referenceCount <= 0) return

            var currentByteOffset = firstOffset
            var currentMediaTimeUs = (earliestPresentationTime * 1_000_000L) / timescale

            for (i in 0 until referenceCount) {
                if (bb.remaining() < 12) break
                val sizeVal = bb.int.toLong() and 0xFFFFFFFFL
                val refSize = sizeVal and 0x7FFFFFFFL
                val subDuration = bb.int.toLong() and 0xFFFFFFFFL
                bb.int // SAP flags

                val durationUs = (subDuration * 1_000_000L) / timescale
                parsedSegments += Mp4Segment(
                    byteOffset = currentByteOffset,
                    byteLength = refSize,
                    startTimeUs = currentMediaTimeUs,
                    durationUs = durationUs,
                )

                currentByteOffset += refSize
                currentMediaTimeUs += durationUs
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sidx parsing encountered exception: ${t.message}")
        }
    }

    override suspend fun getUpcomingRequests(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
    ): List<PrefetchRequest> {
        if (!verifyRangeAndIndexSupport() || parsedSegments.isEmpty() || availableBudgetBytes <= 0L) {
            return emptyList()
        }

        val prefetchStartUs = maxOf(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
        val requests = mutableListOf<PrefetchRequest>()
        var scheduledBytes = 0L

        for (segment in parsedSegments) {
            val endTimeUs = segment.startTimeUs + segment.durationUs
            if (endTimeUs > prefetchStartUs) {
                if (scheduledBytes + segment.byteLength > availableBudgetBytes) {
                    break
                }
                scheduledBytes += segment.byteLength

                val spec = DataSpec.Builder()
                    .setUri(sourceUri)
                    .setPosition(segment.byteOffset)
                    .setLength(segment.byteLength)
                    .setHttpRequestHeaders(defaultHeaders)
                    .build()

                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                val priority = when {
                    segment.startTimeUs <= prefetchStartUs + 15_000_000L -> PrefetchRequest.Priority.NEXT_MEDIA
                    segment.startTimeUs <= prefetchStartUs + 60_000_000L -> PrefetchRequest.Priority.FORWARD_MEDIA
                    else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                }

                requests += PrefetchRequest(
                    identity = identity,
                    priority = priority,
                    mediaTimeUs = segment.startTimeUs,
                    durationUs = segment.durationUs,
                    isInit = false,
                    trackType = C.TRACK_TYPE_VIDEO,
                    dataSpec = spec,
                )
            }
        }

        return requests
    }
}
