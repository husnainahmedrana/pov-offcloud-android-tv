package dev.pov.tv

import android.app.Dialog
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class PlayerSheetChoice(
    val label: String,
    val checked: Boolean = false,
    val selectable: Boolean = true,
    val action: () -> Unit,
)

/** A single TV-focusable bottom sheet style for audio, subtitles, boost, and player messages. */
internal object PlayerOptionSheet {
    fun create(context: Context, title: String, detail: String, choices: List<PlayerSheetChoice>, accent: Int): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            tag = "pov_player_sheet"; orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(22), dp(32), dp(24))
            // Same card as the app's dialogs: neutral gradient, hairline border, rounded top.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2A30.toInt(), 0xFF17171A.toInt())).apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0x2EFFFFFF)
            }
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply {
            text = title; textSize = 23f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val close = Button(context).apply {
            text = "Done"; textSize = 13f; isAllCaps = false; minWidth = dp(90)
            setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(0x24FFFFFF); cornerRadius = dp(10).toFloat() }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply { setColor(if (focused) Color.WHITE else 0x24FFFFFF); cornerRadius = dp(10).toFloat() }
                setTextColor(if (focused) Color.BLACK else Color.WHITE)
            }
            setOnClickListener { dialog.dismiss() }
        }
        heading.addView(close, LinearLayout.LayoutParams(dp(96), dp(44)))
        content.addView(heading)
        if (detail.isNotBlank()) content.addView(TextView(context).apply {
            text = detail; textSize = 13f; setTextColor(0xFFC4C4C8.toInt())
            setPadding(0, dp(3), dp(12), dp(14))
        }, LinearLayout.LayoutParams(-1, -2))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = choices.mapIndexed { index, choice ->
            Button(context).apply {
                tag = "pov_sheet_choice_$index"
                text = if (choice.checked) "✓   ${choice.label}" else "     ${choice.label}"
                contentDescription = choice.label + if (choice.checked) ", selected" else ""
                textSize = 14f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 2; minHeight = dp(47); setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv
                fun style(focused: Boolean) {
                    // Focused rows turn white with dark text; the current choice keeps a light outline.
                    setTextColor(if (focused) Color.BLACK else if (choice.selectable) Color.WHITE else 0xFF8E8E93.toInt())
                    background = GradientDrawable().apply {
                        setColor(if (focused) Color.WHITE else if (choice.checked) 0x33FFFFFF else 0x14FFFFFF)
                        cornerRadius = dp(10).toFloat()
                        if (!focused && choice.checked) setStroke(dp(1), 0x80FFFFFF.toInt())
                    }
                }
                style(false)
                setOnFocusChangeListener { _, focused -> style(focused) }
                // Unavailable formats remain focusable/readable, but cannot request playback.
                setOnClickListener { if (choice.selectable) { dialog.dismiss(); choice.action() } }
            }.also { rows.addView(it, LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(6) }) }
        }
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(rows) }
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.44).toInt()
        content.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(55) * choices.size, maxHeight).coerceAtLeast(dp(50))))
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f; gravity = Gravity.BOTTOM }
        }
        dialog.setOnShowListener {
            (context as? Activity)?.window?.decorView?.systemUiVisibility?.let { flags ->
                dialog.window?.decorView?.systemUiVisibility = flags
            }
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.window?.setGravity(Gravity.BOTTOM)
            val selected = choices.indexOfFirst { it.checked }.takeIf { it >= 0 } ?: 0
            buttons.getOrNull(selected)?.post { buttons[selected].requestFocus() }
        }
        return dialog
    }
}

