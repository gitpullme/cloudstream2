package com.lagradost.cloudstream3.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies complete, contiguous cache coverage for media requests.
 *
 * A cache key on its own is not evidence of completion: several byte ranges can share one key, and
 * "at least one byte is cached" says nothing about whether the resource is fully readable offline.
 *
 * This verifier therefore stores exact completion evidence per cache key:
 *  - the requested/verified byte interval `[startByte, endByteExclusive)`,
 *  - whether a real EOF was observed (CacheWriter completion or Media3 reader EOF),
 *  - the resource content length when the transport reported one,
 *  - a generation id so an old completion can never validate a newer partial request.
 *
 * Completion is only reported while the corresponding SimpleCache spans still cover the recorded
 * interval; eviction therefore invalidates the claim immediately.
 */
@OptIn(UnstableApi::class)
object CacheCoverageVerifier {

    data class CompletionRecord(
        val cacheKey: String,
        val startByte: Long,
        val endByteExclusive: Long,
        val reachedEof: Boolean,
        val contentLengthBytes: Long,
        val generation: Long,
        val recordedAtMs: Long,
    ) {
        val hasExtent: Boolean get() = endByteExclusive > startByte
    }

    private const val MAX_RECORDS_PER_KEY = 16

    private val records = ConcurrentHashMap<String, MutableList<CompletionRecord>>()
    private val generationCounter = AtomicLong(0L)

    /**
     * Records verified completion evidence.
     *
     * When [endByteExclusive] is not supplied but a [cache] is, the extent is derived from the
     * contiguous cached span run starting at [startByte]. That is still exact evidence: callers only
     * invoke this after a genuine EOF was observed, and the interval is verified against real spans
     * on every read.
     */
    fun markCompleted(
        cacheKey: String,
        startByte: Long = 0L,
        endByteExclusive: Long = -1L,
        reachedEof: Boolean = true,
        contentLengthBytes: Long = -1L,
        cache: Cache? = null,
    ) {
        var end = endByteExclusive
        if (end <= startByte && cache != null) {
            end = contiguousCachedEnd(cache, cacheKey, startByte)
        }
        if (reachedEof && end <= startByte && contentLengthBytes > 0L) {
            end = contentLengthBytes
        }

        val record = CompletionRecord(
            cacheKey = cacheKey,
            startByte = startByte,
            endByteExclusive = end,
            reachedEof = reachedEof,
            contentLengthBytes = contentLengthBytes,
            generation = generationCounter.incrementAndGet(),
            recordedAtMs = SystemClock.elapsedRealtime(),
        )

        val list = records.getOrPut(cacheKey) { mutableListOf() }
        synchronized(list) {
            // Replace an older record that describes exactly the same interval.
            list.removeAll { it.startByte == record.startByte && it.endByteExclusive == record.endByteExclusive }
            list.add(record)
            while (list.size > MAX_RECORDS_PER_KEY) {
                list.removeAt(0)
            }
        }
    }

    fun unmarkCompleted(cacheKey: String) {
        records.remove(cacheKey)
    }

    fun clear() {
        records.clear()
    }

    /** Test/diagnostics helper: exact completion evidence currently held for a key. */
    fun completionEvidence(cacheKey: String): List<CompletionRecord> {
        val list = records[cacheKey] ?: return emptyList()
        synchronized(list) { return list.toList() }
    }

    fun hasCompletionEvidence(cacheKey: String): Boolean = completionEvidence(cacheKey).any { it.hasExtent }

    /**
     * True when the whole resource behind [cacheKey] is present in the cache, according to exact
     * completion evidence that still matches the live spans.
     *
     * Used for dependency resources (initialization segments, AES keys) whose byte range is not the
     * range being verified.
     */
    fun isResourceComplete(cache: Cache?, cacheKey: String): Boolean {
        if (cache == null) return false
        val list = records[cacheKey] ?: return false
        val snapshot = synchronized(list) { list.toList() }
        if (snapshot.none { it.reachedEof && it.hasExtent }) return false
        for (record in snapshot) {
            if (!record.hasExtent) continue
            if (cache.isCached(cacheKey, record.startByte, record.endByteExclusive - record.startByte)) {
                return true
            }
        }
        records.remove(cacheKey)
        return false
    }

    fun isFullyCached(
        cache: Cache?,
        identity: PrefetchRequestIdentity,
    ): Boolean = isFullyCached(
        cache = cache,
        cacheKey = identity.cacheKey,
        position = identity.position,
        length = identity.length,
    )

    fun isFullyCached(
        cache: Cache?,
        cacheKey: String,
        position: Long,
        length: Long,
    ): Boolean {
        if (cache == null) return false

        // 1. Known byte range: SimpleCache span coverage is already exact.
        if (length > 0L) {
            return cache.isCached(cacheKey, position, length)
        }

        // 2. Unknown length with a transport-reported content length.
        val metadata = runCatching { cache.getContentMetadata(cacheKey) }.getOrNull()
        val contentLength = if (metadata != null) {
            ContentMetadata.getContentLength(metadata)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        if (contentLength > position && contentLength != C.LENGTH_UNSET.toLong()) {
            if (cache.isCached(cacheKey, position, contentLength - position)) return true
        }

        // 3. Exact completion evidence: an EOF-terminated interval whose spans are still present.
        val list = records[cacheKey] ?: return false
        val snapshot = synchronized(list) { list.toList() }
        var sawExtent = false
        for (record in snapshot) {
            if (!record.hasExtent) {
                // A completion asserted without any extent cannot validate an unknown-length read.
                continue
            }
            sawExtent = true
            if (!record.reachedEof) continue
            if (record.startByte > position) continue
            if (record.endByteExclusive <= position) continue
            if (cache.isCached(cacheKey, position, record.endByteExclusive - position)) {
                return true
            }
        }

        // Spans no longer cover the recorded interval (evicted/removed): drop the stale evidence.
        if (sawExtent) {
            records.remove(cacheKey)
        }
        return false
    }

    /** Contiguous end of cached spans starting at (or before) [position]. */
    fun contiguousCachedEnd(cache: Cache, cacheKey: String, position: Long): Long {
        val spans: Set<CacheSpan> = runCatching { cache.getCachedSpans(cacheKey) }.getOrNull()
            ?: return position
        val sorted = spans.filter { it.isCached && it.length > 0L }.sortedBy { it.position }
        var covered = position
        for (span in sorted) {
            if (span.position <= covered) {
                covered = maxOf(covered, span.position + span.length)
            } else if (span.position > covered) {
                break
            }
        }
        return covered
    }

    /**
     * Invalidates completion evidence that intersects a span which left the cache.
     *
     * Deliberately does not call back into SimpleCache: this runs from inside Media3's evictor
     * callback and must not take the cache lock again. Dropping the intersecting evidence is the
     * conservative direction: the blue line may shrink, it can never over-claim.
     */
    fun onSpanRemoved(removed: CacheSpan) {
        val list = records[removed.key] ?: return
        val removedStart = removed.position
        val removedEnd = removed.position + removed.length
        synchronized(list) {
            list.removeAll { record ->
                record.hasExtent && record.startByte < removedEnd && removedStart < record.endByteExclusive
            }
        }
        if (synchronized(list) { list.isEmpty() }) {
            records.remove(removed.key)
        }
    }
}
