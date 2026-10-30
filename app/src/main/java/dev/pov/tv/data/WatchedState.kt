package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the linked account has watched, for the ✓ badges: movies and fully watched shows by IMDb id, and
 * episodes by show IMDb id, season and number.
 */
data class WatchedState(val movies: Set<String> = emptySet(), val shows: Set<String> = emptySet(), val episodes: Set<String> = emptySet()) {
    fun movie(imdb: String) = imdb in movies
    fun show(imdb: String) = imdb in shows
    fun episode(imdb: String, season: Int, number: Int) = key(imdb, season, number) in episodes
    fun with(identity: PlaybackIdentity): WatchedState =
        if (identity.type == "movie") copy(movies = movies + identity.imdbId)
        else copy(episodes = episodes + key(identity.imdbId, identity.season, identity.episode))

    fun toJson(): JSONObject = JSONObject().put("movies", JSONArray(movies.toList())).put("shows", JSONArray(shows.toList()))
        .put("episodes", JSONArray(episodes.toList()))

    companion object {
        val EMPTY = WatchedState()
        fun key(imdb: String, season: Int, number: Int) = "$imdb:$season:$number"
        fun fromJson(json: JSONObject): WatchedState {
            fun set(name: String) = json.optJSONArray(name)?.let { array -> (0 until array.length()).map(array::optString).filter(String::isNotBlank).toSet() }.orEmpty()
            return WatchedState(set("movies"), set("shows"), set("episodes"))
        }
    }
}

/**
 * The last watched history, saved per service and account so badges show at once on launch. It is downloaded
 * again only when the service's change stamp moves (both services ask apps not to refetch whole libraries).
 */
class WatchedCache(private val file: java.io.File) {
    data class Entry(val service: String, val revision: String, val stamp: String, val state: WatchedState)

    fun load(service: String, revision: String): Entry? = try {
        val json = JSONObject(file.readText())
        Entry(json.getString("service"), json.getString("revision"), json.getString("stamp"), WatchedState.fromJson(json.getJSONObject("state")))
            .takeIf { it.service == service && it.revision == revision && revision.isNotBlank() }
    } catch (_: Exception) { null }

    fun save(entry: Entry) = runCatching {
        val temp = java.io.File(file.parentFile, file.name + ".tmp")
        temp.writeText(JSONObject().put("service", entry.service).put("revision", entry.revision).put("stamp", entry.stamp)
            .put("state", entry.state.toJson()).toString())
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
    }

    fun clear() { file.delete() }
}
