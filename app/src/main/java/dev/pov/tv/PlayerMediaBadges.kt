package dev.pov.tv

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import dev.pov.tv.data.SourceMetadata
import kotlin.math.roundToInt

internal enum class PlayerBadgeKind { QUALITY, SIZE, SOURCE, CODEC, DOLBY_VISION, HDR, AUDIO, CHANNELS }
internal data class PlayerBadge(val label: String, val kind: PlayerBadgeKind)

/** What the decoder reports wins; the link's file name and size fill in what a stream cannot tell the player. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal data class PlayerMediaFacts(
    val name: String = "", val sizeBytes: Long? = null,
    val width: Int = 0, val height: Int = 0, val videoMime: String? = null, val videoCodecs: String? = null, val colorTransfer: Int = Format.NO_VALUE,
    val audioMime: String? = null, val channels: Int = 0,
) {
    companion object {
        fun from(name: String, sizeBytes: Long?, video: Format?, audio: Format?) = PlayerMediaFacts(name, sizeBytes,
            video?.width?.takeIf { it > 0 } ?: 0, video?.height?.takeIf { it > 0 } ?: 0, video?.sampleMimeType, video?.codecs,
            video?.colorInfo?.colorTransfer ?: Format.NO_VALUE, audio?.sampleMimeType, audio?.channelCount?.takeIf { it > 0 } ?: 0)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object PlayerMediaBadges {
    fun from(facts: PlayerMediaFacts): List<PlayerBadge> = buildList {
        val name = facts.name.take(4000)
        fun has(pattern: String) = Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z0-9])").containsMatchIn(name)
        fun add(label: String, kind: PlayerBadgeKind) { add(PlayerBadge(label, kind)) }

        quality(facts.width, facts.height)?.let { add(it, PlayerBadgeKind.QUALITY) }
            ?: SourceMetadata.qualityHeight(name)?.let { add(SourceMetadata.qualityLabel(it), PlayerBadgeKind.QUALITY) }
        facts.sizeBytes?.takeIf { it > 0 }?.let { add(SourceMetadata.sizeLabel(it), PlayerBadgeKind.SIZE) }
        when {
            has("(?:BD[ ._-]?)?REMUX") -> add("REMUX", PlayerBadgeKind.SOURCE)
            has("BLU[ ._-]?RAY|BDRIP|BRRIP") -> add("BLU-RAY", PlayerBadgeKind.SOURCE)
            has("WEB[ ._-]?DL") -> add("WEB-DL", PlayerBadgeKind.SOURCE)
            has("WEB[ ._-]?RIP") -> add("WEBRIP", PlayerBadgeKind.SOURCE)
            has("HDTV") -> add("HDTV", PlayerBadgeKind.SOURCE)
            has("DVD[ ._-]?RIP|DVD") -> add("DVD", PlayerBadgeKind.SOURCE)
        }
        val codec = when (facts.videoMime) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_DOLBY_VISION -> when {
                facts.videoCodecs.orEmpty().startsWith("dav1") -> "AV1"
                facts.videoCodecs.orEmpty().startsWith("dva") -> "H.264"
                else -> "HEVC"
            }
            else -> when {
                has("AV1") -> "AV1"
                has("HEVC|[HX][ ._-]?265") -> "HEVC"
                has("AVC|[HX][ ._-]?264") -> "H.264"
                else -> null
            }
        }
        codec?.let { add(it, PlayerBadgeKind.CODEC) }
        val dolbyVision = facts.videoMime == MimeTypes.VIDEO_DOLBY_VISION ||
            facts.videoCodecs.orEmpty().let { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") } ||
            has("DV|DOVI|DOLBY[ ._-]*VISION")
        if (dolbyVision) add("DV", PlayerBadgeKind.DOLBY_VISION)
        when {
            has("HDR10\\+|HDR10[ ._-]?PLUS") -> add("HDR10+", PlayerBadgeKind.HDR)
            facts.colorTransfer == C.COLOR_TRANSFER_HLG || has("HLG") -> add("HLG", PlayerBadgeKind.HDR)
            facts.colorTransfer == C.COLOR_TRANSFER_ST2084 || has("HDR10") -> add("HDR10", PlayerBadgeKind.HDR)
            has("HDR") -> add("HDR", PlayerBadgeKind.HDR)
        }
        audio(facts.audioMime, ::has).forEach { add(it, PlayerBadgeKind.AUDIO) }
        val channels = when {
            facts.channels >= 8 -> "7.1"
            facts.channels in 6..7 -> "5.1"
            facts.channels == 2 -> "2.0"
            has("7[ .]1") -> "7.1"
            has("5[ .]1") -> "5.1"
            else -> null
        }
        channels?.let { add(it, PlayerBadgeKind.CHANNELS) }
    }

    /** Width decides first, so cropped cinema frames such as 3840x1600 still read as 4K. */
    fun quality(width: Int, height: Int): String? = when {
        width <= 0 && height <= 0 -> null
        width >= 6000 || height >= 3400 -> "8K"
        width >= 3200 || height >= 2000 -> "4K"
        width >= 2200 || height >= 1300 -> "1440p"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        height >= 560 -> "576p"
        else -> "480p"
    }

    private fun audio(mime: String?, has: (String) -> Boolean): List<String> {
        val atmos = has("ATMOS")
        return when (mime) {
            MimeTypes.AUDIO_E_AC3_JOC -> listOf("ATMOS", "DD+")
            MimeTypes.AUDIO_E_AC3 -> listOfNotNull("ATMOS".takeIf { atmos }, "DD+")
            MimeTypes.AUDIO_TRUEHD -> listOfNotNull("ATMOS".takeIf { atmos }, "TRUEHD")
            MimeTypes.AUDIO_AC3 -> listOf("DD")
            MimeTypes.AUDIO_DTS_X -> listOf("DTS:X")
            MimeTypes.AUDIO_DTS_HD -> listOf(if (has("MA|DTS[ ._-]?HD[ ._-]?MA")) "DTS-HD MA" else "DTS-HD")
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> listOf("DTS")
            MimeTypes.AUDIO_AAC -> listOf("AAC")
            MimeTypes.AUDIO_OPUS -> listOf("OPUS")
            MimeTypes.AUDIO_FLAC -> listOf("FLAC")
            else -> when {
                has("TRUE[ ._-]?HD") -> listOfNotNull("ATMOS".takeIf { atmos }, "TRUEHD")
                has("DDP|DD\\+|E[ ._-]?AC[ ._-]?3") -> listOfNotNull("ATMOS".takeIf { atmos }, "DD+")
                has("DTS[ ._-]?X") -> listOf("DTS:X")
                has("DTS[ ._-]?HD[ ._-]?MA") -> listOf("DTS-HD MA")
                has("DTS[ ._-]?HD") -> listOf("DTS-HD")
                has("DTS") -> listOf("DTS")
                atmos -> listOf("ATMOS")
                has("DD|AC[ ._-]?3|DOLBY[ ._-]?DIGITAL") -> listOf("DD")
                has("AAC") -> listOf("AAC")
                else -> emptyList()
            }
        }
    }
}

