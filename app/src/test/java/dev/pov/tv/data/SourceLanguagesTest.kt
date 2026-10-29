package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguagesTest {
    private fun source(title: String, height: Int = 1080, detail: String = "") = SourceItem(title, "fixture", detail = detail, qualityHeight = height)

    @Test fun `release names, native names and flags are read as audio languages`() {
        assertEquals(setOf("pt"), SourceLanguages.detect("Movie.2024.1080p.WEB-DL.DUAL.PT-BR.mkv").audio)
        assertEquals(setOf("pt", "en"), SourceLanguages.detect("Movie 2024 🇧🇷 / 🇬🇧").audio.toSet())
        assertEquals(setOf("es"), SourceLanguages.detect("Movie.2024.LATINO.1080p").audio)
        assertEquals(setOf("pt"), SourceLanguages.detect("Filme Português 1080p").audio)
        assertTrue(SourceLanguages.detect("Movie.2024.MULTi.1080p").multiAudio)
        assertFalse(SourceLanguages.detect("Movie.2024.Multi-Subs.1080p").multiAudio)
        // Two-letter words never count on their own.
        assertTrue(SourceLanguages.detect("It Ends With Us 2024 1080p").audio.isEmpty())
    }

    @Test fun `subtitle mentions are kept apart from audio`() {
        val found = SourceLanguages.detect("Movie.2024.1080p.Legendado.mkv · Subs: Portuguese")
        assertTrue("pt" in found.subtitles); assertFalse("pt" in found.audio)
        val french = SourceLanguages.detect("Film.2024.VOSTFR.1080p")
        assertTrue("fr" in french.subtitles); assertFalse("fr" in french.audio)
        assertEquals(setOf("en"), SourceLanguages.detect("Movie 2024 English subs").subtitles)
    }

    @Test fun `preferred audio leads, then multi audio, untagged and other languages, quality sorts inside`() {
        val items = listOf(source("Movie.2160p.ITALIAN.mkv", 2160), source("Movie.2160p.mkv", 2160), source("Movie.720p.PORTUGUESE.mkv", 720),
            source("Movie.1080p.MULTI.mkv"), source("Movie.1080p.Dublado.mkv"), source("Movie.480p.ENG.mkv", 480), source("Movie.360p.English.mkv", 360))
        val ordered = SourceLanguageOrder("pt", "").sorted(items, SourceSortOrder()).map { it.title }
        assertEquals(listOf("Movie.1080p.Dublado.mkv", "Movie.720p.PORTUGUESE.mkv", "Movie.1080p.MULTI.mkv", "Movie.2160p.mkv", "Movie.2160p.ITALIAN.mkv", "Movie.480p.ENG.mkv", "Movie.360p.English.mkv"), ordered)
    }

    @Test fun `english treats untagged links as english and subtitles break ties`() {
        val items = listOf(source("Movie.1080p.HINDI.mkv"), source("Movie.1080p.mkv"), source("Movie.1080p.ESub.mkv", detail = "English subs"))
        assertEquals(listOf("Movie.1080p.ESub.mkv", "Movie.1080p.mkv", "Movie.1080p.HINDI.mkv"),
            SourceLanguageOrder("en", "en").sorted(items, SourceSortOrder()).map { it.title })
        assertFalse(SourceLanguageOrder("en", "en").audioMatch(items[1]))
    }

    @Test fun `automatic languages keep the plain quality order`() {
        val items = listOf(source("Movie.720p.PORTUGUESE.mkv", 720), source("Movie.2160p.mkv", 2160))
        assertFalse(SourceLanguageOrder("", "off").active)
        assertEquals(SourceSortOrder().sorted(items), SourceLanguageOrder("", "off").sorted(items, SourceSortOrder()))
    }

    @Test fun `meteor audio and subtitle flag lines are kept apart`() {
        val found = SourceLanguages.detect("Jawan (2023) (1080p BluRay x265 SAMPA).mkv · 📺 1080p | bluray | h265 · 🔊 eac3 5.1 · 🌐 🇮🇳 · 💬 🇬🇧 🇮🇳 🇸🇦 🇳🇱")
        assertEquals(setOf("hi"), found.audio)
        assertEquals(setOf("en", "hi", "ar", "nl"), found.subtitles)
        val subsOnly = SourceLanguages.detect("Oppenheimer.2023.2160p.REMUX.mkv · 🌐 🇬🇧 · 💬 🇬🇧 🇮🇳 🇵🇱")
        assertEquals(setOf("en"), subsOnly.audio)
    }

    @Test fun `untagged links count as the title's original language`() {
        val items = listOf(source("Jawan.2023.2160p.WEBRip.mp4", 2160), source("Jawan.2023.1080p.mkv", detail = "🌐 🇷🇺 🇮🇳"),
            source("Jawan.2023.1080p.Hindi.mkv"), source("Jawan.2023.720p.mkv", 720, "🌐 🇮🇳"), source("Jawan.2023.720p.RUS.mkv", 720))
        val order = SourceLanguageOrder("hi", "")
        assertEquals("hi", order.originalLanguage(items))
        assertEquals(listOf("Jawan.2023.1080p.mkv", "Jawan.2023.1080p.Hindi.mkv", "Jawan.2023.720p.mkv", "Jawan.2023.2160p.WEBRip.mp4", "Jawan.2023.720p.RUS.mkv"),
            order.sorted(items, SourceSortOrder()).map { it.title })
    }

    @Test fun `a long result list sorts quickly`() {
        val items = (0 until 300).map { source("Movie.2023.${listOf(2160, 1080, 720)[it % 3]}p.WEB-DL.DUAL.DDP5.1.H.265-Group$it.mkv", detail =
            "[P2P] Meteor · 📺 1080p | webdl | h265 · 🔊 eac3 5.1 · 🌐 🇬🇧 🇮🇳 · 💬 🇬🇧 🇪🇸 🇫🇷 🇩🇪 🇮🇹 🇵🇱 · 💾 3.2 GiB   👥 12 seeders · 🔗 corpus,bitsearch") }
        SourceLanguageOrder("hi", "en").sorted(items.take(5), SourceSortOrder())
        val started = System.nanoTime()
        val order = SourceLanguageOrder("hi", "en")
        order.sorted(items, SourceSortOrder()).forEach { order.audioMatch(it); order.multiAudio(it); order.subtitleMatch(it) }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("300 links sorted with badges in $millis ms")
        assertTrue("took $millis ms", millis < 3000)
    }

    @Test fun `english fallback lists english links before other languages`() {
        val items = listOf(source("Movie.1080p.ITALIAN.mkv"), source("Movie.1080p.ENG.mkv"), source("Movie.1080p.HINDI.mkv"), source("Movie.720p.ENG.mkv", 720))
        assertEquals(listOf("Movie.1080p.HINDI.mkv", "Movie.1080p.ENG.mkv", "Movie.720p.ENG.mkv", "Movie.1080p.ITALIAN.mkv"),
            SourceLanguageOrder("hi", "", "en").sorted(items, SourceSortOrder()).map { it.title })
        assertEquals("Movie.1080p.ITALIAN.mkv", SourceLanguageOrder("hi", "").sorted(items, SourceSortOrder())[1].title)
    }
}
