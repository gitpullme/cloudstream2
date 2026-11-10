package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionStateTest {

    @Test
    fun `unique session ID and independent lifecycle`() {
        val session1 = PlaybackSessionState()
        val session2 = PlaybackSessionState()

        assertNotNull(session1.sessionId)
        assertNotNull(session2.sessionId)
        assertNotEquals(session1.sessionId, session2.sessionId)
        assertEquals(PlaybackSessionState.Lifecycle.INITIALIZING, session1.lifecycle)

        session1.onPlaying()
        assertEquals(PlaybackSessionState.Lifecycle.ACTIVE, session1.lifecycle)
        assertEquals(PlaybackSessionState.Lifecycle.INITIALIZING, session2.lifecycle)

        session1.onPaused()
        assertEquals(PlaybackSessionState.Lifecycle.PAUSED, session1.lifecycle)

        session1.release()
        assertEquals(PlaybackSessionState.Lifecycle.RELEASED, session1.lifecycle)
        // Idempotent release
        session1.release()
        assertEquals(PlaybackSessionState.Lifecycle.RELEASED, session1.lifecycle)
    }

    @Test
    fun `media type classification from uri`() {
        val hlsSession = PlaybackSessionState(sourceUri = "https://cdn.example.com/playlist.m3u8")
        assertEquals(PlaybackSessionState.MediaType.HLS, hlsSession.mediaType)

        val dashSession = PlaybackSessionState(sourceUri = "https://cdn.example.com/manifest.mpd")
        assertEquals(PlaybackSessionState.MediaType.DASH, dashSession.mediaType)

        val progressiveSession = PlaybackSessionState(sourceUri = "https://cdn.example.com/video.mp4")
        assertEquals(PlaybackSessionState.MediaType.PROGRESSIVE, progressiveSession.mediaType)

        val liveSession = PlaybackSessionState(sourceUri = "https://cdn.example.com/live.m3u8", isLive = true)
        assertEquals(PlaybackSessionState.MediaType.LIVE, liveSession.mediaType)

        val offlineSession = PlaybackSessionState(sourceUri = "file:///storage/emulated/0/download/movie.mp4")
        assertEquals(PlaybackSessionState.MediaType.OFFLINE, offlineSession.mediaType)
    }

    @Test
    fun `active cache lease acquisition expiration and release`() {
        val session = PlaybackSessionState()
        val testKey = "https://cdn.example.com/segment_001.ts"

        val lease = session.acquireLease(key = testKey, uri = Uri.parse(testKey), ttlMs = 1000L)
        assertNotNull(lease)
        assertEquals(testKey, lease.key)
        assertTrue(session.isKeyLeased(testKey, nowMs = lease.createdAtMs + 500L))
        assertTrue(session.getActiveLeasedKeys(nowMs = lease.createdAtMs + 500L).contains(testKey))

        // Expired lease
        assertFalse(session.isKeyLeased(testKey, nowMs = lease.createdAtMs + 2000L))
        assertFalse(session.getActiveLeasedKeys(nowMs = lease.createdAtMs + 2000L).contains(testKey))

        // Manual release
        val lease2 = session.acquireLease(key = "key2", ttlMs = 10_000L)
        assertTrue(session.isKeyLeased("key2"))
        session.releaseLease(lease2)
        assertFalse(session.isKeyLeased("key2"))

        val lease3 = session.acquireLease(key = "key3", ttlMs = 10_000L)
        session.releaseLeaseById(lease3.leaseId)
        assertFalse(session.isKeyLeased("key3"))
    }

    @Test
    fun `resource activity check protects exact uri and directory scope for playlists`() {
        val session = PlaybackSessionState(
            sourceUri = "https://cdn.example.com/hls/master.m3u8"
        )
        session.isPlaylist = true

        assertTrue(session.isResourceActive("https://cdn.example.com/hls/master.m3u8"))
        assertTrue(session.isResourceActive("https://cdn.example.com/hls/segment_001.ts"))
        assertFalse(session.isResourceActive("https://cdn.example.com/other/segment_001.ts"))

        // After release, no resource is protected
        session.release()
        assertFalse(session.isResourceActive("https://cdn.example.com/hls/master.m3u8"))
    }

    @Test
    fun `multiple sessions do not cross-contaminate live bypass or cache protection`() {
        val sessionOnline = PlaybackSessionState(sourceUri = "https://cdn.example.com/vod.mp4", isLive = false)
        val sessionLive = PlaybackSessionState(sourceUri = "https://cdn.live.com/feed.m3u8", isLive = true)

        PlaybackCacheManager.registerSession(sessionOnline)
        PlaybackCacheManager.registerSession(sessionLive)

        // Live check
        assertFalse(PlaybackCacheManager.shouldBypassCache(Uri.parse("https://cdn.example.com/vod.mp4"), sessionOnline))
        assertTrue(PlaybackCacheManager.shouldBypassCache(Uri.parse("https://cdn.live.com/feed.m3u8"), sessionLive))

        // Active lease check
        val lease = sessionOnline.acquireLease("https://cdn.example.com/vod_seg0.ts")
        assertTrue(PlaybackCacheManager.isSpanActive("https://cdn.example.com/vod_seg0.ts"))

        // Releasing live session does not remove online session's lease
        sessionLive.release()
        assertTrue(PlaybackCacheManager.isSpanActive("https://cdn.example.com/vod_seg0.ts"))

        sessionOnline.release()
        assertFalse(PlaybackCacheManager.isSpanActive("https://cdn.example.com/vod_seg0.ts"))
    }

    @Test
    fun `rebuffering statistics accumulate correctly`() {
        val session = PlaybackSessionState()

        assertEquals(0, session.rebufferCount)
        assertEquals(0L, session.totalRebufferDurationMs)

        // Start rebuffering at t=1000
        session.onRebufferingChanged(true, nowRealtimeMs = 1000L)
        assertTrue(session.rebuffering)
        assertEquals(1, session.rebufferCount)

        // End rebuffering at t=2500 (1500ms duration)
        session.onRebufferingChanged(false, nowRealtimeMs = 2500L)
        assertFalse(session.rebuffering)
        assertEquals(1, session.rebufferCount)
        assertEquals(1500L, session.totalRebufferDurationMs)

        // Second rebuffer at t=5000 to t=6000 (1000ms duration)
        session.onRebufferingChanged(true, nowRealtimeMs = 5000L)
        session.onRebufferingChanged(false, nowRealtimeMs = 6000L)
        assertEquals(2, session.rebufferCount)
        assertEquals(2500L, session.totalRebufferDurationMs)
    }
}
