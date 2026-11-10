package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Owns the single process-wide [SimpleCache] used for online playback, with session-aware
 * resource ownership and lock-order safety.
 *
 * Requirements:
 * - One shared persistent SimpleCache across the app.
 * - Per-session active leases and playback protection.
 * - Never hold custom evictor lock across SimpleCache API calls.
 * - Never evict active leases.
 * - Reconcile spans safely on failure.
 * - Enforce size limits immediately.
 * - Never delete files under an open SimpleCache.
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

    // Registered active playback sessions
    private val sessions = ConcurrentHashMap<String, PlaybackSessionState>()

    // Global / legacy active tracking for single-player fallbacks
    @Volatile
    private var activePrefix: String? = null
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeUris = ConcurrentHashMap.newKeySet<String>()
    private val liveSourceUris = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var bypassActiveSource = false

    fun registerSession(session: PlaybackSessionState) {
        sessions[session.sessionId] = session
        Log.d(TAG, "registered session id=${session.sessionId} uri=${session.sourceUri}")
    }

    fun unregisterSession(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            Log.d(TAG, "unregistered session id=$sessionId")
        }
    }

    fun getSession(sessionId: String): PlaybackSessionState? = sessions[sessionId]

    /**
     * Returns the shared cache, creating it if necessary.
     *
     * @param requestedBytes the user's Video cache on disk setting in bytes, or 0 for Automatic.
     */
    fun get(context: Context, requestedBytes: Long): SimpleCache? {
        synchronized(lock) {
            cache?.let { existing ->
                if (requestedBytes != configuredRequestBytes) {
                    configuredRequestBytes = requestedBytes
                    val newSize = resolveDiskCacheBytes(context, requestedBytes)
                    configuredBytes = newSize
                    evictor?.setMaxBytes(newSize, existing)
                    Log.i(TAG, "cache size setting changed to ${newSize / (1024 * 1024)}MB")
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

    /** Called when a new online media item becomes the active playback source (legacy support). */
    fun setActiveSource(sourceUri: String?, directoryScope: Boolean) {
        val prefix = sourceUri?.let { keyPrefix(it, directoryScope) }
        activePrefix = prefix
        bypassActiveSource = false
        activeKeys.clear()
        activeUris.clear()
        sourceUri?.let {
            activeUris.add(normalizeUri(it))
        }
        evictor?.setActivePrefix(prefix)
        evictor?.clearActiveOwnership()
        sourceUri?.let {
            evictor?.registerActiveUri(normalizeUri(it))
        }
        Log.d(TAG, "cache active prefix=${prefix ?: "none"}")
    }

    fun clearActiveSource() {
        activePrefix = null
        bypassActiveSource = false
        activeKeys.clear()
        activeUris.clear()
        evictor?.setActivePrefix(null)
        evictor?.clearActiveOwnership()
    }

    /** Registers a specific cache key or segment URI as owned by active playback. */
    fun registerActiveKey(key: String, uri: Uri? = null) {
        activeKeys.add(key)
        uri?.let {
            activeUris.add(normalizeUri(it.toString()))
            evictor?.registerActiveUri(normalizeUri(it.toString()))
        }
        evictor?.registerActiveKey(key)
    }

    fun isSpanActive(key: String): Boolean {
        val now = System.currentTimeMillis()
        for (session in sessions.values) {
            if (session.isResourceActive(key, now)) {
                return true
            }
        }
        return evictor?.isSpanActive(key) ?: defaultIsSpanActive(key)
    }

    private fun defaultIsSpanActive(key: String): Boolean {
        if (activeKeys.contains(key)) return true
        val prefix = activePrefix
        if (prefix != null && key.startsWith(prefix)) return true
        val rawUrl = key.substringBefore('|')
        if (prefix != null && rawUrl.startsWith(prefix)) return true
        val norm = normalizeUri(rawUrl)
        return activeUris.contains(norm)
    }

    fun setSourceLive(isLive: Boolean) {
        bypassActiveSource = isLive
        Log.d(TAG, "cache live bypass=$isLive")
    }

    fun setSourceLive(sourceUri: String?, isLive: Boolean) {
        if (sourceUri == null) return
        val norm = normalizeUri(sourceUri)
        if (isLive) {
            liveSourceUris.add(norm)
        } else {
            liveSourceUris.remove(norm)
        }
        Log.d(TAG, "cache source live=$isLive uri=$norm")
    }

    fun isSourceLive(sourceUri: String?): Boolean {
        if (sourceUri == null) return false
        val norm = normalizeUri(sourceUri)
        for (session in sessions.values) {
            if (session.isLive && session.normalizedSourceUri == norm) {
                return true
            }
        }
        return liveSourceUris.contains(norm)
    }

    fun shouldBypassCache(uri: Uri, session: PlaybackSessionState? = null): Boolean {
        if (session != null && session.isLive) return true
        if (bypassActiveSource) return true
        if (isSourceLive(uri.toString())) return true
        return isManifest(uri)
    }

    fun isManifest(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".ism") ||
            path.endsWith(".ismc") ||
            path.endsWith("/manifest") ||
            path.endsWith("/master") ||
            path.endsWith("/playlist") ||
            path.contains(".m3u8") ||
            path.contains(".mpd")
    }

    fun isBypassingCache(): Boolean = bypassActiveSource

    /**
     * Removes cached resources through Media3's cache API. Returns the number of bytes freed.
     * Spans belonging to any currently playing session are skipped.
     */
    fun clearAll(): Long {
        synchronized(lock) {
            val current = cache ?: return 0L
            val before = runCatching { current.cacheSpace }.getOrDefault(0L)
            var freed = 0L
            runCatching {
                current.keys.toList().forEach { key ->
                    if (isSpanActive(key)) {
                        return@forEach
                    }
                    val spans = current.getCachedSpans(key)
                    val keyBytes = spans.sumOf { it.length }
                    runCatching {
                        current.removeResource(key)
                        freed += keyBytes
                    }
                }
            }.onFailure { Log.w(TAG, "failed to clear cache", it) }
            val after = runCatching { current.cacheSpace }.getOrDefault(0L)
            return max(freed, (before - after).coerceAtLeast(0L))
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

    fun normalizeUri(uriString: String): String = runCatching {
        val uri = Uri.parse(uriString)
        buildString {
            uri.scheme?.let { append(it).append("://") }
            (uri.encodedAuthority ?: uri.host)?.let { append(it) }
            uri.path?.let { append(it) }
        }
    }.getOrDefault(uriString.substringBefore('?').substringBefore('#'))

    /**
     * LRU evictor with lock-order safety.
     *
     * CRITICAL RULE: Never hold the custom evictor lock while calling SimpleCache APIs.
     * Candidate spans are selected and marked pending removal under the lock, then removed
     * outside the lock.
     */
    @OptIn(UnstableApi::class)
    class PlaybackAwareCacheEvictor(
        initialMaxBytes: Long,
        private val protectRecentMs: Long = 30_000L,
    ) : CacheEvictor {
        private val lock = Any()
        private val lru = LinkedHashSet<CacheSpan>()
        private val pendingRemoval = mutableSetOf<CacheSpan>()
        private var currentSize = 0L
        private var initialized = false
        @Volatile
        private var maxBytes: Long = initialMaxBytes
        @Volatile
        private var totalWrittenBytes = 0L
        @Volatile
        private var activePrefix: String? = null
        private val activeKeys = ConcurrentHashMap.newKeySet<String>()
        private val activeUris = ConcurrentHashMap.newKeySet<String>()
        @Volatile
        private var attachedCache: Cache? = null

        fun setMaxBytes(newMaxBytes: Long, cache: Cache? = null) {
            maxBytes = newMaxBytes
            val c = cache ?: attachedCache
            if (c != null) {
                evict(c)
            }
        }

        fun setActivePrefix(prefix: String?) {
            synchronized(lock) {
                activePrefix = prefix?.takeIf { it.isNotBlank() }
            }
        }

        fun registerActiveKey(key: String) {
            activeKeys.add(key)
        }

        fun registerActiveUri(uriNormalized: String) {
            activeUris.add(uriNormalized)
        }

        fun clearActiveOwnership() {
            activeKeys.clear()
            activeUris.clear()
        }

        fun isSpanActive(key: String): Boolean {
            if (activeKeys.contains(key)) return true
            val prefix = activePrefix
            if (prefix != null && key.startsWith(prefix)) return true
            val rawUrl = key.substringBefore('|')
            if (prefix != null && rawUrl.startsWith(prefix)) return true
            val norm = normalizeUri(rawUrl)
            return activeUris.contains(norm)
        }

        fun usageBytes(): Long = synchronized(lock) { currentSize }

        fun writtenBytes(): Long = totalWrittenBytes

        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() {
            synchronized(lock) {
                initialized = true
                if (lru.isNotEmpty()) {
                    val sorted = lru.sortedBy { it.lastTouchTimestamp }
                    lru.clear()
                    lru.addAll(sorted)
                }
            }
            attachedCache?.let { evict(it) }
        }

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            attachedCache = cache
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            attachedCache = cache
            if (!span.isCached || span.length <= 0L) return
            synchronized(lock) {
                if (initialized) totalWrittenBytes += span.length
                addLocked(span)
            }
            if (initialized) {
                evict(cache)
            }
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            attachedCache = cache
            synchronized(lock) {
                removeLocked(span)
                pendingRemoval.remove(span)
            }
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            attachedCache = cache
            synchronized(lock) {
                removeLocked(oldSpan)
                addLocked(newSpan)
            }
            if (initialized) {
                evict(cache)
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

        /**
         * Evicts spans until cache size is at or below maxBytes.
         * SimpleCache.removeSpan is strictly invoked OUTSIDE the evictor lock.
         */
        fun evict(cache: Cache) {
            attachedCache = cache
            val failedVictims = mutableSetOf<CacheSpan>()
            var evictions = 0

            while (evictions < MAX_EVICTIONS_PER_CALL) {
                // 1. Under lock: select candidate, mark pending removal, update accounting
                val victim: CacheSpan? = synchronized(lock) {
                    if (currentSize <= maxBytes) return
                    selectVictimLocked(failedVictims)?.also {
                        pendingRemoval.add(it)
                        removeLocked(it)
                    }
                }
                if (victim == null) break

                // 2. Outside lock: execute SimpleCache removal
                val success = try {
                    cache.removeSpan(victim)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "cache eviction failed for ${victim.key}", t)
                    false
                }

                // 3. Under lock: confirm removal or rollback
                if (success) {
                    synchronized(lock) {
                        pendingRemoval.remove(victim)
                    }
                } else {
                    synchronized(lock) {
                        pendingRemoval.remove(victim)
                        addLocked(victim)
                        failedVictims.add(victim)
                    }
                    // 4. Reconcile outside lock
                    reconcile(cache)
                }
                evictions++
            }

            if (usageBytes() > maxBytes) {
                Log.d(
                    TAG,
                    "cache usage (${usageBytes() / 1024}KB) exceeds max (${maxBytes / 1024}KB) " +
                        "because remaining spans are protected by active playback or leases."
                )
            }
        }

        private fun selectVictimLocked(excluded: Set<CacheSpan>): CacheSpan? {
            val now = System.currentTimeMillis()
            // Active leases and active sessions have absolute protection
            val isProtected: (CacheSpan) -> Boolean = { span ->
                span in excluded || span in pendingRemoval || PlaybackCacheManager.isSpanActive(span.key)
            }

            // 1. Oldest non-protected and non-recent
            val candidate1 = lru.firstOrNull { !isProtected(it) && !isRecent(it, now) }
            if (candidate1 != null) return candidate1

            // 2. Oldest non-protected (even if recent)
            val candidate2 = lru.firstOrNull { !isProtected(it) }
            if (candidate2 != null) return candidate2

            // Active spans are never evicted!
            return null
        }

        private fun reconcile(cache: Cache) {
            val actualSpans = mutableListOf<CacheSpan>()
            var cacheSpace = 0L
            try {
                val keys = cache.keys
                if (keys.isNotEmpty()) {
                    for (k in keys) {
                        actualSpans.addAll(cache.getCachedSpans(k))
                    }
                }
                cacheSpace = cache.cacheSpace
            } catch (t: Throwable) {
                Log.w(TAG, "failed to inspect cache spans during reconciliation", t)
            }

            synchronized(lock) {
                if (actualSpans.isNotEmpty()) {
                    lru.clear()
                    lru.addAll(actualSpans.sortedBy { it.lastTouchTimestamp })
                    currentSize = actualSpans.sumOf { it.length }
                } else if (cacheSpace > 0L) {
                    currentSize = cacheSpace
                }
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
