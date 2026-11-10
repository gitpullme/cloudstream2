package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
interface PrefetchAdapter {
    /**
     * Determines whether this adapter can handle the given media source.
     */
    suspend fun isSupported(sourceUri: Uri, mimeType: String?): Boolean

    /**
     * Returns ordered upcoming prefetch requests for the given playback window.
     */
    suspend fun getUpcomingRequests(
        currentPositionUs: Long,
        bufferedPositionUs: Long,
        durationUs: Long,
        forwardWindowUs: Long,
    ): List<PrefetchRequest>

    fun onSeek(newPositionUs: Long) {}

    fun release() {}
}
