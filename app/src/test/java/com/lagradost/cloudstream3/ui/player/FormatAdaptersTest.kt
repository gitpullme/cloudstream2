package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FormatAdaptersTest {

    private val baseHlsUri = "https://cdn.example.com/hls/"
    private val baseDashUri = "https://cdn.example.com/dash/"
    private val mp4Uri = Uri.parse("https://cdn.example.com/video.mp4")

    private val defaultKeyFactory: (DataSpec) -> String = { it.key ?: it.uri.toString() }

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
            manifestProvider = { playlist },
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        assertTrue(adapter.isSupported(Uri.parse(baseHlsUri + "master.m3u8"), MimeTypes.APPLICATION_M3U8))

        val requests = adapter.getUpcomingRequests(
            currentPositionUs = 0L,
            bufferedPositionUs = 0L,
            durationUs = 12_000_000L,
            forwardWindowUs = 30_000_000L,
        )

        // Init chunk + seg1 + seg2
        assertEquals(3, requests.size)
        // First is initialization segment with IMMEDIATE priority
        val initReq = requests.first()
        assertTrue(initReq.isInit)
        assertEquals(PrefetchRequest.Priority.IMMEDIATE, initReq.priority)
        assertTrue(initReq.identity.uri.toString().endsWith("init.mp4"))

        // Media segments have NEXT_MEDIA priority
        val mediaReq1 = requests[1]
        assertFalse(mediaReq1.isInit)
        assertEquals(PrefetchRequest.Priority.NEXT_MEDIA, mediaReq1.priority)
        assertTrue(mediaReq1.identity.uri.toString().endsWith("seg1.ts"))
    }

    @Test
    fun `MP4 range adapter verifies 206 response and chunks file into 1MB blocks`() = runBlocking {
        val totalLength = 5L * 1024L * 1024L // 5 MB file
        val fakeDataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = 2L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
            override fun getUri(): Uri = mp4Uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "Content-Range" to listOf("bytes 0-1/$totalLength"),
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
            chunkSize = 1024L * 1024L,
        )

        val supported = adapter.isSupported(mp4Uri, "video/mp4")
        assertTrue(supported)

        val requests = adapter.getUpcomingRequests(
            currentPositionUs = 0L,
            bufferedPositionUs = 0L,
            durationUs = 50_000_000L,
            forwardWindowUs = 30_000_000L,
        )

        assertTrue(requests.isNotEmpty())
        // First chunk starts at 0 with length 1 MB
        val first = requests.first()
        assertEquals(0L, first.identity.position)
        assertEquals(1024L * 1024L, first.identity.length)
        assertEquals(PrefetchRequest.Priority.NEXT_MEDIA, first.priority)

        // Second chunk starts at 1 MB
        val second = requests[1]
        assertEquals(1024L * 1024L, second.identity.position)
        assertEquals(1024L * 1024L, second.identity.length)
        assertEquals(PrefetchRequest.Priority.FORWARD_MEDIA, second.priority)
    }

    @Test
    fun `MP4 range adapter detects server ignoring Range header and safely falls back`() = runBlocking {
        // Server returns 200 without Content-Range (server ignored byte range)
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

        val requests = adapter.getUpcomingRequests(0L, 0L, 50_000_000L, 30_000_000L)
        assertTrue("Unsupported source must return empty list to let normal Media3 loading proceed without duplicates", requests.isEmpty())
    }

    @Test
    fun `MP4 range adapter rejects inconsistent Content-Range headers`() = runBlocking {
        // Server returns Content-Range with invalid range or zero total
        val fakeDataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long = 2L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
            override fun getUri(): Uri = mp4Uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "Content-Range" to listOf("bytes 0-1/0") // Inconsistent total
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

        assertFalse(adapter.isSupported(mp4Uri, "video/mp4"))
    }
}
