package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * Owns the single process-wide [SimpleCache] used for online playback.
 *
 * CloudStream previously created a cache with `LeastRecentlyUsedCacheEvictor(0)` whenever the
 * "Video cache on disk" setting was Auto (0 MB). That evictor deletes every span immediately after
 * it is written, so the cache was effectively disabled while still paying all of the write and
 * eviction overhead. The cache directory was also registered for deletion on app exit, defeating
 * reuse across sessions.
 *
 * This manager fixes both issues and adds playback-aware eviction:
 *
 * - Auto size is derived from free disk space instead of zero.
 * - The cache persists across app exits.
 * - Recently touched spans and spans belonging to the active media source are protected from
 *   eviction as long as another eligible span exists.
 * - Clearing the cache uses Media3's supported per-resource removal instead of deleting files
 *   underneath an open cache.
 */
@OptIn(UnstableApi::class)
object PlaybackCacheManager {
    private const val TAG = "CS3Buffer"

    private const val CACHE_DIR_NAME = "exoplayer"
    private const val MIN_CACHE_BYTES = 8L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MIN_AUTO_CACHE_BYTES = 64L * 1024L * 1024L
    private const val MAX_AUTO_CACHE_BYTES = 2L * 1024L * 1024L * 1024L

    private val lock = Any()
    private var cache: SimpleCache? = null
    private var evictor: PlaybackAwareCacheEvictor? = null
    private var configuredBytes: Long = 0L
    private var configuredRequestBytes: Long = -1L

    @Volatile
    private var activePrefix: String? = null

    @Volatile
    private var bypassActiveSource = false

    /**
     * Returns the shared cache, creating it if necessary.
     *
     * @param requestedBytes the user's Video cache on disk setting in bytes, or 0 for Automatic.
     */
    fun get(context: Context, requestedBytes: Long): SimpleCache? {
        synchronized(lock) {
            cache?.let { existing ->
                if (requestedBytes != configuredRequestBytes) {
                    // The user changed the "Video cache on disk" setting. SimpleCache cannot
                    // change evictor, but the limit itself is mutable; a smaller limit is
                    // enforced as soon as the next span is committed or touched.
                    configuredRequestBytes = requestedBytes
                    val newSize = resolveDiskCacheBytes(context, requestedBytes)
                    configuredBytes = newSize
                    evictor?.setMaxBytes(newSize)
                    Log.i(
                        TAG,
                        "cache size setting changed to ${newSize / (1024 * 1024)}MB"
                    )
                }
                return existing
            }

            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            val size = resolveDiskCacheBytes(context, requestedBytes)
            val newEvictor = PlaybackAwareCacheEvictor(size)
            val startedAt = android.os.SystemClock.elapsedRealtime()

            fun create(): SimpleCache = SimpleCache(
                cacheDir,
                newEvictor,
                StandaloneDatabaseProvider(context),
            )

            val created = try {
                create()
            } catch (first: Throwable) {
                Log.w(TAG, "cache initialization failed, attempting a safe recovery", first)
                try {
                    // No SimpleCache is open at this point, so deleting the directory is safe.
                    if (!SimpleCache.isCacheFolderLocked(cacheDir)) {
                        cacheDir.deleteRecursively()
                    }
                    create()
                } catch (second: Throwable) {
                    Log.e(TAG, "cache initialization recovery failed", second)
                    null
                }
            }

            if (created == null) {
                Log.e(TAG, "online playback will continue without a disk cache")
                return null
            }

            cache = created
            evictor = newEvictor
            configuredBytes = size
            configuredRequestBytes = requestedBytes
            Log.i(
                TAG,
                "cache ready dir=$cacheDir size=${size / (1024 * 1024)}MB " +
                    "init=${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return created
        }
    }

    /** Called when a new online media item becomes the active playback source. */
    fun setActiveSource(sourceUri: String?, directoryScope: Boolean) {
        val prefix = sourceUri?.let { keyPrefix(it, directoryScope) }
        activePrefix = prefix
        bypassActiveSource = false
        evictor?.setActivePrefix(prefix)
        Log.d(TAG, "cache active prefix=${prefix ?: "none"}")
    }

    fun clearActiveSource() {
        activePrefix = null
        bypassActiveSource = false
        evictor?.setActivePrefix(null)
    }

    /**
     * Live streams must not be served from a stale disk cache and their segment URLs are often
     * reused. Once a source is known to be live, all of its media requests bypass the disk cache.
     */
    fun setSourceLive(isLive: Boolean) {
        bypassActiveSource = isLive
        Log.d(TAG, "cache live bypass=$isLive")
    }

    /**
     * Returns true for resources that must never be cached: HLS/DASH manifests (a cached live
     * playlist would freeze the live edge) and every request of a source already known to be live.
     */
    fun shouldBypassCache(uri: Uri): Boolean {
        if (bypassActiveSource) return true
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".ism") ||
            path.endsWith(".ismc") ||
            path.endsWith("/manifest")
    }

