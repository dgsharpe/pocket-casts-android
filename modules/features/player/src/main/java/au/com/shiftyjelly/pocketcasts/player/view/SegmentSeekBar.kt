package au.com.shiftyjelly.pocketcasts.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import au.com.shiftyjelly.pocketcasts.repositories.mimi.MimiSegments

/**
 * A seek bar that also paints mimi-hub's ad (green) and self-promotion (yellow) segments over its track, as
 * SponsorBlock does on YouTube. [segments] are in the bar's timeline, in ms (the bar's progress is in seconds).
 */
class SegmentSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle,
) : AppCompatSeekBar(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barHeight = 4 * resources.displayMetrics.density

    var segments: List<MimiSegments.Segment> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val track = progressDrawable
        if (segments.isEmpty() || max <= 0 || track == null) return
        val left = paddingLeft.toFloat()
        val width = (getWidth() - paddingLeft - paddingRight).toFloat()
        val centerY = paddingTop + track.bounds.exactCenterY()
        val maxMs = max * 1000f
        for (s in segments) {
            val a = left + width * (s.startMs / maxMs).coerceIn(0f, 1f)
            val b = left + width * (s.endMs / maxMs).coerceIn(0f, 1f)
            if (b - a < 1f) continue
            paint.color = if (s.isAd) AD_COLOR else PROMO_COLOR
            canvas.drawRoundRect(a, centerY - barHeight / 2, b, centerY + barHeight / 2, barHeight / 2, barHeight / 2, paint)
        }
        // the thumb again, on top of the segments (placed as AbsSeekBar.drawThumb places it)
        thumb?.let {
            canvas.save()
            canvas.translate((paddingLeft - thumbOffset).toFloat(), paddingTop.toFloat())
            it.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        const val AD_COLOR = 0xFF00D400.toInt() // SponsorBlock's "sponsor" green
        const val PROMO_COLOR = 0xFFFFD000.toInt() // ~ its "self-promotion" yellow
    }
}
