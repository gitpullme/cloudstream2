package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Audio rendition topology for HLS presentations.
 *
 * Retained for diagnostics/back-compat; the coordinator now consumes [AudioDependency] so that
 * "audio required but playlist unavailable" can never be mistaken for muxed audio.
 */
enum class HlsAudioTopology {
    MUXED_AUDIO_VIDEO,
    SEPARATE_AUDIO_REQUIRED,
    SEPARATE_AUDIO_READY,
    SEPARATE_AUDIO_UNAVAILABLE,
    NO_AUDIO_TRACK
}

/**
 * Robust HLS VOD and sliding-window live segment prefetch adapter.
 * Uses immutable player snapshots to extract the active media playlist without thread violations.
 * Handles dual-track audio/video prefetching, initialization segments (EXT-X-MAP), and AES-128 encryption keys.
 */
@OptIn(UnstableApi::class)
class HlsPrefetchAdapter(
    val defaultHeaders: Map<String, String> = emptyMap(),
    val upstreamFactory: DataSource.Factory? = null,
    val cacheKeyFactory: (DataSpec) -> String,
) : PrefetchAdapter {

    override val name: String = "HLS"

    var adapterState: AdapterState = AdapterState.UNRESOLVED
        private set

    override val state: AdapterState
        get() = adapterState

    var audioTopology: HlsAudioTopology = HlsAudioTopology.MUXED_AUDIO_VIDEO
        private set

    // ---- Explicit audio topology (Part D) ----
    @Volatile
    var requiresSeparateAudio: Boolean = false
        private set

    @Volatile
    var selectedAudioRenditionIdentity: String? = null
        private set

    @Volatile
    var audioPlaylistReady: Boolean = false
        private set

    @Volatile
    var audioInitializationReady: Boolean = false
        private set

    @Volatile
    var audioSegmentsCoveredUntilUs: Long = 0L
        private set

    @Volatile
    var audioFailureReason: String? = null
        private set

    /** True once the muxed/separate decision has actually been evaluated at least once. */
    @Volatile
    private var topologyResolved: Boolean = false

    /**
     * True when audio depends on a separate rendition that is confirmed usable.
     * Note this is intentionally NOT a test of "some audio exists".
     */
    override fun hasSeparateAudioTrack(): Boolean =
        audioTopology == HlsAudioTopology.SEPARATE_AUDIO_READY

    override val audioDependency: AudioDependency
        get() = when {
            !topologyResolved -> AudioDependency.UNKNOWN
            requiresSeparateAudio && audioPlaylistReady -> AudioDependency.SEPARATE_READY
            requiresSeparateAudio -> AudioDependency.SEPARATE_UNAVAILABLE
            audioTopology == HlsAudioTopology.NO_AUDIO_TRACK -> AudioDependency.NONE
            else -> AudioDependency.MUXED
        }

    private val cachedAudioPlaylists = ConcurrentHashMap<Uri, HlsMediaPlaylist>()

    internal fun setAudioPlaylistForTesting(uri: Uri, playlist: HlsMediaPlaylist) {
        cachedAudioPlaylists[uri] = playlist
        requiresSeparateAudio = true
        audioPlaylistReady = true
        topologyResolved = true
        audioTopology = HlsAudioTopology.SEPARATE_AUDIO_READY
    }

    companion object {
        private const val TAG = "HlsPrefetch"
        private const val NEAR_PLAYHEAD_US = 15_000_000L // 15s
        private const val FORWARD_RUNWAY_US = 60_000_000L // 60s
    }

    override suspend fun isSupported(sourceUri: Uri, mimeType: String?, manifest: Any?): Boolean {
        val matchesMime = mimeType == MimeTypes.APPLICATION_M3U8
        val path = sourceUri.path?.lowercase() ?: ""
        val fullUri = sourceUri.toString().lowercase()
        val matchesExt = path.endsWith(".m3u8") || path.contains(".m3u8") || fullUri.contains(".m3u8")
        val hasManifest = manifest is HlsManifest || manifest is HlsMediaPlaylist

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
        if (manifest is HlsManifest || manifest is HlsMediaPlaylist) {
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
        val mediaPlaylist = when (val m = snapshot.currentManifest) {
            is HlsManifest -> m.mediaPlaylist
            is HlsMediaPlaylist -> m
            else -> null
        } ?: return emptyList()

        if (availableBudgetBytes <= 0L) return emptyList()

        val multivariant = (snapshot.currentManifest as? HlsManifest)?.multivariantPlaylist
        val isVod = mediaPlaylist.hasEndTag ||
            mediaPlaylist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD

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

        // 1. Video Initialization Segment (EXT-X-MAP)
        var videoInitKey: String? = null
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
            videoInitKey = initIdentity.cacheKey
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

        // 2. Video Media Segments & Encryption Keys
        val timelineOffsetUs = if (isVod) {
            0L
        } else {
            if (snapshot.windowPositionInPeriodUs > 0L && mediaPlaylist.startTimeUs > 0L && !mediaPlaylist.hasProgramDateTime) {
                mediaPlaylist.startTimeUs - snapshot.windowPositionInPeriodUs
            } else {
                0L
            }
        }

        var liveSegmentsScheduled = 0
        var firstScheduledUs: Long? = null
        var upcomingCount = 0

        val scheduledVideoKeys = mutableSetOf<String>()

        for (segment in mediaPlaylist.segments) {
            val segmentPresentationStartUs = segment.relativeStartTimeUs + timelineOffsetUs
            val segmentDurationUs = segment.durationUs
            val segmentPresentationEndUs = segmentPresentationStartUs + segmentDurationUs

            if (!isVod && segmentPresentationStartUs > snapshot.currentPositionUs) {
                if (liveSegmentsScheduled >= 3) break
            }

            if (segmentPresentationEndUs > prefetchStartUs) {
                if (firstScheduledUs == null) {
                    firstScheduledUs = segmentPresentationStartUs
                }
                upcomingCount++

                var segKeyCacheKey: String? = null
                // Schedule segment AES-128 encryption key if present and not yet scheduled
                segment.fullSegmentEncryptionKeyUri?.let { rawKeyUri ->
                    val keyUri = UriUtil.resolveToUri(baseUri, rawKeyUri)
                    val keySpec = DataSpec.Builder()
                        .setUri(keyUri)
                        .setHttpRequestHeaders(defaultHeaders)
                        .build()
                    val keyIdentity = PrefetchRequestIdentity.fromDataSpec(keySpec, cacheKeyFactory, defaultHeaders)
                    segKeyCacheKey = keyIdentity.cacheKey

                    if (scheduledVideoKeys.add(rawKeyUri)) {
                        requests += PrefetchRequest(
                            identity = keyIdentity,
                            priority = PrefetchRequest.Priority.IMMEDIATE,
                            mediaTimeUs = segmentPresentationStartUs,
                            durationUs = 0L,
                            isInit = true,
                            trackType = C.TRACK_TYPE_VIDEO,
                            dataSpec = keySpec,
                        )
                    }
                }

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

                if (scheduledBytes + estBytes > availableBudgetBytes) {
                    break
                }
                scheduledBytes += estBytes

                val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                val priority = when {
                    segmentPresentationStartUs <= prefetchStartUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                    segmentPresentationStartUs <= prefetchStartUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
                    else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                }

                requests += PrefetchRequest(
                    identity = identity,
                    priority = priority,
                    mediaTimeUs = segmentPresentationStartUs,
                    durationUs = segmentDurationUs,
                    isInit = false,
                    trackType = C.TRACK_TYPE_VIDEO,
                    dataSpec = spec,
                    dependencyKeys = listOfNotNull(videoInitKey, segKeyCacheKey),
                )

                if (!isVod) {
                    liveSegmentsScheduled++
                }
            }
        }

        // 3. Audio Rendition Segments & Encryption Keys (Separate Audio Renditions)
        val audioRenditions = multivariant?.audios.orEmpty()
        val audioRendition = resolveSelectedAudioRendition(audioRenditions, snapshot.currentTracks)
        topologyResolved = true

        if (audioRendition != null) {
            selectedAudioRenditionIdentity = audioRendition.name ?: audioRendition.url?.toString()
            val audioUrl = audioRendition.url
            if (audioUrl == null) {
                // Rendition without its own URI: audio is muxed into the variant stream.
                requiresSeparateAudio = false
                audioPlaylistReady = false
                audioFailureReason = null
                audioTopology = HlsAudioTopology.MUXED_AUDIO_VIDEO
            } else {
                requiresSeparateAudio = true
                audioPlaylistReady = false
                audioFailureReason = null
                audioTopology = HlsAudioTopology.SEPARATE_AUDIO_REQUIRED
                val audioPlaylist = fetchAudioPlaylist(audioUrl)
                if (audioPlaylist != null) {
                    audioPlaylistReady = true
                    audioTopology = HlsAudioTopology.SEPARATE_AUDIO_READY
                    val audioBaseUri = audioPlaylist.baseUri

                    // Audio init segment
                    var audioInitKey: String? = null
                    audioPlaylist.segments.firstOrNull()?.initializationSegment?.let { audioInit ->
                        val initUri = UriUtil.resolveToUri(audioBaseUri, audioInit.url)
                        val initLength = if (audioInit.byteRangeLength > 0) audioInit.byteRangeLength else C.LENGTH_UNSET.toLong()
                        val initSpec = DataSpec.Builder()
                            .setUri(initUri)
                            .setPosition(audioInit.byteRangeOffset)
                            .setLength(initLength)
                            .setHttpRequestHeaders(defaultHeaders)
                            .build()
                        val initIdentity = PrefetchRequestIdentity.fromDataSpec(initSpec, cacheKeyFactory, defaultHeaders)
                        audioInitKey = initIdentity.cacheKey
                        audioInitializationReady = true
                        requests += PrefetchRequest(
                            identity = initIdentity,
                            priority = PrefetchRequest.Priority.IMMEDIATE,
                            mediaTimeUs = 0L,
                            durationUs = 0L,
                            isInit = true,
                            trackType = C.TRACK_TYPE_AUDIO,
                            dataSpec = initSpec,
                        )
                    }

                    val audioTimelineOffsetUs = if (isVod) {
                        0L
                    } else {
                        if (snapshot.windowPositionInPeriodUs > 0L && audioPlaylist.startTimeUs > 0L && !audioPlaylist.hasProgramDateTime) {
                            audioPlaylist.startTimeUs - snapshot.windowPositionInPeriodUs
                        } else {
                            0L
                        }
                    }

                    val scheduledAudioKeys = mutableSetOf<String>()
                    var audioCoveredUntilUs = audioSegmentsCoveredUntilUs
                    for (audioSeg in audioPlaylist.segments) {
                        val segStartUs = audioSeg.relativeStartTimeUs + audioTimelineOffsetUs
                        val segDurationUs = audioSeg.durationUs
                        val segEndUs = segStartUs + segDurationUs

                        if (segEndUs > prefetchStartUs) {
                            var audioSegKeyCacheKey: String? = null
                            audioSeg.fullSegmentEncryptionKeyUri?.let { rawKeyUri ->
                                val keyUri = UriUtil.resolveToUri(audioBaseUri, rawKeyUri)
                                val keySpec = DataSpec.Builder()
                                    .setUri(keyUri)
                                    .setHttpRequestHeaders(defaultHeaders)
                                    .build()
                                val keyIdentity = PrefetchRequestIdentity.fromDataSpec(keySpec, cacheKeyFactory, defaultHeaders)
                                audioSegKeyCacheKey = keyIdentity.cacheKey

                                if (scheduledAudioKeys.add(rawKeyUri)) {
                                    requests += PrefetchRequest(
                                        identity = keyIdentity,
                                        priority = PrefetchRequest.Priority.IMMEDIATE,
                                        mediaTimeUs = segStartUs,
                                        durationUs = 0L,
                                        isInit = true,
                                        trackType = C.TRACK_TYPE_AUDIO,
                                        dataSpec = keySpec,
                                    )
                                }
                            }

                            val segUri = UriUtil.resolveToUri(audioBaseUri, audioSeg.url)
                            val segLength = if (audioSeg.byteRangeLength > 0) audioSeg.byteRangeLength else C.LENGTH_UNSET.toLong()
                            val spec = DataSpec.Builder()
                                .setUri(segUri)
                                .setPosition(audioSeg.byteRangeOffset)
                                .setLength(segLength)
                                .setHttpRequestHeaders(defaultHeaders)
                                .build()

                            val estAudioBytes = if (segLength > 0) segLength else 64 * 1024L
                            if (scheduledBytes + estAudioBytes > availableBudgetBytes) {
                                break
                            }
                            scheduledBytes += estAudioBytes

                            val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                            val priority = when {
                                segStartUs <= prefetchStartUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                                segStartUs <= prefetchStartUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
                                else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                            }

                            audioCoveredUntilUs = maxOf(audioCoveredUntilUs, segEndUs)

                            requests += PrefetchRequest(
                                identity = identity,
                                priority = priority,
                                mediaTimeUs = segStartUs,
                                durationUs = segDurationUs,
                                isInit = false,
                                trackType = C.TRACK_TYPE_AUDIO,
                                dataSpec = spec,
                                dependencyKeys = listOfNotNull(audioInitKey, audioSegKeyCacheKey),
                            )
                        }
                    }
                    audioSegmentsCoveredUntilUs = audioCoveredUntilUs
                } else {
                    // Audio is required but its playlist is not usable. This is NOT muxed audio and
                    // offline coverage must not advance past the playhead.
                    audioPlaylistReady = false
                    audioInitializationReady = false
                    audioFailureReason = "audio playlist unavailable for ${PlaybackCacheAudit.safeUriPath(audioUrl)}"
                    audioTopology = HlsAudioTopology.SEPARATE_AUDIO_UNAVAILABLE
                    Log.w(TAG, "Separate audio required but playlist fetch failed; coverage will not advance")
                }
            }
        } else {
            // No selected rendition, but the presentation declares audio renditions: audio is
            // required and we cannot verify it. Never report video-only coverage.
            requiresSeparateAudio = audioRenditions.isNotEmpty()
            audioPlaylistReady = false
            audioInitializationReady = false
            if (requiresSeparateAudio) {
                audioFailureReason = "declared audio renditions but no selected rendition could be resolved"
                audioTopology = HlsAudioTopology.SEPARATE_AUDIO_UNAVAILABLE
            } else {
                audioFailureReason = null
                audioTopology = HlsAudioTopology.MUXED_AUDIO_VIDEO
            }
        }

        maybeLogDiagnostics(mediaPlaylist, snapshot, upcomingCount, firstScheduledUs)

        // Sort requests: init requests first, then by presentation time so audio and video stay in sync
        return requests.sortedWith(
            compareBy<PrefetchRequest> { !it.isInit }
                .thenBy { it.mediaTimeUs }
        )
    }

    internal fun resolveSelectedAudioRendition(
        audios: List<HlsMultivariantPlaylist.Rendition>,
        currentTracks: Tracks?,
    ): HlsMultivariantPlaylist.Rendition? {
        if (audios.isEmpty()) return null
        if (currentTracks != null) {
            for (group in currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val format = group.getTrackFormat(i)
                            val match = audios.firstOrNull { rendition ->
                                (format.id != null && rendition.format.id == format.id) ||
                                    (format.label != null && rendition.name.equals(format.label, ignoreCase = true)) ||
                                    (format.language != null && rendition.format.language.equals(format.language, ignoreCase = true))
                            }
                            if (match != null) return match
                        }
                    }
                }
            }
        }
        val defaultRendition = audios.firstOrNull { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
            ?: audios.firstOrNull { it.url != null }
        return defaultRendition
    }

    private suspend fun fetchAudioPlaylist(audioUri: Uri): HlsMediaPlaylist? = withContext(Dispatchers.IO) {
        cachedAudioPlaylists[audioUri]?.let { return@withContext it }
        val factory = upstreamFactory ?: return@withContext null
        try {
            val dataSpec = DataSpec.Builder()
                .setUri(audioUri)
                .setHttpRequestHeaders(defaultHeaders)
                .build()
            val dataSource = factory.createDataSource()
            val inputStream = DataSourceInputStream(dataSource, dataSpec)
            val playlist = inputStream.use { input ->
                HlsPlaylistParser().parse(audioUri, input)
            }
            if (playlist is HlsMediaPlaylist) {
                cachedAudioPlaylists[audioUri] = playlist
                playlist
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load audio playlist ${PlaybackCacheAudit.safeUriPath(audioUri)}: ${t.message}")
            null
        }
    }

    private var lastLogTimeMs = 0L
    private fun maybeLogDiagnostics(
        mediaPlaylist: HlsMediaPlaylist,
        snapshot: PlaybackPlayerSnapshot,
        upcomingCount: Int,
        firstScheduledSegmentTimeUs: Long?,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs < 3000L) return
        lastLogTimeMs = now

        Log.d(
            TAG,
            "HLS Timeline: hasProgramDateTime=${mediaPlaylist.hasProgramDateTime}, rawStartTimeUs=${mediaPlaylist.startTimeUs}, " +
                "durationUs=${mediaPlaylist.durationUs}, playerPosMs=${snapshot.currentPositionMs}, bufferedPosMs=${snapshot.bufferedPositionMs}, " +
                "segmentsTotal=${mediaPlaylist.segments.size}, upcomingScheduled=$upcomingCount, firstScheduledUs=$firstScheduledSegmentTimeUs, " +
                "audioTopology=$audioTopology audioDependency=$audioDependency " +
                "requiresSeparateAudio=$requiresSeparateAudio audioPlaylistReady=$audioPlaylistReady " +
                "audioFailure=${audioFailureReason ?: "none"}"
        )
    }

    override fun release() {
        adapterState = AdapterState.RELEASED
        cachedAudioPlaylists.clear()
    }
}
