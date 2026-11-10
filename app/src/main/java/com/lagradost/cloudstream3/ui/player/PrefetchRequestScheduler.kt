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
 * Priority queue, deduplication registry, and bounded concurrency scheduler for media prefetching.
 * Inspired by FastStream's DownloadManager.
 *
 * Requirements:
 * - Thread-safe synchronization on a single lock object.
 * - URL/range/headers deduplication with exact cache-key matching.
 * - Strict storage-budget enforcement: background prefetch never bypasses the limit.
 * - Worker reservation tracking to prevent collective oversubscription.
 * - Bounded parallel workers (1 to 4 max on Android).
 * - Single-writer coordination: prefetch cancels and cleanly yields before Media3 writes.
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
        const val HANDOFF_TIMEOUT_MS = 100L
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

    // In-flight media3 upstream downloads
    val media3ActiveRequests = ConcurrentHashMap.newKeySet<PrefetchRequestIdentity>()

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

    fun getTargetWorkers(): Int = targetWorkers.get()

    fun getActiveWorkerCount(): Int = activeWorkerCount.get()

    fun getQueueDepth(): Int = synchronized(lock) { priorityQueue.size }

    fun isActivelyWritingKey(cacheKey: String): Boolean {
        return registry.values.any { it.status == PrefetchRequest.Status.RUNNING && it.identity.cacheKey == cacheKey }
    }

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
     * Cancels any overlapping background prefetch and coordinates single-writer cache access.
     */
    fun onMedia3RequestStarting(identity: PrefetchRequestIdentity): PrefetchRequest? {
        media3ActiveRequests.add(identity)

        var matchedRequest: PrefetchRequest? = null
        var latchToAwait: CountDownLatch? = null

        synchronized(lock) {
            val inFlight = registry[identity]
            if (inFlight != null) {
                matchedRequest = inFlight
                inFlight.priority = PrefetchRequest.Priority.IMMEDIATE

                if (inFlight.status == PrefetchRequest.Status.QUEUED) {
                    priorityQueue.remove(inFlight)
                    inFlight.status = PrefetchRequest.Status.CANCELLED
                    cancelledCount.incrementAndGet()
                } else if (inFlight.status == PrefetchRequest.Status.RUNNING) {
                    // Cancel the background prefetch writer so Media3 takes over without conflicting span locks
                    activeWriters[inFlight.id]?.cancel()
                    latchToAwait = activeWorkerLatches[inFlight.id]
                }
            }
        }

        // Await background writer shutdown on loader thread (never on main thread)
        latchToAwait?.let { latch ->
            runCatching {
                latch.await(HANDOFF_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }

        return matchedRequest
    }

    fun onMedia3RequestFinished(identity: PrefetchRequestIdentity) {
        media3ActiveRequests.remove(identity)
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
                    obsoleteRequests += req
                }
            }

            for (req in registry.values) {
                if (!req.isInit && req.mediaTimeUs < newPositionUs - 10_000_000L) {
                    if (req.status != PrefetchRequest.Status.COMPLETED && req.status != PrefetchRequest.Status.CANCELLED) {
                        req.status = PrefetchRequest.Status.CANCELLED
                        cancelledCount.incrementAndGet()
                        obsoleteRequests += req
                    }
                }
            }
        }

        for (req in obsoleteRequests) {
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

        // Verify if already cached
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
        val writer = CacheWriter(
            cacheDataSource,
            request.dataSpec,
            ByteArray(BUFFER_SIZE),
            { total, bytesAlreadyCached, newBytesCached ->
                request.totalBytes = total
                request.bytesDownloaded = bytesAlreadyCached + newBytesCached
                prefetchedBytes.addAndGet(newBytesCached)
            }
        )
        activeWriters[request.id] = writer

        val startTime = clock()
        try {
            writer.cache()
            CacheCoverageVerifier.markCompleted(request.identity.cacheKey)
            request.status = PrefetchRequest.Status.COMPLETED
            session.prefetchCoordinator?.registerCompletedRequest(request)
            onDownloadSuccess(request.bytesDownloaded, clock() - startTime)
        } catch (e: Throwable) {
            if (e is CancellationException || (e is IOException && e.message?.contains("canceled", ignoreCase = true) == true)) {
                request.status = PrefetchRequest.Status.CANCELLED
                cancelledCount.incrementAndGet()
            } else {
                Log.w(TAG, "prefetch failed for ${request.identity.cacheKey}: ${e.message}")
                request.error = e
                request.status = PrefetchRequest.Status.FAILED
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
            synchronized(lock) {
                priorityQueue.clear()
            }
            registry.clear()
            reservedBytes.set(0L)
            scope.cancel()
        }
    }
}
