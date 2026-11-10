package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.mp4.TrackSampleTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Intelligent progressive MP4 prefetch adapter using HTTP byte-range requests.
 *
 * Discovers and parses ISO-BMFF container metadata:
 * - If indexed with 'sidx' (Segment Index), extracts exact byte-ranges and durations.
 * - If fast-start / streaming MP4 with 'moov' (Movie Box), parses sample tables for all playable tracks
 *   (video and audio) to coalesce interleaved samples into contiguous chunks.
 * - Schedules container metadata (0..initHeaderEnd and tail moov) as verified initialization segments
 *   so offline playback can decode without issuing network requests.
 */
@OptIn(UnstableApi::class)
class Mp4RangePrefetchAdapter(
    val sourceUri: Uri,
    val upstreamFactory: DataSource.Factory,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
    val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : PrefetchAdapter {

    override val name: String = "Progressive MP4 (Range)"

    var adapterState: AdapterState = AdapterState.UNRESOLVED
        private set

    override val state: AdapterState
        get() = adapterState

    override suspend fun checkReadiness(snapshot: PlaybackPlayerSnapshot): AdapterState {
        if (adapterState == AdapterState.RELEASED) return AdapterState.RELEASED
        if (adapterState == AdapterState.FAILED_RETRYABLE) {
            val ok = runCatching { verifyRangeAndIndexSupport() }.getOrDefault(false)
            if (ok) {
                adapterState = AdapterState.READY
            }
        }
        return adapterState
    }

    override fun release() {
        adapterState = AdapterState.RELEASED
        parsedSegments.clear()
    }

    companion object {
        private const val TAG = "Mp4RangePrefetch"
        const val DEFAULT_CHUNK_SIZE = 1024L * 1024L // 1 MB chunk target
        const val PROBE_SIZE = 256L * 1024L // 256 KB probe buffer
        const val TAIL_PROBE_SIZE = 512L * 1024L // 512 KB tail probe
        const val MAX_MOOV_SIZE = 15L * 1024L * 1024L // 15 MB max moov size
        private const val SIDX_FOURCC = 0x73696478 // 'sidx'
        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    }

    data class Mp4Segment(
        val byteOffset: Long,
        val byteLength: Long,
        val startTimeUs: Long,
        val durationUs: Long,
    )

    data class RawSample(
        val offset: Long,
        val size: Long,
        val timestampUs: Long,
        val durationUs: Long,
        val trackType: Int,
    )

    private var verified = false
    private var isRangeSupported = false
    private var totalContentLength = -1L
    private var initHeaderEnd = -1L
    private var tailMoovOffset = -1L
    private var tailMoovLength = -1L
    val parsedSegments = mutableListOf<Mp4Segment>()

    private fun safeUriString(uri: Uri): String {
        return runCatching {
            "${uri.scheme}://${uri.host}${uri.path}"
        }.getOrDefault("unknown-uri")
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean {
        val isMp4Mime = mimeType == MimeTypes.VIDEO_MP4 || mimeType == "video/mp4"
        val path = sourceUri.path?.lowercase().orEmpty()
        val fullUri = sourceUri.toString().lowercase()
        val isMp4Ext = path.endsWith(".mp4") || path.contains(".mp4") || fullUri.contains(".mp4")

        // Live / playlist formats are not progressive MP4
        if (mimeType == MimeTypes.APPLICATION_M3U8 || mimeType == MimeTypes.APPLICATION_MPD ||
            path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            return false
        }

        if (!isMp4Mime && !isMp4Ext) {
            adapterState = AdapterState.UNSUPPORTED
            return false
        }

        val ok = try {
            verifyRangeAndIndexSupport()
        } catch (e: Throwable) {
            Log.w(TAG, "Temporary failure verifying range support for $sourceUri: ${e.message}")
            adapterState = AdapterState.FAILED_RETRYABLE
            return true
        }

        adapterState = if (ok) AdapterState.READY else AdapterState.UNSUPPORTED
        return ok
    }

    private suspend fun readByteRange(offset: Long, length: Long): ByteArray? = withContext(Dispatchers.IO) {
        if (length <= 0L) return@withContext ByteArray(0)
        val spec = DataSpec.Builder()
            .setUri(sourceUri)
            .setPosition(offset)
            .setLength(length)
            .setHttpRequestHeaders(defaultHeaders)
            .build()

        val upstream = upstreamFactory.createDataSource()
        val buffer = ByteArray(length.toInt())
        var totalRead = 0

        try {
            upstream.open(spec)
            while (totalRead < buffer.size) {
                val read = upstream.read(buffer, totalRead, buffer.size - totalRead)
                if (read < 0) break
                totalRead += read
            }
            if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
        } catch (e: Throwable) {
            Log.w(TAG, "readByteRange failed at $offset for $length on ${safeUriString(sourceUri)}: ${e.message}")
            null
        } finally {
            runCatching { upstream.close() }
        }
    }

    private suspend fun verifyRangeAndIndexSupport(): Boolean = withContext(Dispatchers.IO) {
        if (verified) return@withContext (isRangeSupported && parsedSegments.isNotEmpty())

        val probeSpec = DataSpec.Builder()
            .setUri(sourceUri)
            .setPosition(0L)
            .setLength(PROBE_SIZE)
            .setHttpRequestHeaders(defaultHeaders)
            .build()

        val upstream = upstreamFactory.createDataSource()
        val probeBuffer = ByteArray(PROBE_SIZE.toInt())
        var bytesReadTotal = 0

        try {
            upstream.open(probeSpec)
            val responseHeaders = upstream.responseHeaders

            val contentRange = responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()

            if (contentRange == null) {
                Log.w(TAG, "Server returned no Content-Range header (200 OK); range prefetch disabled for ${safeUriString(sourceUri)}")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val match = CONTENT_RANGE_REGEX.find(contentRange)
            if (match == null) {
                Log.w(TAG, "Malformed Content-Range header: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            val (startStr, endStr, totalStr) = match.destructured
            val start = startStr.toLongOrNull() ?: -1L
            val total = totalStr.toLongOrNull() ?: -1L

            if (start != 0L || total <= 0L) {
                Log.w(TAG, "Inconsistent Content-Range values: $contentRange; range prefetch disabled")
                isRangeSupported = false
                verified = true
                return@withContext false
            }

            totalContentLength = total
            isRangeSupported = true

            while (bytesReadTotal < probeBuffer.size) {
                val read = upstream.read(probeBuffer, bytesReadTotal, probeBuffer.size - bytesReadTotal)
                if (read < 0) break
                bytesReadTotal += read
            }

            // Discover and parse container index (sidx or moov)
            discoverAndParseIndex(probeBuffer, bytesReadTotal)

            verified = true
            if (parsedSegments.isNotEmpty()) {
                Log.i(TAG, "MP4 range prefetch verified with ${parsedSegments.size} segments (initHeaderEnd=$initHeaderEnd) for ${safeUriString(sourceUri)}")
                true
            } else {
                Log.i(TAG, "MP4 range supported but no verified container index found; falling back safely to standard Media3 progressive loading")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Range probe request failed: ${e.message}; range prefetch disabled")
            isRangeSupported = false
            verified = true
            false
        } finally {
            runCatching { upstream.close() }
        }
    }

    private suspend fun discoverAndParseIndex(probeBuffer: ByteArray, bytesReadTotal: Int) {
        var sidxFound = false
        var moovFound = false

        // 1. Scan top-level boxes in initial probe buffer
        var offset = 0
        while (offset + 8 <= bytesReadTotal) {
            val bb = ByteBuffer.wrap(probeBuffer, offset, bytesReadTotal - offset).order(ByteOrder.BIG_ENDIAN)
            var boxSize = bb.int.toLong() and 0xFFFFFFFFL
            val boxType = bb.int
            var headerSize = 8

            if (boxSize == 1L) {
                if (offset + 16 > bytesReadTotal) break
                boxSize = bb.long
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = totalContentLength - offset
            }

            if (boxSize < headerSize) break

            if (boxType == SIDX_FOURCC) {
                val sidxBytes = if (offset + boxSize <= bytesReadTotal) {
                    probeBuffer.copyOfRange(offset, (offset + boxSize).toInt())
                } else {
                    readByteRange(offset.toLong(), boxSize)
                }
                if (sidxBytes != null && sidxBytes.size >= headerSize) {
                    val sidxBb = ByteBuffer.wrap(sidxBytes).order(ByteOrder.BIG_ENDIAN)
                    sidxBb.position(headerSize)
                    parseSidxPayload(sidxBb, offset.toLong(), boxSize)
                    if (parsedSegments.isNotEmpty()) {
                        sidxFound = true
                        initHeaderEnd = offset.toLong()
                        Log.i(TAG, "Parsed ${parsedSegments.size} segments from sidx box for ${safeUriString(sourceUri)}")
                        break
                    }
                }
            } else if (boxType == Mp4Box.TYPE_moov) {
                if (boxSize <= MAX_MOOV_SIZE) {
                    val moovBytes = if (offset + boxSize <= bytesReadTotal) {
                        probeBuffer.copyOfRange(offset, (offset + boxSize).toInt())
                    } else {
                        readByteRange(offset.toLong(), boxSize)
                    }
                    if (moovBytes != null && moovBytes.size >= headerSize) {
                        moovFound = parseMoovBytes(moovBytes, headerSize, defaultHeaderEnd = (offset + boxSize))
                        if (moovFound) {
                            Log.i(TAG, "Parsed ${parsedSegments.size} segments from moov box at beginning for ${safeUriString(sourceUri)}")
                            break
                        }
                    }
                }
            }

            offset += boxSize.toInt()
        }

        // 2. If neither sidx nor moov found at beginning, probe file tail for moov
        if (!sidxFound && !moovFound && totalContentLength > bytesReadTotal) {
            val tailReadLength = minOf(TAIL_PROBE_SIZE, totalContentLength)
            val tailOffset = totalContentLength - tailReadLength
            val tailBytes = readByteRange(tailOffset, tailReadLength)
            if (tailBytes != null) {
                var tailPos = 0
                while (tailPos + 8 <= tailBytes.size) {
                    val bb = ByteBuffer.wrap(tailBytes, tailPos, tailBytes.size - tailPos).order(ByteOrder.BIG_ENDIAN)
                    var boxSize = bb.int.toLong() and 0xFFFFFFFFL
                    val boxType = bb.int
                    var headerSize = 8

                    if (boxSize == 1L) {
                        if (tailPos + 16 > tailBytes.size) break
                        boxSize = bb.long
                        headerSize = 16
                    } else if (boxSize == 0L) {
                        boxSize = totalContentLength - (tailOffset + tailPos)
                    }

                    if (boxSize < headerSize) {
                        tailPos++
                        continue
                    }

                    if (boxType == Mp4Box.TYPE_moov) {
                        val moovAbsOffset = tailOffset + tailPos
                        if (boxSize <= MAX_MOOV_SIZE && moovAbsOffset + boxSize <= totalContentLength) {
                            val fullMoovBytes = if (tailPos + boxSize <= tailBytes.size) {
                                tailBytes.copyOfRange(tailPos, (tailPos + boxSize).toInt())
                            } else {
                                readByteRange(moovAbsOffset, boxSize)
                            }
                            if (fullMoovBytes != null && fullMoovBytes.size >= headerSize) {
                                tailMoovOffset = moovAbsOffset
                                tailMoovLength = boxSize
                                moovFound = parseMoovBytes(fullMoovBytes, headerSize, defaultHeaderEnd = 0L)
                                if (moovFound) {
                                    Log.i(TAG, "Parsed ${parsedSegments.size} segments from moov box at tail for ${safeUriString(sourceUri)}")
                                    break
                                }
                            }
                        }
                    } else if (boxType == SIDX_FOURCC) {
                        val sidxAbsOffset = tailOffset + tailPos
                        val fullSidxBytes = if (tailPos + boxSize <= tailBytes.size) {
                            tailBytes.copyOfRange(tailPos, (tailPos + boxSize).toInt())
                        } else {
                            readByteRange(sidxAbsOffset, boxSize)
                        }
                        if (fullSidxBytes != null && fullSidxBytes.size >= headerSize) {
                            val sidxBb = ByteBuffer.wrap(fullSidxBytes).order(ByteOrder.BIG_ENDIAN)
                            sidxBb.position(headerSize)
                            parseSidxPayload(sidxBb, sidxAbsOffset, boxSize)
                            if (parsedSegments.isNotEmpty()) {
                                sidxFound = true
                                initHeaderEnd = parsedSegments.first().byteOffset
                                Log.i(TAG, "Parsed ${parsedSegments.size} segments from sidx box at tail for ${safeUriString(sourceUri)}")
                                break
                            }
                        }
                    }
                    tailPos += boxSize.toInt()
                }
            }
        }
    }

    private fun parseMoovBytes(moovBytes: ByteArray, headerSize: Int, defaultHeaderEnd: Long): Boolean {
        return try {
            val moovContainer = parseContainerBox(
                boxType = Mp4Box.TYPE_moov,
                data = moovBytes,
                startOffset = headerSize,
                endOffset = moovBytes.size,
            )

            val sampleTables = BoxParser.parseTraks(
                moovContainer,
                GaplessInfoHolder(),
                C.TIME_UNSET,
                null,
                false,
                false,
                { track -> track },
                false,
            )

            if (sampleTables.isNullOrEmpty()) return false

            val playableTables = sampleTables.filter {
                it.track.type == C.TRACK_TYPE_VIDEO || it.track.type == C.TRACK_TYPE_AUDIO
            }.ifEmpty { sampleTables }

            val allSamples = mutableListOf<RawSample>()
            for (table in playableTables) {
                for (i in 0 until table.sampleCount) {
                    val sampleTimeUs = table.timestampsUs[i]
                    val sampleDurationUs = if (i + 1 < table.sampleCount) {
                        maxOf(0L, table.timestampsUs[i + 1] - sampleTimeUs)
                    } else {
                        maxOf(0L, table.durationUs - sampleTimeUs)
                    }
                    allSamples += RawSample(
                        offset = table.offsets[i],
                        size = table.sizes[i].toLong(),
                        timestampUs = sampleTimeUs,
                        durationUs = sampleDurationUs,
                        trackType = table.track.type,
                    )
                }
            }

            if (allSamples.isEmpty()) return false
            allSamples.sortBy { it.offset }

            val minSampleOffset = allSamples.first().offset
            initHeaderEnd = if (minSampleOffset > 0L) minSampleOffset else defaultHeaderEnd

            coalesceAllSamplesIntoSegments(allSamples)
            parsedSegments.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "parseMoovBytes failed: ${t.message}")
            false
        }
    }

    private fun isContainerBox(type: Int): Boolean {
        return type == Mp4Box.TYPE_moov ||
            type == Mp4Box.TYPE_trak ||
            type == Mp4Box.TYPE_mdia ||
            type == Mp4Box.TYPE_minf ||
            type == Mp4Box.TYPE_stbl ||
            type == Mp4Box.TYPE_edts
    }

    internal fun parseContainerBox(
        boxType: Int,
        data: ByteArray,
        startOffset: Int,
        endOffset: Int,
    ): Mp4Box.ContainerBox {
        val container = Mp4Box.ContainerBox(boxType, endOffset.toLong())
        var pos = startOffset
        while (pos + 8 <= endOffset) {
            val boxStart = pos
            val bb = ByteBuffer.wrap(data, pos, endOffset - pos).order(ByteOrder.BIG_ENDIAN)
            var boxSize = bb.int.toLong() and 0xFFFFFFFFL
            val type = bb.int
            var headerSize = 8

            if (boxSize == 1L) {
                if (pos + 16 > endOffset) break
                boxSize = bb.long
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = (endOffset - pos).toLong()
            }

            if (boxSize < headerSize || pos + boxSize > endOffset) {
                break
            }

            val boxEnd = (pos + boxSize).toInt()
            val payloadStart = pos + headerSize

            if (isContainerBox(type)) {
                val child = parseContainerBox(type, data, payloadStart, boxEnd)
                container.add(child)
            } else {
                val leafBytes = ByteArray(boxSize.toInt())
                System.arraycopy(data, boxStart, leafBytes, 0, boxSize.toInt())
                val leaf = Mp4Box.LeafBox(type, ParsableByteArray(leafBytes))
                container.add(leaf)
            }
            pos = boxEnd
        }
        return container
    }

    internal fun coalesceAllSamplesIntoSegments(samples: List<RawSample>) {
        parsedSegments.clear()
        var currentChunkStartOffset = -1L
        var currentChunkLength = 0L
        var currentChunkStartUs = 0L
        var currentChunkLastEndUs = 0L

        for (sample in samples) {
            val sampleOffset = sample.offset
            val sampleSize = sample.size
            val sampleTimeUs = sample.timestampUs
            val sampleDurationUs = sample.durationUs

            if (currentChunkStartOffset == -1L) {
                currentChunkStartOffset = sampleOffset
                currentChunkLength = sampleSize
                currentChunkStartUs = sampleTimeUs
                currentChunkLastEndUs = sampleTimeUs + sampleDurationUs
            } else {
                val gap = sampleOffset - (currentChunkStartOffset + currentChunkLength)
                val canBridge = gap in 0..(256 * 1024L)
                val shouldSplit = (currentChunkLength + (if (canBridge) gap else 0L) + sampleSize > chunkSize) || !canBridge

                if (shouldSplit) {
                    val chunkDurationUs = maxOf(0L, currentChunkLastEndUs - currentChunkStartUs)
                    parsedSegments += Mp4Segment(
                        byteOffset = currentChunkStartOffset,
                        byteLength = currentChunkLength,
                        startTimeUs = currentChunkStartUs,
                        durationUs = chunkDurationUs,
                    )
                    currentChunkStartOffset = sampleOffset
                    currentChunkLength = sampleSize
                    currentChunkStartUs = sampleTimeUs
                    currentChunkLastEndUs = sampleTimeUs + sampleDurationUs
                } else {
                    currentChunkLength += gap + sampleSize
                    currentChunkStartUs = minOf(currentChunkStartUs, sampleTimeUs)
                    currentChunkLastEndUs = maxOf(currentChunkLastEndUs, sampleTimeUs + sampleDurationUs)
                }
            }
        }

        if (currentChunkStartOffset != -1L && currentChunkLength > 0L) {
            val chunkDurationUs = maxOf(0L, currentChunkLastEndUs - currentChunkStartUs)
            parsedSegments += Mp4Segment(
                byteOffset = currentChunkStartOffset,
                byteLength = currentChunkLength,
                startTimeUs = currentChunkStartUs,
                durationUs = chunkDurationUs,
            )
        }
    }

    internal fun coalesceSamplesIntoSegments(table: TrackSampleTable) {
        val samples = (0 until table.sampleCount).map { i ->
            val sampleTimeUs = table.timestampsUs[i]
            val sampleDurationUs = if (i + 1 < table.sampleCount) {
                maxOf(0L, table.timestampsUs[i + 1] - sampleTimeUs)
            } else {
                maxOf(0L, table.durationUs - sampleTimeUs)
            }
            RawSample(
                offset = table.offsets[i],
                size = table.sizes[i].toLong(),
                timestampUs = sampleTimeUs,
                durationUs = sampleDurationUs,
                trackType = table.track.type,
            )
        }
        if (samples.isNotEmpty()) {
            initHeaderEnd = samples.first().offset
        }
        coalesceAllSamplesIntoSegments(samples)
    }

    private fun parseSidxPayload(bb: ByteBuffer, sidxOffset: Long, sidxSize: Long) {
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

            val anchorPoint = sidxOffset + sidxSize
            var currentByteOffset = anchorPoint + firstOffset
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

        val initKey = if (initHeaderEnd > 0L) {
            val s = DataSpec.Builder().setUri(sourceUri).setPosition(0L).setLength(initHeaderEnd).setHttpRequestHeaders(defaultHeaders).build()
            PrefetchRequestIdentity.fromDataSpec(s, cacheKeyFactory, defaultHeaders).cacheKey
        } else null

        val tailKey = if (tailMoovOffset > 0L && tailMoovLength > 0L) {
            val s = DataSpec.Builder().setUri(sourceUri).setPosition(tailMoovOffset).setLength(tailMoovLength).setHttpRequestHeaders(defaultHeaders).build()
            PrefetchRequestIdentity.fromDataSpec(s, cacheKeyFactory, defaultHeaders).cacheKey
        } else null

        val initDeps = listOfNotNull(initKey, tailKey)

        // 1. Initialization Segment: Progressive container header (ftyp + moov)
        if (prefetchStartUs == 0L && initHeaderEnd > 0L) {
            val initSpec = DataSpec.Builder()
                .setUri(sourceUri)
                .setPosition(0L)
                .setLength(initHeaderEnd)
                .setHttpRequestHeaders(defaultHeaders)
                .build()

            val initIdentity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
            scheduledBytes += initHeaderEnd

            requests += PrefetchRequest(
                identity = initIdentity,
                priority = PrefetchRequest.Priority.IMMEDIATE,
                mediaTimeUs = 0L,
                durationUs = 0L,
                isInit = true,
                trackType = C.TRACK_TYPE_VIDEO,
                dataSpec = initSpec,
            )
        }

        // Tail moov box (if located at tail of progressive MP4)
        if (tailMoovOffset > 0L && tailMoovLength > 0L) {
            val tailSpec = DataSpec.Builder()
                .setUri(sourceUri)
                .setPosition(tailMoovOffset)
                .setLength(tailMoovLength)
                .setHttpRequestHeaders(defaultHeaders)
                .build()

            val tailIdentity = PrefetchRequestIdentity.fromDataSpec(tailSpec, cacheKeyFactory, defaultHeaders)
            scheduledBytes += tailMoovLength

            requests += PrefetchRequest(
                identity = tailIdentity,
                priority = PrefetchRequest.Priority.IMMEDIATE,
                mediaTimeUs = 0L,
                durationUs = 0L,
                isInit = true,
                trackType = C.TRACK_TYPE_VIDEO,
                dataSpec = tailSpec,
            )
        }

        // 2. Media Chunks (Interleaved Video + Audio)
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
                    dependencyKeys = initDeps,
                )
            }
        }

        return requests
    }
}
