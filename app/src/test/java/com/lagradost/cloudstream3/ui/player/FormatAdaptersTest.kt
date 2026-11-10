package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.google.common.collect.ImmutableList
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.mp4.TrackSampleTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FormatAdaptersTest {

    private val baseHlsUri = "https://cdn.example.com/hls/"
    private val mp4Uri = Uri.parse("https://cdn.example.com/video.mp4")
    private val defaultKeyFactory: (DataSpec) -> String = { it.key ?: it.uri.toString() }

    private fun createSnapshot(
        currentPositionMs: Long = 0L,
        bufferedPositionMs: Long = 0L,
        durationMs: Long = 12_000L,
        manifest: Any? = null,
        sourceUri: Uri = Uri.parse(baseHlsUri + "master.m3u8"),
        selectedMime: String? = null,
        isLive: Boolean = false,
    ) = PlaybackPlayerSnapshot(
        currentPositionMs = currentPositionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        playbackState = Player.STATE_READY,
        isPlaying = true,
        playWhenReady = true,
        playbackSpeed = 1.0f,
        currentManifest = manifest,
        sourceUri = sourceUri,
        selectedMime = selectedMime,
        isLive = isLive,
        currentTracks = null,
    )

    @Test
    fun `HLS VOD adapter schedules initialization chunk and upcoming media segments`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser().parse(
            Uri.parse(baseHlsUri + "playlist.m3u8"),
            m3u8.byteInputStream(Charsets.UTF_8)
        )

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        assertTrue(adapter.isSupported(Uri.parse(baseHlsUri + "master.m3u8"), MimeTypes.APPLICATION_M3U8, playlist))

        val snapshot = createSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 12_000L,
            manifest = playlist,
        )

        val requests = adapter.getUpcomingRequests(
            snapshot = snapshot,
            availableBudgetBytes = 50L * 1024L * 1024L,
        )

        // Init chunk + seg1 + seg2
        assertEquals(3, requests.size)
        val initReq = requests.first()
        assertTrue(initReq.isInit)
        assertEquals(PrefetchRequest.Priority.IMMEDIATE, initReq.priority)
        assertTrue(initReq.identity.uri.toString().endsWith("init.mp4"))

        val mediaReq1 = requests[1]
        assertFalse(mediaReq1.isInit)
        assertEquals(PrefetchRequest.Priority.NEXT_MEDIA, mediaReq1.priority)
        assertTrue(mediaReq1.identity.uri.toString().endsWith("seg1.ts"))
    }

    @Test
    fun `HLS adapter starts prefetch after buffered position to avoid duplicate requests`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:7
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
            #EXTINF:6.0,
            seg3.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser().parse(
            Uri.parse(baseHlsUri + "playlist.m3u8"),
            m3u8.byteInputStream(Charsets.UTF_8)
        )

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        // Media3 has already buffered up to 6.5s (seg1 is buffered)
        val snapshot = createSnapshot(
            currentPositionMs = 1_000L,
            bufferedPositionMs = 6_500L,
            durationMs = 18_000L,
            manifest = playlist,
        )

        val requests = adapter.getUpcomingRequests(snapshot, availableBudgetBytes = 50L * 1024L * 1024L)

        // seg1 is skipped because bufferedPosition is 6.5s
        assertEquals(2, requests.size)
        assertTrue(requests[0].identity.uri.toString().endsWith("seg2.ts"))
        assertTrue(requests[1].identity.uri.toString().endsWith("seg3.ts"))
    }

    @Test
    fun `DASH adapter schedules representation segments matching active track`() = runBlocking {
        val adapter = DashPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        val template = SegmentBase.SegmentTemplate(
            RangedUri("init.mp4", 0L, 1024L),
            1000L,
            0L,
            0L,
            3L,
            2000L,
            null,
            0L,
            null,
            androidx.media3.exoplayer.dash.manifest.UrlTemplate.compile("seg_\$Number\$.mp4"),
            0L,
            0L,
        )

        val representation = Representation.newInstance(
            1L,
            Format.Builder().setId("video_high").setSampleMimeType(MimeTypes.VIDEO_MP4).setAverageBitrate(2_000_000).build(),
            listOf(BaseUrl("https://cdn.example.com/dash/")),
            template,
        )

        val adaptationSet = AdaptationSet(
            0L,
            C.TRACK_TYPE_VIDEO,
            listOf(representation),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        val period = Period("0", 0L, listOf(adaptationSet))
        val dashManifest = DashManifest(
            0L,
            6_000L,
            1_500L,
            false,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            0L,
            null,
            null,
            null,
            Uri.parse("https://cdn.example.com/dash/manifest.mpd"),
            listOf(period),
        )

        val snapshot = createSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 6_000L,
            manifest = dashManifest,
            sourceUri = Uri.parse("https://cdn.example.com/dash/manifest.mpd"),
            selectedMime = MimeTypes.APPLICATION_MPD,
        )

        val requests = adapter.getUpcomingRequests(snapshot, availableBudgetBytes = 50L * 1024L * 1024L)
        assertTrue(requests.isNotEmpty())
        assertEquals(0L, requests[0].mediaTimeUs)
    }

    @Test
    fun `MP4 range adapter verifies 206 response and container index for true byte-to-time mapping`() = runBlocking {
        val totalLength = 5L * 1024L * 1024L
        // Create synthetic sidx box data (32 bytes header + 2 * 12 bytes = 56 bytes)
        val sidxBuffer = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN)
        sidxBuffer.putInt(56) // box size
        sidxBuffer.putInt(0x73696478) // "sidx"
        sidxBuffer.put(0.toByte()) // version 0
        sidxBuffer.put(0.toByte()); sidxBuffer.put(0.toByte()); sidxBuffer.put(0.toByte()) // flags
        sidxBuffer.putInt(1) // referenceId
        sidxBuffer.putInt(1000) // timescale: 1000 units/sec
        sidxBuffer.putInt(0) // earliestPresentationTime
        sidxBuffer.putInt(1024) // firstOffset
        sidxBuffer.putShort(0.toShort()) // reserved
        sidxBuffer.putShort(2.toShort()) // referenceCount: 2 subsegments

        // Subsegment 1
        sidxBuffer.putInt(1024 * 1024) // 1 MB
        sidxBuffer.putInt(2000) // 2 sec duration
        sidxBuffer.putInt(0)

        // Subsegment 2
        sidxBuffer.putInt(1024 * 1024) // 1 MB
        sidxBuffer.putInt(2000) // 2 sec duration
        sidxBuffer.putInt(0)

        val fakeBytes = sidxBuffer.array()

        val fakeDataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = fakeBytes.size.toLong()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val toRead = minOf(length, fakeBytes.size)
                System.arraycopy(fakeBytes, 0, buffer, offset, toRead)
                return toRead
            }
            override fun getUri(): Uri = mp4Uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "Content-Range" to listOf("bytes 0-${fakeBytes.size - 1}/$totalLength"),
                "Accept-Ranges" to listOf("bytes"),
            )
            override fun close() = Unit
        }
        val factory = DataSource.Factory { fakeDataSource }

        val adapter = Mp4RangePrefetchAdapter(
            sourceUri = mp4Uri,
            upstreamFactory = factory,
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        assertTrue(adapter.isSupported(mp4Uri, "video/mp4"))

        val snapshot = createSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 4000L,
            sourceUri = mp4Uri,
            selectedMime = "video/mp4",
        )

        val requests = adapter.getUpcomingRequests(snapshot, availableBudgetBytes = 50L * 1024L * 1024L)
        assertEquals(2, requests.size)
        assertEquals(0L, requests[0].mediaTimeUs)
        // ISO BMFF sidx first_offset is relative to the anchor point (byte immediately following the sidx box)
        assertEquals(1080L, requests[0].dataSpec.position)
        assertEquals(1024L * 1024L, requests[0].dataSpec.length)
    }

    @Test
    fun `MP4 range adapter detects server ignoring Range header and safely falls back`() = runBlocking {
        // Server returns 200 without Content-Range
        val fakeDataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = 5000L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
            override fun getUri(): Uri = mp4Uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "Content-Type" to listOf("video/mp4")
            )
            override fun close() = Unit
        }
        val factory = DataSource.Factory { fakeDataSource }

        val adapter = Mp4RangePrefetchAdapter(
            sourceUri = mp4Uri,
            upstreamFactory = factory,
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        val supported = adapter.isSupported(mp4Uri, "video/mp4")
        assertFalse("Server that ignores range requests must be reported as unsupported", supported)

        val snapshot = createSnapshot(sourceUri = mp4Uri, selectedMime = "video/mp4")
        val requests = adapter.getUpcomingRequests(snapshot, availableBudgetBytes = 50L * 1024L * 1024L)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `HLS VOD prefetch starts strictly from current playhead across different timestamps without downloading segment 0`() = runBlocking {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-VERSION:7\n")
        for (i in 0 until 200) {
            sb.append("#EXTINF:6.0,\nseg_$i.ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")

        val playlist = HlsPlaylistParser().parse(
            Uri.parse(baseHlsUri + "playlist.m3u8"),
            sb.toString().byteInputStream(Charsets.UTF_8)
        )

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        // 1. Playback starting at 0s
        val snap0 = createSnapshot(currentPositionMs = 0L, bufferedPositionMs = 0L, durationMs = 1200_000L, manifest = playlist)
        val reqs0 = adapter.getUpcomingRequests(snap0, 50L * 1024L * 1024L)
        assertTrue(reqs0.isNotEmpty())
        assertTrue(reqs0[0].identity.uri.toString().endsWith("seg_0.ts"))
        assertEquals(0L, reqs0[0].mediaTimeUs)

        // 2. Playback starting at 5 minutes (300 seconds)
        val snap300 = createSnapshot(currentPositionMs = 300_000L, bufferedPositionMs = 300_000L, durationMs = 1200_000L, manifest = playlist)
        val reqs300 = adapter.getUpcomingRequests(snap300, 50L * 1024L * 1024L)
        assertTrue(reqs300.isNotEmpty())
        assertTrue(reqs300[0].identity.uri.toString().endsWith("seg_50.ts"))
        assertEquals(300_000_000L, reqs300[0].mediaTimeUs)
        assertFalse("Earlier segments must NOT be requested on mid-stream start", reqs300.any { it.identity.uri.toString().endsWith("seg_0.ts") })

        // 3. Playback starting at 15 minutes (900 seconds)
        val snap900 = createSnapshot(currentPositionMs = 900_000L, bufferedPositionMs = 900_000L, durationMs = 1200_000L, manifest = playlist)
        val reqs900 = adapter.getUpcomingRequests(snap900, 50L * 1024L * 1024L)
        assertTrue(reqs900.isNotEmpty())
        assertTrue(reqs900[0].identity.uri.toString().endsWith("seg_150.ts"))
        assertEquals(900_000_000L, reqs900[0].mediaTimeUs)
        assertFalse("Earlier segments must NOT be requested", reqs900.any { it.identity.uri.toString().endsWith("seg_0.ts") })

        // 4. Playback starting near the end (1190 seconds)
        val snap1190 = createSnapshot(currentPositionMs = 1190_000L, bufferedPositionMs = 1190_000L, durationMs = 1200_000L, manifest = playlist)
        val reqs1190 = adapter.getUpcomingRequests(snap1190, 50L * 1024L * 1024L)
        assertEquals(2, reqs1190.size)
        assertTrue(reqs1190[0].identity.uri.toString().endsWith("seg_198.ts"))
        assertTrue(reqs1190[1].identity.uri.toString().endsWith("seg_199.ts"))
    }

    @Test
    fun `HLS playlist with EXT-X-PROGRAM-DATE-TIME maps presentation times to window time instead of epoch time`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:7
            #EXT-X-PROGRAM-DATE-TIME:2026-11-10T12:00:00.000Z
            #EXTINF:6.0,
            seg_pdt_0.ts
            #EXTINF:6.0,
            seg_pdt_1.ts
            #EXTINF:6.0,
            seg_pdt_2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser().parse(
            Uri.parse(baseHlsUri + "playlist_pdt.m3u8"),
            m3u8.byteInputStream(Charsets.UTF_8)
        )

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        val snap = createSnapshot(currentPositionMs = 0L, bufferedPositionMs = 0L, durationMs = 18_000L, manifest = playlist)
        val reqs = adapter.getUpcomingRequests(snap, 50L * 1024L * 1024L)

        assertEquals(3, reqs.size)
        assertEquals(0L, reqs[0].mediaTimeUs)
        assertEquals(6_000_000L, reqs[1].mediaTimeUs)
        assertEquals(12_000_000L, reqs[2].mediaTimeUs)

        // Starting at 6s must skip seg_pdt_0
        val snapMid = createSnapshot(currentPositionMs = 6_500L, bufferedPositionMs = 6_500L, durationMs = 18_000L, manifest = playlist)
        val reqsMid = adapter.getUpcomingRequests(snapMid, 50L * 1024L * 1024L)
        assertEquals(2, reqsMid.size)
        assertTrue(reqsMid[0].identity.uri.toString().endsWith("seg_pdt_1.ts"))
    }

    @Test
    fun `HLS live sliding window aligns segment presentation time with player timeline window`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:7
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:6.0,
            live_100.ts
            #EXTINF:6.0,
            live_101.ts
            #EXTINF:6.0,
            live_102.ts
        """.trimIndent()

        val playlist = HlsPlaylistParser().parse(
            Uri.parse(baseHlsUri + "live.m3u8"),
            m3u8.byteInputStream(Charsets.UTF_8)
        )

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        val mediaPlaylist = playlist as HlsMediaPlaylist
        // Timeline window has windowPositionInPeriodUs = playlist.startTimeUs
        val snapLive = PlaybackPlayerSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 18_000L,
            playbackState = Player.STATE_READY,
            isPlaying = true,
            playWhenReady = true,
            playbackSpeed = 1.0f,
            currentManifest = playlist,
            sourceUri = Uri.parse(baseHlsUri + "live.m3u8"),
            selectedMime = MimeTypes.APPLICATION_M3U8,
            isLive = true,
            currentTracks = null,
            windowPositionInPeriodUs = mediaPlaylist.startTimeUs,
            windowDurationUs = 18_000_000L,
        )

        val reqs = adapter.getUpcomingRequests(snapLive, 50L * 1024L * 1024L)
        assertEquals(3, reqs.size)
        assertEquals(0L, reqs[0].mediaTimeUs)
        assertEquals(6_000_000L, reqs[1].mediaTimeUs)
    }

    @Test
    fun `HLS adapter schedules both active video and separate audio rendition when multivariant playlist has audio rendition`() = runBlocking {
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="audio"
            video.m3u8
        """.trimIndent()

        val videoM3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:4
            #EXTINF:6.0,
            video_0.ts
            #EXTINF:6.0,
            video_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val audioM3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:4
            #EXTINF:6.0,
            audio_0.aac
            #EXTINF:6.0,
            audio_1.aac
            #EXT-X-ENDLIST
        """.trimIndent()

        val baseUri = "https://cdn.example.com/hls/"
        val masterPlaylist = HlsPlaylistParser().parse(
            Uri.parse(baseUri + "master.m3u8"),
            masterM3u8.byteInputStream()
        ) as HlsMultivariantPlaylist

        val videoPlaylist = HlsPlaylistParser().parse(
            Uri.parse(baseUri + "video.m3u8"),
            videoM3u8.byteInputStream()
        ) as HlsMediaPlaylist

        val hlsManifest = HlsManifest::class.java.declaredConstructors[0].apply {
            isAccessible = true
        }.newInstance(masterPlaylist, videoPlaylist) as HlsManifest

        val adapter = HlsPrefetchAdapter(
            defaultHeaders = emptyMap(),
            upstreamFactory = {
                object : DataSource {
                    var isOpened = false
                    override fun addTransferListener(transferListener: TransferListener) = Unit
                    override fun open(dataSpec: DataSpec): Long {
                        isOpened = true
                        return audioM3u8.length.toLong()
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!isOpened) return -1
                        val bytes = audioM3u8.toByteArray()
                        val toRead = minOf(length, bytes.size)
                        System.arraycopy(bytes, 0, buffer, offset, toRead)
                        isOpened = false
                        return toRead
                    }
                    override fun getUri(): Uri = Uri.parse(baseUri + "audio.m3u8")
                    override fun close() = Unit
                }
            },
            cacheKeyFactory = defaultKeyFactory,
        )

        val snapshot = PlaybackPlayerSnapshot(
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 12_000L,
            playbackState = Player.STATE_READY,
            isPlaying = true,
            playWhenReady = true,
            playbackSpeed = 1.0f,
            currentManifest = hlsManifest,
            sourceUri = Uri.parse(baseUri + "master.m3u8"),
            selectedMime = MimeTypes.APPLICATION_M3U8,
            isLive = false,
            currentTracks = null,
        )

        val reqs = adapter.getUpcomingRequests(snapshot, 50L * 1024L * 1024L)

        assertTrue(adapter.hasSeparateAudioTrack())
        assertEquals(4, reqs.size) // 2 video + 2 audio

        val videoReqs = reqs.filter { it.trackType == C.TRACK_TYPE_VIDEO }
        val audioReqs = reqs.filter { it.trackType == C.TRACK_TYPE_AUDIO }

        assertEquals(2, videoReqs.size)
        assertEquals(2, audioReqs.size)

        assertEquals("https://cdn.example.com/hls/video_0.ts", videoReqs[0].dataSpec.uri.toString())
        assertEquals("https://cdn.example.com/hls/audio_0.aac", audioReqs[0].dataSpec.uri.toString())
    }

    @Test
    fun `MP4 container parser successfully parses nested ISO BMFF container and leaf boxes`() {
        val adapter = Mp4RangePrefetchAdapter(
            sourceUri = mp4Uri,
            upstreamFactory = { throw UnsupportedOperationException() },
            cacheKeyFactory = defaultKeyFactory,
        )

        // Build synthetic binary moov box containing mvhd (leaf) and trak (container) containing tkhd (leaf)
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        // moov box: total size 80 bytes
        buffer.putInt(80)
        buffer.putInt(Mp4Box.TYPE_moov)

        // mvhd leaf box: size 32 bytes
        buffer.putInt(32)
        buffer.putInt(Mp4Box.TYPE_mvhd)
        buffer.put(ByteArray(24)) // 24 bytes payload

        // trak container box: size 40 bytes
        buffer.putInt(40)
        buffer.putInt(Mp4Box.TYPE_trak)

        // tkhd leaf box inside trak: size 32 bytes
        buffer.putInt(32)
        buffer.putInt(Mp4Box.TYPE_tkhd)
        buffer.put(ByteArray(24)) // 24 bytes payload

        val bytes = buffer.array()
        val moov = adapter.parseContainerBox(Mp4Box.TYPE_moov, bytes, 8, 80)

        assertEquals(Mp4Box.TYPE_moov, moov.type)
        assertNotNull(moov.getLeafBoxOfType(Mp4Box.TYPE_mvhd))
        val trak = moov.getContainerBoxOfType(Mp4Box.TYPE_trak)
        assertNotNull(trak)
        assertNotNull(trak!!.getLeafBoxOfType(Mp4Box.TYPE_tkhd))
    }

    @Test
    fun `MP4 adapter coalesces video TrackSampleTable into 1MB chunks and schedules from playhead`() = runBlocking {
        val adapter = Mp4RangePrefetchAdapter(
            sourceUri = mp4Uri,
            upstreamFactory = {
                object : DataSource {
                    override fun addTransferListener(transferListener: TransferListener) = Unit
                    override fun open(dataSpec: DataSpec): Long = 1000L
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun getUri(): Uri = mp4Uri
                    override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                        "Content-Range" to listOf("bytes 0-999/1000")
                    )
                    override fun close() = Unit
                }
            },
            cacheKeyFactory = defaultKeyFactory,
            chunkSize = 1024L * 1024L, // 1 MB chunks
        )

        val track = Track(
            1,
            C.TRACK_TYPE_VIDEO,
            1000L,
            1000L,
            2_000_000L,
            2_000_000L,
            Format.Builder().build(),
            0,
            null,
            0,
            null,
            null
        )

        // Create 50 samples of 40 KB each (total 2 MB), durations 33.3ms each (~1.66s total)
        val sampleCount = 50
        val offsets = LongArray(sampleCount)
        val sizes = IntArray(sampleCount)
        val timestampsUs = LongArray(sampleCount)
        val flags = IntArray(sampleCount)
        var currentOffset = 10_000L
        for (i in 0 until sampleCount) {
            offsets[i] = currentOffset
            sizes[i] = 40 * 1024
            timestampsUs[i] = i * 33_333L
            currentOffset += sizes[i]
        }

        val table = TrackSampleTable(
            track,
            offsets,
            sizes,
            40 * 1024,
            timestampsUs,
            flags,
            intArrayOf(0),
            false,
            sampleCount * 33_333L,
            sampleCount,
        )

        adapter.coalesceSamplesIntoSegments(table)

        // 50 samples * 40 KB = 2,000 KB ~= 2 chunks of ~1 MB
        assertEquals(2, adapter.parsedSegments.size)
        assertEquals(10_000L, adapter.parsedSegments[0].byteOffset)
        assertEquals(25 * 40 * 1024L, adapter.parsedSegments[0].byteLength)
        assertEquals(0L, adapter.parsedSegments[0].startTimeUs)

        // Second chunk starts immediately where the first ends
        assertEquals(adapter.parsedSegments[0].byteOffset + adapter.parsedSegments[0].byteLength, adapter.parsedSegments[1].byteOffset)

        // Test scheduling: from start (0s)
        val snap0 = createSnapshot(currentPositionMs = 0L, bufferedPositionMs = 0L, durationMs = 2000L, sourceUri = mp4Uri, selectedMime = "video/mp4")
        val reqs0 = adapter.getUpcomingRequests(snap0, 50L * 1024L * 1024L)
        val mediaReqs0 = reqs0.filter { !it.isInit }
        assertEquals(2, mediaReqs0.size)

        // From 1.0s: chunk 0 ended around 866ms, so chunk 0 is skipped and only chunk 1 is scheduled!
        val snapMid = createSnapshot(currentPositionMs = 1000L, bufferedPositionMs = 1000L, durationMs = 2000L, sourceUri = mp4Uri, selectedMime = "video/mp4")
        val reqsMid = adapter.getUpcomingRequests(snapMid, 50L * 1024L * 1024L)
        assertEquals(1, reqsMid.size)
        assertEquals(adapter.parsedSegments[1].byteOffset, reqsMid[0].dataSpec.position)
    }

    @Test
    fun `MP4 adapter discovers sidx box at tail of file when placed at the end`() = runBlocking {
        val totalLength = 2L * 1024L * 1024L // 2 MB file
        val sidxSize = 44
        val sidxBuffer = ByteBuffer.allocate(sidxSize).order(ByteOrder.BIG_ENDIAN)
        sidxBuffer.putInt(sidxSize)
        sidxBuffer.putInt(0x73696478) // "sidx"
        sidxBuffer.put(0.toByte()) // version 0
        sidxBuffer.put(0.toByte()); sidxBuffer.put(0.toByte()); sidxBuffer.put(0.toByte())
        sidxBuffer.putInt(1)
        sidxBuffer.putInt(1000)
        sidxBuffer.putInt(0)
        sidxBuffer.putInt(0) // firstOffset
        sidxBuffer.putShort(0.toShort())
        sidxBuffer.putShort(1.toShort()) // 1 subsegment
        sidxBuffer.putInt(1024 * 1024)
        sidxBuffer.putInt(2000)
        sidxBuffer.putInt(0)
        val sidxBytes = sidxBuffer.array()

        // Synthetic source where byte 0 has ftyp (32 bytes), mdat in between, and sidx at the tail
        val fakeDataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                this.dataSpec = dataSpec
                return dataSpec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val start = dataSpec?.position ?: 0L
                if (start == 0L) {
                    // Probe byte 0: ftyp box
                    val bb = ByteBuffer.wrap(buffer, offset, length).order(ByteOrder.BIG_ENDIAN)
                    bb.putInt(32)
                    bb.putInt(0x66747970) // ftyp
                    return minOf(length, 32)
                }
                // Tail probe: returns sidx
                val toCopy = minOf(length, sidxBytes.size)
                System.arraycopy(sidxBytes, 0, buffer, offset, toCopy)
                return toCopy
            }
            private var dataSpec: DataSpec? = null
            override fun getUri(): Uri = mp4Uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "Content-Range" to listOf("bytes 0-${totalLength - 1}/$totalLength")
            )
            override fun close() = Unit
        }

        val adapter = Mp4RangePrefetchAdapter(
            sourceUri = mp4Uri,
            upstreamFactory = { fakeDataSource },
            cacheKeyFactory = defaultKeyFactory,
        )

        // Initial check triggers probe and discovers sidx at start or tail
        val supported = adapter.isSupported(mp4Uri, "video/mp4")
        assertTrue("MP4 with valid sidx must be supported", supported)
    }
}
