package dev.pov.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView

/** Preview every adjustment immediately; Cancel restores the previous saved position. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object SubtitlePositionPicker {
    fun show(context: Context, liveVideo: Boolean, changed: () -> Unit, dismissed: () -> Unit = {}): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val preferences = SubtitlePreferences(context)
        val original = preferences.positionPercent
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val label = TextView(context).apply { textSize = 18f; setTextColor(Color.WHITE) }
        content.addView(label)
        content.addView(TextView(context).apply {
            text = "Left: lower · Right: higher"; textSize = 14f; setTextColor(0xFFB6C6D8.toInt())
        })
        val slider = SeekBar(context).apply {
            max = 29; keyProgressIncrement = 1
            isFocusableInTouchMode = DeviceLayout.tv
            progress = (if (original < 0) 8 else original) - 1
            contentDescription = "Subtitle distance from bottom"
        }
        content.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
        val sample = if (liveVideo) null else SubtitleView(context).also { subtitles ->
            val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
            frame.addView(subtitles, FrameLayout.LayoutParams(-1, -1))
            content.addView(frame, LinearLayout.LayoutParams(-1, dp(150)))
            subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        fun update() {
            label.text = if (preferences.positionPercent < 0) "Original subtitle position" else "Distance from bottom · ${preferences.positionPercent}%"
            sample?.setCues(preferences.positionedCues(listOf(Cue.Builder().setText("Subtitle position preview").build())))
            changed()
        }
        fun restore() { preferences.positionPercent = original; changed() }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { preferences.positionPercent = progress + 1; update() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        val dialog = AlertDialog.Builder(context).setTitle("Subtitle position").setView(content)
            .setPositiveButton("Done", null).setNegativeButton("Cancel") { _, _ -> restore() }
            .setNeutralButton("Reset", null).create()
        dialog.setOnCancelListener { restore() }
        dialog.setOnDismissListener { dismissed() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                preferences.positionPercent = -1; slider.progress = 7; update()
            }
            if (liveVideo) dialog.window?.apply {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(620)), -2)
                attributes = attributes.apply { y = dp(20); dimAmount = 0.1f }
            }
            slider.requestFocus()
        }
        // Keep original placement until the user actually moves the slider.
        label.text = if (original < 0) "Original subtitle position" else "Distance from bottom · $original%"
        sample?.setCues(preferences.positionedCues(listOf(Cue.Builder().setText("Subtitle position preview").build())))
        dialog.show()
        return dialog
    }
}