/**
 * Static, non-focusable badges between the seek and option buttons. Badges that do not fit are left out from the end,
 * so the most useful facts (quality, size, source) always stay visible.
 */
internal class PlayerBadgeRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private var shown: List<PlayerBadge> = emptyList()
    private var fitting = 0
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun show(badges: List<PlayerBadge>) {
        if (badges == shown) return
        shown = badges
        removeAllViews()
        badges.forEach { addView(badgeView(it)) }
        contentDescription = badges.joinToString(", ") { it.label }.ifEmpty { null }
    }

    private fun badgeView(badge: PlayerBadge): TextView {
        val (start, end, border) = when (badge.kind) {
            PlayerBadgeKind.QUALITY -> Triple(0xFFFFE9A3.toInt(), 0xFFE0A21B.toInt(), 0x99E0A21B.toInt())
            PlayerBadgeKind.SIZE -> Triple(0xFFF2F4F7.toInt(), 0xFFC9CED6.toInt(), 0x66C9CED6)
            PlayerBadgeKind.SOURCE -> Triple(0xFF8FD0FF.toInt(), 0xFF2F8CFF.toInt(), 0x992F8CFF.toInt())
            PlayerBadgeKind.CODEC -> Triple(0xFFC8F56A.toInt(), 0xFF6CC72B.toInt(), 0x996CC72B.toInt())
            PlayerBadgeKind.DOLBY_VISION -> Triple(0xFFFFFFFF.toInt(), 0xFFB9A7FF.toInt(), 0x99B9A7FF.toInt())
            PlayerBadgeKind.HDR -> Triple(0xFFFF7A45.toInt(), 0xFF7CC8FF.toInt(), 0x99FFB347.toInt())
            PlayerBadgeKind.AUDIO -> Triple(0xFF9BF2D8.toInt(), 0xFF2EC4A0.toInt(), 0x992EC4A0.toInt())
            PlayerBadgeKind.CHANNELS -> Triple(0xFFE3E8EF.toInt(), 0xFFAEB6C2.toInt(), 0x66AEB6C2)
        }
        return GradientLabel(context, start, end, badge.kind == PlayerBadgeKind.HDR).apply {
            text = badge.label; tag = "player-badge:${badge.label}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
            letterSpacing = 0.04f; isSingleLine = true; includeFontPadding = false; gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { setColor(0x8C0B0E14.toInt()); cornerRadius = dp(6).toFloat(); setStroke(dp(1), border) }
            isFocusable = false; isClickable = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val gap = dp(6)
        var used = 0; var tallest = 0; fitting = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val next = used + (if (fitting > 0) gap else 0) + child.measuredWidth
            if (next > available) break
            used = next; fitting++; tallest = maxOf(tallest, child.measuredHeight)
        }
        setMeasuredDimension(available, resolveSize(tallest, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = dp(6)
        val total = (0 until fitting).sumOf { getChildAt(it).measuredWidth } + gap * (fitting - 1).coerceAtLeast(0)
        var x = ((r - l) - total) / 2
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (index >= fitting) { child.layout(0, 0, 0, 0); continue }
            val y = ((b - t) - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
        }
    }
}

/** Badge text painted with a gradient; HDR gets a warm-to-cool sweep, the rest a light-to-deep tint. */
private class GradientLabel(context: Context, private val start: Int, private val end: Int, private val spectrum: Boolean) : TextView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = if (spectrum) LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFFFF5A5A.toInt(), 0xFFFFB347.toInt(), 0xFFFFE066.toInt(), 0xFF7CE38B.toInt(), 0xFF6CB8FF.toInt()), null, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h.toFloat(), start, end, Shader.TileMode.CLAMP)
    }
}

