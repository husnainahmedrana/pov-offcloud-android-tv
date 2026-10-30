package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.pov.tv.data.SourceItem
import java.security.MessageDigest
import kotlin.math.roundToInt

/** A vertical choice list has one remote target per card, irrespective of text length or geometry. */
internal class TvChoiceList(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipChildren = false
        clipToPadding = false
    }

    fun addChoice(row: TvChoiceRow) {
        if (row.id == View.NO_ID) row.id = View.generateViewId()
        val previous = (0 until childCount).map(::getChildAt).lastOrNull { it.isFocusable }
        previous?.nextFocusDownId = row.id
        row.nextFocusUpId = previous?.id ?: View.NO_ID
        addView(row, LayoutParams(-1, row.cardHeight).apply { bottomMargin = row.gap })
    }

    companion object {
        /** Shared by normal navigation and the mini player's edge detection. */
        fun nextFrom(focused: View, direction: Int): View? {
            if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return null
            val list = focused.parent as? TvChoiceList ?: return null
            val index = list.indexOfChild(focused)
            val step = if (direction == View.FOCUS_DOWN) 1 else -1
            var candidate = index + step
            while (candidate in 0 until list.childCount) {
                val row = list.getChildAt(candidate)
                if (row.isShown && row.isEnabled && row.isFocusable) return row
                candidate += step
            }
            return null
        }
    }
}

/** The card owns focus; bounded, non-interactive text cannot scroll itself or intercept the D-pad. */
internal class TvChoiceRow(
    context: Context,
    key: String,
    title: String,
    detail: String = "",
    eyebrow: String = "",
    palette: ThemePalette = ThemeSpec.FLIX.palette,
    badges: List<SourceBadge> = emptyList(),
    withPoster: Boolean = false,
    action: () -> Unit,
) : FrameLayout(context) {
    private fun px(value: Int) = (value * resources.displayMetrics.widthPixels / DeviceLayout.canvasWidth.toFloat()).roundToInt().coerceAtLeast(1)
    val cardHeight = if (badges.isNotEmpty()) LayoutParams.WRAP_CONTENT else px(if (eyebrow.isBlank()) 79 else 101)
    val gap = px(9)
    private val labels = mutableListOf<TextView>()
    val poster = android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    init {
        tag = key
        contentDescription = (badges.map { it.label } + listOf(eyebrow, title, detail)).filter(String::isNotBlank).joinToString(". ")
        isFocusable = true
        isFocusableInTouchMode = DeviceLayout.tv
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(if (withPoster) 70 else 15), px(10), px(15), px(10))
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun label(value: String, size: Int, lines: Int, bold: Boolean): TextView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = false
            isClickable = false
            isLongClickable = false
            setTextIsSelectable(false)
            movementMethod = null
            setHorizontallyScrolling(false)
            labels += this
        }
        if (badges.isNotEmpty()) column.addView(SourceBadgeStrip(context, badges), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(7) })
        if (eyebrow.isNotBlank()) column.addView(label(eyebrow, 10, 1, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(5) })
        column.addView(label(title, 14, 2, true), LinearLayout.LayoutParams(-1, -2))
        if (detail.isNotBlank()) column.addView(label(detail, 11, 1, false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5) })
        addView(column, LayoutParams(-1, if (badges.isNotEmpty()) LayoutParams.WRAP_CONTENT else -1))
        if (withPoster) addView(poster, LayoutParams(px(48), px(67), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = px(10) })
        // Flix lifts the focused row with a light glass tint and a white outline instead of a solid accent fill.
        val flix = palette == ThemeSpec.FLIX.palette
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(when { !focused -> palette.panel; flix -> 0xFF3A3A40.toInt(); else -> palette.accent })
                cornerRadius = px(if (flix) 9 else 7).toFloat()
            }
            // Flix: a sharp 2px-wide white outline, with no glow or zoom that would soften the text.
            foreground = if (flix && focused) GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = px(9).toFloat(); setStroke(px(2), Color.WHITE) }
                else FocusRingDrawable(if (focused) palette.accent else palette.border, px(7).toFloat(), focused, px(if (focused) 2 else 1).toFloat())
            labels.forEach { it.setTextColor(if (focused && !flix) Color.rgb(4, 15, 24) else palette.text) }
        }
        setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { action() }
        paint(false)
        // Phones never focus rows, so a tap shows a ripple instead.
        if (DeviceLayout.phone) foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = px(9).toFloat() })
    }
}

/** Keys preserve a chosen version across Back/rebuilds without exposing signed stream URLs in tags. */
internal object TvChoiceKeys {
    fun source(source: SourceItem): String {
        val identity = when {
            source.directUrl.isNotBlank() -> listOf("direct", source.directUrl)
            source.infoHash.isNotBlank() -> listOf("torrent", source.infoHash.lowercase(java.util.Locale.ROOT), source.fileIndex?.toString().orEmpty())
            else -> listOf("source", source.provider, source.title, source.detail)
        }.joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "source:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun folder(kind: String, folderId: Long) = "folder:$kind:$folderId"
    fun file(kind: String, folderId: Long, fileId: Long) = "file:$kind:$folderId:$fileId"
}
