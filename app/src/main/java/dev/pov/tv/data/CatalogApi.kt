package dev.pov.tv.data

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Public Cinemeta metadata, with no embedded third-party account credentials. Call on a worker. */
class CatalogApi internal constructor(private val publicJson: (String) -> JSONObject) {
    constructor() : this(Net::publicJson)
    data class SearchResults(val items: List<MediaItemData>, val unavailableTypes: List<String>)

    /** Search both catalogs concurrently and retain useful results if one catalog is unavailable. */
    fun searchAll(query: String): SearchResults {
        if (query.isBlank()) return SearchResults(emptyList(), emptyList())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val types = listOf("movie", "series")
            val futures = types.map { type -> executor.submit(Callable { runCatching { search(query, type) } }) }
            val results = futures.map { it.get() }
            if (results.all { it.isFailure }) throw IllegalStateException("Search is temporarily unavailable. Please try again.")
            val lists = results.map { it.getOrDefault(emptyList()) }
            val merged = (0 until lists.maxOf { it.size }).flatMap { index -> lists.mapNotNull { it.getOrNull(index) } }
            return SearchResults(merged.distinctBy { it.type to it.imdbId }, types.filterIndexed { index, _ -> results[index].isFailure })
        } finally {
            executor.shutdownNow()
        }
    }
    fun popular(type: String, skip: Int = 0): List<MediaItemData> {
        requireType(type)
        require(skip >= 0) { "The catalog offset cannot be negative." }
        return catalog("$BASE/catalog/$type/top/skip=$skip.json", type)
    }

    data class HomeShelf(val title: String, val type: String, val items: List<MediaItemData>, val unavailable: Boolean = false)
    fun newReleases(type: String, year: Int = java.time.Year.now(java.time.ZoneOffset.UTC).value): List<MediaItemData> {
        requireType(type); require(year in 1900..2100)
        return catalog("$BASE/catalog/$type/year/genre=$year.json", type).filter { it.year.take(4) == year.toString() }
    }
    fun featured(type: String): List<MediaItemData> {
        requireType(type)
        return catalog("$BASE/catalog/$type/imdbRating.json", type)
    }
    /** Keep stable shelf order and useful catalogs even when one public endpoint fails. */
    fun homeShelves(): List<HomeShelf> {
        val definitions = listOf("New Movies" to "movie", "New Shows" to "series", "Featured Movies" to "movie",
            "Featured Shows" to "series", "Popular Movies" to "movie", "Popular Shows" to "series")
        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures = definitions.mapIndexed { index, (title, type) -> executor.submit(Callable {
                val result = runCatching { when (index) { 0, 1 -> newReleases(type); 2, 3 -> featured(type); else -> popular(type) } }
                HomeShelf(title, type, result.getOrDefault(emptyList()), result.isFailure)
            }) }
            return futures.map { it.get() }
        } finally { executor.shutdownNow() }
    }

    fun search(query: String, type: String): List<MediaItemData> {
        requireType(type)
        val term = query.trim().take(200)
        if (term.isEmpty()) return emptyList()
        return catalog("$BASE/catalog/$type/top/search=${Net.encode(term)}.json", type)
    }

    fun details(item: MediaItemData): MediaItemData {
        requireType(item.type)
        require(IMDB_ID.matches(item.imdbId)) { "This title has no supported IMDb identifier." }
        val meta = publicJson("$BASE/meta/${item.type}/${item.imdbId}.json")
            .optJSONObject("meta") ?: throw IllegalStateException("Title details are unavailable.")
        check(!meta.has("type") || meta.isNull("type") || meta.optString("type") == item.type) {
            "The catalog returned a different media type. Choose the matching movie or TV show."
        }
        val parsed = parseMeta(meta, item.type) ?: throw IllegalStateException("Title details are unavailable.")
        check(parsed.imdbId == item.imdbId) { "The catalog returned details for a different title. Please try again." }
        return parsed.copy(
            title = parsed.title.ifBlank { item.title },
            year = parsed.year.ifBlank { item.year },
            poster = parsed.poster.ifBlank { item.poster },
            // With TMDB catalogs, keep the card's TMDB backdrop instead of Cinemeta's much larger one.
            background = if (item.background.startsWith("https://image.tmdb.org/")) item.background else parsed.background.ifBlank { item.background },
            description = parsed.description.ifBlank { item.description },
            genres = parsed.genres.ifEmpty { item.genres },
            runtime = parsed.runtime.ifBlank { item.runtime },
            rating = parsed.rating.ifBlank { item.rating }
        )
    }

    private fun catalog(url: String, type: String): List<MediaItemData> {
        val metas = publicJson(url).optJSONArray("metas")
            ?: throw IllegalStateException("The catalog is temporarily unavailable.")
        return (0 until metas.length()).mapNotNull { index ->
            metas.optJSONObject(index)?.let { parseMeta(it, type) }
        }.distinctBy { it.imdbId }
    }

    private fun parseMeta(meta: JSONObject, type: String): MediaItemData? {
        val id = meta.optString("imdb_id").takeIf(IMDB_ID::matches) ?: meta.optString("id")
        if (!IMDB_ID.matches(id)) return null
        val title = meta.optString("name").trim()
        if (title.isBlank()) return null
        val genres = meta.optJSONArray("genres")
        val videos = meta.optJSONArray("videos")
        val episodes = if (type == "series" && videos != null) {
            (0 until videos.length()).mapNotNull { index ->
                val video = videos.optJSONObject(index) ?: return@mapNotNull null
                val season = video.optInt("season", -1)
                val episode = video.optInt("episode", -1)
                if (season < 0 || episode < 1) return@mapNotNull null
                EpisodeData(
                    season = season,
                    episode = episode,
                    title = video.optString("title").ifBlank { video.optString("name") }.ifBlank { "Episode $episode" },
                    released = video.optString("released"),
                    thumbnail = artwork(video.optString("thumbnail")),
                    overview = video.optString("overview").ifBlank { video.optString("description") }.trim().take(600)
                )
            }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
        } else emptyList()
        return MediaItemData(
            imdbId = id,
            type = type,
            title = title,
            year = meta.optString("releaseInfo").ifBlank { meta.optString("year") },
            poster = artwork(meta.optString("poster")),
            background = artwork(meta.optString("background")),
            description = meta.optString("description"),
            genres = if (genres == null) emptyList() else
                (0 until genres.length()).map { genres.optString(it) }.filter { it.isNotBlank() },
            runtime = meta.optString("runtime"),
            rating = meta.optString("imdbRating"),
            episodes = episodes
        )
    }

    private fun requireType(type: String) {
        require(type == "movie" || type == "series") { "Only movies and TV series are supported." }
    }

    private fun artwork(value: String): String = try {
        val uri = URI(value)
        if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) value else ""
    } catch (_: Exception) { "" }

    private companion object {
        const val BASE = "https://v3-cinemeta.strem.io"
        val IMDB_ID = Regex("tt[0-9]{5,12}")
    }
}
