package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simkl over its AUTH V2 device flow with POV Rajput's registered app. The Client ID is public by design;
 * no client secret exists for this app type. Requests carry the user's token and count against that user's
 * own Simkl quota. Access tokens last 7 days and are refreshed with the 180-day refresh token.
 */
class SimklApi internal constructor(
    private val credentials: WeTrakrCredentials,
    private val transport: (SimklRequest) -> String = { send(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) : Tracker {
    constructor(store: SecureStore) : this(object : WeTrakrCredentials {
        override fun get(key: String) = store.get("simkl.$key")
        override fun put(values: Map<String, String>) = store.putAll(values.mapKeys { "simkl.${it.key}" })
    })

    override val name = "Simkl"
    override val supportsLists = false
    @Volatile private var pending: DeviceAuth? = null

    override fun connected() = credentials.get("access_token").isNotBlank()
    override fun accountRevision() = credentials.get("account_revision")
    override fun disconnect() = synchronized(lock) { pending = null; cache.clear(); credentials.put(EMPTY_ACCOUNT) }

    override fun startDeviceAuth(): DeviceAuth {
        val result = parseObject(transport(SimklRequest("POST", url("/oauth2/device"), headers(null),
            form = listOf("client_id" to CLIENT_ID, "scope" to "media:read media:write"))))
        val code = required(result, "user_code"); val device = required(result, "device_code")
        val address = result.optString("verification_uri_complete").ifBlank { required(result, "verification_uri") }
        val seconds = result.optInt("expires_in"); val interval = result.optInt("interval", 5)
        if (!address.startsWith("https://simkl.com/") || seconds !in 1..86400 || interval !in 1..300) malformed()
        return DeviceAuth(device, code, address, interval, seconds).also { pending = it }
    }

    override fun pollDeviceAuth(auth: DeviceAuth): Boolean = synchronized(lock) {
        if (pending?.deviceCode != auth.deviceCode) ended()
        val result = try {
            parseObject(transport(SimklRequest("POST", url("/oauth2/token"), headers(null), form = listOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to CLIENT_ID, "device_code" to auth.deviceCode))))
        } catch (error: ApiException) {
            when (error.errorKey) {
                "authorization_pending", "slow_down" -> return@synchronized false
                "expired_token" -> throw ApiException(410, "This Simkl code expired. Choose Retry to request a new code.")
                else -> throw error
            }
        }
        pending = null; cache.clear()
        saveTokens(result, newAccount = true)
        true
    }

    override fun cancelDeviceAuth(auth: DeviceAuth?) {
        if (auth == null || pending?.deviceCode == auth.deviceCode) pending = null
    }

    override fun scrobblingEnabled() = credentials.get("scrobbling") != "false"
    override fun scrobbleRevision() = credentials.get("scrobble_revision")
    override fun setScrobblingEnabled(enabled: Boolean) =
        credentials.put(mapOf("scrobbling" to enabled.toString(), "scrobble_revision" to UUID.randomUUID().toString()))
    private fun suppressionKey(identity: PlaybackIdentity) = "suppressed.${identity.type}.${identity.imdbId}.${identity.season}.${identity.episode}"
    override fun allowPlayback(identity: PlaybackIdentity) = credentials.put(mapOf(suppressionKey(identity) to ""))

    private fun current(identity: PlaybackIdentity, revision: String, expectedScrobbleRevision: String): Boolean =
        connected() && scrobblingEnabled() && revision.isNotBlank() && accountRevision() == revision &&
            credentials.get(suppressionKey(identity)) != revision && expectedScrobbleRevision == scrobbleRevision()

    override fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String,
                                    expectedScrobbleRevision: String): Boolean {
        if (!current(identity, revision, expectedScrobbleRevision)) return false
        validate(identity); require(percent.isFinite() && action in setOf("start", "pause", "stop"))
        val body = itemBody(identity).put("progress", Math.round(percent.coerceIn(0.0, 100.0) * 100) / 100.0)
        try {
            // At 90% or more a stop marks it watched; below that Simkl keeps a resumable paused session.
            val result = parseObject(request("POST", "/scrobble/$action" + if (action == "stop") "?min_progress=$WATCHED" else "", body))
            val accepted = if (action == "stop") setOf("pause", "scrobble") else setOf(action)
            if (result.optString("action") !in accepted) throw ApiException(0, "Simkl did not accept this playback update.")
        } catch (error: ApiException) {
            // 409: this title was already finished within the last hour. 404: Simkl cannot match it; retrying won't help.
            if (error.code != 409 && error.code != 404) throw error
        }
        cache.clear(); return true
    }

    override fun restoreProgress(identity: PlaybackIdentity, percent: Double, action: String, timestamp: String, revision: String,
                                 expectedScrobbleRevision: String): Boolean {
        if (!current(identity, revision, expectedScrobbleRevision)) return false
        require(percent.isFinite() && percent in 0.0..100.0 && action in setOf("pause", "stop"))
        require(java.time.Instant.parse(timestamp).toEpochMilli() <= now() + 60_000) { "Invalid saved Simkl playback time." }
        if (action == "stop" && percent >= WATCHED) { history(identity, watchedAt = timestamp); return true }
        if (percent <= 0) return true
        return progressForAccount(identity, percent, "pause", revision, expectedScrobbleRevision)
    }

    /** Most recently paused first. */
    override fun continueWatching(): List<Pair<PlaybackIdentity, Double>> = array(get("/sync/playback"))
        .sortedByDescending { it.optString("paused_at") }.mapNotNull { item ->
            val progress = item.optDouble("progress", Double.NaN)
            if (!progress.isFinite() || progress <= 0 || progress >= 95) return@mapNotNull null
            playbackIdentity(item)?.let { it to progress }
        }

    override fun removeContinueWatching(identity: PlaybackIdentity, revision: String) {
        if (revision.isBlank() || accountRevision() != revision || !connected()) ended()
        validate(identity)
        val key = "${identity.type}:${identity.imdbId}:${identity.season}:${identity.episode}"
        array(request("GET", "/sync/playback")).filter { playbackIdentity(it)?.let { found ->
            "${found.type}:${found.imdbId}:${found.season}:${found.episode}" == key } == true
        }.forEach { session ->
            val id = session.optLong("id").takeIf { it > 0 } ?: return@forEach
            try { request("DELETE", "/sync/playback/$id") } catch (error: ApiException) { if (error.code != 404) throw error }
        }
        // A queued pause or stop must not recreate the removed session; a deliberate new play clears this.
        credentials.put(mapOf(suppressionKey(identity) to revision))
        cache.clear()
    }

    override fun nextEpisodes(): List<PlaybackIdentity> = library("shows", "watching", "&next_watch_info=yes").mapNotNull { item ->
        val show = item.optJSONObject("show") ?: return@mapNotNull null
        val imdb = imdb(show) ?: return@mapNotNull null
        val info = item.optJSONObject("next_to_watch_info")
        val (season, episode) = if (info != null && info.optInt("episode") > 0) info.optInt("season") to info.optInt("episode")
            else NEXT.matchEntire(item.optString("next_to_watch"))?.destructured?.let { (s, e) -> s.toInt() to e.toInt() } ?: return@mapNotNull null
        if (season < 0 || episode <= 0) null else PlaybackIdentity(imdb, "series", show.optString("title"), season, episode)
    }

    override fun watchlist(type: String): List<MediaItemData> {
        require(type in setOf("movie", "series")) { "Invalid Simkl watchlist type." }
        val (kind, key) = if (type == "movie") "movies" to "movie" else "shows" to "show"
        return library(kind, "plantowatch").mapNotNull { item ->
            val media = item.optJSONObject(key) ?: return@mapNotNull null
            val imdb = imdb(media) ?: return@mapNotNull null
            MediaItemData(imdb, type, media.optString("title").ifBlank { return@mapNotNull null }, media.optString("year").take(4).takeIf { it != "0" }.orEmpty())
        }
    }

    override fun setWatchlist(identity: PlaybackIdentity, add: Boolean) {
        validate(identity, episode = false)
        val group = if (identity.type == "movie") "movies" else "shows"
        if (add) {
            val body = JSONObject().put(group, JSONArray().put(JSONObject().put("to", "plantowatch").put("ids", ids(identity))))
            checkFound(parseObject(request("POST", "/sync/add-to-list", body)), group)
        } else {
            // Simkl removes a title through its history endpoint, which also erases watch history. Only a title that is
            // purely on the watchlist is removed; one with history stays untouched.
            if (watchlist(identity.type).none { it.imdbId == identity.imdbId })
                throw ApiException(0, "This title isn't on your Simkl watchlist. Titles you've watched are kept so their history isn't erased.")
            val body = JSONObject().put(group, JSONArray().put(JSONObject().put("ids", ids(identity))))
            checkFound(parseObject(request("POST", "/sync/history/remove", body)), group)
        }
        cache.clear()
    }

    override fun markWatched(identity: PlaybackIdentity) { validate(identity); history(identity, watchedAt = null); cache.clear() }

    override fun refreshCaches() = cache.clear()

    override fun watchedStamp(): String {
        val activity = parseObject(request("GET", "/sync/activities"))
        return listOf("movies", "tv_shows").joinToString("|") { section ->
            val stamps = activity.optJSONObject(section)
            listOf("completed", "watching", "hold", "dropped", "removed_from_list").joinToString("/") { stamps?.optString(it).orEmpty() }
        }
    }

    /** Completed movies, and every show with the episodes watched in it (one library call per type). */
    override fun watched(): WatchedState {
        fun items(text: String, kind: String): List<JSONObject> {
            if (text.isBlank() || text == "null") return emptyList()
            val list = parseObject(text).optJSONArray(kind) ?: return emptyList()
            return (0 until list.length()).mapNotNull(list::optJSONObject)
        }
        val movies = items(request("GET", "/sync/all-items/movies/completed"), "movies")
            .mapNotNull { it.optJSONObject("movie")?.let(::imdb) }.toSet()
        val shows = mutableSetOf<String>(); val episodes = mutableSetOf<String>()
        items(request("GET", "/sync/all-items/shows?extended=full"), "shows").forEach { item ->
            val imdb = item.optJSONObject("show")?.let(::imdb) ?: return@forEach
            if (item.optString("status") == "completed") shows += imdb
            val seasons = item.optJSONArray("seasons") ?: return@forEach
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val list = season.optJSONArray("episodes") ?: continue
                for (e in 0 until list.length()) list.optJSONObject(e)?.optInt("number", -1)?.takeIf { it > 0 }?.let {
                    episodes += WatchedState.key(imdb, season.optInt("number", -1), it)
                }
            }
        }
        return WatchedState(movies, shows, episodes)
    }

    override fun lists(): List<Pair<String, String>> = emptyList()
    override fun listItems(id: String): List<MediaItemData> = emptyList()
    override fun removeListItem(listId: String, identity: PlaybackIdentity) = throw ApiException(0, "Simkl lists aren't supported yet.")
    override fun likedLists(): List<Pair<String, String>> = emptyList()
    override fun likedListItems(id: String): List<MediaItemData> = emptyList()
    override fun likedListPreview(id: String): List<MediaItemData> = emptyList()

    private fun history(identity: PlaybackIdentity, watchedAt: String?) {
        val item = JSONObject().put("ids", ids(identity))
        watchedAt?.let { item.put("watched_at", it) }
        val group = if (identity.type == "movie") "movies" else "shows"
        if (identity.type == "series") item.put("seasons", JSONArray().put(JSONObject().put("number", identity.season)
            .put("episodes", JSONArray().put(JSONObject().put("number", identity.episode)))))
        checkFound(parseObject(request("POST", "/sync/history", JSONObject().put(group, JSONArray().put(item)))), group)
    }

    private fun checkFound(result: JSONObject, group: String) {
        val missing = result.optJSONObject("not_found") ?: return
        if ((missing.optJSONArray(group)?.length() ?: 0) > 0 || (missing.optJSONArray("episodes")?.length() ?: 0) > 0)
            throw ApiException(0, "Simkl couldn't find this title.")
    }

    private fun itemBody(identity: PlaybackIdentity): JSONObject =
        if (identity.type == "movie") JSONObject().put("movie", JSONObject().put("ids", ids(identity)))
        else JSONObject().put("show", JSONObject().put("ids", ids(identity)))
            .put("episode", JSONObject().put("season", identity.season).put("number", identity.episode))
    private fun ids(identity: PlaybackIdentity) = JSONObject().put("imdb", identity.imdbId)

    /** Paused sessions: movies directly, episodes by their show's IMDb id and season/episode. */
    internal fun playbackIdentity(item: JSONObject): PlaybackIdentity? {
        item.optJSONObject("movie")?.let { movie -> return imdb(movie)?.let { PlaybackIdentity(it, "movie", movie.optString("title")) } }
        val show = item.optJSONObject("show") ?: return null
        val episode = item.optJSONObject("episode") ?: return null
        val imdb = imdb(show) ?: return null
        val season = episode.optInt("season", -1); val number = episode.optInt("number", -1)
        return if (season < 0 || number <= 0) null else PlaybackIdentity(imdb, "series", show.optString("title"), season, number)
    }
    private fun imdb(media: JSONObject) = media.optJSONObject("ids")?.optString("imdb")?.takeIf { IMDB.matches(it) }
    private fun validate(identity: PlaybackIdentity, episode: Boolean = true) {
        require(IMDB.matches(identity.imdbId) && identity.type in setOf("movie", "series")) { "A matched movie or TV episode is required for Simkl." }
        if (episode && identity.type == "series") require(identity.season >= 0 && identity.episode > 0) { "Select a specific episode first." }
    }

    private fun library(kind: String, status: String, extra: String = ""): List<JSONObject> {
        val text = get("/sync/all-items/$kind/$status?extended=full$extra")
        if (text.isBlank() || text == "null") return emptyList()
        val root = if (text.trimStart().startsWith("[")) JSONObject().put(kind, JSONArray(text)) else parseObject(text)
        return root.optJSONArray(kind)?.let { list -> (0 until list.length()).mapNotNull(list::optJSONObject) }.orEmpty()
    }
    private fun array(text: String): List<JSONObject> {
        if (text.isBlank() || text == "null") return emptyList()
        val list = try { JSONArray(text) } catch (_: Exception) { malformed() }
        return (0 until list.length()).mapNotNull(list::optJSONObject)
    }

    /** Library reads are cached briefly per account; any write clears them. */
    private fun get(path: String): String {
        val key = "${accountRevision()}:$path"
        cache[key]?.takeIf { elapsed() - it.first in 0..CACHE_MS }?.let { return it.second }
        return request("GET", path).also { cache[key] = elapsed() to it }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val token = accessToken()
        return try { transport(SimklRequest(method, url(path), headers(token), json = body)) }
        catch (error: ApiException) {
            if (error.code != 401) throw error
            transport(SimklRequest(method, url(path), headers(accessToken(force = true)), json = body))
        }
    }

    private fun accessToken(force: Boolean = false): String = synchronized(lock) {
        val access = credentials.get("access_token").ifBlank { ended() }
        val expires = credentials.get("expires_at").toLongOrNull() ?: 0L
        if (!force && (expires == 0L || expires - now() > REFRESH_MARGIN_MS)) return@synchronized access
        val refresh = credentials.get("refresh_token").ifBlank { return@synchronized access }
        val result = try {
            parseObject(transport(SimklRequest("POST", url("/oauth2/token"), headers(null),
                form = listOf("grant_type" to "refresh_token", "client_id" to CLIENT_ID, "refresh_token" to refresh))))
        } catch (error: ApiException) {
            // A refused refresh token means the user must link Simkl again.
            if (error.code in 400..401) { credentials.put(EMPTY_ACCOUNT); cache.clear(); ended() }
            throw error
        }
        saveTokens(result, newAccount = false)
        credentials.get("access_token")
    }

    private fun saveTokens(result: JSONObject, newAccount: Boolean) {
        val access = required(result, "access_token")
        val seconds = result.optLong("expires_in", 0L)
        val values = mutableMapOf("access_token" to access,
            "refresh_token" to result.optString("refresh_token").ifBlank { credentials.get("refresh_token") },
            "expires_at" to (if (seconds > 0) now() + seconds * 1000 else 0L).toString())
        if (newAccount) values["account_revision"] = UUID.randomUUID().toString()
        credentials.put(values)
    }

    private fun url(path: String) = BASE + path + (if ('?' in path) "&" else "?") +
        "client_id=$CLIENT_ID&app-name=pov-rajput&app-version=${Net.encode(VERSION)}"
    private fun headers(token: String?) = buildMap {
        put("User-Agent", "pov-rajput/$VERSION"); put("simkl-api-key", CLIENT_ID)
        if (token != null) put("Authorization", "Bearer $token")
    }
    private fun required(result: JSONObject, key: String) = (result.opt(key) as? String)?.takeIf { it.isNotBlank() } ?: malformed()
    private fun parseObject(text: String) = try { JSONObject(text) } catch (_: Exception) { malformed() }
    private fun malformed(): Nothing = throw ApiException(0, "Simkl returned an unreadable response. Try again later.")
    private fun ended(): Nothing = throw ApiException(410, "This Simkl connection changed or expired. Link it again in Settings.")

    companion object {
        /** POV Rajput's registered Simkl application (AUTH V2, device sign-in; public identifier, not a secret). */
        const val CLIENT_ID = "d07365e45e403abc28fd1ae4c315d2eb585e2850789451a2077ba427622b465b"
        private const val BASE = "https://api.simkl.com"
        private const val WATCHED = 90
        private const val CACHE_MS = 60_000L
        private const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
        private val VERSION = dev.pov.tv.BuildConfig.VERSION_NAME
        private val IMDB = Regex("tt[0-9]{5,12}")
        private val NEXT = Regex("S(\\d+)E(\\d+)")
        private val EMPTY_ACCOUNT = listOf("access_token", "refresh_token", "expires_at", "account_revision").associateWith { "" }
        private val lock = Any()
        private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

        private fun send(request: SimklRequest): String = when {
            request.form != null -> Net.form(request.url, request.method, request.headers, request.form)
            else -> Net.raw(request.url, request.method, request.headers, request.json)
        }
    }
}

internal data class SimklRequest(val method: String, val url: String, val headers: Map<String, String>,
                                 val json: JSONObject? = null, val form: List<Pair<String, String>>? = null)
