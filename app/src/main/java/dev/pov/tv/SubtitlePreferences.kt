package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import java.util.Locale

/** Device-local text subtitle preferences, shared by fullscreen and mini-player views. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SubtitlePreferences(private val context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE)
    var transparentBackground: Boolean
        get() = preferences.getBoolean("transparent_background", true)
        set(value) { preferences.edit().putBoolean("transparent_background", value).apply() }
    var boldText: Boolean
        get() = preferences.getBoolean("bold_text", true)
        set(value) { preferences.edit().putBoolean("bold_text", value).apply() }

    var sizePercent: Int
        get() = preferences.getInt("size_percent", 100).takeIf { it in SIZES } ?: 100
        set(value) { require(value in SIZES); preferences.edit().putInt("size_percent", value).apply() }
    val sizeLabel: String get() = SIZE_LABELS[SIZES.indexOf(sizePercent)]

    var positionPercent: Int
        get() = preferences.getInt("position_percent", DEFAULT_POSITION).takeIf { it == -1 || it in 1..30 } ?: DEFAULT_POSITION
        set(value) { require(value == -1 || value in 1..30); preferences.edit().putInt("position_percent", value).apply() }
    val positionLabel: String get() = if (positionPercent < 0) "Default" else "$positionPercent% from bottom"

    fun positionedCues(cues: List<Cue>): List<Cue> {
        val selected = positionPercent
        if (selected < 0) return cues
        return cues.map { cue ->
            if (cue.text == null || cue.verticalType != Cue.TYPE_UNSET) cue else cue.buildUpon()
                .setLine(1f - selected / 100f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        }
    }

    var language: String
        get() = preferences.getString("language", "en").orEmpty().takeIf { value -> LANGUAGES.any { it.first == value } }.orEmpty()
        set(value) { require(LANGUAGES.any { it.first == value }); preferences.edit().putString("language", value).apply() }
    val languageLabel: String get() = LANGUAGES.first { it.first == language }.second

    fun trackParameters(current: TrackSelectionParameters): TrackSelectionParameters {
        val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        return when (val selected = language) {
            "off" -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            "" -> {
                val defaults = TrackSelectionParameters.Builder(context).build()
                builder.setPreferredTextLanguages(*defaults.preferredTextLanguages.toTypedArray())
                    .setPreferredTextRoleFlags(defaults.preferredTextRoleFlags)
                    .setIgnoredTextSelectionFlags(defaults.ignoredTextSelectionFlags)
                    .setSelectUndeterminedTextLanguage(defaults.selectUndeterminedTextLanguage)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT in defaults.disabledTrackTypes).build()
            }
            else -> builder.setPreferredTextLanguages(*AudioPreferences(context).withFallback(selected)).setPreferredTextRoleFlags(0)
                .setSelectUndeterminedTextLanguage(false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
    }

    internal fun style(base: CaptionStyleCompat): CaptionStyleCompat = CaptionStyleCompat(
        base.foregroundColor,
        if (transparentBackground) Color.TRANSPARENT else base.backgroundColor,
        if (transparentBackground) Color.TRANSPARENT else base.windowColor,
        if (transparentBackground && base.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE)
            CaptionStyleCompat.EDGE_TYPE_OUTLINE else base.edgeType,
        base.edgeColor,
        if (boldText) Typeface.create(base.typeface ?: Typeface.DEFAULT, Typeface.BOLD) else base.typeface,
    )

    fun applyTo(view: PlayerView) {
        val subtitles = view.subtitleView ?: return
        view.player?.let { subtitles.setCues(positionedCues(it.currentCues.cues)) }
        val override = transparentBackground || boldText
        // Embedded cue spans/windows can override background and font weight. Restore them
        // when both options are off, keeping the original rendering as the default.
        subtitles.setApplyEmbeddedStyles(!override)
        subtitles.setApplyEmbeddedFontSizes(!override && sizePercent == 100)
        val captions = view.context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        subtitles.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
            sizePercent / 100f * (captions?.fontScale ?: 1f))
        if (!override) {
            subtitles.setUserDefaultStyle()
            return
        }
        val base = captions?.userStyle?.let(CaptionStyleCompat::createFromCaptionStyle) ?: CaptionStyleCompat.DEFAULT
        subtitles.setStyle(style(base))
    }

    companion object {
        val LANGUAGES: List<Pair<String, String>> = listOf("" to "Automatic (device preference)", "off" to "Off") +
            Locale.getISOLanguages().map { it to Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
                .distinctBy { it.first }.sortedBy { it.second }
        val COMMON_LANGUAGES = listOf("", "off", "en", "ur", "hi", "ar", "es", "fr", "de", "pt", "zh", "ja", "ko")
        val SIZES = listOf(80, 100, 130)
        /** New installs: bold text on a transparent background, 3% above the bottom, English. */
        const val DEFAULT_POSITION = 3
        val SIZE_LABELS = listOf("Small", "Medium", "Large")
    }
}
