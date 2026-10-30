package dev.pov.tv

import android.content.Context

/**
 * The two layouts: Flix (living backdrop, left menu, landscape shelves) and Editorial (light canvas,
 * split feature, poster collection). Dimensions use a 960 x 540 logical canvas scaled to the screen.
 * Theme changes contain presentation only; account and playback state are separate.
 */
enum class ThemeSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val palette: ThemePalette,
    private val tvLayout: ThemeLayout,
) {
    FLIX(
        id = "flix",
        displayName = "Flix",
        description = "Living backdrop, rotating Top 10 feature, title logos, and a hidden menu.",
        palette = ThemePalette(
            background = 0xFF141414.toInt(), panel = 0xFF232323.toInt(),
            text = 0xFFFFFFFF.toInt(), muted = 0xFFB3B3B3.toInt(),
            accent = 0xFFE50914.toInt(), secondaryAccent = 0xFFB20710.toInt(),
            buttonText = 0xFF000000.toInt(), border = 0xFF2F2F2F.toInt(),
            navSelected = 0x33FFFFFF, progress = 0xFFE50914.toInt(),
            primaryButton = 0xFFFFFFFF.toInt(),
        ),
        tvLayout = ThemeLayout(
            navigation = NavigationPlacement.LEFT_RAIL, hero = HeroComposition.FULL_BLEED,
            cards = CardFormat.LANDSCAPE, contentInsetDp = 36, railWidthDp = 0,
            headerHeightDp = 0, heroHeightDp = 300, cardWidthDp = 190, cardHeightDp = 107,
            cardGapDp = 9, cornerRadiusDp = 4, titleSizeSp = 46,
            titleTypeface = "sans-serif-black", titleLetterSpacing = 0f,
            sectionTitleSizeSp = 15, focusScale = 1.09f,
        ),
    ),
    EDITORIAL(
        id = "editorial",
        displayName = "Editorial",
        description = "An ivory canvas, a split feature, and a clean poster collection.",
        palette = ThemePalette(
            background = 0xFFFFFCF7.toInt(), panel = 0xFFF0EDE7.toInt(),
            text = 0xFF12203F.toInt(), muted = 0xFF656970.toInt(),
            accent = 0xFF6AD7FF.toInt(), secondaryAccent = 0xFFAD33ED.toInt(),
            buttonText = 0xFFF9FCFF.toInt(), border = 0xFFDAD9D5.toInt(),
            navSelected = 0xFFEEE8F8.toInt(), progress = 0xFF6AD7FF.toInt(),
            primaryButton = 0xFF16264B.toInt(),
        ),
        tvLayout = ThemeLayout(
            navigation = NavigationPlacement.TOP_BAR, hero = HeroComposition.SPLIT,
            cards = CardFormat.POSTER, contentInsetDp = 34, railWidthDp = 0,
            headerHeightDp = 59, heroHeightDp = 225, cardWidthDp = 170, cardHeightDp = 197,
            cardGapDp = 11, cornerRadiusDp = 8, titleSizeSp = 44,
            titleTypeface = "sans-serif-condensed", titleLetterSpacing = 0.12f,
            sectionTitleSizeSp = 17, focusScale = 1.018f,
        ),
    );

    /** The phone app draws Flix on a ~400-unit portrait canvas with a bottom navigation bar. */
    val layout: ThemeLayout get() = if (DeviceLayout.phone) tvLayout.copy(
        navigation = NavigationPlacement.BOTTOM_BAR, contentInsetDp = 14, headerHeightDp = 0,
        heroHeightDp = 380, cardWidthDp = 150, cardHeightDp = 84, cardGapDp = 8,
        titleSizeSp = 30, sectionTitleSizeSp = 15, focusScale = 1f,
    ) else tvLayout

    companion object {
        /** Unknown, retired or older preferences (including "streaming") fall back to Flix. */
        fun fromId(id: String?): ThemeSpec = entries.firstOrNull { it.id == id } ?: FLIX
    }
}

data class ThemePalette(
    val background: Int,
    val panel: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val secondaryAccent: Int,
    val buttonText: Int,
    val border: Int,
    val navSelected: Int,
    val progress: Int,
    val primaryButton: Int = accent,
    val success: Int = 0xFF38E965.toInt(),
)

data class ThemeLayout(
    val navigation: NavigationPlacement,
    val hero: HeroComposition,
    val cards: CardFormat,
    val contentInsetDp: Int,
    val railWidthDp: Int,
    val headerHeightDp: Int,
    val heroHeightDp: Int,
    val cardWidthDp: Int,
    val cardHeightDp: Int,
    val cardGapDp: Int,
    val cornerRadiusDp: Int,
    val titleSizeSp: Int,
    val titleTypeface: String,
    val titleLetterSpacing: Float,
    val sectionTitleSizeSp: Int,
    val focusScale: Float,
)

enum class NavigationPlacement { LEFT_RAIL, TOP_BAR, BOTTOM_BAR }
enum class HeroComposition { FULL_BLEED, SPLIT }
enum class CardFormat { LANDSCAPE, POSTER }

/** Appearance is a non-secret device preference and survives app updates. */
class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)

    // The phone layouts are built for Flix only.
    fun selected(): ThemeSpec = if (DeviceLayout.phone) ThemeSpec.FLIX else ThemeSpec.fromId(preferences.getString(KEY, null))

    fun select(theme: ThemeSpec) {
        preferences.edit().putString(KEY, theme.id).apply()
    }

    private companion object { const val KEY = "theme" }
}
