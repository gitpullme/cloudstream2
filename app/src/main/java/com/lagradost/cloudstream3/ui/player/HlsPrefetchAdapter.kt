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

    private var hasSeparateAudio: Boolean = false
    private val cachedAudioPlaylists = ConcurrentHashMap<Uri, HlsMediaPlaylist>()

    override fun hasSeparateAudioTrack(): Boolean = hasSeparateAudio

    internal fun setAudioPlaylistForTesting(uri: Uri, playlist: HlsMediaPlaylist) {
        cachedAudioPlaylists[uri] = playlist
        hasSeparateAudio = true
    }

    companion object {
        private const val TAG = "HlsPrefetch"
        private const val NEAR_PLAYHEAD_US = 15_000_000L // 15s
        private const val FORWARD_RUNWAY_US = 60_000_000L // 60s
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

                // Schedule segment AES-128 encryption key if present and not yet scheduled
                segment.fullSegmentEncryptionKeyUri?.let { rawKeyUri ->
                    if (scheduledVideoKeys.add(rawKeyUri)) {
                        val keyUri = UriUtil.resolveToUri(baseUri, rawKeyUri)
                        val keySpec = DataSpec.Builder()
                            .setUri(keyUri)
                            .setHttpRequestHeaders(defaultHeaders)
                            .build()
                        val keyIdentity = PrefetchRequestIdentity.fromDataSpec(keySpec, cacheKeyFactory, defaultHeaders)
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
                )

                if (!isVod) {
                    liveSegmentsScheduled++
                }
            }
        }

        // 3. Audio Rendition Segments & Encryption Keys (Separate Audio Renditions)
        val audioRenditions = multivariant?.audios.orEmpty()
        val audioRendition = resolveSelectedAudioRendition(audioRenditions, snapshot.currentTracks)
        val audioPlaylist = audioRendition?.url?.let { fetchAudioPlaylist(it) }
        hasSeparateAudio = audioPlaylist != null

        if (audioPlaylist != null) {
            val audioBaseUri = audioPlaylist.baseUri
            // Audio init segment
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

            val scheduledAudioKeys = mutableSetOf<String>()
            for (audioSeg in audioPlaylist.segments) {
                val segStartUs = audioSeg.relativeStartTimeUs + timelineOffsetUs
                val segDurationUs = audioSeg.durationUs
                val segEndUs = segStartUs + segDurationUs

                if (segEndUs > prefetchStartUs) {
                    audioSeg.fullSegmentEncryptionKeyUri?.let { rawKeyUri ->
                        if (scheduledAudioKeys.add(rawKeyUri)) {
                            val keyUri = UriUtil.resolveToUri(audioBaseUri, rawKeyUri)
                            val keySpec = DataSpec.Builder()
                                .setUri(keyUri)
                                .setHttpRequestHeaders(defaultHeaders)
                                .build()
                            val keyIdentity = PrefetchRequestIdentity.fromDataSpec(keySpec, cacheKeyFactory, defaultHeaders)
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
                    if (scheduledBytes + estAudioBytes > availableBudgetBytes) break
                    scheduledBytes += estAudioBytes

                    val identity = PrefetchRequestIdentity.fromDataSpec(spec, cacheKeyFactory, defaultHeaders)
                    val priority = when {
                        segStartUs <= prefetchStartUs + NEAR_PLAYHEAD_US -> PrefetchRequest.Priority.NEXT_MEDIA
                        segStartUs <= prefetchStartUs + FORWARD_RUNWAY_US -> PrefetchRequest.Priority.FORWARD_MEDIA
                        else -> PrefetchRequest.Priority.USEFUL_PREFETCH
                    }

                    requests += PrefetchRequest(
                        identity = identity,
                        priority = priority,
                        mediaTimeUs = segStartUs,
                        durationUs = segDurationUs,
                        isInit = false,
                        trackType = C.TRACK_TYPE_AUDIO,
                        dataSpec = spec,
                    )
                }
            }
        }

        maybeLogDiagnostics(mediaPlaylist, snapshot, upcomingCount, firstScheduledUs)

        // Sort requests: init requests first, then by presentation time so audio and video stay in sync
        return requests.sortedWith(
            compareBy<PrefetchRequest> { !it.isInit }
                .thenBy { it.mediaTimeUs }
        )
    }

    private fun resolveSelectedAudioRendition(
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
        return audios.firstOrNull { it.url != null }
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
            Log.w(TAG, "Failed to load audio playlist $audioUri: ${t.message}")
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
                "hasSeparateAudio=$hasSeparateAudio"
        )
    }
}
