package com.lagradost.cloudstream3.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.util.UUID

@OptIn(UnstableApi::class)
data class PrefetchRequest(
    val id: String = UUID.randomUUID().toString(),
    val identity: PrefetchRequestIdentity,
    @Volatile var priority: Priority,
    val mediaTimeUs: Long = 0L,
    val durationUs: Long = 0L,
    val isInit: Boolean = false,
    val trackType: Int = C.TRACK_TYPE_DEFAULT,
    val dataSpec: DataSpec,
    val dependencyKeys: List<String> = emptyList(),
    @Volatile var status: Status = Status.QUEUED,
    /**
     * Set when the scheduler asked this request's CacheWriter to stop (playback handoff, seek or
     * release). A cancelled writer must never be retried and must never be reported as failed.
     */
    @Volatile var cancelRequested: Boolean = false,
    @Volatile var bytesDownloaded: Long = 0L,
    @Volatile var totalBytes: Long = -1L,
    @Volatile var watchers: Int = 1,
    @Volatile var retryCount: Int = 0,
    @Volatile var error: Throwable? = null,
    val createdAtMs: Long = SystemClock.elapsedRealtime(),
) : Comparable<PrefetchRequest> {

    enum class Priority(val rank: Int) {
        /** 1. Immediate playback requirements (Media3 currently playing or about to read) */
        IMMEDIATE(1),
        /** 2. Next missing audio/video media (the next sequential fragments ahead of playhead) */
        NEXT_MEDIA(2),
        /** 3. Upcoming forward media (standard forward runway) */
        FORWARD_MEDIA(3),
        /** 4. Remaining useful prefetch work */
        USEFUL_PREFETCH(4),
        /** 5. Backward seek recovery */
        BACKWARD_SEEK(5),
        /** 6. Distant speculative requests */
        SPECULATIVE(6);
    }

    enum class Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /**
     * Priority comparison:
     * 1. Lower rank value means higher priority (IMMEDIATE=1 beats NEXT_MEDIA=2, etc.)
     * 2. Initialization chunks beat media chunks at the same priority level
     * 3. Closer mediaTimeUs comes first
     * 4. Earlier createdAtMs comes first
     */
    override fun compareTo(other: PrefetchRequest): Int {
        val pComp = this.priority.rank.compareTo(other.priority.rank)
        if (pComp != 0) return pComp

        if (this.isInit != other.isInit) {
            return if (this.isInit) -1 else 1
        }

        val timeComp = this.mediaTimeUs.compareTo(other.mediaTimeUs)
        if (timeComp != 0) return timeComp

        return this.createdAtMs.compareTo(other.createdAtMs)
    }

    fun isTerminal(): Boolean =
        status == Status.COMPLETED || status == Status.CANCELLED || status == Status.FAILED
}
