package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PrefetchSchedulerTest {

    private val testUri = Uri.parse("https://cdn.example.com/video/segment_001.ts")
    private val defaultHeaders = mapOf("User-Agent" to "CloudStreamTest")

    @Test
    fun `request identity equality and deduplication with identical properties`() {
        val id1 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts",
            position = 0L,
            length = 1024L,
        )
        val id2 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts",
            position = 0L,
            length = 1024L,
        )

        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
    }

    @Test
    fun `request identity differs when cache key or headers differ`() {
        val id1 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts|h=token1",
            position = 0L,
            length = 1024L,
        )
        val id2 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts|h=token2",
            position = 0L,
            length = 1024L,
        )

        assertNotEquals(id1, id2)
    }

    @Test
    fun `request identity differs when byte ranges differ`() {
        val id1 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts",
            position = 0L,
            length = 1024L,
        )
        val id2 = PrefetchRequestIdentity(
            uri = testUri,
            cacheKey = "https://cdn.example.com/video/segment_001.ts",
            position = 1024L,
            length = 1024L,
        )

        assertNotEquals(id1, id2)
    }

    @Test
    fun `priority ordering prefers immediate over speculative and init over media`() {
        val id = PrefetchRequestIdentity(uri = testUri, cacheKey = "key1")
        val spec = DataSpec(testUri)

        val immediateReq = PrefetchRequest(
            identity = id,
            priority = PrefetchRequest.Priority.IMMEDIATE,
            mediaTimeUs = 20_000_000L,
            dataSpec = spec,
        )

        val forwardReq = PrefetchRequest(
            identity = id,
            priority = PrefetchRequest.Priority.FORWARD_MEDIA,
            mediaTimeUs = 10_000_000L,
            dataSpec = spec,
        )

        val initReq = PrefetchRequest(
            identity = id,
            priority = PrefetchRequest.Priority.FORWARD_MEDIA,
            isInit = true,
            mediaTimeUs = 10_000_000L,
            dataSpec = spec,
        )

        // Lower rank value is higher priority
        assertTrue(immediateReq < forwardReq)
        // Init chunks beat media chunks at the same priority level
        assertTrue(initReq < forwardReq)
    }

    @Test
    fun `multiple consumers for identical request share in-flight work and promote priority`() = runBlocking {
        val session = PlaybackSessionState(sourceUri = testUri.toString())
        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { null },
            getCacheDataSourceFactory = { null },
        )

        val identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "shared_key", position = 0, length = 2048)
        val spec = DataSpec(testUri, 0, 2048)

        val lowPriorityReq = PrefetchRequest(
            identity = identity,
            priority = PrefetchRequest.Priority.SPECULATIVE,
            mediaTimeUs = 50_000_000L,
            dataSpec = spec,
        )

        val first = scheduler.enqueue(lowPriorityReq)
        assertEquals(PrefetchRequest.Priority.SPECULATIVE, first.priority)
        assertEquals(1, first.watchers)

        val highPriorityReq = PrefetchRequest(
            identity = identity,
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 50_000_000L,
            dataSpec = spec,
        )

        val second = scheduler.enqueue(highPriorityReq)
        // Deduplicated to the same request instance
        assertEquals(first.id, second.id)
        assertEquals(2, first.watchers)
        // Priority promoted to NEXT_MEDIA
        assertEquals(PrefetchRequest.Priority.NEXT_MEDIA, first.priority)

        scheduler.release()
    }

    @Test
    fun `seeking cancels stale low priority work far behind the seek head`() = runBlocking {
        val session = PlaybackSessionState(sourceUri = testUri.toString())
        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { null },
            getCacheDataSourceFactory = { null },
        )

        val staleIdentity = PrefetchRequestIdentity(uri = testUri, cacheKey = "stale_key", position = 0, length = 1024)
        val staleReq = PrefetchRequest(
            identity = staleIdentity,
            priority = PrefetchRequest.Priority.USEFUL_PREFETCH,
            mediaTimeUs = 5_000_000L, // 5s into video
            dataSpec = DataSpec(testUri, 0, 1024),
        )

        val aheadIdentity = PrefetchRequestIdentity(uri = testUri, cacheKey = "ahead_key", position = 1024, length = 1024)
        val aheadReq = PrefetchRequest(
            identity = aheadIdentity,
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            mediaTimeUs = 60_000_000L, // 60s into video
            dataSpec = DataSpec(testUri, 1024, 1024),
        )

        scheduler.enqueue(staleReq)
        scheduler.enqueue(aheadReq)

        // User seeks ahead to 50s
        scheduler.onSeek(50_000_000L)

        // The request at 5s is more than 10s behind the seek position (50s - 10s = 40s > 5s), so it should be cancelled
        assertEquals(PrefetchRequest.Status.CANCELLED, staleReq.status)

        scheduler.release()
    }

    @Test
    fun `media3 request coordination prevents duplicate upstream prefetch and promotes work`() = runBlocking {
        val session = PlaybackSessionState(sourceUri = testUri.toString())
        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { null },
            getCacheDataSourceFactory = { null },
        )

        val identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "m3_coord_key", position = 0, length = 4096)
        val spec = DataSpec(testUri, 0, 4096)

        val prefetchReq = PrefetchRequest(
            identity = identity,
            priority = PrefetchRequest.Priority.FORWARD_MEDIA,
            mediaTimeUs = 20_000_000L,
            dataSpec = spec,
        )
        scheduler.enqueue(prefetchReq)

        // Media3 requests this exact resource. The handoff must never report a held cache lock, and
        // the key must be owned by playback so no prefetch worker can open a second writer.
        val handoff = scheduler.onMedia3RequestStarting(identity)
        assertTrue("handoff must never report a held cache lock: $handoff", handoff !is CacheHandoffResult.LockStillHeld)
        assertTrue(scheduler.isKeyInPlaybackUse(identity.cacheKey))
        assertFalse(scheduler.isActivelyWritingKey(identity.cacheKey))
        assertTrue(scheduler.media3ActiveRequests.contains(identity))

        // When Media3 is done
        scheduler.onMedia3RequestFinished(identity)
        assertFalse(scheduler.media3ActiveRequests.contains(identity))

        scheduler.release()
    }

    @Test
    fun `session release cancels in-flight work and empties registry`() = runBlocking {
        val session = PlaybackSessionState(sourceUri = testUri.toString())
        val scheduler = PrefetchRequestScheduler(
            context = android.app.Application(),
            session = session,
            getCache = { null },
            getCacheDataSourceFactory = { null },
        )

        val identity = PrefetchRequestIdentity(uri = testUri, cacheKey = "release_test_key")
        val req = PrefetchRequest(
            identity = identity,
            priority = PrefetchRequest.Priority.NEXT_MEDIA,
            dataSpec = DataSpec(testUri),
        )
        scheduler.enqueue(req)

        scheduler.release()
        assertEquals(0, scheduler.registry.size)
        assertEquals(0, scheduler.getQueueDepth())
    }
}