    fun isBypassingCache(): Boolean = bypassActiveSource

    /**
     * Removes cached resources through Media3's cache API. Returns the number of bytes freed.
     *
     * Spans belonging to the currently playing title are skipped, so clearing the cache while
     * audio-only or picture-in-picture playback is active cannot remove data under the player.
     * This is intentionally not a recursive file delete.
     */
    fun clearAll(): Long {
        synchronized(lock) {
            val current = cache ?: return 0L
            val before = runCatching { current.cacheSpace }.getOrDefault(0L)
            val protectedPrefix = activePrefix
            runCatching {
                current.keys.toList().forEach { key ->
                    if (!protectedPrefix.isNullOrEmpty() && key.startsWith(protectedPrefix)) {
                        return@forEach
                    }
                    runCatching { current.removeResource(key) }
                }
            }.onFailure { Log.w(TAG, "failed to clear cache", it) }
            val after = runCatching { current.cacheSpace }.getOrDefault(0L)
            return (before - after).coerceAtLeast(0L)
        }
    }

    fun usageBytes(): Long = evictor?.usageBytes()
        ?: runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun maxBytes(): Long = configuredBytes

    fun snapshot(): CacheSnapshot = CacheSnapshot(
        usageBytes = usageBytes(),
        maxBytes = configuredBytes,
        initialized = cache != null,
        writtenBytes = evictor?.writtenBytes() ?: 0L,
    )

    data class CacheSnapshot(
        val usageBytes: Long,
        val maxBytes: Long,
        val initialized: Boolean,
        val writtenBytes: Long,
    )

    /** Resolves the user setting or a free-space-derived Automatic size. */
    fun resolveDiskCacheBytes(context: Context, requestedBytes: Long): Long {
        if (requestedBytes > 0L) {
            return requestedBytes.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
        }
        val free = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        if (free <= 0L) return MIN_AUTO_CACHE_BYTES
        return (free / 8L).coerceIn(MIN_AUTO_CACHE_BYTES, MAX_AUTO_CACHE_BYTES)
    }

