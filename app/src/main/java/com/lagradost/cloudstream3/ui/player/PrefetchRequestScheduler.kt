package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
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
 * - One deduplicated request registry per playback session.
 * - URL/range/headers deduplication. Multiple consumers watching same request.
 * - Bounded priority queue (IMMEDIATE, NEXT_MEDIA, FORWARD_MEDIA, USEFUL_PREFETCH, BACKWARD_SEEK, SPECULATIVE).
 * - Bounded parallel download workers: start with 1-2 workers, max 4 workers on Android.
 * - Adaptive worker count: increase on measured throughput improvement, reduce on 429/503/timeout.
 * - Respect cache capacity and disk storage safety reserve (never fill device disk).
 * - Cancellation on seek and session release.
 * - Single-writer coordination: prevent races between prefetch and Media3.
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
        const val MIN_DISK_RESERVE_BYTES = 50L * 1024L * 1024L // 50 MB reserve
        const val BUFFER_SIZE = 32 * 1024 // 32 KB streaming chunk
        const val MAX_RETRIES = 2
        const val BACKOFF_DELAY_MS = 1500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val priorityQueue = PriorityQueue<PrefetchRequest>()

    // Registry of all known requests (queued, running, completed, etc.)
    val registry = ConcurrentHashMap<PrefetchRequestIdentity, PrefetchRequest>()

    // Active workers
    private val activeWriters = ConcurrentHashMap<String, CacheWriter>()
    private val activeWorkerJobs = ConcurrentHashMap<Int, Job>()

    // In-flight media3 upstream downloads (to prevent prefetch from racing with Media3)
    val media3ActiveRequests = ConcurrentHashMap.newKeySet<PrefetchRequestIdentity>()

    // Concurrency state
    private val targetWorkers = AtomicInteger(INITIAL_WORKERS)
    private val activeWorkerCount = AtomicInteger(0)
    private val isPaused = AtomicBoolean(false)
    private val lastBackoffTimeMs = AtomicLong(0L)

    // Throughput tracking for concurrency adaptation
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

    fun getQueueDepth(): Int = synchronized(priorityQueue) { priorityQueue.size }

    /**
     * Enqueues or deduplicates a prefetch request.
     * If an identical request is already registered, attaches as watcher and promotes priority if higher.
     */
    suspend fun enqueue(request: PrefetchRequest): PrefetchRequest {
        val identity = request.identity

        // If Media3 is currently loading this exact resource upstream, skip speculative prefetch
        if (media3ActiveRequests.contains(identity)) {
            Log.d(TAG, "media3 is already loading ${identity.cacheKey}, skipping prefetch")
            request.status = PrefetchRequest.Status.CANCELLED
            return request
        }

        // Check if resource is already completely cached in SimpleCache
        val cache = getCache()
        if (cache != null) {
            val length = if (identity.length > 0) identity.length else 1L
            if (cache.isCached(identity.cacheKey, identity.position, length)) {
                cacheHitCount.incrementAndGet()
                request.status = PrefetchRequest.Status.COMPLETED
                return request
            }
        }

        queueMutex.withLock {
            val existing = registry[identity]
            if (existing != null && !existing.isTerminal()) {
                // Deduplicate and promote priority if new request has higher priority
                duplicateReuseCount.incrementAndGet()
                existing.watchers++
                if (request.priority.rank < existing.priority.rank) {
                    existing.priority = request.priority
                    // Re-sort priority queue if still queued
                    if (existing.status == PrefetchRequest.Status.QUEUED) {
                        priorityQueue.remove(existing)
                        priorityQueue.offer(existing)
                    }
                }
                return existing
            }

            // Fresh request
            request.status = PrefetchRequest.Status.QUEUED
            registry[identity] = request
            priorityQueue.offer(request)
        }

        ensureWorkerPool()
        return request
    }

    /**
     * Called when Media3 begins loading a resource.
     * Prevents duplicate upstream downloads and coordinates single-writer cache access.
     */
    fun onMedia3RequestStarting(identity: PrefetchRequestIdentity): PrefetchRequest? {
        media3ActiveRequests.add(identity)
        val inFlight = registry[identity]
        if (inFlight != null) {
            inFlight.priority = PrefetchRequest.Priority.IMMEDIATE
            if (inFlight.status == PrefetchRequest.Status.RUNNING) {
                // If it just started or has low progress, cancel prefetch writer so Media3 takes over without contention
                val bytesDone = inFlight.bytesDownloaded
                if (bytesDone < 64 * 1024L) {
                    activeWriters[inFlight.id]?.cancel()
                }
            } else if (inFlight.status == PrefetchRequest.Status.QUEUED) {
                // Dequeue so prefetch worker does not duplicate Media3's load
                synchronized(priorityQueue) { priorityQueue.remove(inFlight) }
                inFlight.status = PrefetchRequest.Status.CANCELLED
            }
        }
        return inFlight
    }

    fun onMedia3RequestFinished(identity: PrefetchRequestIdentity) {
        media3ActiveRequests.remove(identity)
    }

    /**
     * Promotes or cancels requests on playback seek.
     */
    suspend fun onSeek(newPositionUs: Long) {
        queueMutex.withLock {
            val iterator = priorityQueue.iterator()
            while (iterator.hasNext()) {
                val req = iterator.next()
                // Drop obsolete requests far behind the new seek position
                if (!req.isInit && req.mediaTimeUs < newPositionUs - 10_000_000L) {
                    iterator.remove()
                    req.status = PrefetchRequest.Status.CANCELLED
                    cancelledCount.incrementAndGet()
                }
            }
        }

        // Cancel running requests that are far behind the seek point
        for ((id, writer) in activeWriters) {
            val req = registry.values.firstOrNull { it.id == id } ?: continue
            if (!req.isInit && req.mediaTimeUs < newPositionUs - 10_000_000L) {
                writer.cancel()
                req.status = PrefetchRequest.Status.CANCELLED
                cancelledCount.incrementAndGet()
            }
        }
    }

    private fun ensureWorkerPool() {
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
        while (scope.isActive && session.lifecycle != PlaybackSessionState.Lifecycle.RELEASED) {
            if (workerIndex >= targetWorkers.get()) {
                // Worker count was reduced, exit this extra worker
                break
            }

            if (isPaused.get()) {
                delay(200)
                continue
            }

            val request = queueMutex.withLock {
                priorityQueue.poll()
            }

            if (request == null) {
                delay(100)
                continue
            }

            // Check if storage capacity or disk space is full
            if (!hasAvailableStorage(request)) {
                // Requeue with delay or wait
                queueMutex.withLock {
                    if (!request.isTerminal()) {
                        priorityQueue.offer(request)
                    }
                }
                delay(500)
                continue
            }

            executeRequest(request)
        }
    }

    private suspend fun executeRequest(request: PrefetchRequest) {
        val cache = getCache()
        val factory = getCacheDataSourceFactory()
        if (cache == null || factory == null) {
            request.status = PrefetchRequest.Status.FAILED
            return
        }

        // Check if already cached
        val length = if (request.identity.length > 0) request.identity.length else 1L
        if (cache.isCached(request.identity.cacheKey, request.identity.position, length)) {
            cacheHitCount.incrementAndGet()
            request.status = PrefetchRequest.Status.COMPLETED
            return
        }

        cacheMissCount.incrementAndGet()
        request.status = PrefetchRequest.Status.RUNNING
        activeWorkerCount.incrementAndGet()

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
            request.status = PrefetchRequest.Status.COMPLETED
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
                    queueMutex.withLock { priorityQueue.offer(request) }
                }
            }
        } finally {
            activeWriters.remove(request.id)
            activeWorkerCount.decrementAndGet()
            session.releaseLease(lease)
            runCatching { cacheDataSource.close() }
        }
    }

    private fun hasAvailableStorage(request: PrefetchRequest): Boolean {
        // Guard device filesystem free space
        val freeDisk = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)

        if (freeDisk < MIN_DISK_RESERVE_BYTES) {
            Log.w(TAG, "device disk space critically low ($freeDisk bytes), pausing prefetch")
            return false
        }

        // Guard PlaybackCacheManager capacity limit
        val currentUsage = PlaybackCacheManager.usageBytes()
        val maxAllowed = if (session.prefetchLimitBytes > 0L) {
            session.prefetchLimitBytes
        } else {
            PlaybackCacheManager.maxBytes()
        }

        if (maxAllowed > 0L && currentUsage >= maxAllowed) {
            // Only allow top priority requests when near capacity
            return request.priority == PrefetchRequest.Priority.IMMEDIATE ||
                request.priority == PrefetchRequest.Priority.NEXT_MEDIA
        }

        return true
    }

    private fun onDownloadSuccess(bytes: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val currentBps = (bytes * 8000L) / durationMs
        consecutiveSuccesses++

        // If network is consistently fast and queue has pending work, adaptively increase workers
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
                // Drop concurrency to minimum on server throttling
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
        for ((_, writer) in activeWriters) {
            runCatching { writer.cancel() }
        }
        activeWriters.clear()
        media3ActiveRequests.clear()
        synchronized(priorityQueue) {
            priorityQueue.clear()
        }
        registry.clear()
    }
}
