package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SubtitlePreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `new installs default to bold transparent subtitles and options persist independently`() {
        val first = SubtitlePreferences(context)
        assertTrue(first.transparentBackground); assertTrue(first.boldText)
        first.transparentBackground = false
        val second = SubtitlePreferences(context)
        assertTrue(second.boldText); assertFalse(second.transparentBackground)
        second.transparentBackground = true
        second.boldText = false
        val third = SubtitlePreferences(context)
        assertTrue(third.transparentBackground); assertFalse(third.boldText)
    }

    @Test fun `transparent bold captions remove both boxes while retaining foreground and readable edge`() {
        val preferences = SubtitlePreferences(context).apply { transparentBackground = true; boldText = true }
        val base = CaptionStyleCompat(Color.YELLOW, Color.BLACK, Color.BLUE,
            CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.SERIF)
        val style = preferences.style(base)
        assertEquals(Color.TRANSPARENT, style.backgroundColor)
        assertEquals(Color.TRANSPARENT, style.windowColor)
        assertEquals(Color.YELLOW, style.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
        assertTrue(style.typeface!!.isBold)
    }

    @Test fun `bold only preserves user caption colors and edge preferences`() {
        val preferences = SubtitlePreferences(context).apply { boldText = true; transparentBackground = false }
        val base = CaptionStyleCompat(Color.YELLOW, Color.BLUE, Color.RED,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.GREEN, Typeface.SERIF)
        val style = preferences.style(base)
        assertEquals(base.backgroundColor, style.backgroundColor)
        assertEquals(base.windowColor, style.windowColor)
        assertEquals(base.edgeType, style.edgeType)
        assertEquals(base.edgeColor, style.edgeColor)
        assertTrue(style.typeface!!.isBold)
    }
    @Test fun `medium preserves original size and selected size survives recreation`() {
        val first = SubtitlePreferences(context)
        assertEquals(100, first.sizePercent)
        first.sizePercent = 130
        assertEquals(130, SubtitlePreferences(context).sizePercent)
        first.sizePercent = 80
        assertEquals("Small", SubtitlePreferences(context).sizeLabel)
    }
    @Test fun `subtitle position overrides text regions and default restores the authored cue`() {
        val preferences = SubtitlePreferences(context)
        val text = androidx.media3.common.text.Cue.Builder().setText("Position check")
            .setLine(0.7f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()
        val image = androidx.media3.common.text.Cue.Builder()
            .setBitmap(android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)).build()
        // New installs sit 3% above the bottom; -1 keeps the authored position.
        assertEquals(3, preferences.positionPercent)
        assertEquals(0.97f, preferences.positionedCues(listOf(text)).single().line, 0.001f)
        preferences.positionPercent = 2
        val lower = SubtitlePreferences(context).positionedCues(listOf(text, image))
        assertEquals(0.98f, lower.first().line, 0.001f)
        assertEquals(androidx.media3.common.text.Cue.ANCHOR_TYPE_END, lower.first().lineAnchor)
        assertSame(image, lower.last())
        preferences.positionPercent = 16
        assertEquals(0.84f, preferences.positionedCues(listOf(text)).single().line, 0.001f)
        preferences.positionPercent = -1
        assertSame(text, preferences.positionedCues(listOf(text)).single())
    }

    @Test fun `preferred language persists enables subtitles and preserves audio video settings`() {
        val first = SubtitlePreferences(context)
        assertEquals("en", first.language)
        first.language = "ur"
        val preferences = SubtitlePreferences(context)
        assertEquals("ur", preferences.language)
        assertEquals("Urdu", preferences.languageLabel)
        val before = androidx.media3.common.TrackSelectionParameters.Builder(context)
            .setPreferredAudioLanguage("de").setMaxVideoBitrate(1234567)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        val changed = preferences.trackParameters(before)
        assertEquals(listOf("ur", "en"), changed.preferredTextLanguages)
        assertFalse(androidx.media3.common.C.TRACK_TYPE_TEXT in changed.disabledTrackTypes)
        assertEquals(before.preferredAudioLanguages, changed.preferredAudioLanguages)
        assertEquals(before.maxVideoBitrate, changed.maxVideoBitrate)
    }
    @Test fun `off automatic and explicit language replace only text overrides`() {
        val text = androidx.media3.common.TrackGroup(androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.TEXT_VTT).setLanguage("es").build())
        val audio = androidx.media3.common.TrackGroup(androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC).setLanguage("de").build())
        val before = androidx.media3.common.TrackSelectionParameters.Builder(context)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(text, 0))
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(audio, 0)).build()
        val preferences = SubtitlePreferences(context)
        preferences.language = "off"
        val off = preferences.trackParameters(before)
        assertTrue(androidx.media3.common.C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertEquals(setOf(audio), off.overrides.keys)
        preferences.language = "en"
        val english = preferences.trackParameters(off)
        assertEquals(listOf("en"), english.preferredTextLanguages)
        assertFalse(androidx.media3.common.C.TRACK_TYPE_TEXT in english.disabledTrackTypes)
        preferences.language = ""
        val automatic = preferences.trackParameters(english)
        assertEquals(androidx.media3.common.TrackSelectionParameters.Builder(context).build().preferredTextLanguages,
            automatic.preferredTextLanguages)
        assertEquals(setOf(audio), automatic.overrides.keys)
    }
}
