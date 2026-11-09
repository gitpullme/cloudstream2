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
 * A single [androidx.media3.datasource.cache.CacheDataSource.Factory] is shared by manifests and
 * media segments. Caching an HLS/DASH manifest is harmful: a live playlist reload would be served
 * from disk with the old media sequence and freeze the live edge. The cache manager therefore marks
 * manifests (and every request of a source already known to be live) as bypassed.
 *
 * The decision is made per [DataSource.open], because one ExoPlayer media source can request both
 * manifests and segments through the same factory.
 */
@UnstableApi
class CacheRoutingDataSourceFactory(
    private val cacheFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory,
    private val shouldBypass: (Uri) -> Boolean,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutedDataSource(
        cacheFactory = cacheFactory,
        upstreamFactory = upstreamFactory,
        shouldBypass = shouldBypass,
    )

    private class RoutedDataSource(
        private val cacheFactory: DataSource.Factory,
        private val upstreamFactory: DataSource.Factory,
        private val shouldBypass: (Uri) -> Boolean,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            if (!listeners.contains(transferListener)) {
                listeners += transferListener
            }
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            // Ensure any previous delegate is closed cleanly before a repeated open.
            runCatching { delegate?.close() }
            delegate = null

            // Register this resource with active playback protection.
            val cacheKey = dataSpec.key ?: dataSpec.uri.toString()
            PlaybackCacheManager.registerActiveKey(cacheKey, dataSpec.uri)

            // Only GET responses are cacheable. DRM license requests and analytics are POSTs
            // and must never be served from (or written to) the media cache.
            // Do not assume gzip flags mean a manifest: media chunks may also negotiate gzip.
            val bypass = dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
                shouldBypass(dataSpec.uri)

            val source = if (bypass) {
                upstreamFactory.createDataSource()
            } else {
                cacheFactory.createDataSource()
            }
            listeners.forEach { source.addTransferListener(it) }
            delegate = source

            return try {
                source.open(dataSpec)
            } catch (e: Exception) {
                // Media3 requires close() even if open() throws an exception; keep delegate assigned.
                throw if (e is IOException) e else IOException("Failed to open DataSource", e)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = delegate ?: throw IOException("read() called before open()")
            return source.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            val source = delegate
            delegate = null
            source?.close()
        }
    }
}
