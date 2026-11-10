package com.lagradost.cloudstream3.ui.player.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R
import com.github.rubensousa.previewseekbar.media3.PreviewTimeBar
import java.lang.ref.WeakReference
import kotlin.math.min

@OptIn(UnstableApi::class)
class LivePreviewTimeBar(val ctx: Context, attrs: AttributeSet) : PreviewTimeBar(ctx, attrs) {

    private var _currentPlayerView: WeakReference<PlayerView>? = null
    var player: Player? = null
    val currentPlayer: Player? get() = player ?: _currentPlayerView?.get()?.player

    private var originalBufferedColor: Int = DefaultTimeBar.DEFAULT_BUFFERED_COLOR
    private var prefetchPositionMs: Long = 0L
    private var lastPositionMs: Long = 0L
    private var lastBufferedPositionMs: Long = 0L
    private var lastDurationMs: Long = 0L

    var prefetchModeEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    setBufferedColor(Color.TRANSPARENT)
                } else {
                    setBufferedColor(originalBufferedColor)
                    prefetchPositionMs = 0L
                }
                postInvalidate()
            }
        }

    private val prefetchPaint = Paint().apply {
        color = 0xFF29B6F6.toInt() // Material Light Blue 400 at 100% opacity for high visibility
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val barBounds = Rect()

    private val progressBarField = runCatching {
        DefaultTimeBar::class.java.getDeclaredField("progressBar").apply { isAccessible = true }
    }.getOrNull()

    private val scrubberDrawableField = runCatching {
        DefaultTimeBar::class.java.getDeclaredField("scrubberDrawable").apply { isAccessible = true }
    }.getOrNull()

    fun registerPlayerView(player: PlayerView?) {
        _currentPlayerView = WeakReference(player)
        val controller =
            _currentPlayerView?.get()?.findViewById<PlayerControlView>(R.id.exo_controller)

        controller?.setProgressUpdateListener { position, bufferedPosition ->
            lastPositionMs = position
            lastBufferedPositionMs = bufferedPosition
            currentPlayer?.let { p ->
                lastDurationMs = p.duration
                if (isAtLiveEdge()) {
                    setPosition(p.duration)
                }
            }
        }
    }

    /**
     * Sets the validated contiguous prefetch coverage in milliseconds.
     * Displays a distinct visual indicator ahead of the normal ExoPlayer decoded buffer.
     */
    fun setPrefetchPosition(positionMs: Long) {
        if (prefetchPositionMs != positionMs) {
            prefetchPositionMs = positionMs
            if (prefetchModeEnabled) {
                setBufferedColor(Color.TRANSPARENT)
            }
            postInvalidate()
        }
    }

    fun getPrefetchPosition(): Long = prefetchPositionMs

    fun isAtLiveEdge(): Boolean {
        return LiveHelper.getLiveManager(currentPlayer)?.isAtLiveEdge() == true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPrefetchBar(canvas)
    }

    private fun drawPrefetchBar(canvas: Canvas) {
        if (!prefetchModeEnabled) return
        val duration = if (lastDurationMs > 0) lastDurationMs else (currentPlayer?.duration ?: 0L)
        if (duration <= 0L || prefetchPositionMs <= 0L) return

        val position = if (lastPositionMs > 0) lastPositionMs else (currentPlayer?.currentPosition ?: 0L)
        val prefetch = min(duration, prefetchPositionMs)

        if (prefetch <= position) return

        val bar = progressBarField?.get(this) as? Rect
        val left = bar?.left ?: paddingLeft
        val right = bar?.right ?: (width - paddingRight)
        val totalWidth = right - left
        if (totalWidth <= 0) return

        val top = bar?.top ?: (((height - paddingTop - paddingBottom) / 2 + paddingTop) - (4 * resources.displayMetrics.density).toInt() / 2)
        val bottom = bar?.bottom ?: (((height - paddingTop - paddingBottom) / 2 + paddingTop) + (4 * resources.displayMetrics.density).toInt() / 2)

        val startFraction = (position.coerceAtLeast(0L).toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        val endFraction = (prefetch.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)

        val startX = left + (totalWidth * startFraction).toInt()
        val endX = left + (totalWidth * endFraction).toInt()

        if (endX > startX) {
            barBounds.set(startX, top, endX, bottom)
            canvas.drawRect(barBounds, prefetchPaint)

            // Redraw scrubber thumb to ensure it stays crisp and on top of prefetch indicator
            (scrubberDrawableField?.get(this) as? Drawable)?.draw(canvas)
        }
    }
}
