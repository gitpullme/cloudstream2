package com.lagradost.cloudstream3.ui.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import com.lagradost.cloudstream3.BuildConfig
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Explicit network conditions observed by [PlaybackNetworkMonitor].
 */
enum class NetworkState {
    /** No usable transfer history yet. */
    UNKNOWN,
    /** Fast, stable connection with adequate headroom. */
    HEALTHY,
    /** High latency, high variance, or low throughput link. */
    DEGRADED,
    /** An active request is in flight but has ceased delivering bytes or headers. */
    STALLED,
    /** Connection failed or network interface is down. */
    OFFLINE
}

/**
 * Measures actual upstream request behaviour with session scoping.
 *
 * Media3's [TransferListener] only sees the network data source. When a CacheDataSource serves a
 * range from disk, no network transfer is created, which is exactly what we want: cached bytes
 * must never be mistaken for network throughput.
 *
 * Samples are exponentially weighted, outliers are protected against via trimmed samples, and
 * active stalls are detected in real-time.
 */
@OptIn(UnstableApi::class)
class PlaybackNetworkMonitor(
    val sessionId: String? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : TransferListener {

    private class RequestState(
        val initRealtimeMs: Long,
        val gapSincePreviousEndMs: Long,
        val uri: String,
    ) {
        var startRealtimeMs: Long = 0L
        var firstByteRealtimeMs: Long = 0L
        var lastByteRealtimeMs: Long = 0L
        var bytes: Long = 0L
        var isFailed: Boolean = false
        var isAborted: Boolean = false
    }

    private val lock = Any()
    private val requests = IdentityHashMap<DataSource, RequestState>()
    private val recentSamplesBps = mutableListOf<Double>()

    private var ewmaBps = 0.0
    private var longTermEwmaBps = 0.0
    private var variance = 0.0
    private var peakBps = 0L
    private var startupEwmaMs = 0.0
    private var lastSchedulingGapEwmaMs = 0.0
    private var bodyTransferEwmaMs = 0.0
    private var samples = 0
    private var lastLogRealtimeMs = 0L
    private var lastPhaseLogRealtimeMs = 0L
    private var lastTransferEndRealtimeMs = 0L
    private var lastUsefulTransferRealtimeMs = 0L
    private var offlineRealtimeMs = 0L

    fun reset() {
        synchronized(lock) {
            requests.clear()
            recentSamplesBps.clear()
            ewmaBps = 0.0
            longTermEwmaBps = 0.0
            variance = 0.0
            peakBps = 0L
            startupEwmaMs = 0.0
            lastSchedulingGapEwmaMs = 0.0
            bodyTransferEwmaMs = 0.0
            samples = 0
            lastLogRealtimeMs = 0L
            lastPhaseLogRealtimeMs = 0L
            lastTransferEndRealtimeMs = 0L
            lastUsefulTransferRealtimeMs = 0L
            offlineRealtimeMs = 0L
        }
    }

    fun recordConnectionFailure() {
        synchronized(lock) {
            offlineRealtimeMs = clock()
        }
    }

    fun recordConnectionRecovery() {
        synchronized(lock) {
            offlineRealtimeMs = 0L
        }
    }

    fun onTransferError(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = clock()
        synchronized(lock) {
            val state = requests.remove(source)
            if (state != null) {
                state.isFailed = true
            }
            offlineRealtimeMs = now
        }
    }

    fun onTransferAborted(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        synchronized(lock) {
            val state = requests.remove(source)
            if (state != null) {
                state.isAborted = true
            }
        }
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = clock()
        synchronized(lock) {
            pruneAbandonedRequestsLocked(now)

            val gap = if (lastTransferEndRealtimeMs > 0L) {
                (now - lastTransferEndRealtimeMs).coerceAtLeast(0L)
            } else {
                -1L
            }
            requests[source] = RequestState(now, gap, dataSpec.uri.toString())
            if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                Log.d(
                    TAG,
                    "net init [${sessionId ?: "default"}] gap=${if (gap >= 0L) "${gap}ms" else "first"} " +
                        "uri=${shortUri(dataSpec.uri.toString())} pos=${dataSpec.position}"
                )
            }
        }
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = clock()
        synchronized(lock) {
            val state = requests[source] ?: RequestState(now, -1L, dataSpec.uri.toString())
                .also { requests[source] = it }
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                Log.d(
                    TAG,
                    "net headers [${sessionId ?: "default"}] wait=${(now - state.initRealtimeMs).coerceAtLeast(0L)}ms " +
                        "uri=${shortUri(dataSpec.uri.toString())}"
                )
            }
        }
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = clock()
        synchronized(lock) {
            offlineRealtimeMs = 0L // bytes arriving implies link is online
            val state = requests[source] ?: RequestState(now, -1L, dataSpec.uri.toString())
                .also { requests[source] = it }
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            if (state.firstByteRealtimeMs == 0L) {
                state.firstByteRealtimeMs = now
                if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                    Log.d(
                        TAG,
                        "net first byte [${sessionId ?: "default"}] after=${now - state.initRealtimeMs}ms " +
                            "headers=${now - state.startRealtimeMs}ms " +
                            "uri=${shortUri(dataSpec.uri.toString())}"
                    )
                }
            }
            state.lastByteRealtimeMs = now
            state.bytes += bytesTransferred.toLong()
            lastUsefulTransferRealtimeMs = now
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val snapshot: RequestResult?
        synchronized(lock) {
            val state = requests.remove(source) ?: return
            val now = clock()
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            lastTransferEndRealtimeMs = now
            offlineRealtimeMs = 0L

            val totalMs = (now - state.initRealtimeMs).coerceAtLeast(1L)
            val ttfbMs = if (state.firstByteRealtimeMs > 0L) {
                (state.firstByteRealtimeMs - state.initRealtimeMs).coerceAtLeast(0L)
            } else {
                totalMs
            }
            val headerWaitMs = (state.startRealtimeMs - state.initRealtimeMs).coerceAtLeast(0L)
            val bodyMs = if (state.firstByteRealtimeMs > 0L) {
                (now - state.firstByteRealtimeMs).coerceAtLeast(50L)
            } else {
                totalMs
            }
            val bytes = state.bytes
            // Ignore tiny samples that would skew the estimate
            if (bytes < MIN_SAMPLE_BYTES || totalMs < MIN_SAMPLE_MS) {
                return
            }
            lastUsefulTransferRealtimeMs = now

            val rawBps = (bytes.toDouble() * 8_000.0 / bodyMs.toDouble())
                .coerceAtLeast(1.0)
                .coerceAtMost(MAX_TRUSTED_BPS)

            // Outlier protection via bounded sliding window and trimmed calculation
            recentSamplesBps.add(rawBps)
            if (recentSamplesBps.size > MAX_RECENT_SAMPLES) {
                recentSamplesBps.removeAt(0)
            }

            val bps = if (recentSamplesBps.size >= 4) {
                val sorted = recentSamplesBps.sorted()
                // Drop lowest and highest sample
                val trimmed = sorted.subList(1, sorted.size - 1)
                trimmed.average()
            } else {
                rawBps
            }

            if (samples == 0) {
                ewmaBps = bps
                longTermEwmaBps = bps
                variance = 0.0
            } else {
                val oldMean = ewmaBps
                ewmaBps = EWMA_ALPHA_FAST * bps + (1.0 - EWMA_ALPHA_FAST) * oldMean
                longTermEwmaBps = EWMA_ALPHA_SLOW * bps + (1.0 - EWMA_ALPHA_SLOW) * longTermEwmaBps
                val delta = bps - oldMean
                variance = EWMA_ALPHA_FAST * delta * delta + (1.0 - EWMA_ALPHA_FAST) * variance
            }
            peakBps = max(peakBps, ewmaBps.toLong())
            startupEwmaMs = if (startupEwmaMs <= 0.0) {
                ttfbMs.toDouble()
            } else {
                EWMA_ALPHA_FAST * ttfbMs + (1.0 - EWMA_ALPHA_FAST) * startupEwmaMs
            }

            if (state.gapSincePreviousEndMs >= 0L) {
                lastSchedulingGapEwmaMs = if (lastSchedulingGapEwmaMs <= 0.0) {
                    state.gapSincePreviousEndMs.toDouble()
                } else {
                    EWMA_ALPHA_FAST * state.gapSincePreviousEndMs + (1.0 - EWMA_ALPHA_FAST) * lastSchedulingGapEwmaMs
                }
            }

            bodyTransferEwmaMs = if (bodyTransferEwmaMs <= 0.0) {
                bodyMs.toDouble()
            } else {
                EWMA_ALPHA_FAST * bodyMs + (1.0 - EWMA_ALPHA_FAST) * bodyTransferEwmaMs
            }

            samples += 1
            snapshot = RequestResult(
                bytes = bytes,
                totalMs = totalMs,
                ttfbMs = ttfbMs,
                headerWaitMs = headerWaitMs,
                gapMs = state.gapSincePreviousEndMs,
                bps = bps.toLong(),
                ewmaBps = ewmaBps.toLong(),
                samples = samples,
            )
            val nowRealtime = clock()
            if (BuildConfig.DEBUG && nowRealtime - lastLogRealtimeMs >= LOG_INTERVAL_MS) {
                lastLogRealtimeMs = nowRealtime
                Log.d(
                    TAG,
                    "net end [${sessionId ?: "default"}] bytes=${snapshot.bytes} dur=${snapshot.totalMs}ms " +
                        "ttfb=${snapshot.ttfbMs}ms headers=${snapshot.headerWaitMs}ms " +
                        "rate=${snapshot.bps}bps ewma=${snapshot.ewmaBps}bps " +
                        "samples=${snapshot.samples} uri=${shortUri(dataSpec.uri.toString())}"
                )
            }
        }
    }

    /** Returns a thread-safe immutable snapshot for the load policy. */
    fun snapshot(): ForwardBufferPolicy.NetworkStats {
        val now = clock()
        synchronized(lock) {
            val isOffline = offlineRealtimeMs > 0L && (now - offlineRealtimeMs < OFFLINE_HOLD_MS)

            val timeSinceUseful = if (lastUsefulTransferRealtimeMs > 0L) {
                (now - lastUsefulTransferRealtimeMs).coerceAtLeast(0L)
            } else {
                0L
            }

            val isStale = lastUsefulTransferRealtimeMs > 0L && timeSinceUseful > STALE_SAMPLE_TIMEOUT_MS
            val decay = if (isStale) {
                val staleIntervals = (timeSinceUseful - STALE_SAMPLE_TIMEOUT_MS) / STALE_SAMPLE_TIMEOUT_MS
                (1.0 / (1.0 + 0.5 * (staleIntervals + 1))).coerceIn(0.1, 1.0)
            } else {
                1.0
            }

            var currentEwma = ewmaBps * decay
            var currentPeak = (peakBps * decay).toLong().coerceAtLeast(currentEwma.toLong())

            var longestStallMs = 0L
            var isStalled = false

            // Detect active in-flight stalled requests
            for (req in requests.values) {
                if (req.startRealtimeMs > 0L) {
                    val inFlightMs = (now - req.startRealtimeMs).coerceAtLeast(1L)
                    val idleMs = now - (if (req.lastByteRealtimeMs > 0L) req.lastByteRealtimeMs else req.startRealtimeMs)
                    if (idleMs >= STALL_IDLE_MS || inFlightMs >= STALL_DURATION_MS) {
                        isStalled = true
                        longestStallMs = max(longestStallMs, idleMs)
                        val inFlightBps = (req.bytes.toDouble() * 8_000.0 / inFlightMs)
                        if (inFlightBps < currentEwma) {
                            currentEwma = inFlightBps
                            currentPeak = min(currentPeak, max(inFlightBps.toLong(), 1L))
                        }
                    }
                }
            }

            val variation = if (samples >= 3 && currentEwma > 1.0) {
                (sqrt(variance) / currentEwma).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }

            val schedulingGap = if (lastSchedulingGapEwmaMs > 0.0) {
                lastSchedulingGapEwmaMs.toLong()
            } else {
                0L
            }

            // Confidence score: based on sample count and freshness (0.0 to 1.0)
            val sampleConfidence = (samples.toFloat() / 5f).coerceIn(0f, 1f)
            val freshnessConfidence = decay.toFloat()
            val confidence = sampleConfidence * freshnessConfidence

            val resolvedState = when {
                isOffline -> NetworkState.OFFLINE
                isStalled -> NetworkState.STALLED
                samples == 0 -> NetworkState.UNKNOWN
                currentEwma < 1_500_000.0 || variation > 0.40f || startupEwmaMs > 2_000.0 -> NetworkState.DEGRADED
                else -> NetworkState.HEALTHY
            }

            return ForwardBufferPolicy.NetworkStats(
                throughputBps = if (samples > 0) currentEwma.toLong() else 0L,
                peakThroughputBps = currentPeak,
                throughputVariation = variation,
                startupLatencyMs = startupEwmaMs.toLong(),
                lastTransferMs = schedulingGap,
                samples = if (isStale) max(1, samples / 2) else samples,
                state = resolvedState,
                longTermThroughputBps = (longTermEwmaBps * decay).toLong(),
                confidence = confidence,
                activeRequests = requests.size,
                longestStallMs = longestStallMs,
                isIdle = requests.isEmpty() && timeSinceUseful > 3_000L,
            )
        }
    }

    private fun pruneAbandonedRequestsLocked(now: Long) {
        if (requests.size >= MAX_CONCURRENT_REQUESTS) {
            val iterator = requests.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.initRealtimeMs > REQUEST_TIMEOUT_MS) {
                    iterator.remove()
                }
            }
            if (requests.size >= MAX_CONCURRENT_REQUESTS) {
                val oldest = requests.minByOrNull { it.value.initRealtimeMs }
                if (oldest != null) requests.remove(oldest.key)
            }
        }
    }

    private data class RequestResult(
        val bytes: Long,
        val totalMs: Long,
        val ttfbMs: Long,
        val headerWaitMs: Long,
        val gapMs: Long,
        val bps: Long,
        val ewmaBps: Long,
        val samples: Int,
    )

    private fun shouldLogPhase(now: Long): Boolean {
        if (now - lastPhaseLogRealtimeMs < LOG_INTERVAL_MS) return false
        lastPhaseLogRealtimeMs = now
        return true
    }

    companion object {
        private const val TAG = "CS3Buffer"
        private const val EWMA_ALPHA_FAST = 0.25
        private const val EWMA_ALPHA_SLOW = 0.08
        private const val MAX_RECENT_SAMPLES = 8
        private const val MIN_SAMPLE_BYTES = 32L * 1024L
        private const val MIN_SAMPLE_MS = 100L
        private const val MAX_TRUSTED_BPS = 1_000_000_000.0 // 1 Gbps sanity cap
        private const val LOG_INTERVAL_MS = 2_000L
        private const val MAX_CONCURRENT_REQUESTS = 64
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val STALE_SAMPLE_TIMEOUT_MS = 15_000L
        private const val STALL_IDLE_MS = 2_500L
        private const val STALL_DURATION_MS = 3_000L
        private const val OFFLINE_HOLD_MS = 10_000L

        private fun shortUri(uri: String): String =
            if (uri.length <= 120) uri else uri.substring(0, 117) + "..."
    }
}

