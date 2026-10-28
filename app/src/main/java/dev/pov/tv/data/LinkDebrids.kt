package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Real-Debrid, AllDebrid, Premiumize and Debrid-Link: an API key, a cache answer and direct HTTPS links.
 *
 * Premiumize answers cache questions up front. Real-Debrid, AllDebrid and Debrid-Link removed their
 * instant-availability APIs, so a link is checked by adding it and looking: a cached torrent is ready at
 * once, and an uncached one is removed again so a check never leaves a download behind.
 */
class LinkDebrids internal constructor(
    private val keyFor: (String) -> String,
    private val saveKey: (String, String) -> Unit,
    private val transport: LinkTransport = LinkTransport.NET,
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    constructor(store: SecureStore) : this({ store.get(DebridServices.credential(it)) }, { id, key -> store.put(DebridServices.credential(id), key) })

    fun connected(service: String) = keyFor(service).isNotBlank()

    fun setKey(service: String, value: String, cancelled: () -> Boolean = { false }) {
        if (cancelled()) return
        require(service in IDS)
        val key = value.trim()
        require(key.length in 8..512 && key.none { it.isWhitespace() || it.isISOControl() }) { "Enter your ${DebridServices.name(service)} API key." }
        check(validate(service, key)) { "${DebridServices.name(service)} could not validate this API key." }
        if (cancelled()) return
        saveKey(service, key)
    }

    fun disconnect(service: String) { require(service in IDS); saveKey(service, "") }

    fun ownsTag(service: String, tag: String) = service in IDS && keyFor(service).let { it.isNotBlank() && tag(it) == tag }

    /** Up-front cache answers; only Premiumize offers them. */
    fun cached(service: String, hashes: List<String>): Set<String> {
        if (service != "premiumize" || hashes.isEmpty()) return emptySet()
        val key = credential(service)
        val query = hashes.joinToString("&") { "items[]=$it" }
        val response = json(transport.get(premiumizeUrl("cache/check?$query", key), emptyMap()))
        premiumizeOk(response)
        val answers = response.optJSONArray("response") ?: throw IllegalStateException("Premiumize cache availability is unknown.")
        check(answers.length() == hashes.size) { "Premiumize cache availability is unknown." }
        return hashes.filterIndexed { index, _ -> answers.optBoolean(index, false) }.toSet()
    }

    /** The cached files for this torrent, or null when the service does not have it cached. */
    fun prepare(service: String, source: SourceItem, identity: PlaybackIdentity): PreparedSource.Links? {
        val hash = source.infoHash.lowercase(Locale.ROOT)
        require(HASH.matches(hash)) { "This source has no valid torrent hash." }
        val key = credential(service)
        val files = when (service) {
            "realdebrid" -> realDebrid(key, hash, source, identity)
            "alldebrid" -> allDebrid(key, hash)
            "premiumize" -> premiumize(key, hash)
            "debridlink" -> debridLink(key, hash)
            else -> error("Unknown debrid service.")
        } ?: return null
        val eligible = try { EpisodeFilename.choices(files.map { VideoCandidate(it.id, it.name, it.size) }, source.title, identity) }
            catch (_: IllegalStateException) { return null }
        check(keyFor(service) == key) { "The ${DebridServices.name(service)} account changed. Please try again." }
        return PreparedSource.Links(service, files.filter { file -> eligible.any { it.id == file.id } }, identity,
            EpisodeFilename.preferredId(eligible, source.title), tag(key))
    }

    /** A playable HTTPS address for one prepared file. Real-Debrid and AllDebrid hand out hoster links that must be unlocked first. */
    fun streamUrl(prepared: PreparedSource.Links, file: DebridFile): String {
        check(ownsTag(prepared.service, prepared.accountTag)) { "The ${DebridServices.name(prepared.service)} account changed. Select the source again." }
        val key = credential(prepared.service)
        val url = when (prepared.service) {
            "realdebrid" -> json(transport.form("POST", "$REAL_DEBRID/unrestrict/link", bearer(key), listOf("link" to file.link))).optString("download")
            "alldebrid" -> {
                val data = allDebridData(transport.get("$ALL_DEBRID/v4/link/unlock?agent=$AGENT&link=${Net.encode(file.link)}", bearer(key)))
                data.optString("link").ifBlank {
                    throw IllegalStateException("AllDebrid is still preparing this file. Try again in a moment.")
                }
            }
            else -> file.link
        }
        require(isHttps(url)) { "${DebridServices.name(prepared.service)} did not return a supported HTTPS playback link." }
        return url
    }

    private fun validate(service: String, key: String): Boolean = when (service) {
        "realdebrid" -> json(transport.get("$REAL_DEBRID/user", bearer(key))).has("id")
        "alldebrid" -> allDebridData(transport.get("$ALL_DEBRID/v4/user?agent=$AGENT", bearer(key))).optJSONObject("user") != null
        "premiumize" -> json(transport.get(premiumizeUrl("account/info", key), emptyMap())).optString("status") == "success"
        "debridlink" -> json(transport.get("$DEBRID_LINK/account/infos", bearer(key))).optBoolean("success")
        else -> false
    }

    private fun realDebrid(key: String, hash: String, source: SourceItem, identity: PlaybackIdentity): List<DebridFile>? {
        val auth = bearer(key)
        val id = json(transport.form("POST", "$REAL_DEBRID/torrents/addMagnet", auth, listOf("magnet" to magnet(hash)))).optString("id")
        check(TORRENT_ID.matches(id)) { "Real-Debrid did not accept this link." }
        var keep = false
        try {
            var info = waitFor(4_000) { json(transport.get("$REAL_DEBRID/torrents/info/$id", auth)).takeIf { it.optString("status") != "magnet_conversion" } }
                ?: return null
            if (info.optString("status") == "waiting_files_selection") {
                val all = info.optJSONArray("files") ?: return null
                val files = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                    .map { VideoCandidate(it.optLong("id"), it.optString("path"), it.optLong("bytes")) }
                // Select only this title's videos: a cached pack is ready at once, anything else starts downloading.
                val wanted = try { EpisodeFilename.choices(files, source.title, identity) } catch (_: IllegalStateException) { return null }
                transport.form("POST", "$REAL_DEBRID/torrents/selectFiles/$id", auth, listOf("files" to wanted.joinToString(",") { it.id.toString() }))
                info = waitFor(4_000) { json(transport.get("$REAL_DEBRID/torrents/info/$id", auth)).takeIf { it.optString("status") != "waiting_files_selection" && it.optString("status") != "magnet_conversion" } }
                    ?: return null
            }
            if (info.optString("status") != "downloaded") return null
            val all = info.optJSONArray("files") ?: return null
            val selected = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it.optInt("selected") == 1 }.sortedBy { it.optLong("id") }
            val links = info.optJSONArray("links") ?: return null
            // Links follow the selected files in order; a mismatch means the files were packed into an archive.
            if (links.length() != selected.size) return null
            keep = true
            return selected.mapIndexed { index, file -> DebridFile(file.optLong("id"), file.optString("path").substringAfterLast('/'), file.optLong("bytes"), links.getString(index)) }
        } finally {
            if (!keep) runCatching { transport.delete("$REAL_DEBRID/torrents/delete/$id", auth) }
        }
    }

    private fun allDebrid(key: String, hash: String): List<DebridFile>? {
        val auth = bearer(key)
        val magnet = allDebridData(transport.form("POST", "$ALL_DEBRID/v4/magnet/upload?agent=$AGENT", auth, listOf("magnets[]" to hash)))
            .optJSONArray("magnets")?.optJSONObject(0) ?: return null
        if (magnet.has("error")) return null
        val id = magnet.optLong("id")
        if (!magnet.optBoolean("ready")) {
            runCatching { transport.get("$ALL_DEBRID/v4/magnet/delete?agent=$AGENT&id=$id", auth) }
            return null
        }
        val entries = allDebridData(transport.form("POST", "$ALL_DEBRID/v4/magnet/files?agent=$AGENT", auth, listOf("id[]" to id.toString())))
            .optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files") ?: return null
        val files = mutableListOf<DebridFile>()
        fun walk(array: JSONArray) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                entry.optJSONArray("e")?.let(::walk)
                val link = entry.optString("l")
                if (link.isNotBlank()) files += DebridFile(files.size.toLong(), entry.optString("n"), entry.optLong("s"), link)
            }
        }
        walk(entries)
        return files.ifEmpty { null }
    }

    private fun premiumize(key: String, hash: String): List<DebridFile>? {
        val response = json(transport.form("POST", premiumizeUrl("transfer/directdl", key), emptyMap(), listOf("src" to magnet(hash))))
        if (response.optString("status") != "success") return null
        val content = response.optJSONArray("content") ?: return null
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }.mapIndexed { index, file ->
            DebridFile(index.toLong(), file.optString("path").substringAfterLast('/'), file.optLong("size"), file.optString("link"))
        }.filter { isHttps(it.link) }.ifEmpty { null }
    }

    private fun debridLink(key: String, hash: String): List<DebridFile>? {
        val auth = bearer(key)
        val response = json(transport.json("POST", "$DEBRID_LINK/seedbox/add", auth, JSONObject().put("url", magnet(hash)).put("async", false)))
        if (!response.optBoolean("success")) return null
        val value = response.optJSONObject("value") ?: return null
        val id = value.optString("id")
        val files = value.optJSONArray("files")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
        val ready = value.optInt("downloadPercent") == 100 && files.isNotEmpty() && files.all { isHttps(it.optString("downloadUrl")) }
        if (!ready) {
            if (TORRENT_ID.matches(id)) runCatching { transport.delete("$DEBRID_LINK/seedbox/$id/remove", auth) }
            return null
        }
        return files.mapIndexed { index, file -> DebridFile(index.toLong(), file.optString("name"), file.optLong("size"), file.optString("downloadUrl")) }
    }

    private fun <T : Any> waitFor(limitMs: Long, attempt: () -> T?): T? {
        val deadline = now() + limitMs * 1_000_000
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Source preparation cancelled.")
            attempt()?.let { return it }
            if (now() >= deadline) return null
            pause(700)
        }
    }

    private fun credential(service: String) = keyFor(service).also { check(it.isNotBlank()) { "Connect ${DebridServices.name(service)} in Settings first." } }
    private fun premiumizeUrl(path: String, key: String) = "$PREMIUMIZE/$path${if ('?' in path) "&" else "?"}apikey=${Net.encode(key)}"
    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")
    private fun json(text: String): JSONObject = try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException("The debrid service returned an unreadable response.") }

    private fun premiumizeOk(response: JSONObject) =
        check(response.optString("status") == "success") { "Premiumize could not complete the request. Check your API key and plan." }

    private fun allDebridData(text: String): JSONObject {
        val response = json(text)
        check(response.optString("status") == "success") {
            when (response.optJSONObject("error")?.optString("code")) {
                "AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY" -> "AllDebrid did not accept this API key."
                "AUTH_BLOCKED" -> "AllDebrid blocked this sign-in. Approve it in the email AllDebrid sent you, then try again."
                "MUST_BE_PREMIUM" -> "AllDebrid needs a premium account for this."
                else -> "AllDebrid could not complete the request."
            }
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    companion object {
        val IDS = listOf("realdebrid", "alldebrid", "premiumize", "debridlink")
        /** Services that can only tell whether a link is cached by trying it. */
        val CHECK_ON_PLAY = setOf("realdebrid", "alldebrid", "debridlink")
        /** How many links a check-on-play attempt tries before giving up. */
        const val CHECK_LIMIT = 7
        private const val REAL_DEBRID = "https://api.real-debrid.com/rest/1.0"
        private const val ALL_DEBRID = "https://api.alldebrid.com"
        private const val PREMIUMIZE = "https://www.premiumize.me/api"
        private const val DEBRID_LINK = "https://debrid-link.com/api/v2"
        private const val AGENT = "povrajput"
        private val HASH = Regex("[a-f0-9]{40}")
        private val TORRENT_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"
        private fun tag(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun isHttps(value: String) = try {
            val uri = URI(value); uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: Exception) { false }
    }
}

/** Requests go through [Net]; tests replace this with canned responses. */
internal interface LinkTransport {
    fun get(url: String, headers: Map<String, String>): String
    fun delete(url: String, headers: Map<String, String>): String
    fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>): String
    fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject): String

    companion object {
        val NET = object : LinkTransport {
            override fun get(url: String, headers: Map<String, String>) = Net.raw(url, "GET", headers)
            override fun delete(url: String, headers: Map<String, String>) = Net.raw(url, "DELETE", headers)
            override fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>) = Net.form(url, method, headers, fields)
            override fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject) = Net.raw(url, method, headers, body)
        }
    }
}

data class DebridFile(val id: Long, val name: String, val size: Long, val link: String)
