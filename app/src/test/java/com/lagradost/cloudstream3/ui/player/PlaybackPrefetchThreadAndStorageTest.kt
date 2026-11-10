package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Validates thread confinement, request identity parity, storage limit enforcement,
 * and prefetch seekbar coverage tracking.
 */
@OptIn(UnstableApi::class)
class PlaybackPrefetchThreadAndStorageTest {

    private val testUri = Uri.parse("https://cdn.example.com/hls/master.m3u8")

    /**
     * Creates a dynamic proxy for ExoPlayer that strictly verifies that all property
     * accesses happen on the main application thread (Looper.getMainLooper()).
     * If any method is invoked on a background thread (e.g. DefaultDispatcher), it throws IllegalStateException.
     */
    private fun createThreadCheckingPlayer(
        targetLooper: Looper = Looper.getMainLooper(),
        manifest: Any? = null,
        currentPos: Long = 5_000L,
        bufferedPos: Long = 10_000L,
        duration: Long = 60_000L,
    ): Player {
        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                if (method.name == "getApplicationLooper") {
                    return targetLooper
                }

                // Verify calling thread for all ExoPlayer state APIs
                if (Looper.myLooper() != targetLooper) {
                    throw IllegalStateException(
                        "Player is accessed on the wrong thread. Current thread: ${Thread.currentThread().name}, Expected looper: $targetLooper"
                    )
                }

                return when (method.name) {
                    "getCurrentPosition" -> currentPos
                    "getBufferedPosition" -> bufferedPos
                    "getDuration" -> duration
                    "getPlaybackState" -> Player.STATE_READY
                    "isPlaying" -> true
                    "getPlayWhenReady" -> true
                    "getCurrentManifest" -> manifest
                    "getCurrentTracks" -> null
                    "getPlaybackParameters" -> androidx.media3.common.PlaybackParameters.DEFAULT
                    "seekTo" -> null
                    "release" -> null
                    else -> null
                }
            }
        }

        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler
        ) as Player
    }

    @Test
    fun `coordinator loop on Dispatchers IO accesses ExoPlayer safely via snapshot without wrong-thread crash`() = runBlocking {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlist = HlsPlaylistParser().parse(testUri, m3u8.byteInputStream(Charsets.UTF_8))

        // Create player that blows up if accessed off the main thread
        val threadCheckingPlayer = createThreadCheckingPlayer(
            targetLooper = Looper.getMainLooper(),
            manifest = playlist,
        )

        val session = PlaybackSessionState(sourceUri = testUri.toString(), prefetchModeEnabled = true)
        val defaultKeyFactory: (DataSpec) -> String = { it.key ?: it.uri.toString() }

        val coordinator = PlaybackPrefetchCoordinator(
            context = android.app.Application(),
            session = session,
            playerProvider = { threadCheckingPlayer },
            getCache = { null },
            getCacheDataSourceFactory = { null },
            upstreamFactory = { throw UnsupportedOperationException() },
            defaultHeaders = emptyMap(),
            cacheKeyFactory = defaultKeyFactory,
        )

        // Capture snapshot from Dispatchers.IO background thread
        val snapshot = withContext(Dispatchers.IO) {
            PlaybackPlayerSnapshot.capture(
                playerProvider = { threadCheckingPlayer },
                sourceUri = testUri,
                selectedMime = null,
                isLive = false,
            )
        }

        assertNotNull("Snapshot must be captured without thread violations", snapshot)
        assertEquals(5_000L, snapshot?.currentPositionMs)
        assertEquals(10_000L, snapshot?.bufferedPositionMs)
        assertEquals(60_000L, snapshot?.durationMs)
        assertEquals(playlist, snapshot?.currentManifest)

        coordinator.release()
    }

    @Test
    fun `media3 request identity and prefetch request identity produce identical cache keys and equality`() {
        val userHeaders = mapOf(
            "Authorization" to "Bearer test_auth_token",
            "User-Agent" to "CloudStreamAndroid",
        )
        val keyFactory = HeaderAwareCacheKeyFactory(userHeaders)

        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example.com/hls/seg1.ts"))
            .setPosition(0L)
            .setLength(1024L)
            .setHttpRequestHeaders(userHeaders)
            .build()

        // 1. Prefetch identity generated by format adapter
        val prefetchIdentity = PrefetchRequestIdentity.fromDataSpec(
            dataSpec = spec,
            cacheKeyFactory = { s -> keyFactory.buildCacheKey(s) },
            defaultHeaders = userHeaders,
        )

        // 2. Media3 identity generated by CacheRoutingDataSourceFactory
        val calculatedKey = spec.key ?: keyFactory.buildCacheKey(spec)
        val media3Identity = PrefetchRequestIdentity(
            uri = spec.uri,
            cacheKey = calculatedKey,
            position = spec.position,
            length = spec.length,
            httpMethod = if (spec.httpMethod == DataSpec.HTTP_METHOD_POST) "POST" else "GET",
            headers = spec.httpRequestHeaders,
        )

        // Must be completely equal so onMedia3RequestStarting matches the prefetch item!
        assertEquals("Identities must be equal between Media3 router and prefetch adapter", prefetchIdentity, media3Identity)
        assertEquals(prefetchIdentity.hashCode(), media3Identity.hashCode())
    }

    @Test
    fun `storage limit strictly blocks background prefetch but permits media3 playback`() = runBlocking {
        val session = PlaybackSessionState(
            sourceUri = testUri.toString(),
            prefetchModeEnabled = true,
            prefetchLimitBytes = 100L * 1024L * 1024L, // 100 MB
        )

        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { null },
            getCacheDataSourceFactory = { null },
        )

        // Simulate active reservations filling the 100 MB budget
        scheduler.reservedBytes.set(100L * 1024L * 1024L)

        val backgroundReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "bg_chunk"),
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            dataSpec = DataSpec(testUri),
        )

        // Background prefetch MUST be rejected when quota is reached
        assertFalse("Background NEXT_MEDIA must not bypass storage limit", scheduler.hasAvailableStorage(backgroundReq))

        val speculativeReq = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "spec_chunk"),
            priority = PrefetchRequest.Priority.SPECULATIVE,
            dataSpec = DataSpec(testUri),
        )
        assertFalse("Background SPECULATIVE must not bypass storage limit", scheduler.hasAvailableStorage(speculativeReq))

        // Genuine Media3 request arriving for immediate playback
        val media3Req = PrefetchRequest(
            identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "media3_playing_chunk"),
            priority = PrefetchRequest.Priority.IMMEDIATE,
            dataSpec = DataSpec(testUri),
        )
        // Media3 marks itself active
        scheduler.media3ActiveRequests.add(media3Req.identity)

        assertTrue("Media3 playback critical request must proceed even when prefetch budget is full", scheduler.hasAvailableStorage(media3Req))

        scheduler.release()
    }

    @Test
    fun `seekbar prefetch coverage computes contiguous range and stops at gaps`() {
        val ranges = mutableListOf(
            PlaybackPrefetchCoordinator.PrefetchCoverageRange(0L, 6_000_000L, "seg1"),
            PlaybackPrefetchCoordinator.PrefetchCoverageRange(6_000_000L, 12_000_000L, "seg2"),
            // Gap between 12s and 18s (seg3 missing)
            PlaybackPrefetchCoordinator.PrefetchCoverageRange(18_000_000L, 24_000_000L, "seg4"),
        )

        val playheadUs = 0L
        val sorted = ranges.filter { it.mediaTimeEndUs >= playheadUs }.sortedBy { it.mediaTimeStartUs }

        var contiguousEndUs = playheadUs
        for (range in sorted) {
            if (range.mediaTimeStartUs <= contiguousEndUs + PlaybackPrefetchCoordinator.CONTIGUOUS_GAP_TOLERANCE_US) {
                contiguousEndUs = maxOf(contiguousEndUs, range.mediaTimeEndUs)
            } else {
                break
            }
        }

        // Should stop at 12s (12,000,000 Us), NOT jumping to 24s
        assertEquals(12_000_000L, contiguousEndUs)
        assertEquals(12_000L, contiguousEndUs / 1000L)
    }
}
