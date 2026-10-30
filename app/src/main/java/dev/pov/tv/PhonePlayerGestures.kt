package dev.pov.tv

import android.app.Activity
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phone player gestures: double-tap the left or right third to skip 10 s (middle: play/pause), drag sideways
 * to scrub, drag up/down on the left for brightness and on the right for volume. Taps still reach the
 * player view, which shows and hides the controls.
 */
internal class PhonePlayerGestures(
    private val activity: Activity,
    private val surface: View,
    private val label: TextView,
    private val player: () -> Player?,
    /** True while a touch at this height belongs to the visible controls (seek bar and buttons). */
    private val overControls: (Float) -> Boolean,
    private val showControls: () -> Unit,
) {
    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME, CONSUMED }
    private var mode = Mode.NONE
    private var ignored = false
    private var startX = 0f
    private var startY = 0f
    private var seekFrom = 0L
    private var seekTarget = 0L
    private var startLevel = 0f
    private val audio = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
    private val hideLabel = Runnable { label.visibility = View.GONE }

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            val exo = player() ?: return false
            val third = surface.width / 3f
            when {
                event.x < third -> skip(exo, -SKIP_MS)
                event.x > third * 2 -> skip(exo, SKIP_MS)
                else -> { if (exo.isPlaying) exo.pause() else exo.play(); showControls() }
            }
            // The second tap of a double tap is ours; the player view only saw the first.
            mode = Mode.CONSUMED
            return true
        }
    })

    /** Returns true when the event was used by a gesture and must not reach the player view. */
    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            mode = Mode.NONE
            ignored = overControls(event.y)
            startX = event.x; startY = event.y
        }
        if (ignored) return false
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (mode == Mode.NONE) begin(event) else if (mode != Mode.CONSUMED) move(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = mode
                if (was == Mode.SEEK && event.actionMasked == MotionEvent.ACTION_UP) player()?.seekTo(seekTarget)
                if (was != Mode.NONE) { label.removeCallbacks(hideLabel); label.postDelayed(hideLabel, 600) }
                mode = Mode.NONE
                return was != Mode.NONE
            }
        }
        return mode != Mode.NONE
    }

    private fun begin(event: MotionEvent) {
        val dx = event.x - startX; val dy = event.y - startY
        val slop = surface.resources.displayMetrics.density * 18
        if (abs(dx) < slop && abs(dy) < slop) return
        val exo = player()
        mode = when {
            abs(dx) > abs(dy) -> if (exo != null && exo.isCurrentMediaItemSeekable && exo.duration > 0) Mode.SEEK else Mode.CONSUMED
            startX < surface.width / 2f -> Mode.BRIGHTNESS
            else -> Mode.VOLUME
        }
        when (mode) {
            Mode.SEEK -> { seekFrom = exo!!.currentPosition; seekTarget = seekFrom }
            Mode.BRIGHTNESS -> startLevel = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                ?: (android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            Mode.VOLUME -> startLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            else -> {}
        }
        // Whatever the player view saw of this touch so far is not a tap.
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        surface.dispatchTouchEvent(cancel); cancel.recycle()
        move(event)
    }

    private fun move(event: MotionEvent) {
        val exo = player()
        when (mode) {
            Mode.SEEK -> if (exo != null) {
                // A full sweep of the screen moves two minutes.
                val offset = ((event.x - startX) / surface.width * SWEEP_MS).toLong()
                seekTarget = (seekFrom + offset).coerceIn(0, exo.duration)
                show("${DpadSeekPreview.time(seekTarget)}\n${DpadSeekPreview.offset(seekTarget - seekFrom)}  ·  ${DpadSeekPreview.time(exo.duration)}")
            }
            Mode.BRIGHTNESS -> {
                val level = (startLevel + (startY - event.y) / (surface.height * 0.7f)).coerceIn(0.01f, 1f)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = level }
                show("☀  ${(level * 100).roundToInt()}%")
            }
            Mode.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (startLevel + (startY - event.y) / (surface.height * 0.7f)).coerceIn(0f, 1f)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                show("🔊  ${(level * 100).roundToInt()}%")
            }
            else -> {}
        }
    }

    private fun skip(exo: Player, by: Long) {
        if (!exo.isCurrentMediaItemSeekable) return
        val target = (exo.currentPosition + by).coerceIn(0, if (exo.duration > 0) exo.duration else Long.MAX_VALUE)
        exo.seekTo(target)
        show(if (by < 0) "⏪  10 s" else "10 s  ⏩")
        label.removeCallbacks(hideLabel); label.postDelayed(hideLabel, 700)
    }

    private fun show(text: String) {
        label.removeCallbacks(hideLabel)
        label.text = text; label.visibility = View.VISIBLE
    }

    companion object {
        const val SKIP_MS = 10_000L
        const val SWEEP_MS = 120_000f

        /** Video fills the screen behind the camera cut-out in landscape. */
        fun fullBleed(activity: Activity) {
            if (android.os.Build.VERSION.SDK_INT >= 28) activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
