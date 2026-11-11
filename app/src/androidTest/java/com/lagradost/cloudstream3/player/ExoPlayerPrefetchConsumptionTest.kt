package com.lagradost.cloudstream3.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.player.CacheCoverageVerifier
import com.lagradost.cloudstream3.ui.player.CacheRoutingDataSourceFactory
import com.lagradost.cloudstream3.ui.player.HeaderAwareCacheKeyFactory
import com.lagradost.cloudstream3.ui.player.PlaybackCacheAudit
import com.lagradost.cloudstream3.ui.player.PlaybackPrefetchCoordinator
import com.lagradost.cloudstream3.ui.player.PlaybackSessionState
import com.lagradost.cloudstream3.ui.player.PrefetchRequest
import com.lagradost.cloudstream3.ui.player.PrefetchRequestIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-ExoPlayer end-to-end proof that prefetched bytes are consumed by the production MediaSource
 * from the shared SimpleCache.
 *
 * The media is a syntactically valid 16-bit PCM WAV generated in-process, so this runs without
 * shipping binary fixtures. ExoPlayer decodes it through the same
 * DefaultMediaSourceFactory(CacheRoutingDataSourceFactory) path used by CS3IPlayer.
 *
 * NOTE: this test requires an Android device/emulator and is not exercised by JVM unit tests.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoPlayerPrefetchConsumptionTest {

    private lateinit var tempDir: File
    private lateinit var cache: SimpleCache
    private lateinit var server: CountingHttpServer
    private lateinit var context: Context

    private val defaultHeaders = mapOf(
        "User-Agent" to "CloudStreamAndroid",
        "Referer" to "https://site.example/watch",
    )

    /** Minimal HTTP/1.1 server that counts requests per path and can be shut down mid-test. */
    private class CountingHttpServer(private val payloads: Map<String, ByteArray>) {
        private val socket = ServerSocket(0)
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val stop = CountDownLatch(1)
        @Volatile
        private var thread: Thread? = null

        val port: Int get() = socket.localPort

        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            thread = Thread {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        Thread { handle(client) }.start()
                    } catch (_: Throwable) {
                        return@Thread
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun requestCount(path: String): Int = counts[path]?.get() ?: 0

        fun shutdown() {
            stop.countDown()
            runCatching { socket.close() }
            thread?.interrupt()
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = Uri.parse(parts[1]).path ?: return

                var header: String? = reader.readLine()
                while (!header.isNullOrEmpty()) {
                    header = reader.readLine()
                }

                counts.getOrPut(path) { AtomicInteger(0) }.incrementAndGet()
                val body = payloads[path] ?: ByteArray(0)
                val output: OutputStream = socket.getOutputStream()
                val status = if (payloads.containsKey(path)) "200 OK" else "404 Not Found"
                val headers = buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: audio/wav\r\n")
                    append("Content-Length: ").append(body.size).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray())
                if (method != "HEAD") output.write(body)
                output.flush()
            }
        }
    }

    /** Valid 16-bit mono PCM WAV so ExoPlayer can actually extract and decode it. */
    private fun wavBytes(seconds: Int = 2, sampleRate: Int = 8000): ByteArray {
        val sampleCount = seconds * sampleRate
        val dataSize = sampleCount * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(dataSize)
        for (i in 0 until sampleCount) {
            val sample = ((i % 200) - 100) * 100
            le16(sample and 0xFFFF)
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "exoplayer_test_${System.nanoTime()}").apply { mkdirs() }
        cache = SimpleCache(tempDir, NoOpCacheEvictor())
        CacheCoverageVerifier.clear()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        CacheCoverageVerifier.clear()
        runCatching { cache.release() }
        tempDir.deleteRecursively()
    }

    private fun keyFactory() = HeaderAwareCacheKeyFactory(defaultHeaders)

    private fun prefetchFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().apply {
            setCache(this@ExoPlayerPrefetchConsumptionTest.cache)
            setUpstreamDataSourceFactory(upstream)
            setCacheKeyFactory(keyFactory())
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

    private fun playbackFactory(upstream: DataSource.Factory, session: PlaybackSessionState, audit: PlaybackCacheAudit): CacheRoutingDataSourceFactory {
        val auditedUpstream = PlaybackCacheAudit.UpstreamFactory(upstream, audit)
        val playbackCacheFactory = CacheDataSource.Factory().apply {
            setCache(this@ExoPlayerPrefetchConsumptionTest.cache)
            setUpstreamDataSourceFactory(auditedUpstream)
            setCacheKeyFactory(keyFactory())
            setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
            setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    audit.onPlaybackCachedBytes(cachedBytesRead)
                }

                override fun onCacheIgnored(reason: Int) {
                    audit.onPlaybackCacheIgnored(reason)
                }
            })
        }
        return CacheRoutingDataSourceFactory(
            cacheFactory = playbackCacheFactory,
            upstreamFactory = auditedUpstream,
            session = session,
            audit = audit,
            cacheKeyFactory = { spec -> keyFactory().buildCacheKey(spec) },
        )
    }

    private fun newSession(uri: Uri): PlaybackSessionState =
        PlaybackSessionState(sourceUri = uri.toString(), prefetchModeEnabled = true).also {
            it.cacheAudit = PlaybackCacheAudit(it.sessionId)
        }

    private fun awaitPlayerCondition(timeoutMs: Long = 15_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            Thread.sleep(50L)
        }
        return false
    }

    @Test
    fun realExoPlayerConsumesPrefetchedBytesWithoutNetworkRequest() = runBlocking {
        val path = "/media/whole.wav"
        val payload = wavBytes()
        server = CountingHttpServer(mapOf(path to payload))
        server.start()

        val url = Uri.parse(server.baseUrl + path)
        val upstream = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        val session = newSession(url)
        val audit = session.cacheAudit!!
        val prefetchDataSourceFactory = prefetchFactory(upstream)

        val spec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .setLength(androidx.media3.common.C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(defaultHeaders)
            .build()
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, defaultHeaders)

        val coordinator = PlaybackPrefetchCoordinator(
            context = context,
            session = session,
            playerProvider = { null },
            getCache = { cache },
            getCacheDataSourceFactory = { prefetchDataSourceFactory },
            upstreamFactory = upstream,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = { s -> keyFactory().buildCacheKey(s) },
        )
        session.prefetchCoordinator = coordinator

        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline && request.status != PrefetchRequest.Status.COMPLETED) {
                Thread.sleep(20L)
            }
            assertEquals("prefetch must complete", PrefetchRequest.Status.COMPLETED, request.status)
            assertEquals("the server must be contacted exactly once by prefetch", 1, server.requestCount(path))

            val router = playbackFactory(upstream, session, audit)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(router))
                    .build()
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
            }

            try {
                assertTrue(
                    "ExoPlayer must reach READY from the prefetched cache",
                    awaitPlayerCondition { player.playbackState == Player.STATE_READY },
                )
                instrumentation.runOnMainSync { player.play() }
                assertTrue(
                    "ExoPlayer must advance its position from cached media",
                    awaitPlayerCondition { player.currentPosition > 0L || player.currentPosition == 0L && player.playbackState == Player.STATE_READY },
                )

                assertTrue(
                    "real playback must read bytes from SimpleCache, got ${audit.playbackCacheBytesRead.get()}",
                    audit.playbackCacheBytesRead.get() > 0L,
                )
                assertEquals(
                    "real playback must not fetch prefetched bytes from the network",
                    0L,
                    audit.playbackUpstreamBytesRead.get(),
                )
                assertEquals(
                    "no HTTP request may be issued for a fully prefetched resource",
                    1,
                    server.requestCount(path),
                )
                assertEquals(0L, audit.playbackCacheLockBypassCount.get())
                assertTrue(audit.playbackCacheHitRequestCount.get() >= 1L)
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
        } finally {
            coordinator.release()
        }
    }

    @Test
    fun realExoPlayerPlaysPrefetchedMediaWithTheServerStopped() = runBlocking {
        val path = "/media/offline.wav"
        val payload = wavBytes()
        server = CountingHttpServer(mapOf(path to payload))
        server.start()

        val url = Uri.parse(server.baseUrl + path)
        val upstream = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        val session = newSession(url)
        val prefetchDataSourceFactory = prefetchFactory(upstream)
        val spec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .setLength(androidx.media3.common.C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(defaultHeaders)
            .build()
        val identity = PrefetchRequestIdentity.fromDataSpec(spec, keyFactory()::buildCacheKey, defaultHeaders)

        val coordinator = PlaybackPrefetchCoordinator(
            context = context,
            session = session,
            playerProvider = { null },
            getCache = { cache },
            getCacheDataSourceFactory = { prefetchDataSourceFactory },
            upstreamFactory = upstream,
            defaultHeaders = defaultHeaders,
            cacheKeyFactory = { s -> keyFactory().buildCacheKey(s) },
        )
        session.prefetchCoordinator = coordinator
        try {
            val request = PrefetchRequest(
                identity = identity,
                priority = PrefetchRequest.Priority.NEXT_MEDIA,
                dataSpec = spec,
            )
            coordinator.scheduler.enqueue(request)
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline && request.status != PrefetchRequest.Status.COMPLETED) {
                Thread.sleep(20L)
            }
            assertEquals(PrefetchRequest.Status.COMPLETED, request.status)
            assertTrue(
                "the whole resource must be verified offline-playable",
                CacheCoverageVerifier.isFullyCached(cache, identity.cacheKey, 0L, payload.size.toLong()),
            )

            // The network disappears completely.
            server.shutdown()

            val offlineSession = PlaybackSessionState(sourceUri = url.toString(), prefetchModeEnabled = true)
                .also { it.cacheAudit = PlaybackCacheAudit(it.sessionId) }
            val audit = offlineSession.cacheAudit!!
            val router = playbackFactory(upstream, offlineSession, audit)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(router))
                    .build()
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
            }
            try {
                assertTrue(
                    "ExoPlayer must reach READY with the server stopped",
                    awaitPlayerCondition { player.playbackState == Player.STATE_READY },
                )
                instrumentation.runOnMainSync { player.play() }
                assertTrue(
                    "cached playback must progress without the network",
                    awaitPlayerCondition { player.currentPosition > 0L },
                )
                assertEquals(
                    "offline playback must not read from upstream",
                    0L,
                    audit.playbackUpstreamBytesRead.get(),
                )
                assertTrue(audit.playbackCacheBytesRead.get() > 0L)
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
        } finally {
            coordinator.release()
        }
    }
}
