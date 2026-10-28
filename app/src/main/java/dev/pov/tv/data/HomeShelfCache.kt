package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last Home rows, so Home can open at once and refresh behind the scenes. Only public catalog data
 * (titles, years, artwork addresses, descriptions) is kept; nothing from accounts. Rows older than
 * [MAX_AGE_MS], or saved under different catalog settings, are not used.
 */
internal class HomeShelfCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    fun load(revision: String): List<CatalogApi.HomeShelf>? = try {
        val root = JSONObject(file.readText())
        val age = now() - root.getLong("savedAt")
        if (root.optString("revision") != revision || age !in 0 until MAX_AGE_MS) null
        else root.getJSONArray("shelves").let { shelves ->
            (0 until shelves.length()).map { index ->
                val shelf = shelves.getJSONObject(index)
                val items = shelf.getJSONArray("items")
                CatalogApi.HomeShelf(shelf.getString("title"), shelf.getString("type"),
                    (0 until items.length()).map { item(items.getJSONObject(it)) })
            }
        }.takeIf { shelves -> shelves.any { it.items.isNotEmpty() } }
    } catch (_: Exception) { null }

    /** Unavailable catalogs are never saved: a row that failed once should be retried, not remembered empty. */
    fun save(revision: String, shelves: List<CatalogApi.HomeShelf>) {
        if (shelves.any { it.unavailable } || shelves.none { it.items.isNotEmpty() }) return
        val array = JSONArray()
        shelves.forEach { shelf ->
            array.put(JSONObject().put("title", shelf.title).put("type", shelf.type)
                .put("items", JSONArray().apply { shelf.items.forEach { put(json(it)) } }))
        }
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(JSONObject().put("revision", revision).put("savedAt", now()).put("shelves", array).toString())
            temp.renameTo(file)
        }
    }

    companion object {
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /** Same titles in the same order: a refresh that changes nothing visible is not redrawn. */
        fun sameRows(a: List<CatalogApi.HomeShelf>, b: List<CatalogApi.HomeShelf>) =
            a.map { shelf -> shelf.title to shelf.items.map { it.type + it.imdbId } } == b.map { shelf -> shelf.title to shelf.items.map { it.type + it.imdbId } }

        private fun json(item: MediaItemData) = JSONObject().put("imdbId", item.imdbId).put("type", item.type).put("title", item.title)
            .put("year", item.year).put("poster", item.poster).put("background", item.background).put("description", item.description)
            .put("genres", JSONArray(item.genres)).put("runtime", item.runtime).put("rating", item.rating)

        private fun item(json: JSONObject) = MediaItemData(json.getString("imdbId"), json.getString("type"), json.getString("title"),
            json.optString("year"), json.optString("poster"), json.optString("background"), json.optString("description"),
            json.optJSONArray("genres")?.let { genres -> (0 until genres.length()).map(genres::getString) }.orEmpty(),
            json.optString("runtime"), json.optString("rating"))
    }
}
