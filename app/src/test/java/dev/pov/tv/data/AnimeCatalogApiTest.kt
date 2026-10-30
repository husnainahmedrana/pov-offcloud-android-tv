package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnimeCatalogApiTest {
    private fun api(
        fetch: (String) -> JSONObject = { error("Unexpected request") },
        lookup: (String, String, String) -> MediaItemData? = { _, _, _ -> null },
        clock: () -> Long = { 0L }
    ) = AnimeCatalogApi(fetch, lookup, clock)

    private fun anime(id: String = "42", subtype: String = "TV", attributes: JSONObject = JSONObject()): JSONObject = JSONObject()
        .put("type", "anime").put("id", id).put("attributes", JSONObject()
            .put("canonicalTitle", "Example Anime").put("subtype", subtype).put("startDate", "2023-04-01")
            .also { target -> attributes.keys().forEach { target.put(it, attributes.get(it)) } })

    private fun page(vararg records: JSONObject): JSONObject = JSONObject().put("data", JSONArray(records.toList()))
    private fun reference(type: String, id: String) = JSONObject().put("type", type).put("id", id)
    private fun mapping(id: String, site: String, externalId: String) = reference("mappings", id)
        .put("attributes", JSONObject().put("externalSite", site).put("externalId", externalId))

    private fun withMappings(record: JSONObject, vararg records: JSONObject): JSONObject {
        record.put("relationships", JSONObject().put("mappings", JSONObject().put("data", JSONArray(records.map {
            reference("mappings", it.getString("id"))
        }))))
        return page(record).put("included", JSONArray(records.toList()))
    }

    @Test fun `public discovery uses real Kitsu identity and secure artwork with English title preference`() {
        val record = anime(attributes = JSONObject().put("titles", JSONObject().put("en", "English Title"))
            .put("posterImage", JSONObject().put("large", "http://unsafe.test/poster.jpg")
                .put("medium", "https://media.kitsu.app/poster.jpg"))
            .put("coverImage", JSONObject().put("large", "https://user:password@unsafe.test/backdrop.jpg")))
        val result = api().parsePage(page(record)).items.single()
        assertEquals("42", result.kitsuId)
        assertEquals("English Title", result.title)
        assertEquals("series", result.type)
        assertEquals("2023", result.year)
        assertEquals("https://media.kitsu.app/poster.jpg", result.poster)
        assertEquals("", result.background)
        assertEquals(listOf("Anime"), result.genres)
        assertTrue(result.mappingNotice.contains("verified episodes"))
    }

    @Test fun `explicit adult records unknown formats manga and malformed IDs are omitted`() {
        val response = page(
            anime("1", attributes = JSONObject().put("nsfw", true)),
            anime("2", attributes = JSONObject().put("ageRating", "R18")),
            anime("3", "music"), anime("4").put("type", "manga"), anime("../bad"), anime("5", "movie")
        )
        assertEquals(listOf("5"), api().parsePage(response).items.map { it.kitsuId })
        assertEquals("movie", api().parsePage(response).items.single().type)
    }

    @Test fun `pagination advances by raw records even when records are filtered`() {
        val response = page(anime("1", attributes = JSONObject().put("nsfw", true)), anime("2"), anime("3", "movie"))
            .put("links", JSONObject().put("next", "https://kitsu.app/api/edge/anime?page[offset]=23"))
        val result = api().parsePage(response, 20, "series")
        assertEquals(listOf("2"), result.items.map { it.kitsuId })
        assertEquals(23, result.nextOffset)
        assertTrue(result.hasMore)
        assertFalse(api().parsePage(page(), 23).hasMore)
    }

    @Test fun `Kitsu seasons only carry explicit show IDs and never assign playback episodes`() {
        val response = withMappings(anime(), mapping("a", "trakt", "1420"),
            mapping("b", "thetvdb", "267440/4"), mapping("c", "thetvdb/season", "991122"))
        val title = api().parsePage(response).items.single()
        assertEquals("1420", title.traktId)
        assertEquals("267440", title.tvdbId)
        assertFalse(title.tvdbId.contains("991122"))
    }

    @Test fun `ambiguous direct mappings and conflicting TVDB show mappings are ignored`() {
        val response = withMappings(anime(), mapping("a", "trakt", "11"), mapping("b", "trakt", "12"),
            mapping("c", "thetvdb/series", "21"), mapping("d", "thetvdb", "22/1"))
        val title = api().parsePage(response).items.single()
        assertEquals("", title.traktId)
        assertEquals("", title.tvdbId)
    }

    @Test fun `mapping resources cannot be borrowed from another anime without a relationship`() {
        val response = withMappings(anime(), mapping("a", "trakt", "11"))
        response.getJSONArray("data").put(anime("43"))
        val titles = api().parsePage(response).items
        assertEquals("11", titles.first().traktId)
        assertEquals("", titles.last().traktId)
        response.getJSONArray("included").put(mapping("a", "trakt", "22"))
        assertThrows(ApiException::class.java) { api().parsePage(response) }
    }

    @Test fun `catalog requests encode search terms and page safely without mass fetching`() {
        val calls = mutableListOf<String>()
        val catalog = api(fetch = { calls += it; page(anime()) })
        catalog.search("Frieren & action/romance?", "series", 20)
        assertEquals(1, calls.size)
        val address = calls.single()
        assertTrue(address.startsWith("https://kitsu.app/api/edge/anime?"))
        assertTrue(address.contains("page%5Blimit%5D=20"))
        assertTrue(address.contains("page%5Boffset%5D=20"))
        assertTrue(address.contains("filter%5Btext%5D=Frieren%20%26%20action%2Fromance%3F"))
        assertTrue(address.contains("filter%5Bsubtype%5D=TV%2CONA"))
        assertFalse(address.contains("sort="))
        assertEquals(0, catalog.search("  ").items.size)
        assertEquals(1, calls.size)
        assertThrows(IllegalArgumentException::class.java) { catalog.popular("manga") }
        assertThrows(IllegalArgumentException::class.java) { catalog.popular(skip = -1) }
    }

    @Test fun `discovery page cache expires without accumulating an offline anime database`() {
        var count = 0
        var now = 0L
        val catalog = api(fetch = { count++; page(anime()) }, clock = { now })
        catalog.popular("movie"); catalog.popular("movie")
        assertEquals(1, count)
        now = 600_001L
        catalog.popular("movie")
        assertEquals(2, count)
    }

    @Test fun `rate limited catalog respects retry delay rather than repeating requests`() {
        var count = 0
        var now = 0L
        val catalog = api(fetch = { count++; throw ApiException(429, "Do not display server body", retryAfterSeconds = 60) }, clock = { now })
        val first = assertThrows(ApiException::class.java) { catalog.popular() }
        assertEquals(60, first.retryAfterSeconds)
        assertFalse(first.message.orEmpty().contains("server body"))
        assertThrows(ApiException::class.java) { catalog.popular() }
        assertEquals(1, count)
        now = 60_001L
        assertThrows(ApiException::class.java) { catalog.popular() }
        assertEquals(2, count)
    }

    @Test fun `same-ID Trakt match returns canonical show stub and bypasses community mapping`() {
        var lookups = 0
        val catalog = api(lookup = { site, id, type ->
            assertEquals("trakt", site); assertEquals("1420", id); assertEquals("series", type)
            lookups++
            MediaItemData("tt2560140", "series", "Canonical Whole Show")
        })
        val title = AnimeTitle("42", "Season Four", "series", traktId = "1420")
        val result = catalog.resolve(title)
        assertEquals("Canonical Whole Show", result.title)
        assertEquals("tt2560140", result.imdbId)
        assertTrue(result.episodes.isEmpty())
        catalog.resolve(title)
        assertEquals(1, lookups)
    }

    private fun mappedMeta(title: AnimeTitle, imdb: String = "tt1234567"): JSONObject = JSONObject().put("meta", JSONObject()
        .put("id", "kitsu:${title.kitsuId}").put("kitsu_id", title.kitsuId).put("type", title.type).put("imdb_id", imdb))

    @Test fun `movie can use explicit same-Kitsu IMDb mapping without a Trakt account`() {
        val title = AnimeTitle("11614", "Your Name.", "movie", "2016")
        val catalog = api(fetch = { address ->
            assertEquals("https://anime-kitsu.strem.fun/meta/movie/kitsu:11614.json", address)
            mappedMeta(title, "tt5311514")
        })
        val result = catalog.resolve(title)
        assertEquals("tt5311514", result.imdbId)
        assertEquals("movie", result.type)
    }

    @Test fun `fallback metadata must match both requested Kitsu identity and media type`() {
        val title = AnimeTitle("42", "Example", "series")
        for ((key, value) in listOf("id" to "kitsu:43", "kitsu_id" to "43", "type" to "movie", "imdb_id" to "nm1234567")) {
            val meta = mappedMeta(title)
            meta.getJSONObject("meta").put(key, value)
            assertThrows(ApiException::class.java) { api().parseImdbMapping(meta, title) }
        }
    }

    @Test fun `series video mappings supply root IMDb only and never guessed season episode numbers`() {
        val title = AnimeTitle("42", "A second cour", "series")
        val response = mappedMeta(title).also { it.getJSONObject("meta").remove("imdb_id") }
        response.getJSONObject("meta").put("videos", JSONArray().put(JSONObject()
            .put("id", "kitsu:42:1").put("imdb_id", "tt1234567").put("imdbSeason", 8).put("imdbEpisode", 14)))
        val result = api().parseImdbMapping(response, title)
        assertEquals("tt1234567", result.imdbId)
        assertTrue(result.episodes.isEmpty())
    }

    @Test fun `mixed IMDb anthology identity and foreign video identities require user selection`() {
        val title = AnimeTitle("42", "Anthology", "series")
        val response = mappedMeta(title)
        val videos = JSONArray().put(JSONObject().put("id", "kitsu:42:1").put("imdb_id", "tt7654321"))
        response.getJSONObject("meta").put("videos", videos)
        val error = assertThrows(ApiException::class.java) { api().parseImdbMapping(response, title) }
        assertTrue(error.message.orEmpty().contains("unambiguous IMDb"))
        videos.getJSONObject(0).put("id", "kitsu:43:1").put("imdb_id", "tt1234567")
        assertThrows(ApiException::class.java) { api().parseImdbMapping(response, title) }
    }

    @Test fun `Trakt rejection can use public mapping but rate limiting remains visible`() {
        val title = AnimeTitle("42", "Example", "series", traktId = "11")
        val fallback = api(fetch = { mappedMeta(title) }, lookup = { _, _, _ -> throw ApiException(401, "No client") })
        assertEquals("tt1234567", fallback.resolve(title).imdbId)
        val busy = api(lookup = { _, _, _ -> throw ApiException(429, "Wait") })
        assertEquals(429, assertThrows(ApiException::class.java) { busy.resolve(title) }.code)
    }

    @Test
    fun synopsisDropsKitsuCreditNotes() {
        assertEquals("A hero rises.\n\nThe end.",
            AnimeCatalogApi.kitsuSynopsis("A hero rises.\n\n\n\nThe end. (Source: Crunchyroll)\n\n[Written by MAL Rewrite]"))
        assertEquals("Keeps (sources of power) in the story.", AnimeCatalogApi.kitsuSynopsis("Keeps (sources of power) in the story."))
    }
}
