package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Routes each request either through the disk cache or directly upstream.
 *
 * Requirements:
 * - One routing decision per DataSpec/open.
 * - Cache manifests only when the policy explicitly allows it; default should bypass manifests/live playlists.
 * - Bypass DRM license POST requests and other non-GET requests.
 * - Do not use FLAG_ALLOW_GZIP as a universal manifest classifier.
 * - Preserve all DataSpec fields, headers, flags, key, byte ranges, HTTP method/body, and custom data.
 * - Preserve TransferListeners.
 * - Preserve response URI and response headers.
 * - Close delegates safely.
 * - Correctly handle repeated open.
 * - Correctly handle open failure and read failure.
 * - Release active cache lease on close/failure.
 * - Ensure upstream failure never corrupts the cache.
 */
@UnstableApi
class CacheRoutingDataSourceFactory(
    private val cacheFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val session: PlaybackSessionState? = null,
    private val shouldBypass: (Uri) -> Boolean = { uri -> PlaybackCacheManager.shouldBypassCache(uri, session) },
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutedDataSource(
        cacheFactory = cacheFactory,
        upstreamFactory = upstreamFactory,
        session = session,
        shouldBypass = shouldBypass,
    )

    private class RoutedDataSource(
        private val cacheFactory: DataSource.Factory,
        private val upstreamFactory: DataSource.Factory,
        private val session: PlaybackSessionState?,
        private val shouldBypass: (Uri) -> Boolean,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        private var currentLease: PlaybackSessionState.CacheLease? = null

        override fun addTransferListener(transferListener: TransferListener) {
            if (!listeners.contains(transferListener)) {
                listeners += transferListener
            }
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            // Ensure any previous delegate and lease are closed cleanly before repeated open
            closeInternal()

            val cacheKey = dataSpec.key ?: dataSpec.uri.toString()

            // Non-GET requests (DRM license POST, telemetry) must never be cached
            val isGet = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
            val bypass = !isGet || (session?.isLive == true) || shouldBypass(dataSpec.uri)

            val source = if (bypass) {
                upstreamFactory.createDataSource()
            } else {
                // Acquire active cache lease for this request
                currentLease = session?.acquireLease(
                    key = cacheKey,
                    uri = dataSpec.uri,
                    rangeStart = dataSpec.position,
                    rangeLength = dataSpec.length
                )
                PlaybackCacheManager.registerActiveKey(cacheKey, dataSpec.uri)
                cacheFactory.createDataSource()
            }

            listeners.forEach { source.addTransferListener(it) }
            delegate = source

            return try {
                source.open(dataSpec)
            } catch (e: Exception) {
                // Release active lease immediately on open failure
                currentLease?.let { session?.releaseLease(it) }
                currentLease = null
                throw if (e is IOException) e else IOException("Failed to open DataSource", e)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = delegate ?: throw IOException("read() called before open()")
            return try {
                source.read(buffer, offset, length)
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

            val source = delegate
            delegate = null
            try {
                source?.close()
            } catch (_: Throwable) {
                // Delegate close errors should not crash the player
            }
        }
    }
}
