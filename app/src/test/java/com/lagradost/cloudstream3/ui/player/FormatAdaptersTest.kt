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
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Create synthetic sidx box data
        val sidxBuffer = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN)
        sidxBuffer.putInt(44) // box size
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
        assertEquals(1024L, requests[0].dataSpec.position)
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
}
