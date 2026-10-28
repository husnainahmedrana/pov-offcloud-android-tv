package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TmdbCatalogTest {
    private class MemoryIds : TmdbCatalog.IdCache {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(values: Map<String, String>) { this.values += values }
    }

    private fun page(type: String, id: Int) =
        """{"results":[{"id":$id,"${if (type == "movie") "title" else "name"}":"Title $id","${if (type == "movie") "release_date" else "first_air_date"}":"2026-01-02",
        "poster_path":"/p$id.jpg","backdrop_path":"/b$id.jpg","overview":"About $id","vote_average":7.46,"genre_ids":[28,18]}]}"""

    private fun catalog(key: String, ids: MemoryIds, requests: MutableList<Pair<String, Map<String, String>>>) =
        TmdbCatalog(key, "PK", ids) { url, headers ->
            synchronized(requests) { requests += url to headers }
            when {
                "/external_ids" in url -> if ("/tv/" in url) """{"imdb_id":null}""" else """{"imdb_id":"tt0${url.substringAfter("/movie/").substringBefore('/')}000"}"""
                "/movie/" in url || "/trending/movie" in url -> page("movie", 11)
                else -> page("tv", 22)
            }
        }

    @Test fun `shelves keep the chosen order and map TMDB titles to IMDb ids`() {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val shelves = catalog("abc123", MemoryIds(), requests).homeShelves()
        assertEquals(listOf("Trending Movies Today", "Popular Movies", "Now Playing in Cinemas", "Trending Shows Today", "Popular Shows"), shelves.map { it.title })
        val movie = shelves[1].items.single()
        assertEquals("tt011000", movie.imdbId)
        assertEquals("2026", movie.year)
        assertEquals("7.5", movie.rating)
        assertEquals(listOf("Action", "Drama"), movie.genres)
        assertEquals("https://image.tmdb.org/t/p/w1280/b11.jpg", movie.background)
        // Shows without an IMDb id are left out rather than shown unplayable.
        assertTrue(shelves[4].items.isEmpty())
        assertTrue(requests.any { (url, _) -> "/movie/now_playing?region=PK&language=en-US&api_key=abc123" in url })
    }

    @Test fun `ids are looked up once and read access tokens use a bearer header`() {
        val ids = MemoryIds()
        val first = mutableListOf<Pair<String, Map<String, String>>>()
        catalog("eyJtoken", ids, first).homeShelves()
        assertTrue(first.all { (url, headers) -> "api_key" !in url && headers["Authorization"] == "Bearer eyJtoken" })
        val second = mutableListOf<Pair<String, Map<String, String>>>()
        catalog("eyJtoken", ids, second).homeShelves()
        assertTrue("cached ids must not be fetched again", second.none { "/external_ids" in it.first })
    }
}
