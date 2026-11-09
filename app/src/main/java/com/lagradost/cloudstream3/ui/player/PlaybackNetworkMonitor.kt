package com.lagradost.cloudstream3.ui.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import com.lagradost.cloudstream3.BuildConfig
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures actual upstream request behaviour.
 *
 * Media3's [TransferListener] only sees the network data source. When a [androidx.media3.datasource.cache.CacheDataSource]
 * serves a range from disk, no network transfer is created, which is exactly what we want: cached
 * bytes must not be mistaken for network throughput.
 *
 * Samples are exponentially weighted and tiny/short transfers are ignored, so a single 304, a
 * subtitle fetch or one unusually fast segment cannot swing the adaptive policy.
 */
@OptIn(UnstableApi::class)
class PlaybackNetworkMonitor : TransferListener {
    private class RequestState(
        val initRealtimeMs: Long,
        val gapSincePreviousEndMs: Long,
    ) {
        var startRealtimeMs: Long = 0L
        var firstByteRealtimeMs: Long = 0L
        var bytes: Long = 0L
    }

    private val lock = Any()
    private val requests = IdentityHashMap<DataSource, RequestState>()

    private var ewmaBps = 0.0
    private var variance = 0.0
    private var peakBps = 0L
    private var startupEwmaMs = 0.0
    private var lastTransferEwmaMs = 0.0
    private var samples = 0
    private var lastLogRealtimeMs = 0L
    private var lastPhaseLogRealtimeMs = 0L
    private var lastTransferEndRealtimeMs = 0L

