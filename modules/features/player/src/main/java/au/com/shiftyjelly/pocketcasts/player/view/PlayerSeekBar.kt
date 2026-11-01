package au.com.shiftyjelly.pocketcasts.player.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.player.R
import au.com.shiftyjelly.pocketcasts.repositories.mimi.MimiTimeline
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.ui.theme.ThemeColor
import au.com.shiftyjelly.pocketcasts.utils.toHhMmSs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import au.com.shiftyjelly.pocketcasts.localization.R as LR

class PlayerSeekBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val view = inflater.inflate(R.layout.view_playback_seek_bar, this)
    private var seeking = false

    // currentTime and duration are on the bar's timeline: the real ones, or trimmed by [timeline]'s cuts
    private var currentTime = Duration.ZERO
    private var duration = Duration.ZERO
    private var realCurrentTime = Duration.ZERO
    private var realDuration = Duration.ZERO
    private var timeline: MimiTimeline? = null
    private var chapters = Chapters()
    private var playbackSpeed = 1.0
    private var adjustDuration = false
    private val seekBar = view.findViewById(R.id.seekBarInternal) as SegmentSeekBar
    private val elapsedTimeText = view.findViewById(R.id.elapsedTime) as TextView
    private val remainingTimeText = view.findViewById(R.id.remainingTime) as TextView
    private val bufferingSeekbar = view.findViewById(R.id.indeterminateProgressBar) as MaterialProgressBar

    var bufferedUpToInSecs: Int = 0
        set(value) {
            field = value
            seekBar.secondaryProgress = toBar(value.seconds).inWholeSeconds.toInt()
        }

    var isBuffering: Boolean = false
        set(value) {
            field = value
            bufferingSeekbar.isVisible = value
        }

    var changeListener: OnUserSeekListener? = null

    init {
        setupSeekBar()
    }

    fun setAdjustDuration(adjust: Boolean) {
        this.adjustDuration = adjust
        updateTextViews()
    }

    fun setCurrentTime(currentTime: Duration) {
        if (seeking) {
            return
        }
        realCurrentTime = currentTime
        this.currentTime = toBar(currentTime)
        seekBar.progress = this.currentTime.inWholeSeconds.toInt()
        updateTextViews()
    }

    /** mimi-hub's ad / self-promotion segments to highlight, and any to cut from the timeline (null: none). */
    fun setTimeline(timeline: MimiTimeline?) {
        if (timeline == this.timeline) return
        this.timeline = timeline
        seekBar.segments = timeline?.shown.orEmpty()
        setDuration(realDuration)
        seekBar.secondaryProgress = toBar(bufferedUpToInSecs.seconds).inWholeSeconds.toInt()
        if (!seeking) setCurrentTime(realCurrentTime)
    }

    private fun toBar(real: Duration): Duration = timeline?.toTrimmed(real.inWholeMilliseconds)?.milliseconds ?: real

    private fun toReal(bar: Duration): Duration = timeline?.toReal(bar.inWholeMilliseconds)?.milliseconds ?: bar

    fun setChapters(chapters: Chapters) {
        if (seeking) {
            return
        }
        this.chapters = chapters
        updateTextViews()
    }

    fun setDuration(duration: Duration) {
        realDuration = duration
        this.duration = toBar(duration)
        seekBar.max = this.duration.inWholeSeconds.toInt()
        updateTextViews()
    }

    fun setPlaybackSpeed(playbackSpeed: Double) {
        this.playbackSpeed = playbackSpeed
        updateTextViews()
    }

    fun setTintColor(color: Int?, theme: Theme.ThemeType) {
        val themeColor = ThemeColor.playerHighlight01(theme, color ?: Color.WHITE)
        seekBar.thumbTintList = ColorStateList.valueOf(ThemeColor.playerContrast01(theme))
        seekBar.progressTintList = ColorStateList.valueOf(ThemeColor.playerContrast01(theme))
        seekBar.secondaryProgressTintList = ColorStateList.valueOf(ThemeColor.playerContrast05(theme))
        seekBar.backgroundTintList = ColorStateList.valueOf(ThemeColor.playerContrast05(theme))

        with(bufferingSeekbar) {
            supportIndeterminateTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(themeColor, 0x1A))
        }

        elapsedTimeText.setTextColor(ThemeColor.playerContrast02(theme))
        remainingTimeText.setTextColor(ThemeColor.playerContrast02(theme))
    }

    private fun setupSeekBar() {
        bufferingSeekbar.isVisible = false
        seekBar.secondaryProgress = 0
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                changeListener?.onSeekPositionChangeStop(toReal(currentTime)) {
                    seeking = false
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                changeListener?.onSeekPositionChangeStart()
                seeking = true
            }

            override fun onProgressChanged(seekBar: SeekBar, progressSecs: Int, fromUser: Boolean) {
                if (!fromUser) return

                currentTime = progressSecs.seconds
                updateTextViews()

                changeListener?.onSeekPositionChanging(toReal(currentTime))
            }
        })
    }

    private fun updateTextViews() {
        if (duration <= Duration.ZERO) {
            elapsedTimeText.text = ""
            elapsedTimeText.contentDescription = ""
            remainingTimeText.text = ""
            remainingTimeText.contentDescription = ""
        } else {
            val elapsedTime = currentTime.toHhMmSs()
            elapsedTimeText.text = currentTime.toHhMmSs()
            elapsedTimeText.contentDescription = resources.getString(LR.string.player_played_up_to, elapsedTime)
            val timeLeft = remainingDuration()
            val remainingTime = buildString {
                if (timeLeft > Duration.ZERO) {
                    append('-')
                }
                append(timeLeft.toHhMmSs())
            }
            remainingTimeText.text = remainingTime
            remainingTimeText.contentDescription = resources.getString(LR.string.player_time_remaining, remainingTime.removePrefix("-"))
        }
    }

    private fun remainingDuration(): Duration {
        return if (adjustDuration) {
            (duration - currentTime - chapters.skippedChaptersDuration(toReal(currentTime))) / playbackSpeed
        } else {
            duration - currentTime
        }.coerceAtLeast(Duration.ZERO)
    }

    interface OnUserSeekListener {
        fun onSeekPositionChangeStop(progress: Duration, seekComplete: () -> Unit)
        fun onSeekPositionChanging(progress: Duration)
        fun onSeekPositionChangeStart()
    }
}
