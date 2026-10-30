package dev.pov.tv.data

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Public native-client device flow with POV Rajput's registered WeTrakr app. Its Client ID is public by
 * design; no client secret is accepted, stored, or bundled. Requests made with a user's token count
 * against that user's own WeTrakr quota, not the app's.
 */
class WeTrakrApi internal constructor(
    private val credentials: WeTrakrCredentials,
    private val transport: (String, String, Map<String, String>, JSONObject?) -> String = { path, method, headers, body -> Net.raw(BASE + path, method, headers, body) },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime
) : Tracker {
    override val name = "WeTrakr"
    constructor(store: SecureStore) : this(object : WeTrakrCredentials {
        override fun get(key: String) = store.get("wetrakr.$key")
        override fun put(values: Map<String, String>) = store.putAll(values.mapKeys { "wetrakr.${it.key}" })
    })
    private data class Pending(val auth: DeviceAuth, val client: String, val generation: Long, val started: Long, var lastPoll: Long, var interval: Long)
    @Volatile private var pending: Pending? = null
    private var attempt = 0L
    fun configured() = validClientId(clientId())
    override fun connected() = configured() && credentials.get("access_token").isNotBlank() && credentials.get("reauth") != "true"
    override fun accountRevision() = credentials.get("account_revision")
    /** Accounts linked with an older user-entered Client ID keep it until they disconnect; everyone else uses the app's. */
    fun clientId() = credentials.get("client_id").trim().takeIf(::validClientId) ?: CLIENT_ID
    fun setClientId(value: String) = synchronized(stateLock) {
        val client = value.trim()
        require(validClientId(client)) { "Enter your registered WeTrakr application Client ID." }
        if (credentials.get("client_id") != client) {
            generation++; attempt++; pending = null; cache.clear()
            credentials.put(emptyAccount() + mapOf("client_id" to client, "retry_at" to ""))
        }
    }
    /** Local unlink is immediate; in-flight old responses cannot reconnect or replace tokens. */
    override fun disconnect(): Unit = synchronized(stateLock) {
        generation++; attempt++; pending = null; cache.clear(); credentials.put(emptyAccount() + mapOf("client_id" to ""))
    }
    override fun cancelDeviceAuth(auth: DeviceAuth?): Unit = synchronized(stateLock) {
        if (auth == null || pending?.auth?.deviceCode == auth.deviceCode) { attempt++; pending = null }
    }
    override fun startDeviceAuth(): DeviceAuth {
        val (client, version, request) = synchronized(stateLock) { pending = null; Triple(clientId(), generation, ++attempt) }
        val start = elapsed()
        val result = objectCall("/oauth/device/code", JSONObject().put("client_id", client))
        val seconds = result.optInt("expires_in")
        val interval = result.optInt("interval")
        val url = required(result, "verification_url")
        val uri = try { URI(url) } catch (_: Exception) { malformed() }
        if (uri.scheme != "https" || uri.host != "wetrakr.com" || uri.userInfo != null || uri.fragment != null ||
            seconds !in 1..86400 || interval !in 1..300) malformed()
        val auth = DeviceAuth(required(result, "device_code"), required(result, "user_code"), url, interval, seconds)
        synchronized(stateLock) {
            if (version != generation || request != attempt || clientId() != client) ended()
            pending = Pending(auth, client, version, start, start, interval.toLong())
        }
        return auth
    }
    @Synchronized override fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val state = synchronized(stateLock) { pending?.takeIf { it.auth.deviceCode == auth.deviceCode && it.generation == generation && it.client == clientId() } ?: ended() }
        val time = elapsed()
        if (time - state.started >= auth.expiresSeconds * 1000L) { cancelDeviceAuth(auth); ended() }
        if (time - state.lastPoll < state.interval * 1000L) return false
        state.lastPoll = time
        val result = try { objectCall("/oauth/device/token", JSONObject().put("code", auth.deviceCode).put("client_id", state.client)) }
        catch (error: ApiException) {
            if (error.code == 400 && error.errorKey == "authorization_pending") return false
            if (error.code == 429) {
                state.interval = maxOf(state.interval + 5, error.retryAfterSeconds.toLong()); return false
            }
            cancelDeviceAuth(auth); throw error
        }
        synchronized(stateLock) {
            if (pending !== state || generation != state.generation || clientId() != state.client) ended()
            saveTokens(result, newAccount = true); generation++; attempt++; pending = null; cache.clear()
        }
        return true
    }
    override fun scrobblingEnabled() = credentials.get("scrobbling") != "false"
    override fun scrobbleRevision() = credentials.get("scrobble_revision")
    override fun setScrobblingEnabled(enabled: Boolean): Unit = synchronized(stateLock) {
        credentials.put(mapOf("scrobbling" to enabled.toString(), "scrobble_revision" to UUID.randomUUID().toString()))
    }
    private fun suppressionKey(identity: PlaybackIdentity) = "suppressed.${identity.type}.${identity.imdbId}.${identity.season}.${identity.episode}"
    override fun allowPlayback(identity: PlaybackIdentity): Unit = synchronized(stateLock) { credentials.put(mapOf(suppressionKey(identity) to "")) }
    /** Cancel exactly this title's session. Never delete watched plays or another device's current title. */
    override fun removeContinueWatching(identity: PlaybackIdentity, revision: String): Unit = synchronized(refreshLock) {
        if (revision.isBlank() || accountRevision() != revision || !connected()) ended()
        validate(identity)
        val body = playbackBody(identity)
        try {
            val result = parseObject(request("/scrobble/playing", "DELETE", body))
            if (result.optString("action") != "cancel") throw ApiException(0, "WeTrakr did not confirm removal. Try again.")
        } catch (error: ApiException) {
            if (error.code != 404 || error.errorKey != "NO_SESSION") throw error
        }
        // Prevent a queued pause/stop from recreating this removed item. A deliberate new play clears it.
        synchronized(stateLock) {
            if (accountRevision() != revision || !connected()) ended()
            credentials.put(mapOf(suppressionKey(identity) to revision))
        }
        cache.clear()
    }
    override fun likedLists(): List<Pair<String, String>> = pages("/sync/likes/lists", paginated = false).mapNotNull { entry ->
        val list = entry.optJSONObject("list") ?: return@mapNotNull null
        list.optString("id").takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.let { it to list.optString("name", "List") }
    }.distinctBy { it.first }
    override fun likedListItems(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid WeTrakr list." }
        return pages("/lists/$id/items/all").mapNotNull(::media)
    }
    override fun likedListPreview(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid WeTrakr list." }
        val path = "/lists/$id/items/all?page=1&limit=20"
        val key = "${accountRevision()}:$path"
        cache[key]?.takeIf { elapsed() - it.at in 0..120_000 }?.let { return it.records.mapNotNull(::media) }
        val array = try { JSONArray(request(path)) } catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }
        val records = (0 until array.length()).mapNotNull(array::optJSONObject)
        cache[key] = Cached(elapsed(), records)
        return records.mapNotNull(::media)
    }
    private fun playbackBody(identity: PlaybackIdentity): JSONObject {
        val media = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
        return if (identity.type == "movie") JSONObject().put("movie", media)
        else JSONObject().put("show", media).put("episode", JSONObject().put("season", identity.season).put("number", identity.episode))
    }
    private fun saveTokens(result: JSONObject, newAccount: Boolean = false) {
        val access = required(result, "access_token")
        val refresh = result.optString("new_refresh_token").ifBlank { required(result, "refresh_token") }
        val lifetime = result.optLong("expires_in")
        if (lifetime !in 1..31_536_000) malformed()
        val values = mutableMapOf("access_token" to access, "refresh_token" to refresh,
            "expires_at" to (now() + lifetime * 1000).toString(), "refresh_pending" to "", "reauth" to "")
        if (newAccount) values["account_revision"] = UUID.randomUUID().toString()
        credentials.put(values)
    }
    private data class Snapshot(val client: String, val access: String, val refresh: String, val revision: String, val generation: Long)
    private fun snapshot() = synchronized(stateLock) { Snapshot(clientId(), credentials.get("access_token"), credentials.get("refresh_token"), accountRevision(), generation) }
    private fun ensureCurrent(value: Snapshot) {
        if (value.generation != generation || value.client != clientId() || value.revision != accountRevision() ||
            value.access != credentials.get("access_token") || value.refresh != credentials.get("refresh_token")) ended()
    }
    private fun accessToken(revision: String, force: String? = null): String = synchronized(refreshLock) {
        val snapshot = snapshot()
        if (snapshot.revision != revision) ended()
        if (!connected()) throw ApiException(401, "Connect WeTrakr in Settings first.")
        val expires = credentials.get("expires_at").toLongOrNull() ?: 0
        if (if (force != null) snapshot.access != force else expires > now() + 60_000) return@synchronized snapshot.access
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(snapshot.refresh.toByteArray()).joinToString("") { "%02x".format(it) }
        synchronized(stateLock) {
            ensureCurrent(snapshot)
            if (snapshot.refresh.isBlank() || credentials.get("refresh_pending") == fingerprint) {
                credentials.put(mapOf("reauth" to "true")); throw ApiException(401, "Link WeTrakr again to renew this session.")
            }
            credentials.put(mapOf("refresh_pending" to fingerprint))
        }
        val result = try { objectCall("/oauth/token/refresh", JSONObject().put("refresh_token", snapshot.refresh)) }
        catch (error: ApiException) {
            synchronized(stateLock) {
                ensureCurrent(snapshot)
                if (error.code == 429) credentials.put(mapOf("refresh_pending" to ""))
                if (error.code in setOf(400, 401, 403)) credentials.put(mapOf("reauth" to "true"))
            }
            throw error
        }
        synchronized(stateLock) { ensureCurrent(snapshot); saveTokens(result) }
        required(result, "access_token")
    }
    private fun call(path: String, method: String, body: JSONObject?, access: String? = null, appClient: String? = null): String {
        val limitingClient = appClient ?: clientId()
        if (appClient != null && appClient != clientId()) ended()
        val until = credentials.get("retry_at").toLongOrNull() ?: 0
        if (now() < until) throw ApiException(429, "WeTrakr asked us to wait before making more requests.", retryAfterSeconds = ((until - now() + 999) / 1000).toInt())
        val headers = mutableMapOf("wetrakr-api-version" to "1")
        if (access != null) { headers["wetrakr-api-key"] = checkNotNull(appClient); headers["Authorization"] = "Bearer $access" }
        try { return transport(path, method, headers, body) }
        catch (error: ApiException) {
            if (error.code == 429) {
                val fallback = if (path == "/oauth/device/code") 900 else if (error.errorKey == "slow_down") 5 else 60
                synchronized(stateLock) {
                    if (clientId() == limitingClient)
                        credentials.put(mapOf("retry_at" to (now() + maxOf(fallback, error.retryAfterSeconds) * 1000L).toString()))
                }
            }
            throw error
        }
    }
    private fun objectCall(path: String, body: JSONObject) = parseObject(call(path, "POST", body))
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val current = snapshot()
        val revision = current.revision
        val access = accessToken(revision)
        val result = try { call(path, method, body, access, current.client) } catch (error: ApiException) {
            if (error.code != 401) throw error
            call(path, method, body, accessToken(revision, access), current.client)
        }
        if (revision != accountRevision() || !connected()) ended()
        return result
    }
    private data class Cached(val at: Long, val records: List<JSONObject>)
    private fun pages(path: String, paginated: Boolean = true): List<JSONObject> {
        val revision = accountRevision()
        if (!connected() || revision.isBlank()) throw ApiException(401, "Connect WeTrakr in Settings first.")
        val key = "$revision:$path"
        cache[key]?.takeIf { elapsed() - it.at in 0..120_000 }?.let { return it.records }
        val records = mutableListOf<JSONObject>()
        for (page in 1..20) {
            val raw = request(if (paginated) path + (if ('?' in path) "&" else "?") + "page=$page&limit=100" else path)
            val array = try { JSONArray(raw) } catch (_: Exception) { malformed() }
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(records::add)
            if (!paginated || array.length() < 100) { cache[key] = Cached(elapsed(), records); return records }
        }
        throw ApiException(0, "This WeTrakr list is too large to load at once. Open it on the website.")
    }
    /** Only an owned/collaborated list selected by the user can be edited. */
    override fun removeListItem(listId: String, identity: PlaybackIdentity) {
        require(listId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series"))
        val target = if (identity.type == "movie") "movies" else "shows"
        val body = JSONObject().put(target, JSONArray().put(JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))))
        val result = parseObject(request("/sync/lists/$listId/items/remove", "POST", body))
        if ((result.optJSONObject("notFound")?.optJSONArray(target)?.length() ?: 0) > 0 ||
            (result.optJSONObject("errored")?.optJSONArray(target)?.length() ?: 0) > 0 || result.optJSONObject("removed") == null) {
            throw ApiException(0, "WeTrakr could not confirm list removal. Refresh the list and try again.")
        }
        cache.clear()
    }
    override fun watchlist(type: String): List<MediaItemData> {
        require(type in setOf("movie", "series")) { "Invalid WeTrakr watchlist type." }
        return pages("/sync/tracking/planning/${if (type == "movie") "movies" else "shows"}").mapNotNull(::media)
    }
    override fun lists(): List<Pair<String, String>> = pages("/sync/lists").mapNotNull { record ->
        record.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }?.let { it to record.optString("name", "List") }
    }
    override fun listItems(id: String): List<MediaItemData> {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid WeTrakr list." }
        return pages("/sync/lists/$id/items").mapNotNull(::media)
    }
    /** Movies and episodes are fetched side by side; token refresh is already serialized. */
    override fun continueWatching(): List<Pair<PlaybackIdentity, Double>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            return listOf("movies", "episodes").map { kind -> pool.submit(java.util.concurrent.Callable {
                pages("/sync/tracking/playing/$kind?extended=show_level_1").mapNotNull { item ->
                    val playback = item.optJSONObject("playback") ?: return@mapNotNull null
                    val progress = playback.optDouble("progress_percent", Double.NaN)
                    if (!progress.isFinite() || progress <= 0 || progress >= 95) return@mapNotNull null
                    identity(item)?.let { Triple(it, progress, playback.optString("tracked_at")) }
                }
            }) }.flatMap { task ->
                try { task.get() } catch (error: java.util.concurrent.ExecutionException) { throw (error.cause as? Exception ?: error) }
            }.sortedByDescending { it.third }.map { (identity, progress, _) -> identity to progress }  // Most recently paused first.
        } finally { pool.shutdownNow() }
    }
    override fun nextEpisodes(): List<PlaybackIdentity> = pages("/sync/tracking/watching/shows?extended=episode_level_1").mapNotNull { show ->
        val next = show.optJSONObject("next_watching_episode") ?: return@mapNotNull null
        identity(next, show)
    }
    internal fun identity(item: JSONObject, parent: JSONObject? = null): PlaybackIdentity? {
        val show = parent ?: item.optJSONObject("show")
        val media = media(show ?: item) ?: return null
        if (show == null && media.type == "movie") return PlaybackIdentity(media.imdbId, "movie", media.title)
        val season = item.optInt("season_number", item.optInt("season", -1)); val number = item.optInt("number", -1)
        if (season < 0 || number <= 0) return null
        return PlaybackIdentity(media.imdbId, "series", media.title, season, number)
    }
    internal fun media(item: JSONObject): MediaItemData? {
        val id = item.optJSONObject("ids")?.optString("imdb").orEmpty()
        if (!id.matches(Regex("tt[0-9]{5,12}"))) return null
        val type = when (item.optString("type")) { "movie" -> "movie"; "show", "series" -> "series"; else -> return null }
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
        fun image(key: String) = item.optString(key).takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty()
        return MediaItemData(id, type, title, item.optString(if (type == "movie") "release_date" else "first_air_date").take(4), image("poster_path"), image("backdrop_path"))
    }
    override fun refreshCaches() = cache.clear()

    override fun watchedStamp(): String {
        val activity = parseObject(request("/sync/last_activities"))
        return listOf("movies", "shows", "episodes").joinToString("|") { section ->
            val stamps = activity.optJSONObject(section)
            "${stamps?.optString("last_tracking_watched_at")}/${stamps?.optString("last_tracking_removed_at")}"
        }
    }

    /** Compact rows (ids and numbers only), one page of up to 5,000 each, as WeTrakr asks for library syncs. */
    override fun watched(): WatchedState {
        fun rows(path: String): List<JSONObject> {
            val array = try { JSONArray(request("$path${if ('?' in path) "&" else "?"}compact=true&limit=5000")) } catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }
            return (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        fun imdb(ids: JSONObject?) = ids?.optString("imdb")?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
        val movies = rows("/sync/tracking/watched/movies").mapNotNull { imdb(it.optJSONObject("ids")) }.toSet()
        val shows = rows("/sync/tracking/watched/shows").mapNotNull { imdb(it.optJSONObject("ids")) }.toSet()
        val episodes = rows("/sync/tracking/watched/history/episodes").mapNotNull { row ->
            val show = imdb(row.optJSONObject("show_ids")) ?: imdb(row.optJSONObject("show")?.optJSONObject("ids")) ?: return@mapNotNull null
            val season = row.optInt("season_number", -1); val number = row.optInt("number", -1)
            if (season < 0 || number <= 0) null else WatchedState.key(show, season, number)
        }.toSet()
        return WatchedState(movies, shows, episodes)
    }

    override fun setWatchlist(identity: PlaybackIdentity, add: Boolean) { tracking(identity, "planning", remove = !add) }
    override fun markWatched(identity: PlaybackIdentity) { tracking(identity, "watched") }
    private fun tracking(identity: PlaybackIdentity, status: String, remove: Boolean = false, percent: Double? = null, timestamp: String? = null, replay: Boolean = false) = synchronized(refreshLock) {
        val item = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId)).put("status", status)
        val body = trackingBody(identity, item, status, percent, timestamp).put("allow_rewatch", false)
        val result = parseObject(request(if (remove) "/sync/tracking/remove" else "/sync/tracking", "POST", body))
        for (key in listOf("notFound", "errored")) {
            val errors = result.optJSONObject(key)
            if (errors != null && errors.keys().asSequence().any { (errors.optJSONArray(it)?.length() ?: 0) > 0 }) {
                // A lost response may have already logged the play. Never keep retrying it or
                // log a rewatch; manual writes still show the service's rejection to the user.
                val reasons = errors.keys().asSequence().flatMap { name ->
                    val array = errors.optJSONArray(name) ?: JSONArray()
                    (0 until array.length()).asSequence().map { array.optJSONObject(it)?.optString("error").orEmpty() }
                }.toList()
                if (replay && key == "errored" && reasons.isNotEmpty() && reasons.all {
                    it == "Already watched!" || it.startsWith("Already added!")
                }) return@synchronized
                throw ApiException(0, "WeTrakr could not apply this update. Refresh your library to check its current state.")
            }
        }
        if (result.optJSONObject(if (remove) "removed" else "added") == null && result.optJSONObject("existing") == null) malformed()
        cache.clear()
    }
    internal fun trackingBody(identity: PlaybackIdentity, item: JSONObject, status: String, percent: Double?, timestamp: String?): JSONObject {
        if (status == "planning" && identity.type == "series" && identity.episode == 0) {
            require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")))
            return JSONObject().put("shows", JSONArray().put(item))
        }
        validate(identity)
        val episode = JSONObject().put("number", identity.episode).put("status", status)
        val target = if (identity.type == "movie") item else episode
        if (percent != null) target.put("progress", percent)
        if (timestamp != null) target.put(if (status == "watched") "tracked_at" else "paused_at", timestamp)
        if (identity.type != "movie") item.put("seasons", JSONArray().put(JSONObject().put("number", identity.season).put("episodes", JSONArray().put(episode))))
        return JSONObject().put(if (identity.type == "movie") "movies" else "shows", JSONArray().put(item))
    }
    /** Retry stored final state without restarting an old now-playing session. */
    override fun restoreProgress(identity: PlaybackIdentity, percent: Double, action: String, timestamp: String, revision: String, expectedScrobbleRevision: String): Boolean = synchronized(refreshLock) {
        if (!connected() || !scrobblingEnabled() || credentials.get(suppressionKey(identity)) == revision || revision.isBlank() || accountRevision() != revision) return false
        if (expectedScrobbleRevision != scrobbleRevision()) return false
        require(percent.isFinite() && percent in 0.0..100.0 && action in setOf("pause", "stop"))
        require(java.time.Instant.parse(timestamp).toEpochMilli() <= now() + 60_000) { "Invalid saved WeTrakr playback time." }
        val watched = action == "stop" && percent >= 95
        if (!watched && percent <= 0) return true
        tracking(identity, if (watched) "watched" else "paused", percent = if (watched) null else percent.coerceIn(0.01, 99.99), timestamp = timestamp, replay = true)
        true
    }
    override fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String, expectedScrobbleRevision: String): Boolean = synchronized(refreshLock) {
        if (!connected() || !scrobblingEnabled() || credentials.get(suppressionKey(identity)) == revision || revision.isBlank() || accountRevision() != revision) return false
        if (expectedScrobbleRevision != scrobbleRevision()) return false
        validate(identity); require(percent.isFinite() && action in setOf("start", "pause", "stop"))
        val body = playbackBody(identity).put("progress", percent.coerceIn(0.0, 100.0)).put("app_version", "POV Rajput 0.2.11")
        val result = parseObject(request("/scrobble/$action?min_progress=95", "POST", body))
        val accepted = if (action == "stop") setOf("pause", "scrobble") else setOf(action)
        if (result.optBoolean("ignored") || result.optString("action") !in accepted) throw ApiException(0, "WeTrakr did not accept this playback update.")
        cache.clear(); true
    }
    private fun validate(identity: PlaybackIdentity) {
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series")) { "A matched movie or TV episode is required for WeTrakr." }
        if (identity.type == "series") require(identity.season >= 0 && identity.episode > 0) { "Select a specific episode first." }
    }
    private fun required(objectValue: JSONObject, key: String): String {
        val value = objectValue.opt(key) as? String ?: malformed()
        if (value.isBlank() || value == "null" || value.length > 8192 || value.any { it.code < 32 || it.code == 127 }) malformed()
        return value
    }
    private fun parseObject(raw: String) = try { JSONObject(raw) } catch (_: Exception) { malformed() }
    private fun malformed(): Nothing = throw ApiException(0, "WeTrakr returned an unreadable response. Try again later.")
    private fun ended(): Nothing = throw ApiException(410, "This WeTrakr connection changed or expired. Link it again in Settings.")
    private fun emptyAccount() = listOf("access_token", "refresh_token", "expires_at", "account_revision", "refresh_pending", "reauth").associateWith { "" }
    companion object {
        private const val BASE = "https://api.wetrakr.com"
        /** POV Rajput's registered WeTrakr application (public identifier, not a secret). */
        const val CLIENT_ID = "68a87ec819a3083e4884821c24ab8c0a"
        fun validClientId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{16,256}"))
        private val stateLock = Any()
        private val refreshLock = Any()
        private var generation = 0L
        private val cache = ConcurrentHashMap<String, Cached>()
    }
}
internal interface WeTrakrCredentials {
    fun get(key: String): String
    fun put(values: Map<String, String>)
}