    fun reset() {
        synchronized(lock) {
            requests.clear()
            ewmaBps = 0.0
            variance = 0.0
            peakBps = 0L
            startupEwmaMs = 0.0
            lastTransferEwmaMs = 0.0
            samples = 0
            lastLogRealtimeMs = 0L
            lastPhaseLogRealtimeMs = 0L
            lastTransferEndRealtimeMs = 0L
        }
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val gap = if (lastTransferEndRealtimeMs > 0L) {
                (now - lastTransferEndRealtimeMs).coerceAtLeast(0L)
            } else {
                -1L
            }
            requests[source] = RequestState(now, gap)
            if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                Log.d(
                    TAG,
                    "net init gap=${if (gap >= 0L) "${gap}ms" else "first"} " +
                        "uri=${shortUri(dataSpec.uri.toString())} pos=${dataSpec.position}"
                )
            }
        }
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val state = requests[source] ?: RequestState(now, -1L)
                .also { requests[source] = it }
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                Log.d(
                    TAG,
                    "net headers wait=${(now - state.initRealtimeMs).coerceAtLeast(0L)}ms " +
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
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val state = requests[source] ?: RequestState(now, -1L)
                .also { requests[source] = it }
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            if (state.firstByteRealtimeMs == 0L) {
                state.firstByteRealtimeMs = now
                if (BuildConfig.DEBUG && shouldLogPhase(now)) {
                    Log.d(
                        TAG,
                        "net first byte after=${now - state.initRealtimeMs}ms " +
                            "headers=${now - state.startRealtimeMs}ms " +
                            "uri=${shortUri(dataSpec.uri.toString())}"
                    )
                }
            }
            state.bytes += bytesTransferred.toLong()
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val snapshot: RequestResult?
        synchronized(lock) {
            val state = requests.remove(source) ?: return
            val now = SystemClock.elapsedRealtime()
            if (state.startRealtimeMs == 0L) state.startRealtimeMs = now
            lastTransferEndRealtimeMs = now

            val totalMs = (now - state.initRealtimeMs).coerceAtLeast(1L)
            // Full startup: source open -> DNS/connect -> response headers -> first body byte.
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
            // Ignore metadata, tiny probes and failed/empty requests.
            if (bytes < MIN_SAMPLE_BYTES || totalMs < MIN_SAMPLE_MS) {
                return
            }
            // Internally measured in bytes/second, converted to bits/second so it matches
            // Media3's Format.bitrate (also bits/second) in the buffer policy.
            val bps = (bytes.toDouble() * 8_000.0 / bodyMs.toDouble())
                .coerceAtLeast(1.0)
                .coerceAtMost(MAX_TRUSTED_BPS)
            if (samples == 0) {
                ewmaBps = bps
                variance = 0.0
            } else {
                val oldMean = ewmaBps
                ewmaBps = EWMA_ALPHA * bps + (1.0 - EWMA_ALPHA) * oldMean
                val delta = bps - oldMean
                variance = EWMA_ALPHA * delta * delta + (1.0 - EWMA_ALPHA) * variance
            }
            peakBps = max(peakBps, ewmaBps.toLong())
            startupEwmaMs = if (startupEwmaMs <= 0.0) {
                ttfbMs.toDouble()
            } else {
                EWMA_ALPHA * ttfbMs + (1.0 - EWMA_ALPHA) * startupEwmaMs
            }
            lastTransferEwmaMs = if (lastTransferEwmaMs <= 0.0) {
                totalMs.toDouble()
            } else {
                EWMA_ALPHA * totalMs + (1.0 - EWMA_ALPHA) * lastTransferEwmaMs
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
            val nowRealtime = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG && nowRealtime - lastLogRealtimeMs >= LOG_INTERVAL_MS) {
                lastLogRealtimeMs = nowRealtime
                Log.d(
                    TAG,
                    "net end bytes=${snapshot.bytes} dur=${snapshot.totalMs}ms " +
                        "ttfb=${snapshot.ttfbMs}ms headers=${snapshot.headerWaitMs}ms " +
                        "gap=${if (snapshot.gapMs >= 0L) "${snapshot.gapMs}ms" else "first"} " +
                        "rate=${snapshot.bps}bps ewma=${snapshot.ewmaBps}bps " +
                        "samples=${snapshot.samples} uri=${shortUri(dataSpec.uri.toString())}"
                )
            }
        }
    }

    /** Returns a thread-safe immutable snapshot for the load policy. */
    fun snapshot(): ForwardBufferPolicy.NetworkStats {
        synchronized(lock) {
            val variation = if (samples >= 3 && ewmaBps > 1.0) {
                (sqrt(variance) / ewmaBps).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }
            return ForwardBufferPolicy.NetworkStats(
                throughputBps = if (samples > 0) ewmaBps.toLong() else 0L,
                peakThroughputBps = peakBps,
                throughputVariation = variation,
                startupLatencyMs = startupEwmaMs.toLong(),
                lastTransferMs = lastTransferEwmaMs.toLong(),
                samples = samples,
            )
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

    /** Must be called while holding [lock]. */
    private fun shouldLogPhase(now: Long): Boolean {
        if (now - lastPhaseLogRealtimeMs < LOG_INTERVAL_MS) return false
        lastPhaseLogRealtimeMs = now
        return true
    }

    companion object {
        private const val TAG = "CS3Buffer"
        private const val EWMA_ALPHA = 0.25
        private const val MIN_SAMPLE_BYTES = 32L * 1024L
        private const val MIN_SAMPLE_MS = 100L
        private const val MAX_TRUSTED_BPS = 1_000_000_000.0 // 1 Gbps sanity cap
        private const val LOG_INTERVAL_MS = 2_000L

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
 * Authorization/Referer/Cookie headers could therefore reuse the wrong cached payload. The
 * factory also receives the data source's default request headers, because providers in
 * CloudStream set most auth headers there rather than on the [DataSpec]. Ranges and positions are
 * deliberately not part of the key (they identify the same resource).
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
        // Per-request headers take precedence over defaults, matching HttpDataSource behaviour.
        for ((name, value) in dataSpec.httpRequestHeaders) {
            val lower = name.lowercase()
            if (lower in headerWhitelist) relevant[lower] = value
        }
        if (relevant.isEmpty()) return base
        val fingerprint = relevant.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        return "$base|$fingerprint"
    }
}
