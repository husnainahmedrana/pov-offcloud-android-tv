package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceLanguageOrder
import dev.pov.tv.data.SourceMetadata
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SourceBadgeTone { UHD, FULL_HD, HD, OTHER, NEUTRAL }
internal data class SourceBadge(val label: String, val tone: SourceBadgeTone = SourceBadgeTone.NEUTRAL, val quality: Boolean = false)

/** Release-name tags are provider claims, not a probe of the selected video's capabilities. */
internal object SourceBadges {
    fun from(source: SourceItem, languages: SourceLanguageOrder? = null): List<SourceBadge> = buildList {
        add(SourceBadge(SourceMetadata.qualityLabel(source.qualityHeight), when (source.qualityHeight) {
            2160, 4320 -> SourceBadgeTone.UHD
            1080 -> SourceBadgeTone.FULL_HD
            720 -> SourceBadgeTone.HD
            else -> SourceBadgeTone.OTHER
        }, quality = true))
        add(SourceBadge(SourceMetadata.sizeLabel(source.sizeBytes)))
        val metadata = "${source.title} ${source.detail}".take(4000)
        fun has(pattern: String) = Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z0-9])").containsMatchIn(metadata)
        fun tag(label: String) { add(SourceBadge(label)) }
        if (has("DV|DOVI|DOLBY[ ._-]*VISION")) tag("DV")
        when {
            has("HDR10\\+|HDR10PLUS") -> tag("HDR10+")
            has("HDR10") -> tag("HDR10")
            has("HDR") -> tag("HDR")
        }
        if (has("HLG")) tag("HLG")
        if (has("SDR") && none { it.label in listOf("DV", "HDR10+", "HDR10", "HDR", "HLG") }) tag("SDR")
        when {
            has("WEB[ ._-]?DL") -> tag("WEB-DL")
            has("WEB[ ._-]?RIP") -> tag("WEBRip")
            has("BLU[ ._-]?RAY|BDRIP|BDREMUX") -> tag("BLU-RAY")
        }
        if (has("(?:BD[ ._-]?)?REMUX")) tag("REMUX")
        when {
            has("AV1") -> tag("AV1")
            has("HEVC|[HX][ ._-]?265") -> tag("HEVC")
            has("AVC|[HX][ ._-]?264") -> tag("H.264")
        }
        if (languages != null) {
            fun name(code: String) = SubtitlePreferences.LANGUAGES.firstOrNull { it.first == code }?.second?.uppercase() ?: code.uppercase()
            when {
                languages.audioMatch(source) -> tag(name(languages.audio))
                languages.multiAudio(source) -> tag("MULTI AUDIO")
            }
            if (languages.subtitleMatch(source)) tag("${name(languages.subtitle)} SUBS")
        }
    }
}

/** Wrap chips instead of clipping metadata or introducing a second remote focus target. */
internal class SourceBadgeStrip(context: Context, badges: List<SourceBadge>) : ViewGroup(context) {
    private fun px(value: Int) = (value * resources.displayMetrics.widthPixels / 960f).roundToInt().coerceAtLeast(1)
    private val gap = px(6)
    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        badges.forEach { badge ->
            val (ink, fill, border) = when (badge.tone) {
                SourceBadgeTone.UHD -> Triple("#FFE34D", "#302800", "#CFB92E")
                SourceBadgeTone.FULL_HD -> Triple("#FF8891", "#33171A", "#D95D67")
                SourceBadgeTone.HD -> Triple("#D7ACFF", "#2C203A", "#9564C4")
                SourceBadgeTone.OTHER -> Triple("#BDE6FF", "#183344", "#608CA6")
                SourceBadgeTone.NEUTRAL -> Triple("#FFFFFF", "#273442", "#66788A")
            }
            addView(TextView(context).apply {
                text = badge.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(if (badge.quality) 16 else 13).toFloat())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(Color.parseColor(ink))
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                setPadding(px(8), px(4), px(8), px(4)); minHeight = px(28)
                isFocusable = false; isClickable = false
                tag = "source-badge:${badge.label}"
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(fill)); setStroke(px(1), Color.parseColor(border)); cornerRadius = px(6).toFloat()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowHeight = 0; var widest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > available) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            widest = max(widest, x + child.measuredWidth)
            x += child.measuredWidth + gap; rowHeight = max(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(y + rowHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0; var y = 0; var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + child.measuredWidth > width) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap; rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}
