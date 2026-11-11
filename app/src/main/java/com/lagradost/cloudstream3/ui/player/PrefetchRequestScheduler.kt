package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Outcome of handing a Media3 request over from a background prefetch writer.
 *
 * [WriterCancelledAndUnlocked] is only returned when the worker completion latch actually counted
 * down, i.e. CacheWriter returned/aborted and therefore released its cache span lock. It is never
 * derived from [CacheWriter.cancel] alone.
 */
@OptIn(UnstableApi::class)
sealed interface CacheHandoffResult {
    /** Nothing was prefetching this cache key/range. Playback may open the cache normally. */
    data object NoMatchingWriter : CacheHandoffResult

    /** The requested bytes are already complete in SimpleCache; no handoff was needed. */
    data object AlreadyCompleted : CacheHandoffResult

    /** Every overlapping prefetch worker terminated and released its span lock. */
    data class WriterCancelledAndUnlocked(
        val cancelledQueuedRequests: Int,
        val releasedRunningRequests: Int,
    ) : CacheHandoffResult

    /**
     * A worker still holds a cache span lock over the requested bytes. The caller must not open a
     * cache reader for this request; it has to fall back to upstream explicitly.
     */
    data class LockStillHeld(val reason: String) : CacheHandoffResult
}

/**
 * Priority queue, deduplication registry, and bounded concurrency scheduler for media prefetching.
 * Inspired by FastStream's DownloadManager.
 *
 * Requirements:
 * - Thread-safe synchronization on a single lock object.
 * - Cache-key + byte-interval deduplication (overlap aware, not just exact DataSpec equality).
 * - Strict storage-budget enforcement: background prefetch never bypasses the limit.
 * - Worker reservation tracking to prevent collective oversubscription.
 * - Bounded parallel workers (1 to 4 max on Android).
 * - Single-writer coordination: prefetch cancels and provably yields before Media3 writes, and no
 *   worker may start writing a cache key while a Media3 playback request is using it.
 * - Complete lifecycle cancellation on release.
 */
