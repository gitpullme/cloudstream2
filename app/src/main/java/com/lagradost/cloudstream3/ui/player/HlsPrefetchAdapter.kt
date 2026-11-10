package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/**
 * Robust HLS VOD and sliding-window live segment prefetch adapter.
 * Uses immutable player snapshots to extract the active media playlist without thread violations.
 */
@OptIn(UnstableApi::class)
class HlsPrefetchAdapter(
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
) : PrefetchAdapter {

    override val name: String = "HLS"

    companion object {
        private const val TAG = "HlsPrefetch"
        private const val NEAR_PLAYHEAD_US = 15_000_000L // 15s
        private const val FORWARD_RUNWAY_US = 60_000_000L // 60s
        private const val DEFAULT_SEGMENT_BYTES_ESTIMATE = 1024L * 1024L // 1 MB fallback
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean {
        if (manifest is HlsManifest || manifest is HlsMediaPlaylist) return true
        if (mimeType == MimeTypes.APPLICATION_M3U8) return true
        val path = sourceUri.path?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.contains(".m3u8")
    }

    override suspend fun getUpcomingRequests(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
    ): List<PrefetchRequest> {
        val mediaPlaylist = when (val m = snapshot.currentManifest) {
            is HlsManifest -> m.mediaPlaylist
            is HlsMediaPlaylist -> m
            else -> null
        } ?: return emptyList()

        if (availableBudgetBytes <= 0L) return emptyList()

        val isVod = mediaPlaylist.hasEndTag ||
            mediaPlaylist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD

        // Prefetch starts after the currently buffered position to avoid re-requesting buffered media
        val prefetchStartUs = maxOf(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
        val requests = mutableListOf<PrefetchRequest>()
        val baseUri = mediaPlaylist.baseUri

        // Calculate average segment bitrate to estimate byte sizes when byte-range is not specified
        val estimatedBitrate = runCatching {
            snapshot.currentTracks?.groups?.asSequence()
                ?.flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i) } }
                ?.filter { it.sampleMimeType?.startsWith("video/") == true }
                ?.maxOfOrNull { it.bitrate }
        }.getOrNull()?.takeIf { it > 0 } ?: 2_500_000 // default 2.5 Mbps

        var scheduledBytes = 0L

        // 1. Initialization Segment
        val firstSegment = mediaPlaylist.segments.firstOrNull()
        firstSegment?.initializationSegment?.let { initSeg ->
            val initUri = UriUtil.resolveToUri(baseUri, initSeg.url)
            val initLength = if (initSeg.byteRangeLength > 0) initSeg.byteRangeLength else C.LENGTH_UNSET.toLong()
            val initSpec = DataSpec.Builder()
                .setUri(initUri)
                .setPosition(initSeg.byteRangeOffset)
                .setLength(initLength)
                .setHttpRequestHeaders(defaultHeaders)
                .build()

            val initIdentity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
            val initBytes = if (initLength > 0) initLength else 128 * 1024L
            scheduledBytes += initBytes

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

        // 2. Media Segments
        var segmentStartTimeUs = mediaPlaylist.startTimeUs
        var liveSegmentsScheduled = 0

        for (segment in mediaPlaylist.segments) {
            val segmentDurationUs = segment.durationUs
            val segmentEndTimeUs = segmentStartTimeUs + segmentDurationUs

            // For live streams, constrain prefetch to at most 3 segments beyond the playhead
            if (!isVod && segmentStartTimeUs > snapshot.currentPositionUs) {
                if (liveSegmentsScheduled >= 3) break
            }

            if (segmentEndTimeUs > prefetchStartUs) {
                val segUri = UriUtil.resolveToUri(baseUri, segment.url)
                val segLength = if (segment.byteRangeLength > 0) segment.byteRangeLength else C.LENGTH_UNSET.toLong()
                val spec = DataSpec.Builder()
                    .setUri(segUri)
                    .setPosition(segment.byteRangeOffset)
                    .setLength(segLength)
                    .setHttpRequestHeaders(defaultHeaders)
                    .build()

                val estBytes = if (segLength > 0) {
                    segLength
                } else {
                    maxOf(256 * 1024L, (segmentDurationUs * estimatedBitrate) / 8_000_000L)
                }

                // Check budget limit
                if (scheduledBytes + estBytes > availableBudgetBytes) {
                    break
                }
                scheduledBytes += estBytes

                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                val priority = when {
                    segmentStartTimeUs <= prefetchStartUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                    segmentStartTimeUs <= prefetchStartUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
                    else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                }

                requests += PrefetchRequest(
                    identity = identity,
                    priority = priority,
                    mediaTimeUs = segmentStartTimeUs,
                    durationUs = segmentDurationUs,
                    isInit = false,
                    trackType = C.TRACK_TYPE_VIDEO,
                    dataSpec = spec,
                )

                if (!isVod) {
                    liveSegmentsScheduled++
                }
            }

            segmentStartTimeUs = segmentEndTimeUs
        }

        return requests
    }
}
