package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Representation

/**
 * DASH segment prefetch adapter respecting active track selection and storage budget.
 */
@OptIn(UnstableApi::class)
class DashPrefetchAdapter(
    val defaultHeaders: Map<String, String> = emptyMap(),
    val cacheKeyFactory: (DataSpec) -> String,
) : PrefetchAdapter {

    override val name: String = "DASH"

    var adapterState: AdapterState = AdapterState.UNRESOLVED
        private set

    override val state: AdapterState
        get() = adapterState

    private var hasSeparateAudio: Boolean = false
    override fun hasSeparateAudioTrack(): Boolean = hasSeparateAudio

    companion object {
        private const val NEAR_PLAYHEAD_US = 15_000_000L
        private const val FORWARD_RUNWAY_US = 60_000_000L
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean {
        val matchesMime = mimeType == MimeTypes.APPLICATION_MPD
        val path = sourceUri.path?.lowercase() ?: ""
        val fullUri = sourceUri.toString().lowercase()
        val matchesExt = path.endsWith(".mpd") || path.contains(".mpd") || fullUri.contains(".mpd")
        val hasManifest = manifest is DashManifest

        if (hasManifest || matchesMime || matchesExt) {
            adapterState = if (hasManifest) AdapterState.READY else AdapterState.WAITING_FOR_MANIFEST
            return true
        }
        adapterState = AdapterState.UNSUPPORTED
        return false
    }

    override suspend fun checkReadiness(snapshot: PlaybackPlayerSnapshot): AdapterState {
        if (adapterState == AdapterState.RELEASED) return AdapterState.RELEASED
        val manifest = snapshot.currentManifest
        if (manifest is DashManifest) {
            adapterState = AdapterState.READY
        } else if (adapterState != AdapterState.READY) {
            adapterState = AdapterState.WAITING_FOR_MANIFEST
        }
        return adapterState
    }

    override suspend fun getUpcomingRequests(
        snapshot: PlaybackPlayerSnapshot,
        availableBudgetBytes: Long,
    ): List<PrefetchRequest> {
        val manifest = snapshot.currentManifest as? DashManifest ?: return emptyList()
        if (availableBudgetBytes <= 0L) return emptyList()

        hasSeparateAudio = (0 until manifest.periodCount).any { p ->
            manifest.getPeriod(p).adaptationSets.any { it.type == C.TRACK_TYPE_AUDIO }
        }

        val isDynamic = manifest.dynamic
        val prefetchStartUs = maxOf(snapshot.currentPositionUs, snapshot.bufferedPositionUs)
        val requests = mutableListOf<PrefetchRequest>()
        var scheduledBytes = 0L

        // Find active video and audio format IDs from snapshot track selection
        val selectedFormatIds = snapshot.currentTracks?.groups?.asSequence()
            ?.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it).id } }
            ?.filterNotNull()
            ?.toSet() ?: emptySet()

        for (periodIndex in 0 until manifest.periodCount) {
            val period = manifest.getPeriod(periodIndex)
            val periodDurationUs = manifest.getPeriodDurationUs(periodIndex)

            for (adaptationSet in period.adaptationSets) {
                val isVideo = adaptationSet.type == C.TRACK_TYPE_VIDEO
                val isAudio = adaptationSet.type == C.TRACK_TYPE_AUDIO
                if (!isVideo && !isAudio) continue

                // Select representation matching currently selected track format ID if available
                val representation = resolveActiveRepresentation(adaptationSet, selectedFormatIds) ?: continue
                val baseUri = representation.baseUrls.firstOrNull()?.url ?: ""

                // 1. Initialization Segment
                var initCacheKey: String? = null
                representation.initializationUri?.let { initRangedUri ->
                    val initUri = initRangedUri.resolveUri(baseUri)
                    val initLength = if (initRangedUri.length > 0) initRangedUri.length else C.LENGTH_UNSET.toLong()
                    val initSpec = DataSpec.Builder()
                        .setUri(initUri)
                        .setPosition(initRangedUri.start)
                        .setLength(initLength)
                        .setHttpRequestHeaders(defaultHeaders)
                        .build()

                    val initBytes = if (initLength > 0) initLength else 128 * 1024L
                    if (scheduledBytes + initBytes <= availableBudgetBytes) {
                        scheduledBytes += initBytes
                        val identity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
                        initCacheKey = identity.cacheKey
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
                }

                // 2. Media Segments
                val index: DashSegmentIndex? = representation.index
                if (index != null) {
                    val firstSegmentNum = index.firstSegmentNum
                    val segmentCount = index.getSegmentCount(periodDurationUs)
                    var liveSegmentsScheduled = 0

                    if (segmentCount != DashSegmentIndex.INDEX_UNBOUNDED.toLong() && segmentCount > 0) {
                        for (segNum in firstSegmentNum until (firstSegmentNum + segmentCount)) {
                            val timeUs = index.getTimeUs(segNum)
                            val duration = index.getDurationUs(segNum, periodDurationUs)
                            val endTimeUs = timeUs + duration

                            // Bound live streams
                            if (isDynamic && timeUs > snapshot.currentPositionUs) {
                                if (liveSegmentsScheduled >= 3) break
                            }

                            if (endTimeUs > prefetchStartUs) {
                                val rangedUri = index.getSegmentUrl(segNum)
                                val segUri = rangedUri.resolveUri(baseUri)
                                val segLength = if (rangedUri.length > 0) rangedUri.length else C.LENGTH_UNSET.toLong()
                                val spec = DataSpec.Builder()
                                    .setUri(segUri)
                                    .setPosition(rangedUri.start)
                                    .setLength(segLength)
                                    .setHttpRequestHeaders(defaultHeaders)
                                    .build()

                                val estBytes = if (segLength > 0) {
                                    segLength
                                } else {
                                    val bitrate = representation.format.bitrate.takeIf { it > 0 } ?: 2_000_000
                                    (duration * bitrate) / 8_000_000L
                                }

                                if (scheduledBytes + estBytes > availableBudgetBytes) {
                                    break
                                }
                                scheduledBytes += estBytes

                                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                                val priority = when {
                                    timeUs <= prefetchStartUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                                    timeUs <= prefetchStartUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
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
                                    dependencyKeys = listOfNotNull(initCacheKey),
                                )

                                if (isDynamic) {
                                    liveSegmentsScheduled++
                                }
                            }
                        }
                    }
                }
            }
        }

        return requests
    }

    private fun resolveActiveRepresentation(
        adaptationSet: AdaptationSet,
        selectedFormatIds: Set<String>,
    ): Representation? {
        if (selectedFormatIds.isNotEmpty()) {
            val matched = adaptationSet.representations.firstOrNull { it.format.id in selectedFormatIds }
            if (matched != null) return matched
        }
        return adaptationSet.representations.firstOrNull()
    }

    override fun release() {
        adapterState = AdapterState.RELEASED
    }
}
