package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Routes each Media3 request either through the playback disk cache or directly upstream.
 *
 * Responsibilities added by the prefetch repair:
 *  - Every request gets an explicit cache-writer handoff decision before the DataSource is opened.
 *    Playback never opens a cache reader while a prefetch writer still holds the span lock; in that
 *    case it takes a *recorded* upstream fallback instead of Media3's silent non-blocking bypass.
 *  - Per-open state (EOF, byte counters, request identity) is reset, so a previous EOF can never
 *    mark a later partial request complete.
 *  - Byte accounting is reported to [PlaybackCacheAudit] only for actual playback, never for
 *    background prefetch.
 *
 * Invariants:
 *  - One routing decision per DataSpec/open.
 *  - DRM license POST requests and other non-GET requests bypass the cache.
 *  - All DataSpec fields, headers, flags, key, byte ranges, HTTP method/body and custom data are
 *    preserved.
 *  - TransferListeners, response URI and response headers are preserved.
 *  - The session cache lease is always released on close and on failed open.
 */
@UnstableApi
class CacheRoutingDataSourceFactory(
    private val cacheFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val session: PlaybackSessionState? = null,
    private val audit: PlaybackCacheAudit? = session?.cacheAudit,
    private val cacheKeyFactory: (DataSpec) -> String = { it.key ?: it.uri.toString() },
    private val shouldBypass: (Uri) -> Boolean = { uri -> PlaybackCacheManager.shouldBypassCache(uri, session) },
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutedDataSource(
        cacheFactory = cacheFactory,
        upstreamFactory = upstreamFactory,
        session = session,
        audit = audit,
        cacheKeyFactory = cacheKeyFactory,
        shouldBypass = shouldBypass,
    )

    private class RoutedDataSource(
        private val cacheFactory: DataSource.Factory,
        private val upstreamFactory: DataSource.Factory,
        private val session: PlaybackSessionState?,
        private val audit: PlaybackCacheAudit?,
        private val cacheKeyFactory: (DataSpec) -> String,
        private val shouldBypass: (Uri) -> Boolean,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        private var currentLease: PlaybackSessionState.CacheLease? = null
        private var currentIdentity: PrefetchRequestIdentity? = null
        private var currentRecord: PlaybackCacheAudit.RequestRecord? = null

        // ---- Per-open state. Reset in open() and never carried across requests. ----
        private var hasReadEof = false
        private var bytesReadThisOpen = 0L
        private var openedLength = -1L

        override fun addTransferListener(transferListener: TransferListener) {
            if (!listeners.contains(transferListener)) {
                listeners += transferListener
            }
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            // Close the previous request first so its EOF evidence stays attached to its own
            // identity/range before all per-open fields are reset.
            closeInternal()

            hasReadEof = false
            bytesReadThisOpen = 0L
            openedLength = -1L

            // Always go through the shared key factory: it is the single source of truth and it also
            // honours a custom DataSpec key, so playback and prefetch can never disagree.
            val cacheKey = cacheKeyFactory(dataSpec)

            val isGet = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
            val bypassReason = when {
                !isGet -> "NON_GET_REQUEST"
                session?.isLive == true -> "LIVE_SESSION"
                shouldBypass(dataSpec.uri) -> "POLICY_BYPASS"
                else -> null
            }

            val identity = PrefetchRequestIdentity(
                uri = dataSpec.uri,
                cacheKey = cacheKey,
                position = dataSpec.position,
                length = dataSpec.length,
                httpMethod = if (isGet) "GET" else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) "POST" else "OTHER",
                headers = dataSpec.httpRequestHeaders,
            )

            var handoff: CacheHandoffResult = CacheHandoffResult.NoMatchingWriter
            val coordinator = session?.prefetchCoordinator
            if (coordinator != null && bypassReason == null) {
                currentIdentity = identity
                handoff = coordinator.scheduler.onMedia3RequestStarting(identity)
            } else if (coordinator != null) {
                // Still register the request so prefetch does not fight playback for this key.
                currentIdentity = identity
                handoff = coordinator.scheduler.onMedia3RequestStarting(identity)
            }
            if (bypassReason != null) {
                audit?.lastCacheBypassReason = "$bypassReason ${PlaybackCacheAudit.safeUriPath(dataSpec.uri)}"
            }

            val lockHeld = handoff is CacheHandoffResult.LockStillHeld
            if (lockHeld) {
                // Explicit, measured fallback. Never open a cache reader while a writer holds the
                // lock, and never open a second overlapping writer.
                val reason = (handoff as CacheHandoffResult.LockStillHeld).reason
                audit?.onCacheLockBypass("UPSTREAM_FALLBACK_WHILE_LOCKED: $reason")
            }

            val useCache = bypassReason == null && !lockHeld
            val source = if (useCache) {
                currentLease = session?.acquireLease(
                    key = cacheKey,
                    uri = dataSpec.uri,
                    rangeStart = dataSpec.position,
                    rangeLength = dataSpec.length
                )
                PlaybackCacheManager.registerActiveKey(cacheKey, dataSpec.uri)
                cacheFactory.createDataSource()
            } else {
                upstreamFactory.createDataSource()
            }

            listeners.forEach { source.addTransferListener(it) }
            delegate = source

            val route = if (useCache) PlaybackCacheAudit.Route.CACHE else PlaybackCacheAudit.Route.UPSTREAM
            currentRecord = audit?.beginPlaybackRequest(
                cacheKey = cacheKey,
                uri = dataSpec.uri,
                position = dataSpec.position,
                length = dataSpec.length,
                route = route,
                handoff = when (handoff) {
                    is CacheHandoffResult.LockStillHeld -> PlaybackCacheAudit.Handoff.LOCK_STILL_HELD
                    is CacheHandoffResult.AlreadyCompleted -> PlaybackCacheAudit.Handoff.ALREADY_COMPLETED
                    is CacheHandoffResult.WriterCancelledAndUnlocked -> PlaybackCacheAudit.Handoff.WRITER_CANCELLED_AND_UNLOCKED
                    is CacheHandoffResult.NoMatchingWriter -> PlaybackCacheAudit.Handoff.NO_MATCH
                },
            )

            return try {
                val length = source.open(dataSpec)
                openedLength = length
                currentRecord?.openedLength = length
                length
            } catch (e: Exception) {
                currentLease?.let { session?.releaseLease(it) }
                currentLease = null
                currentIdentity?.let {
                    session?.prefetchCoordinator?.scheduler?.onMedia3RequestFinished(it)
                }
                currentIdentity = null
                audit?.endPlaybackRequest(
                    currentRecord,
                    reachedEof = false,
                    failure = if (e is IOException) e else IOException("Failed to open DataSource", e),
                )
                currentRecord = null
                throw if (e is IOException) e else IOException("Failed to open DataSource", e)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = delegate ?: throw IOException("read() called before open()")
            return try {
                val bytesRead = source.read(buffer, offset, length)
                if (bytesRead < 0) {
                    hasReadEof = true
                } else {
                    bytesReadThisOpen += bytesRead
                    audit?.onPlaybackBytesRead(bytesRead)
                }
                bytesRead
            } catch (e: Exception) {
                throw if (e is IOException) e else IOException("Failed to read from DataSource", e)
            }
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            closeInternal()
        }

        private fun closeInternal() {
            val lease = currentLease
            currentLease = null
            if (lease != null && session != null) {
                session.releaseLease(lease)
            }

            val identity = currentIdentity
            val record = currentRecord
            val reachedEof = hasReadEof
            val bytesRead = bytesReadThisOpen
            val length = openedLength

            // Close the delegate first: CacheDataSource reports onCachedBytesRead during close, and
            // the audit attributes it through the per-thread request context installed at open().
            val source = delegate
            delegate = null
            try {
                source?.close()
            } catch (_: Throwable) {
                // Delegate close errors should not crash the player
            }

            if (identity != null) {
                if (reachedEof) {
                    // Exact completion evidence for this request's own identity and byte range only.
                    CacheCoverageVerifier.markCompleted(
                        cacheKey = identity.cacheKey,
                        startByte = identity.position,
                        endByteExclusive = if (bytesRead > 0L) identity.position + bytesRead else -1L,
                        reachedEof = true,
                        contentLengthBytes = if (length > 0L) length else -1L,
                    )
                }
                session?.prefetchCoordinator?.scheduler?.onMedia3RequestFinished(identity)
            }
            currentIdentity = null

            if (record != null) {
                // Cached bytes are authoritative from CacheDataSource's close callback. Only when the
                // request actually opened the cache factory may unaccounted bytes be attributed to
                // the cache; an upstream-routed request must never be reported as a cache hit.
                if (record.route == PlaybackCacheAudit.Route.CACHE && record.cachedBytesRead.get() == 0L) {
                    val unaccounted = bytesRead - record.upstreamBytesRead.get()
                    if (unaccounted > 0L) {
                        record.cachedBytesRead.addAndGet(unaccounted)
                    }
                }
                audit?.endPlaybackRequest(record, reachedEof, null)
            }
            currentRecord = null

            hasReadEof = false
            bytesReadThisOpen = 0L
            openedLength = -1L
        }
    }
}
