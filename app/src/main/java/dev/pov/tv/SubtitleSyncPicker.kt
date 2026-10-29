package dev.pov.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small panel at the top of the video: Left/Right move subtitles 0.1 s earlier/later while they keep playing below,
 * so the viewer can watch the correction live. Holding a direction repeats the step.
 */
internal object SubtitleSyncPicker {
    private const val STEP_MS = 100L

    fun show(context: Context, dismissed: () -> Unit = {}): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun chip(focused: Boolean) = GradientDrawable().apply {
            setColor(if (focused) Color.WHITE else 0x24FFFFFF); cornerRadius = dp(12).toFloat()
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(6), dp(24), dp(4)) }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val value = TextView(context).apply {
            textSize = 26f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isFocusable = true; isFocusableInTouchMode = true; background = chip(false); setTextColor(Color.WHITE)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnFocusChangeListener { _, focused -> background = chip(focused); setTextColor(if (focused) Color.BLACK else Color.WHITE) }
        }
        fun update() {
            value.text = SubtitleSync.label()
            value.contentDescription = "Subtitle sync ${SubtitleSync.label()}. Left for earlier, right for later."
        }
        fun step(button: String, ms: Long) = TextView(context).apply {
            text = button; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(12).toFloat() }
            // Remote users press Left/Right on the value; these stay clickable for touch without taking focus.
            isFocusable = false; isClickable = true; setOnClickListener { SubtitleSync.adjust(ms); update() }
        }
        row.addView(step("−", -STEP_MS), LinearLayout.LayoutParams(dp(64), dp(56)))
        row.addView(value, LinearLayout.LayoutParams(dp(170), dp(56)).apply { marginStart = dp(12); marginEnd = dp(12) })
        row.addView(step("+", STEP_MS), LinearLayout.LayoutParams(dp(64), dp(56)))
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(context).apply {
            text = "◀  Left: subtitles earlier  ·  Right: subtitles later  ▶"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFFB6C6D8.toInt()); setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        value.setOnKeyListener { _, keyCode, event ->
            val direction = when (keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (direction == 0) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) { SubtitleSync.adjust(direction * STEP_MS); update() }
            true
        }
        update()
        val dialog = AlertDialog.Builder(context).setTitle("Subtitle sync").setView(content)
            .setPositiveButton("Done", null).setNeutralButton("Reset to 0", null).create()
        dialog.setOnDismissListener { dismissed() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { SubtitleSync.reset(); update(); value.requestFocus() }
            dialog.window?.apply {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(560)), -2)
                attributes = attributes.apply { y = dp(16); dimAmount = 0.1f }
            }
            value.requestFocus()
        }
        dialog.show()
        return dialog
    }
}
