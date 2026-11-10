package com.lagradost.cloudstream3.ui.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Immutable snapshot of player state captured exclusively on the player application looper.
 * Protects against IllegalStateException from accessing ExoPlayer on background worker threads.
 */
@OptIn(UnstableApi::class)
data class PlaybackPlayerSnapshot(
    val currentPositionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long,
    val playbackState: Int,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackSpeed: Float,
    val currentManifest: Any?,
    val sourceUri: Uri?,
    val selectedMime: String?,
    val isLive: Boolean,
    val currentTracks: Tracks?,
    val windowPositionInPeriodUs: Long = 0L,
    val windowDurationUs: Long = C.TIME_UNSET,
    val windowDefaultStartPositionUs: Long = 0L,
) {
    val currentPositionUs: Long get() = currentPositionMs * 1000L
    val bufferedPositionUs: Long get() = bufferedPositionMs * 1000L
    val durationUs: Long get() = if (durationMs > 0) durationMs * 1000L else 0L
    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED

    companion object {
        /**
         * Safely dispatches to the player's application looper to capture an immutable state snapshot.
         */
        suspend fun capture(
            playerProvider: () -> Player?,
            sourceUri: Uri?,
            selectedMime: String?,
            isLive: Boolean,
        ): PlaybackPlayerSnapshot? {
            val player = playerProvider() ?: return null
            return try {
                runOnPlayerThread(player) { p ->
                    val timeline = runCatching { p.currentTimeline }.getOrNull()
                    var winPosInPeriodUs = 0L
                    var winDurUs = C.TIME_UNSET
                    var winDefStartUs = 0L
                    if (timeline != null && !timeline.isEmpty) {
                        val winIndex = runCatching { p.currentMediaItemIndex }.getOrDefault(0)
                        if (winIndex in 0 until timeline.windowCount) {
                            val window = androidx.media3.common.Timeline.Window()
                            runCatching { timeline.getWindow(winIndex, window) }.onSuccess {
                                winPosInPeriodUs = window.positionInFirstPeriodUs
                                winDurUs = window.durationUs
                                winDefStartUs = window.defaultPositionUs
                            }
                        }
                    }

                    PlaybackPlayerSnapshot(
                        currentPositionMs = p.currentPosition,
                        bufferedPositionMs = p.bufferedPosition,
                        durationMs = p.duration,
                        playbackState = p.playbackState,
                        isPlaying = p.isPlaying,
                        playWhenReady = p.playWhenReady,
                        playbackSpeed = runCatching { p.playbackParameters.speed }.getOrDefault(1.0f),
                        currentManifest = p.currentManifest,
                        sourceUri = sourceUri,
                        selectedMime = selectedMime,
                        isLive = isLive,
                        currentTracks = runCatching { p.currentTracks }.getOrNull(),
                        windowPositionInPeriodUs = winPosInPeriodUs,
                        windowDurationUs = winDurUs,
                        windowDefaultStartPositionUs = winDefStartUs,
                    )
                }
            } catch (e: Throwable) {
                // Player may have been released or detached
                null
            }
        }

        /**
         * Runs the given block on the player's actual application looper.
         */
        suspend fun <T> runOnPlayerThread(player: Player, block: (Player) -> T): T {
            val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
            val looper = runCatching { player.applicationLooper }.getOrNull() ?: mainLooper

            // In pure JVM test environments without active Looper, execute directly
            if (looper == null) {
                return block(player)
            }

            // If already on the player thread, execute synchronously without dispatch overhead
            if (Looper.myLooper() == looper) {
                return block(player)
            }

            // If player looper is the main looper, attempt Dispatchers.Main.immediate
            if (mainLooper != null && looper == mainLooper) {
                return runCatching {
                    withContext(Dispatchers.Main.immediate) {
                        block(player)
                    }
                }.getOrElse {
                    dispatchViaHandler(looper, player, block)
                }
            }

            // Otherwise dispatch to the specific player looper via Handler
            return dispatchViaHandler(looper, player, block)
        }

        private suspend fun <T> dispatchViaHandler(looper: Looper, player: Player, block: (Player) -> T): T {
            return suspendCancellableCoroutine { continuation ->
                val handler = Handler(looper)
                val runnable = Runnable {
                    try {
                        continuation.resume(block(player))
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                }
                handler.post(runnable)
                continuation.invokeOnCancellation {
                    handler.removeCallbacks(runnable)
                }
            }
        }
    }
}