@OptIn(UnstableApi::class)
class PrefetchRequestScheduler(
    val context: Context,
    val session: PlaybackSessionState,
    val getCache: () -> SimpleCache?,
    val getCacheDataSourceFactory: () -> CacheDataSource.Factory?,
    val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "PrefetchScheduler"
        const val INITIAL_WORKERS = 1
        const val MAX_WORKERS = 4
        const val MIN_WORKERS = 1
        const val MAX_QUEUE_DEPTH = 50
        const val MIN_DISK_RESERVE_BYTES = 50L * 1024L * 1024L // 50 MB safety margin
        const val BUFFER_SIZE = 32 * 1024 // 32 KB chunk
        const val MAX_RETRIES = 2
        const val BACKOFF_DELAY_MS = 1500L

        /**
         * Upper bound on how long a Media3 loading thread may wait for a cancelled writer to release
         * its cache span lock. This is a deadline, not a success criterion: exceeding it produces
         * [CacheHandoffResult.LockStillHeld] and an explicit upstream fallback.
         */
        const val HANDOFF_TIMEOUT_MS = 2_000L

        /** How long a worker defers a request whose cache key is currently used by playback. */
        private const val PLAYBACK_KEY_DEFER_MS = 200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val reservationLock = Any()
    private val priorityQueue = PriorityQueue<PrefetchRequest>()

    // Registry of all active requests
    val registry = ConcurrentHashMap<PrefetchRequestIdentity, PrefetchRequest>()

    // Active workers
    private val activeWriters = ConcurrentHashMap<String, CacheWriter>()
    private val activeWorkerLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val activeWorkerJobs = ConcurrentHashMap<Int, Job>()

    // In-flight media3 requests
    val media3ActiveRequests = ConcurrentHashMap.newKeySet<PrefetchRequestIdentity>()

    // Cache keys currently held by Media3 playback (reference counted across DataSources)
    private val media3KeyUsers = ConcurrentHashMap<String, AtomicInteger>()

    // Storage reservations (bytes actively claimed by running workers)
    val reservedBytes = AtomicLong(0L)

    // Concurrency state
    private val targetWorkers = AtomicInteger(INITIAL_WORKERS)
    private val activeWorkerCount = AtomicInteger(0)
    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val lastBackoffTimeMs = AtomicLong(0L)

    // Throughput tracking for adaptive scaling
    private var lastMeasuredThroughputBps = 0L
    private var consecutiveSuccesses = 0

    // Diagnostics counters
    val prefetchedBytes = AtomicLong(0L)
    val cacheHitCount = AtomicLong(0L)
    val cacheMissCount = AtomicLong(0L)
    val duplicateReuseCount = AtomicLong(0L)
    val cancelledCount = AtomicLong(0L)

    init {
        ensureWorkerPool()
    }

    private val audit: PlaybackCacheAudit?
        get() = session.cacheAudit

    fun getTargetWorkers(): Int = targetWorkers.get()

    fun getActiveWorkerCount(): Int = activeWorkerCount.get()

    fun getQueueDepth(): Int = synchronized(lock) { priorityQueue.size }

    fun isActivelyWritingKey(cacheKey: String): Boolean {
        return registry.values.any { it.status == PrefetchRequest.Status.RUNNING && it.identity.cacheKey == cacheKey }
    }

    /** True when an actual Media3 playback request currently occupies this cache key. */
    fun isKeyInPlaybackUse(cacheKey: String): Boolean =
        (media3KeyUsers[cacheKey]?.get() ?: 0) > 0

    /**
     * Enqueues or deduplicates a prefetch request.
     */
    fun enqueue(request: PrefetchRequest): PrefetchRequest {
        if (isReleased.get()) {
            request.status = PrefetchRequest.Status.CANCELLED
            return request
        }

        val identity = request.identity

        // If Media3 is currently loading this exact chunk, skip speculative prefetch
        if (media3ActiveRequests.contains(identity)) {
            request.status = PrefetchRequest.Status.CANCELLED
            return request
        }

        // Check if resource is already in SimpleCache
        val cache = getCache()
        if (cache != null) {
            if (CacheCoverageVerifier.isFullyCached(cache, identity.cacheKey, identity.position, identity.length)) {
                cacheHitCount.incrementAndGet()
                request.status = PrefetchRequest.Status.COMPLETED
                session.prefetchCoordinator?.registerCompletedRequest(request)
                return request
            }
        }

        synchronized(lock) {
            val existing = registry[identity]
            if (existing != null && !existing.isTerminal()) {
                duplicateReuseCount.incrementAndGet()
                existing.watchers++
                if (request.priority.rank < existing.priority.rank) {
                    existing.priority = request.priority
                    if (existing.status == PrefetchRequest.Status.QUEUED) {
                        priorityQueue.remove(existing)
                        priorityQueue.offer(existing)
                    }
                }
                return existing
            }

            if (priorityQueue.size >= MAX_QUEUE_DEPTH) {
                if (request.priority.rank >= PrefetchRequest.Priority.USEFUL_PREFETCH.rank) {
                    return request
                }
            }

            request.status = PrefetchRequest.Status.QUEUED
            registry[identity] = request
            priorityQueue.offer(request)
        }

        ensureWorkerPool()
        return request
    }

    /**
     * Called when Media3 begins loading a resource.
     *
     * Cancels queued/overlapping background prefetch, cancels a running prefetch writer over the
     * same byte interval, and waits for the worker's actual completion (which is what releases the
     * SimpleCache span lock). Running on Media3's loading thread, never on the player main thread.
     */
    fun onMedia3RequestStarting(identity: PrefetchRequestIdentity): CacheHandoffResult {
        media3ActiveRequests.add(identity)
        media3KeyUsers.getOrPut(identity.cacheKey) { AtomicInteger(0) }.incrementAndGet()

        val queued = mutableListOf<PrefetchRequest>()
        val running = mutableListOf<PrefetchRequest>()

        synchronized(lock) {
            for (req in registry.values) {
                if (req.isTerminal()) continue
                if (!req.identity.overlaps(identity)) continue
                when (req.status) {
                    PrefetchRequest.Status.QUEUED -> {
                        if (priorityQueue.remove(req)) {
                            req.status = PrefetchRequest.Status.CANCELLED
                            cancelledCount.incrementAndGet()
                            audit?.onPrefetchCancelled()
                            queued += req
                        }
                    }
                    PrefetchRequest.Status.RUNNING -> {
                        req.priority = PrefetchRequest.Priority.IMMEDIATE
                        running += req
                    }
                    else -> Unit
                }
            }
        }

        // Request cancellation of every overlapping writer. The flag makes a cancelled writer
        // terminal, so it can never be retried or reported as a failure.
        for (req in running) {
            req.cancelRequested = true
            runCatching { activeWriters[req.id]?.cancel() }
        }

        // Wait for the real worker completion. The latch is counted down in the worker's finally
        // block, which is only reached after CacheWriter.cache() returned (or threw) and therefore
        // after the cache span lock was released.
        var lockReleased = true
        var lockReason: String? = null
        val deadline = clock() + HANDOFF_TIMEOUT_MS
        for (req in running) {
            val latch = activeWorkerLatches[req.id]
            if (latch == null) {
                // Worker already passed its finally block between poll and here.
                if (activeWriters.containsKey(req.id)) {
                    lockReleased = false
                    lockReason = "worker ${req.id.take(8)} has no completion latch but is still writing"
                }
                continue
            }
            val remaining = max(0L, deadline - clock())
            val completed = runCatching { latch.await(remaining, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!completed) {
                lockReleased = false
                lockReason = "cache writer for keyHash=${PlaybackCacheAudit.safeKeyHash(req.identity.cacheKey)} " +
                    "did not release its span lock within ${HANDOFF_TIMEOUT_MS}ms"
                break
            }
        }

        if (lockReleased && running.any { activeWriters.containsKey(it.id) }) {
            lockReleased = false
            lockReason = "cache writer still registered after completion"
        }

        if (!lockReleased) {
            return CacheHandoffResult.LockStillHeld(
                lockReason ?: "cache writer did not release its span lock in time"
            )
        }

        val cache = getCache()
        val alreadyCompleted = cache != null &&
            CacheCoverageVerifier.isFullyCached(cache, identity)
        if (alreadyCompleted) return CacheHandoffResult.AlreadyCompleted

        return if (queued.isNotEmpty() || running.isNotEmpty()) {
            CacheHandoffResult.WriterCancelledAndUnlocked(queued.size, running.size)
        } else {
            CacheHandoffResult.NoMatchingWriter
        }
    }

    fun onMedia3RequestFinished(identity: PrefetchRequestIdentity) {
        media3ActiveRequests.remove(identity)
        media3KeyUsers[identity.cacheKey]?.let { counter ->
            if (counter.decrementAndGet() <= 0) {
                media3KeyUsers.remove(identity.cacheKey, counter)
            }
        }
    }

    /**
     * Cancels requests obsolete due to a playback seek.
     */
    fun onSeek(newPositionUs: Long) {
        val obsoleteRequests = mutableListOf<PrefetchRequest>()

        synchronized(lock) {
            val iterator = priorityQueue.iterator()
            while (iterator.hasNext()) {
                val req = iterator.next()
                if (!req.isInit && req.mediaTimeUs < newPositionUs - 10_000_000L) {
                    iterator.remove()
                    req.status = PrefetchRequest.Status.CANCELLED
                    cancelledCount.incrementAndGet()
                    audit?.onPrefetchCancelled()
                    obsoleteRequests += req
                }
            }

            for (req in registry.values) {
                if (!req.isInit && req.mediaTimeUs < newPositionUs - 10_000_000L) {
                    if (req.status != PrefetchRequest.Status.COMPLETED && req.status != PrefetchRequest.Status.CANCELLED) {
                        req.status = PrefetchRequest.Status.CANCELLED
                        cancelledCount.incrementAndGet()
                        audit?.onPrefetchCancelled()
                        obsoleteRequests += req
                    }
                }
            }
        }

        for (req in obsoleteRequests) {
            req.cancelRequested = true
            activeWriters[req.id]?.cancel()
        }
    }

    fun ensureWorkerPool() {
        if (isReleased.get()) return
        val target = targetWorkers.get()
        for (i in 0 until target) {
            if (activeWorkerJobs[i]?.isActive != true) {
                activeWorkerJobs[i] = scope.launch {
                    runWorkerLoop(i)
                }
            }
        }
    }

    private suspend fun runWorkerLoop(workerIndex: Int) {
        while (scope.isActive && !isReleased.get() && session.lifecycle != PlaybackSessionState.Lifecycle.RELEASED) {
            try {
                if (workerIndex >= targetWorkers.get()) {
                    break
                }

                if (isPaused.get()) {
                    delay(200)
                    continue
                }

                val request = synchronized(lock) {
                    priorityQueue.poll()
                }

                if (request == null) {
                    delay(100)
                    continue
                }

                // Single-writer rule: never write bytes a Media3 playback request is reading.
                if (request.status == PrefetchRequest.Status.QUEUED && isKeyInPlaybackUse(request.identity.cacheKey)) {
                    synchronized(lock) {
                        if (!request.isTerminal()) priorityQueue.offer(request)
                    }
                    delay(PLAYBACK_KEY_DEFER_MS)
                    continue
                }

                // Atomically check storage capacity and claim worker reservation
                if (!tryAdmitAndReserve(request)) {
                    synchronized(lock) {
                        if (!request.isTerminal()) {
                            priorityQueue.offer(request)
                        }
                    }
                    delay(500)
                    continue
                }

                executeRequest(request)
            } catch (c: CancellationException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Worker $workerIndex loop caught unexpected error: ${t.message}", t)
                delay(200)
            }
        }
    }

    private suspend fun executeRequest(request: PrefetchRequest) {
        val estimatedBytes = if (request.identity.length > 0) request.identity.length else 512 * 1024L
        val cache = getCache()
        val factory = getCacheDataSourceFactory()
        if (cache == null || factory == null) {
            reservedBytes.updateAndGet { cur -> maxOf(0L, cur - estimatedBytes) }
            request.status = PrefetchRequest.Status.FAILED
            return
        }

        // Playback owns this key right now: do not open a second overlapping writer.
        if (isKeyInPlaybackUse(request.identity.cacheKey)) {
            synchronized(lock) {
                if (!request.isTerminal()) {
                    request.status = PrefetchRequest.Status.QUEUED
                    priorityQueue.offer(request)
                }
            }
            reservedBytes.updateAndGet { cur -> maxOf(0L, cur - estimatedBytes) }
            return
        }

        // Verify if already cached (exact evidence only)
        if (CacheCoverageVerifier.isFullyCached(cache, request.identity.cacheKey, request.identity.position, request.identity.length)) {
            reservedBytes.updateAndGet { cur -> maxOf(0L, cur - estimatedBytes) }
            cacheHitCount.incrementAndGet()
            request.status = PrefetchRequest.Status.COMPLETED
            session.prefetchCoordinator?.registerCompletedRequest(request)
            return
        }

        cacheMissCount.incrementAndGet()
        request.status = PrefetchRequest.Status.RUNNING
        activeWorkerCount.incrementAndGet()
        audit?.onPrefetchStarted()

        val latch = CountDownLatch(1)
        activeWorkerLatches[request.id] = latch

        // Acquire active session lease while downloading
        val lease = session.acquireLease(
            key = request.identity.cacheKey,
            uri = request.identity.uri,
            rangeStart = request.identity.position,
            rangeLength = request.identity.length,
            ttlMs = 120_000L,
        )

        val cacheDataSource = factory.createDataSource()
        var writtenBytes = 0L
        var reusedBytes = 0L
        val writer = CacheWriter(
            cacheDataSource,
            request.dataSpec,
            ByteArray(BUFFER_SIZE),
            { total, bytesAlreadyCached, newBytesCached ->
                request.totalBytes = total
                request.bytesDownloaded = bytesAlreadyCached + newBytesCached
                writtenBytes += newBytesCached
                reusedBytes = bytesAlreadyCached
                prefetchedBytes.addAndGet(newBytesCached)
                audit?.onPrefetchBytesWritten(newBytesCached, 0L)
                audit?.onPrefetchUpstreamBytes(newBytesCached)
            }
        )
        activeWriters[request.id] = writer

        val startTime = clock()
        try {
            writer.cache()

            // Exact completion evidence: end = requested position + resolved request length.
            val resolvedLength = when {
                request.identity.length > 0L -> request.identity.length
                request.totalBytes > 0L -> request.totalBytes
                request.bytesDownloaded > 0L -> request.bytesDownloaded
                else -> -1L
            }
            CacheCoverageVerifier.markCompleted(
                cacheKey = request.identity.cacheKey,
                startByte = request.identity.position,
                endByteExclusive = if (resolvedLength > 0L) request.identity.position + resolvedLength else -1L,
                reachedEof = true,
                contentLengthBytes = if (request.totalBytes > 0L) request.totalBytes else resolvedLength,
                cache = cache,
            )
            if (reusedBytes > 0L) audit?.onPrefetchBytesWritten(0L, reusedBytes)

            request.status = PrefetchRequest.Status.COMPLETED
            audit?.onPrefetchCompleted(clock() - startTime)
            session.prefetchCoordinator?.registerCompletedRequest(request)
            onDownloadSuccess(request.bytesDownloaded, clock() - startTime)
        } catch (e: Throwable) {
            val wasCancelled = request.cancelRequested ||
                isReleased.get() ||
                e is CancellationException ||
                e is java.io.InterruptedIOException ||
                e is InterruptedException ||
                (e is IOException && e.message?.contains("cancel", ignoreCase = true) == true)
            if (wasCancelled) {
                request.status = PrefetchRequest.Status.CANCELLED
                cancelledCount.incrementAndGet()
                audit?.onPrefetchCancelled()
            } else {
                Log.w(TAG, "prefetch failed for keyHash=${PlaybackCacheAudit.safeKeyHash(request.identity.cacheKey)}: ${e.message}")
                request.error = e
                request.status = PrefetchRequest.Status.FAILED
                audit?.onPrefetchFailed()
                onDownloadError(e)

                if (request.retryCount < MAX_RETRIES && isRetryableError(e)) {
                    request.retryCount++
                    request.status = PrefetchRequest.Status.QUEUED
                    synchronized(lock) { priorityQueue.offer(request) }
                }
            }
        } finally {
            activeWriters.remove(request.id)
            activeWorkerLatches.remove(request.id)
            latch.countDown()

            reservedBytes.updateAndGet { cur -> maxOf(0L, cur - estimatedBytes) }
            activeWorkerCount.decrementAndGet()
            session.releaseLease(lease)
            runCatching { cacheDataSource.close() }
        }
    }

    /**
     * Atomically validates available storage headroom and claims worker reservation under reservationLock.
     * Prevents collective oversubscription across parallel workers.
     */
    fun tryAdmitAndReserve(request: PrefetchRequest): Boolean {
        // 1. Guard device filesystem free space
        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)

        if (freeDisk < MIN_DISK_RESERVE_BYTES) {
            Log.w(TAG, "device disk space critically low ($freeDisk bytes), pausing prefetch")
            return false
        }

        val estimatedBytes = if (request.identity.length > 0) request.identity.length else 512 * 1024L

        synchronized(reservationLock) {
            val currentReserved = reservedBytes.get()
            val result = PlaybackCacheManager.ensureRoomForPrefetch(
                session = session,
                incomingBytes = estimatedBytes,
                reservedBytes = currentReserved,
                snapshot = session.prefetchCoordinator?.latestSnapshot,
            )

            if (!result.success) {
                val isMedia3Playback = request.priority == PrefetchRequest.Priority.IMMEDIATE &&
                    media3ActiveRequests.contains(request.identity)
                if (!isMedia3Playback) {
                    return false
                }
            }

            reservedBytes.addAndGet(estimatedBytes)
            return true
        }
    }

    /**
     * Storage-limit check: Ensures background prefetch NEVER bypasses the configured limit.
     */
    fun hasAvailableStorage(request: PrefetchRequest): Boolean {
        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (freeDisk < MIN_DISK_RESERVE_BYTES) return false

        val estimatedBytes = if (request.identity.length > 0) request.identity.length else 512 * 1024L
        val limit = if (session.prefetchLimitBytes > 0L) session.prefetchLimitBytes else PlaybackCacheManager.maxBytes()
        if (limit <= 0L) return true
        val current = PlaybackCacheManager.usageBytes() + reservedBytes.get()
        if (current + estimatedBytes > limit) {
            val isMedia3Playback = request.priority == PrefetchRequest.Priority.IMMEDIATE &&
                media3ActiveRequests.contains(request.identity)
            return isMedia3Playback
        }
        return true
    }

    private fun onDownloadSuccess(bytes: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val currentBps = (bytes * 8000L) / durationMs
        consecutiveSuccesses++

        if (consecutiveSuccesses >= 3 && targetWorkers.get() < MAX_WORKERS) {
            if (lastMeasuredThroughputBps > 0 && currentBps >= (lastMeasuredThroughputBps * 0.9)) {
                val newTarget = min(MAX_WORKERS, targetWorkers.incrementAndGet())
                Log.i(TAG, "increasing workers to $newTarget (throughput: ${currentBps / 1000} kbps)")
                consecutiveSuccesses = 0
                ensureWorkerPool()
            }
        }
        lastMeasuredThroughputBps = currentBps
    }

    private fun onDownloadError(error: Throwable) {
        consecutiveSuccesses = 0
        val msg = error.message.orEmpty()
        val isRateLimitOrOverload = msg.contains("429") || msg.contains("503") ||
            error is java.net.SocketTimeoutException || error is java.net.ConnectException

        if (isRateLimitOrOverload) {
            val now = clock()
            if (now - lastBackoffTimeMs.get() > BACKOFF_DELAY_MS) {
                lastBackoffTimeMs.set(now)
                targetWorkers.set(MIN_WORKERS)
                Log.w(TAG, "rate limit or timeout detected, reducing concurrency to $MIN_WORKERS")
                scope.launch {
                    isPaused.set(true)
                    delay(BACKOFF_DELAY_MS)
                    isPaused.set(false)
                }
            }
        }
    }

    private fun isRetryableError(e: Throwable): Boolean {
        if (e is CancellationException) return false
        val msg = e.message.orEmpty()
        if (msg.contains("404") || msg.contains("403") || msg.contains("401")) return false
        return true
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            for (req in registry.values) {
                req.cancelRequested = true
            }
            for ((_, writer) in activeWriters) {
                runCatching { writer.cancel() }
            }
            activeWriters.clear()
            activeWorkerLatches.values.forEach { it.countDown() }
            activeWorkerLatches.clear()

            for ((_, job) in activeWorkerJobs) {
                job.cancel()
            }
            activeWorkerJobs.clear()

            media3ActiveRequests.clear()
            media3KeyUsers.clear()
            synchronized(lock) {
                priorityQueue.clear()
            }
            registry.clear()
            reservedBytes.set(0L)
            scope.cancel()
        }
    }
}
