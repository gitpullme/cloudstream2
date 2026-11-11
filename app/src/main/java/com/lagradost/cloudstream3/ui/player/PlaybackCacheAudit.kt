package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lagradost.cloudstream3.BuildConfig
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent measurements for the two consumers of the shared [androidx.media3.datasource.cache.SimpleCache]:
 *
 * - The background prefetch pipeline (CacheWriter workers).
 * - The real ExoPlayer Media3 playback pipeline (CacheDataSource inside the loading thread).
 *
 * Why this exists: both pipelines previously shared one CacheDataSource.Factory, one upstream
 * factory and one CacheDataSource.EventListener, so `cachedBytesRead` could be produced entirely by
 * background prefetch. That makes it impossible to answer the only question that matters:
 * *did the actual player read prefetched bytes from the shared cache?*
 *
 * All values are thread-safe. Cache keys are exposed only as short hashes and URIs are reduced to
 * scheme://host/path, so signed query parameters, tokens and cookies never reach the logs.
 */
@OptIn(UnstableApi::class)
class PlaybackCacheAudit(
    val sessionId: String,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "CacheAudit"

        fun safeKeyHash(cacheKey: String?): String {
            if (cacheKey.isNullOrEmpty()) return "none"
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(cacheKey.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }.take(12)
            } catch (_: Throwable) {
                cacheKey.hashCode().toUInt().toString(16).padStart(8, '0')
            }
        }

        /** Scheme + host + path only. Query strings (signed URLs / tokens) are dropped. */
        fun safeUriPath(uri: Uri?): String {
            if (uri == null) return "none"
            return runCatching {
                buildString {
                    uri.scheme?.let { append(it).append("://") }
                    (uri.host ?: uri.encodedAuthority)?.let { append(it) }
                    append(uri.path ?: "")
                }
            }.getOrDefault("unknown")
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var index = 0
            while (value >= 1024.0 && index < units.size - 1) {
                value /= 1024.0
                index++
            }
            return String.format("%.1f %s", value, units[index])
        }
    }

    /** Which pipeline actually produced the bytes for a playback request. */
    enum class Route { CACHE, UPSTREAM }

    /** Outcome of the cache-writer handoff performed before opening the playback DataSource. */
    enum class Handoff {
        /** No prefetch request was involved. */
        NO_MATCH,
        /** A prefetch request existed and its bytes were already complete in the cache. */
        ALREADY_COMPLETED,
        /** A prefetch writer was cancelled and provably released its cache span lock. */
        WRITER_CANCELLED_AND_UNLOCKED,
        /** The writer could not release its lock in time; this request used upstream explicitly. */
        LOCK_STILL_HELD,
    }

    class RequestRecord(
        val requestId: Long,
        val cacheKey: String,
        val uriPath: String,
        val position: Long,
        val length: Long,
        val route: Route,
        val handoff: Handoff,
        val startedAtMs: Long,
    ) {
        val bytesRead = AtomicLong(0L)
        val cachedBytesRead = AtomicLong(0L)
        val upstreamBytesRead = AtomicLong(0L)
        val cacheIgnoredCount = AtomicLong(0L)

        @Volatile
        var reachedEof: Boolean = false

        @Volatile
        var openedLength: Long = -1L

        @Volatile
        var failed: Boolean = false

        val isFullyServedFromCache: Boolean
            get() = cachedBytesRead.get() > 0L && upstreamBytesRead.get() == 0L && reachedEof
    }

    // ---- Prefetch pipeline (CacheWriter workers only) ----
    val prefetchBytesWritten = AtomicLong(0L)
    val prefetchCacheBytesReused = AtomicLong(0L)
    val prefetchUpstreamBytesRead = AtomicLong(0L)
    val prefetchRequestCount = AtomicLong(0L)
    val prefetchCompletedRequestCount = AtomicLong(0L)
    val prefetchFailedRequestCount = AtomicLong(0L)
    val prefetchCancelledRequestCount = AtomicLong(0L)
    val prefetchWorkerDurationMs = AtomicLong(0L)

    // ---- Real Media3 playback pipeline ----
    val playbackCacheBytesRead = AtomicLong(0L)
    val playbackUpstreamBytesRead = AtomicLong(0L)
    val playbackRequestCount = AtomicLong(0L)
    val playbackCacheHitRequestCount = AtomicLong(0L)
    val playbackCacheMissRequestCount = AtomicLong(0L)
    val playbackPartialCacheRequestCount = AtomicLong(0L)
    val playbackFullyCachedRequestCount = AtomicLong(0L)
    val playbackCacheIgnoredCount = AtomicLong(0L)
    val playbackCacheLockBypassCount = AtomicLong(0L)
    val playbackHandoffCount = AtomicLong(0L)
    val playbackHandoffTimeoutCount = AtomicLong(0L)
    val playbackUpstreamFallbackWhileLockedCount = AtomicLong(0L)

    @Volatile
    var lastPlaybackCacheHitTimeMs: Long = 0L

    @Volatile
    var lastPlaybackUpstreamRequestTimeMs: Long = 0L

    @Volatile
    var lastCacheKeyMismatch: String? = null

    @Volatile
    var lastCacheBypassReason: String? = null

    @Volatile
    var lastHandoffDetail: String? = null

    /** Bytes currently considered offline-playable by the coordinator (diagnostics only). */
    @Volatile
    var offlinePlayableEndMs: Long = -1L

    @Volatile
    var missingVideoDependency: String? = null

    @Volatile
    var missingAudioDependency: String? = null

    @Volatile
    var missingInitializationDependency: String? = null

    /**
     * Returns true when the given request lies inside a range the UI currently paints as
     * offline-playable. Used to emit BLUE_RANGE_NOT_REUSED.
     */
    var coverageOracle: ((cacheKey: String, position: Long, length: Long) -> Boolean)? = null

    /** Invoked when a claimed offline-playable interval turned out not to be readable from cache. */
    var onClaimedRangeMissed: ((cacheKey: String) -> Unit)? = null

    private var requestIdCounter = AtomicLong(0L)
    private val activeRequests = ConcurrentHashMap<Long, RequestRecord>()

    /** Request context for the loading thread that is currently inside DataSource.open/read/close. */
    private val threadRequest = ThreadLocal<RequestRecord?>()

    fun beginPlaybackRequest(
        cacheKey: String,
        uri: Uri?,
        position: Long,
        length: Long,
        route: Route,
        handoff: Handoff,
    ): RequestRecord {
        val record = RequestRecord(
            requestId = requestIdCounter.incrementAndGet(),
            cacheKey = cacheKey,
            uriPath = safeUriPath(uri),
            position = position,
            length = length,
            route = route,
            handoff = handoff,
            startedAtMs = clock(),
        )
        playbackRequestCount.incrementAndGet()
        handoffWhen(handoff)
        activeRequests[record.requestId] = record
        threadRequest.set(record)
        return record
    }

    fun currentRequest(): RequestRecord? = threadRequest.get()

    fun clearThreadRequest() {
        threadRequest.remove()
    }

    private fun handoffWhen(handoff: Handoff) {
        when (handoff) {
            Handoff.WRITER_CANCELLED_AND_UNLOCKED -> playbackHandoffCount.incrementAndGet()
            Handoff.LOCK_STILL_HELD -> {
                playbackHandoffTimeoutCount.incrementAndGet()
                lastHandoffDetail = "cache writer still held the span lock"
            }
            else -> Unit
        }
    }

    /** Bytes returned to ExoPlayer by the playback DataSource (cache + upstream combined). */
    fun onPlaybackBytesRead(bytes: Int) {
        if (bytes <= 0) return
        val record = threadRequest.get() ?: return
        record.bytesRead.addAndGet(bytes.toLong())
    }

    /** Bytes fetched from the network by the upstream DataSource of a playback request. */
    fun onPlaybackUpstreamBytes(bytes: Long) {
        if (bytes <= 0L) return
        playbackUpstreamBytesRead.addAndGet(bytes)
        lastPlaybackUpstreamRequestTimeMs = clock()
        val record = threadRequest.get() ?: return
        record.upstreamBytesRead.addAndGet(bytes)
    }

    /** Bytes served by SimpleCache for the request currently open on this thread. */
    fun onPlaybackCachedBytes(bytes: Long) {
        if (bytes <= 0L) return
        playbackCacheBytesRead.addAndGet(bytes)
        lastPlaybackCacheHitTimeMs = clock()
        val record = threadRequest.get() ?: return
        record.cachedBytesRead.addAndGet(bytes)
    }

    fun onPlaybackCacheIgnored(reason: Int) {
        playbackCacheIgnoredCount.incrementAndGet()
        lastCacheBypassReason = "cache ignored reason=$reason"
        val record = threadRequest.get() ?: return
        record.cacheIgnoredCount.incrementAndGet()
    }

    fun onCacheLockBypass(reason: String) {
        playbackCacheLockBypassCount.incrementAndGet()
        playbackUpstreamFallbackWhileLockedCount.incrementAndGet()
        lastCacheBypassReason = reason
    }

    fun onCacheKeyMismatch(detail: String) {
        lastCacheKeyMismatch = detail
    }

    /**
     * Completes a playback request and classifies it.
     *
     * Emits BLUE_RANGE_NOT_REUSED when the request lies inside a range the seekbar claims is
     * offline-playable but the bytes were fetched from upstream instead of SimpleCache.
     */
    fun endPlaybackRequest(record: RequestRecord?, reachedEof: Boolean, failure: Throwable?) {
        threadRequest.remove()
        val r = record ?: return
        r.reachedEof = reachedEof
        r.failed = failure != null
        activeRequests.remove(r.requestId)

        val cached = r.cachedBytesRead.get()
        val upstream = r.upstreamBytesRead.get()
        if (cached > 0L && upstream == 0L) {
            playbackCacheHitRequestCount.incrementAndGet()
            if (reachedEof) playbackFullyCachedRequestCount.incrementAndGet()
        } else if (cached > 0L && upstream > 0L) {
            playbackPartialCacheRequestCount.incrementAndGet()
        } else if (upstream > 0L) {
            playbackCacheMissRequestCount.incrementAndGet()
        }

        val insideClaimed = runCatching {
            coverageOracle?.invoke(r.cacheKey, r.position, r.length) == true
        }.getOrDefault(false)

        if (insideClaimed && upstream > 0L) {
            val reason = buildString {
                append("requestedRange=[${r.position}, ${if (r.length > 0) r.position + r.length else -1})")
                append(" route=${r.route}")
                append(" handoff=${r.handoff}")
                append(" cachedBytes=$cached")
                append(" upstreamBytes=$upstream")
                append(" cacheIgnored=${r.cacheIgnoredCount.get()}")
                if (r.handoff == Handoff.LOCK_STILL_HELD) append(" writerLockHeld=true")
                if (r.route == Route.UPSTREAM) append(" bypass=${lastCacheBypassReason ?: "routing"}")
            }
            Log.e(
                TAG,
                "BLUE_RANGE_NOT_REUSED session=$sessionId keyHash=${safeKeyHash(r.cacheKey)} " +
                    "uri=${r.uriPath} $reason",
            )
            runCatching { onClaimedRangeMissed?.invoke(r.cacheKey) }
        }

        if (insideClaimed && r.route == Route.UPSTREAM && failure != null) {
            Log.w(
                TAG,
                "PLAYBACK_UPSTREAM_ON_CLAIMED_RANGE session=$sessionId " +
                    "keyHash=${safeKeyHash(r.cacheKey)} failure=${failure.javaClass.simpleName}",
            )
        }

        if (BuildConfig.DEBUG && cached > 0L) {
            Log.d(
                TAG,
                "PLAYBACK_CACHE_READ session=$sessionId keyHash=${safeKeyHash(r.cacheKey)} " +
                    "position=${r.position} cachedBytes=$cached upstreamBytes=$upstream " +
                    "handoff=${r.handoff} eof=$reachedEof",
            )
        }
    }

    // ---- Prefetch side helpers (never contribute to playback counters) ----

    fun onPrefetchBytesWritten(written: Long, reused: Long) {
        if (written > 0L) prefetchBytesWritten.addAndGet(written)
        if (reused > 0L) prefetchCacheBytesReused.addAndGet(reused)
    }

    fun onPrefetchUpstreamBytes(bytes: Long) {
        if (bytes > 0L) prefetchUpstreamBytesRead.addAndGet(bytes)
    }

    fun onPrefetchStarted() {
        prefetchRequestCount.incrementAndGet()
    }

    fun onPrefetchCompleted(durationMs: Long) {
        prefetchCompletedRequestCount.incrementAndGet()
        if (durationMs > 0L) prefetchWorkerDurationMs.addAndGet(durationMs)
    }

    fun onPrefetchFailed() {
        prefetchFailedRequestCount.incrementAndGet()
    }

    fun onPrefetchCancelled() {
        prefetchCancelledRequestCount.incrementAndGet()
    }

    fun activePlaybackRequestCount(): Int = activeRequests.size

    /**
     * PREFETCH / ACTUAL PLAYBACK blocks of the diagnostics dump. The caller adds the OFFLINE
     * section because only the coordinator knows the verified coverage endpoint.
     */
    fun diagnostics(
        cacheUsageBytes: Long,
        workersActive: Int,
        workersTarget: Int,
        queued: Int,
    ): String = buildString {
        append("PREFETCH:")
        append("\n  downloaded=").append(formatBytes(prefetchBytesWritten.get()))
        append("\n  cacheReused=").append(formatBytes(prefetchCacheBytesReused.get()))
        append("\n  upstream=").append(formatBytes(prefetchUpstreamBytesRead.get()))
        append("\n  cacheUsage=").append(formatBytes(cacheUsageBytes))
        append("\n  workers=").append(workersActive).append('/').append(workersTarget)
        append("\n  queued=").append(queued)
        append("\n  requests=").append(prefetchRequestCount.get())
        append(" completed=").append(prefetchCompletedRequestCount.get())
        append(" failed=").append(prefetchFailedRequestCount.get())
        append(" cancelled=").append(prefetchCancelledRequestCount.get())
        append("\n")
        append("ACTUAL PLAYBACK:")
        append("\n  cachedBytesRead=").append(formatBytes(playbackCacheBytesRead.get()))
        append("\n  upstreamBytesRead=").append(formatBytes(playbackUpstreamBytesRead.get()))
        append("\n  requests=").append(playbackRequestCount.get())
        append("\n  cacheHitRequests=").append(playbackCacheHitRequestCount.get())
        append("\n  cacheMissRequests=").append(playbackCacheMissRequestCount.get())
        append("\n  partialCacheRequests=").append(playbackPartialCacheRequestCount.get())
        append("\n  fullyCachedRequests=").append(playbackFullyCachedRequestCount.get())
        append("\n  cacheIgnored=").append(playbackCacheIgnoredCount.get())
        append("\n  lockBypass=").append(playbackCacheLockBypassCount.get())
        append("\n  handoff=").append(playbackHandoffCount.get())
        append("\n  handoffTimeout=").append(playbackHandoffTimeoutCount.get())
        lastCacheBypassReason?.let { append("\n  lastBypassReason=").append(it) }
        lastCacheKeyMismatch?.let { append("\n  lastCacheKeyMismatch=").append(it) }
        lastHandoffDetail?.let { append("\n  lastHandoffDetail=").append(it) }
        if (offlinePlayableEndMs >= 0L) {
            append("\n  offlinePlayableEndMs=").append(offlinePlayableEndMs)
        }
    }

    fun reset() {
        prefetchBytesWritten.set(0L)
        prefetchCacheBytesReused.set(0L)
        prefetchUpstreamBytesRead.set(0L)
        prefetchRequestCount.set(0L)
        prefetchCompletedRequestCount.set(0L)
        prefetchFailedRequestCount.set(0L)
        prefetchCancelledRequestCount.set(0L)
        playbackCacheBytesRead.set(0L)
        playbackUpstreamBytesRead.set(0L)
        playbackRequestCount.set(0L)
        playbackCacheHitRequestCount.set(0L)
        playbackCacheMissRequestCount.set(0L)
        playbackPartialCacheRequestCount.set(0L)
        playbackFullyCachedRequestCount.set(0L)
        playbackCacheIgnoredCount.set(0L)
        playbackCacheLockBypassCount.set(0L)
        playbackHandoffCount.set(0L)
        playbackHandoffTimeoutCount.set(0L)
        playbackUpstreamFallbackWhileLockedCount.set(0L)
        lastCacheBypassReason = null
        lastCacheKeyMismatch = null
        lastHandoffDetail = null
        activeRequests.clear()
        clearThreadRequest()
    }

    /**
     * Wraps an upstream DataSource.Factory so that bytes fetched from the network by playback are
     * attributed to the request open on the current loading thread, never to prefetch workers.
     */
    class UpstreamFactory(
        private val delegate: DataSource.Factory,
        private val audit: PlaybackCacheAudit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AuditedUpstreamDataSource(delegate.createDataSource(), audit)
    }

    private class AuditedUpstreamDataSource(
        private val delegate: DataSource,
        private val audit: PlaybackCacheAudit,
    ) : DataSource {
        private val transferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                audit.onPlaybackUpstreamBytes(bytesTransferred.toLong())
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }

        private var listeningAttached = false

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
            if (!listeningAttached) {
                listeningAttached = true
                delegate.addTransferListener(this.transferListener)
            }
        }

        override fun open(dataSpec: DataSpec): Long {
            // Guarantee attribution even when nothing else registers a TransferListener on the
            // upstream source (attempted before open, as Media3 requires).
            if (!listeningAttached) {
                listeningAttached = true
                runCatching { delegate.addTransferListener(transferListener) }
            }
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() = delegate.close()
    }
}