    /**
     * Computes the cache-key prefix protected for the active source.
     *
     * For HLS/DASH the playlist URL is not a prefix of segment URLs, so the containing directory is
     * protected. For progressive files the exact URL prefix is enough.
     */
    fun keyPrefix(sourceUri: String, directoryScope: Boolean): String {
        if (!directoryScope) return sourceUri
        return runCatching {
            val uri = Uri.parse(sourceUri)
            val path = uri.path ?: return sourceUri
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isEmpty()) sourceUri else {
                buildString {
                    uri.scheme?.let { append(it).append("://") }
                    (uri.encodedAuthority ?: uri.host)?.let { append(it) }
                    append(parent).append('/')
                }
            }
        }.getOrDefault(sourceUri)
    }

    /**
     * LRU evictor that protects recently read spans and the active media source.
     *
     * Media3's stock LRU evictor is a strictly byte-bounded TreeSet. This implementation keeps the
     * same bound but prefers to evict older, unrelated content first. If only protected spans
     * remain, it degrades to ordinary LRU rather than letting the cache grow without limit.
     */
    @OptIn(UnstableApi::class)
    class PlaybackAwareCacheEvictor(
        initialMaxBytes: Long,
        private val protectRecentMs: Long = 30_000L,
    ) : CacheEvictor {
        private val lock = Any()
        private val lru = LinkedHashSet<CacheSpan>()
        private var currentSize = 0L
        private var initialized = false
        @Volatile
        private var maxBytes: Long = initialMaxBytes
        @Volatile
        private var totalWrittenBytes = 0L
        @Volatile
        private var activePrefix: String? = null

        fun setMaxBytes(newMaxBytes: Long) {
            // Deliberately does not call into SimpleCache while holding this lock: a cache thread
            // may hold the SimpleCache lock and wait for this lock. The new limit is applied by
            // the next onSpanAdded/onSpanTouched callback.
            maxBytes = newMaxBytes
        }

        fun setActivePrefix(prefix: String?) {
            synchronized(lock) {
                activePrefix = prefix?.takeIf { it.isNotBlank() }
            }
        }

        fun usageBytes(): Long = synchronized(lock) { currentSize }

        fun writtenBytes(): Long = totalWrittenBytes

        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() {
            // Existing spans were delivered in directory order, not LRU order. Re-sort once so the
            // eviction order reflects their persisted last-touch timestamps.
            synchronized(lock) {
                initialized = true
                if (lru.size <= 1) return
                val sorted = lru.sortedBy { it.lastTouchTimestamp }
                lru.clear()
                lru.addAll(sorted)
            }
        }

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            // Deliberately do not pre-evict length bytes. For a file larger than maxBytes the
            // stock evictor would wipe the whole cache before writing a single byte. onSpanAdded
            // maintains the budget incrementally instead.
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            if (!span.isCached) return
            synchronized(lock) {
                if (initialized && span.length > 0L) totalWrittenBytes += span.length
                addLocked(span)
                evictLocked(cache)
            }
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            synchronized(lock) {
                removeLocked(span)
            }
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            synchronized(lock) {
                removeLocked(oldSpan)
                addLocked(newSpan)
                evictLocked(cache)
            }
        }

        private fun addLocked(span: CacheSpan) {
            if (!span.isCached || span.length <= 0L) return
            if (lru.add(span)) currentSize += span.length
        }

        private fun removeLocked(span: CacheSpan) {
            if (lru.remove(span)) {
                currentSize = (currentSize - span.length.coerceAtLeast(0L)).coerceAtLeast(0L)
            }
        }

        private fun evictLocked(cache: Cache) {
            if (currentSize <= maxBytes) return
            val now = System.currentTimeMillis()
            var evictions = 0
            while (currentSize > maxBytes && lru.isNotEmpty() && evictions < MAX_EVICTIONS_PER_CALL) {
                val prefix = activePrefix
                val victim =
                    // First: old, unrelated content.
                    lru.firstOrNull {
                        prefix != null && !it.key.startsWith(prefix) && !isRecent(it, now)
                    }
                        // Then: any unrelated content, even if recently touched.
                        ?: lru.firstOrNull { prefix != null && !it.key.startsWith(prefix) }
                        // Then: old spans of the active source (already consumed or far behind).
                        ?: lru.firstOrNull { !isRecent(it, now) }
                        // Last resort: ordinary LRU, including active/recent spans.
                        ?: lru.firstOrNull()
                        ?: break
                // Remove locally first so a failed SimpleCache removal cannot loop forever.
                removeLocked(victim)
                runCatching { cache.removeSpan(victim) }
                    .onFailure { Log.w(TAG, "cache eviction failed for ${victim.key}", it) }
                evictions += 1
            }
            if (currentSize > maxBytes) {
                Log.w(
                    TAG,
                    "cache still above target after $evictions evictions: " +
                        "$currentSize > $maxBytes (protected spans)"
                )
            }
        }

        private fun isRecent(span: CacheSpan, now: Long): Boolean =
            span.lastTouchTimestamp != C.TIME_UNSET &&
                now - span.lastTouchTimestamp in 0 until protectRecentMs

        private companion object {
            const val MAX_EVICTIONS_PER_CALL = 512
        }
    }
}
