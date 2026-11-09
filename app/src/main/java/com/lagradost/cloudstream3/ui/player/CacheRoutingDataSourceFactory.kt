package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Routes each request either through the disk cache or directly upstream.
 *
 * A single [androidx.media3.datasource.cache.CacheDataSource.Factory] is shared by manifests and
 * media segments. Caching an HLS/DASH manifest is harmful: a live playlist reload would be served
 * from disk with the old media sequence and freeze the live edge. The cache manager therefore marks
 * `.m3u8`/`.mpd` resources (and every request of a source already known to be live) as bypassed.
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
            listeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            delegate?.close()
            // Only GET responses are cacheable. DRM license requests are POSTs and must never be
            // served from (or written to) the media cache. HLS/DASH manifests and encryption keys
            // request gzip and must always be re-fetched; an extensionless live playlist would
            // otherwise freeze on the cached live edge.
            val bypass = dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
                dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) ||
                shouldBypass(dataSpec.uri)
            val source = if (bypass) {
                upstreamFactory.createDataSource()
            } else {
                cacheFactory.createDataSource()
            }
            listeners.forEach { source.addTransferListener(it) }
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val source = delegate ?: error("read() called before open()")
            return source.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            val source = delegate ?: return
            delegate = null
            source.close()
        }
    }
}