/**
 * Wraps an [HttpDataSource.Factory] so every source Media3 creates reports real network activity
 * to [listener]. Cache reads never reach this data source, so the resulting rate is an upstream
 * rate, not a cache-hit rate.
 */
@OptIn(UnstableApi::class)
class InstrumentedHttpDataSourceFactory(
    private val delegate: HttpDataSource.Factory,
    private val listener: TransferListener,
) : HttpDataSource.Factory {

    override fun createDataSource(): HttpDataSource {
        val source = delegate.createDataSource()
        (source as? BaseDataSource)?.addTransferListener(listener)
        return source
    }

    override fun setDefaultRequestProperties(
        defaultRequestProperties: Map<String, String>,
    ): HttpDataSource.Factory {
        delegate.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }
}

/**
 * Cache key that keeps request headers that change the bytes returned by the server.
 *
 * The default Media3 key is only the URI. Two requests for the same URI with different
 * Authorization/Referer/Cookie headers could therefore reuse the wrong cached payload.
 *
 * Sensitive headers (e.g. authorization tokens and cookies) are hashed to protect user
 * credentials from being stored in plaintext on disk or in cache databases.
 */
@OptIn(UnstableApi::class)
class HeaderAwareCacheKeyFactory(
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : CacheKeyFactory {
    private val headerWhitelist = setOf(
        "authorization",
        "cookie",
        "referer",
        "user-agent",
    )

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val base = dataSpec.key ?: dataSpec.uri.toString()
        val relevant = LinkedHashMap<String, String>()
        for ((name, value) in defaultHeaders) {
            val lower = name.lowercase()
            if (lower in headerWhitelist) relevant[lower] = value
        }
        for ((name, value) in dataSpec.httpRequestHeaders) {
            val lower = name.lowercase()
            if (lower in headerWhitelist) relevant[lower] = value
        }
        if (relevant.isEmpty()) return base

        val rawFingerprint = relevant.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }

        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rawFingerprint.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Throwable) {
            rawFingerprint.hashCode().toString()
        }

        return "$base|h=$hash"
    }
}
