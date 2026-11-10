package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest

@OptIn(UnstableApi::class)
class DashPrefetchAdapter(
    val manifestProvider: () -> Any?,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
) : PrefetchAdapter {

    companion object {
        private const val NEAR_PLAYHEAD_US = 10_000_000L
        private const val FORWARD_RUNWAY_US = 30_000_000L
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?): Boolean {
        if (mimeType == MimeTypes.APPLICATION_MPD) return true
        val path = sourceUri.path?.lowercase() ?: return false
        return path.endsWith(".mpd") || path.contains(".mpd")
    }

    override suspend fun getUpcomingRequests(
        currentPositionUs: Long,
        bufferedPositionUs: Long,
        durationUs: Long,
        forwardWindowUs: Long,
    ): List<PrefetchRequest> {
        val manifest = manifestProvider() as? DashManifest ?: return emptyList()
        val isDynamic = manifest.dynamic
        val effectiveWindowUs = if (isDynamic) minOf(forwardWindowUs, 15_000_000L) else forwardWindowUs
        val maxTargetTimeUs = currentPositionUs + effectiveWindowUs

        val requests = mutableListOf<PrefetchRequest>()

        for (periodIndex in 0 until manifest.periodCount) {
            val period = manifest.getPeriod(periodIndex)
            val periodDurationUs = manifest.getPeriodDurationUs(periodIndex)

            for (adaptationSet in period.adaptationSets) {
                // Prefetch both audio and video representations
                val isVideo = adaptationSet.type == C.TRACK_TYPE_VIDEO
                val isAudio = adaptationSet.type == C.TRACK_TYPE_AUDIO
                if (!isVideo && !isAudio) continue

                // Pick primary representation
                val representation = adaptationSet.representations.firstOrNull() ?: continue
                val baseUri = representation.baseUrls.firstOrNull()?.url ?: ""

                // 1. Initialization Segment
                representation.initializationUri?.let { initRangedUri ->
                    val initUri = initRangedUri.resolveUri(baseUri)
                    val initSpec = DataSpec.Builder()
                        .setUri(initUri)
                        .setPosition(initRangedUri.start)
                        .setLength(if (initRangedUri.length > 0) initRangedUri.length else C.LENGTH_UNSET.toLong())
                        .setHttpRequestHeaders(defaultHeaders)
                        .build()

                    val identity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
                    requests += PrefetchRequest(
                        identity = identity,
                        priority = PrefetchRequest.Priority.IMMEDIATE,
                        mediaTimeUs = 0L,
                        durationUs = 0L,
                        isInit = true,
                        trackType = adaptationSet.type,
                        dataSpec = initSpec,
                    )
                }

                // 2. Media Segments
                val index: DashSegmentIndex? = representation.index
                if (index != null) {
                    val firstSegmentNum = index.firstSegmentNum
                    val segmentCount = index.getSegmentCount(periodDurationUs)
                    if (segmentCount != DashSegmentIndex.INDEX_UNBOUNDED.toLong() && segmentCount > 0) {
                        for (segNum in firstSegmentNum until (firstSegmentNum + segmentCount)) {
                            val timeUs = index.getTimeUs(segNum)
                            val duration = index.getDurationUs(segNum, periodDurationUs)
                            val endTimeUs = timeUs + duration

                            if (endTimeUs > currentPositionUs && timeUs <= maxTargetTimeUs) {
                                val rangedUri = index.getSegmentUrl(segNum)
                                val segUri = rangedUri.resolveUri(baseUri)
                                val spec = DataSpec.Builder()
                                    .setUri(segUri)
                                    .setPosition(rangedUri.start)
                                    .setLength(if (rangedUri.length > 0) rangedUri.length else C.LENGTH_UNSET.toLong())
                                    .setHttpRequestHeaders(defaultHeaders)
                                    .build()

                                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                                val priority = when {
                                    timeUs <= currentPositionUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                                    timeUs <= currentPositionUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
                                    else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                                }

                                requests += PrefetchRequest(
                                    identity = identity,
                                    priority = priority,
                                    mediaTimeUs = timeUs,
                                    durationUs = duration,
                                    isInit = false,
                                    trackType = adaptationSet.type,
                                    dataSpec = spec,
                                )
                            }
                        }
                    }
                }
            }
        }

        return requests
    }
}
