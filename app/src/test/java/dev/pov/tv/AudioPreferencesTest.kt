package dev.pov.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AudioPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("pov_audio", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `saved audio language enables audio without changing text or video preferences`() {
        val preferences = AudioPreferences(context)
        assertEquals("en", preferences.language)
        preferences.language = "hi"
        val fresh = AudioPreferences(context)
        assertEquals("Hindi", fresh.languageLabel)
        val before = TrackSelectionParameters.Builder(context).setPreferredTextLanguage("ur")
            .setMaxVideoBitrate(1234567).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        val changed = fresh.trackParameters(before)
        assertEquals(listOf("hi", "en"), changed.preferredAudioLanguages)
        assertFalse(C.TRACK_TYPE_AUDIO in changed.disabledTrackTypes)
        assertTrue(C.TRACK_TYPE_TEXT in changed.disabledTrackTypes)
        assertEquals(before.preferredTextLanguages, changed.preferredTextLanguages)
        assertEquals(before.maxVideoBitrate, changed.maxVideoBitrate)
    }

    @Test fun `audio preference and automatic clear only audio overrides leaving manual subtitles intact`() {
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("de").build())
        val text = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("es").build())
        val before = TrackSelectionParameters.Builder(context)
            .setOverrideForType(TrackSelectionOverride(audio, 0)).setOverrideForType(TrackSelectionOverride(text, 0)).build()
        val preferences = AudioPreferences(context).apply { language = "fr" }
        val changed = preferences.trackParameters(before)
        assertEquals(setOf(text), changed.overrides.keys)
        assertEquals(listOf("fr", "en"), changed.preferredAudioLanguages)
        preferences.englishFallback = false
        assertEquals(listOf("fr"), preferences.trackParameters(before).preferredAudioLanguages)
        preferences.englishFallback = true
        preferences.language = ""
        val automatic = preferences.trackParameters(changed)
        assertTrue(automatic.preferredAudioLanguages.isEmpty())
        assertEquals(setOf(text), automatic.overrides.keys)
    }
}
