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

@OptIn(UnstableApi::class)
class HlsPrefetchAdapter(
    val manifestProvider: () -> Any?,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
) : PrefetchAdapter {

    companion object {
        private const val TAG = "HlsPrefetch"
        private const val NEAR_PLAYHEAD_US = 10_000_000L // 10s
        private const val FORWARD_RUNWAY_US = 30_000_000L // 30s
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?): Boolean {
        if (mimeType == MimeTypes.APPLICATION_M3U8) return true
        val path = sourceUri.path?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.contains(".m3u8")
    }

    override suspend fun getUpcomingRequests(
        currentPositionUs: Long,
        bufferedPositionUs: Long,
        durationUs: Long,
        forwardWindowUs: Long,
    ): List<PrefetchRequest> {
        val mediaPlaylist = when (val m = manifestProvider()) {
            is HlsManifest -> m.mediaPlaylist
            is HlsMediaPlaylist -> m
            else -> null
        } ?: return emptyList()

        val isVod = mediaPlaylist.hasEndTag ||
            mediaPlaylist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD

        // For live HLS, bound strictly to near-live window (max 15s ahead)
        val effectiveWindowUs = if (isVod) forwardWindowUs else minOf(forwardWindowUs, 15_000_000L)
        val maxTargetTimeUs = currentPositionUs + effectiveWindowUs

        val requests = mutableListOf<PrefetchRequest>()
        val baseUri = mediaPlaylist.baseUri

        // Check if initialization segment exists
        val firstSegment = mediaPlaylist.segments.firstOrNull()
        firstSegment?.initializationSegment?.let { initSeg ->
            val initUri = UriUtil.resolveToUri(baseUri, initSeg.url)
            val initSpec = DataSpec.Builder()
                .setUri(initUri)
                .setPosition(initSeg.byteRangeOffset)
                .setLength(if (initSeg.byteRangeLength > 0) initSeg.byteRangeLength else C.LENGTH_UNSET.toLong())
                .setHttpRequestHeaders(defaultHeaders)
                .build()

            val initIdentity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
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

        var segmentStartTimeUs = mediaPlaylist.startTimeUs
        for (segment in mediaPlaylist.segments) {
            val segmentDurationUs = segment.durationUs
            val segmentEndTimeUs = segmentStartTimeUs + segmentDurationUs

            if (segmentEndTimeUs > currentPositionUs && segmentStartTimeUs <= maxTargetTimeUs) {
                val segUri = UriUtil.resolveToUri(baseUri, segment.url)
                val spec = DataSpec.Builder()
                    .setUri(segUri)
                    .setPosition(segment.byteRangeOffset)
                    .setLength(if (segment.byteRangeLength > 0) segment.byteRangeLength else C.LENGTH_UNSET.toLong())
                    .setHttpRequestHeaders(defaultHeaders)
                    .build()

                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                val priority = when {
                    segmentStartTimeUs <= currentPositionUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                    segmentStartTimeUs <= currentPositionUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
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
            }

            segmentStartTimeUs = segmentEndTimeUs
        }

        return requests
    }
}
