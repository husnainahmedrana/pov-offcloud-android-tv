package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Home shelves from TMDB with the viewer's own API key (v3 key or v4 read-access token). Call on a worker.
 *
 * TMDB lists carry TMDB ids only, while sources, tracking and artwork all key on IMDb ids, so each title's
 * IMDb id is looked up once and remembered on the device. Titles TMDB cannot map to IMDb are left out.
 */
class TmdbCatalog internal constructor(
    private val key: String,
    private val region: String,
    private val ids: IdCache,
    private val get: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, "GET", headers) },
) {
    constructor(context: Context, key: String, region: String) : this(key, region, PreferenceIdCache(context))

    interface IdCache {
        fun get(key: String): String?
        fun put(values: Map<String, String>)
    }

    private data class Shelf(val title: String, val type: String, val path: String)

    fun homeShelves(): List<CatalogApi.HomeShelf> {
        val shelves = listOf(
            Shelf("Trending Movies Today", "movie", "/trending/movie/day"),
            Shelf("Popular Movies", "movie", "/movie/popular"),
            Shelf("Now Playing in Cinemas", "movie", "/movie/now_playing?region=$region"),
            Shelf("Trending Shows Today", "series", "/trending/tv/day"),
            Shelf("Popular Shows", "series", "/tv/popular"),
        )
        val pool = Executors.newFixedThreadPool(6)
        try {
            val lists = shelves.map { shelf -> pool.submit(Callable { runCatching { results(shelf) } }) }.map { it.get() }
            // Resolve every unknown IMDb id across all shelves in one parallel pass.
            val missing = lists.flatMap { it.getOrDefault(emptyList()) }.map { it.first }.distinct().filter { ids.get(it) == null }
            val found = missing.map { ref -> pool.submit(Callable { ref to runCatching { imdbId(ref) }.getOrNull() }) }
                .mapNotNull { task -> task.get().let { (ref, imdb) -> imdb?.let { ref to it } } }.toMap()
            ids.put(found)
            return shelves.mapIndexed { index, shelf ->
                val result = lists[index]
                val items = result.getOrDefault(emptyList()).mapNotNull { (ref, item) ->
                    ids.get(ref)?.takeIf { it.startsWith("tt") }?.let { item.copy(imdbId = it) }
                }
                CatalogApi.HomeShelf(shelf.title, shelf.type, items, result.isFailure)
            }
        } finally { pool.shutdownNow() }
    }

    /** Checks the key with the smallest authenticated request. */
    fun validate() { json("/configuration") }

    private fun results(shelf: Shelf): List<Pair<String, MediaItemData>> {
        val list = json(shelf.path).optJSONArray("results") ?: return emptyList()
        val tmdbType = if (shelf.type == "movie") "movie" else "tv"
        return (0 until minOf(list.length(), 20)).mapNotNull { index ->
            val entry = list.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val title = entry.optString(if (shelf.type == "movie") "title" else "name").ifBlank { return@mapNotNull null }
            val genres = entry.optJSONArray("genre_ids")?.let { array -> (0 until array.length()).mapNotNull { GENRES[array.optInt(it)] } }.orEmpty()
            val rating = entry.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
            "$tmdbType:$id" to MediaItemData(
                imdbId = "", type = shelf.type, title = title,
                year = entry.optString(if (shelf.type == "movie") "release_date" else "first_air_date").take(4),
                poster = image(entry.optString("poster_path"), "w342"),
                background = image(entry.optString("backdrop_path"), "w1280"),
                description = entry.optString("overview"), genres = genres.distinct().take(3), rating = rating,
            )
        }
    }

    private fun imdbId(ref: String): String {
        val (type, id) = ref.split(':', limit = 2)
        // "-" records a title TMDB has no IMDb id for, so it is not asked about again.
        return json("/$type/$id/external_ids").optString("imdb_id").takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: "-"
    }

    private fun json(path: String): JSONObject {
        val bearer = key.startsWith("eyJ")
        val separator = if ('?' in path) '&' else '?'
        val url = "$BASE$path${separator}language=en-US" + if (bearer) "" else "&api_key=$key"
        val raw = get(url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())
        return try { JSONObject(raw) } catch (_: Exception) { throw IllegalStateException("TMDB returned an unreadable response.") }
    }

    private fun image(path: String, size: String) = if (path.startsWith('/')) "https://image.tmdb.org/t/p/$size$path" else ""

    private class PreferenceIdCache(context: Context) : IdCache {
        private val preferences = context.applicationContext.getSharedPreferences("pov_tmdb_ids", Context.MODE_PRIVATE)
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(values: Map<String, String>) {
            if (values.isNotEmpty()) preferences.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.apply()
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        // TMDB's fixed genre ids for movies and TV.
        private val GENRES = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
            10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
            10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
        )
    }
}
