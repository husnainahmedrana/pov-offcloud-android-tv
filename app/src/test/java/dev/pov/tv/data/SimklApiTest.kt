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
class SimklApiTest {
    private class Harness {
        val data = mutableMapOf<String, String>()
        val requests = mutableListOf<SimklRequest>()
        var time = 1_000_000_000L
        var respond: (SimklRequest) -> String = { error("Unexpected request ${it.method} ${it.url}") }
        val api = SimklApi(object : WeTrakrCredentials {
            override fun get(key: String) = data[key].orEmpty()
            override fun put(values: Map<String, String>) { data.putAll(values) }
        }, { request -> requests += request; respond(request) }, { time }, { time })
        fun link() {
            respond = { JSONObject().put("device_code", "device").put("user_code", "ABCD-EFGH").put("verification_uri", "https://simkl.com/pin")
                .put("verification_uri_complete", "https://simkl.com/pin?user_code=ABCD-EFGH").put("expires_in", 900).put("interval", 5).toString() }
            val auth = api.startDeviceAuth()
            respond = { JSONObject().put("access_token", "simkl_at_fixture").put("refresh_token", "simkl_rt_fixture").put("expires_in", 604800).toString() }
            assertTrue(api.pollDeviceAuth(auth))
            requests.clear()
        }
    }
    private val movie = PlaybackIdentity("tt1375666", "movie", "Inception")
    private val episode = PlaybackIdentity("tt0903747", "series", "Breaking Bad", 2, 3)

    @Test fun `device sign-in shows the QR address with the code and stores tokens`() {
        val h = Harness()
        h.respond = { JSONObject().put("device_code", "device").put("user_code", "ABCD-EFGH").put("verification_uri", "https://simkl.com/pin")
            .put("verification_uri_complete", "https://simkl.com/pin?user_code=ABCD-EFGH").put("expires_in", 900).put("interval", 5).toString() }
        val auth = h.api.startDeviceAuth()
        assertEquals("https://simkl.com/pin?user_code=ABCD-EFGH", auth.verificationUrl)
        assertEquals("ABCD-EFGH", auth.userCode)
        assertEquals(SimklApi.CLIENT_ID, h.requests.single().form!!.toMap()["client_id"])
        h.respond = { throw ApiException(400, "pending", errorKey = "authorization_pending") }
        assertFalse(h.api.pollDeviceAuth(auth))
        assertFalse(h.api.connected())
        h.respond = { JSONObject().put("access_token", "simkl_at_fixture").put("refresh_token", "simkl_rt_fixture").put("expires_in", 604800).toString() }
        assertTrue(h.api.pollDeviceAuth(auth))
        assertTrue(h.api.connected())
        assertTrue(h.api.accountRevision().isNotBlank())
    }

    @Test fun `scrobble stop identifies an episode by show IMDb id and marks it watched from 90 percent`() {
        val h = Harness(); h.link()
        h.respond = { JSONObject().put("action", "scrobble").toString() }
        assertTrue(h.api.progressForAccount(episode, 93.456, "stop", h.api.accountRevision()))
        val request = h.requests.single()
        assertTrue(request.url.startsWith("https://api.simkl.com/scrobble/stop?min_progress=90&client_id="))
        assertEquals("Bearer simkl_at_fixture", request.headers["Authorization"])
        val body = request.json!!
        assertEquals("tt0903747", body.getJSONObject("show").getJSONObject("ids").getString("imdb"))
        assertEquals(2, body.getJSONObject("episode").getInt("season"))
        assertEquals(3, body.getJSONObject("episode").getInt("number"))
        assertEquals(93.46, body.getDouble("progress"), 0.0001)
    }

    @Test fun `a title already finished within the hour is not retried`() {
        val h = Harness(); h.link()
        h.respond = { throw ApiException(409, "already") }
        assertTrue(h.api.progressForAccount(movie, 97.0, "stop", h.api.accountRevision()))
    }

    @Test fun `marking an episode watched sends its season and number`() {
        val h = Harness(); h.link()
        h.respond = { JSONObject().put("added", JSONObject()).put("not_found", JSONObject().put("shows", JSONArray())).toString() }
        h.api.markWatched(episode)
        val shows = h.requests.single().json!!.getJSONArray("shows").getJSONObject(0)
        assertEquals(2, shows.getJSONArray("seasons").getJSONObject(0).getInt("number"))
        assertEquals(3, shows.getJSONArray("seasons").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getInt("number"))
    }

    @Test fun `removing from the watchlist never erases a watched title's history`() {
        val h = Harness(); h.link()
        h.respond = { JSONObject().put("movies", JSONArray()).toString() }
        assertThrows(ApiException::class.java) { h.api.setWatchlist(movie, add = false) }
        assertTrue(h.requests.none { it.url.contains("/sync/history/remove") })
    }

    @Test fun `next episodes and paused sessions map to playable identities`() {
        val h = Harness(); h.link()
        h.respond = { request -> when {
            request.url.contains("/sync/all-items/shows/watching") -> JSONObject().put("shows", JSONArray().put(JSONObject()
                .put("next_to_watch", "S02E04").put("show", JSONObject().put("title", "Breaking Bad").put("ids", JSONObject().put("imdb", "tt0903747"))))).toString()
            request.url.contains("/sync/playback") -> JSONArray().put(JSONObject().put("progress", 42.5).put("type", "movie")
                .put("movie", JSONObject().put("title", "Inception").put("ids", JSONObject().put("imdb", "tt1375666")))).toString()
            else -> error("Unexpected ${request.url}")
        } }
        assertEquals(PlaybackIdentity("tt0903747", "series", "Breaking Bad", 2, 4), h.api.nextEpisodes().single())
        val (identity, progress) = h.api.continueWatching().single()
        assertEquals("tt1375666", identity.imdbId); assertEquals(42.5, progress, 0.0)
    }

    @Test fun `an expiring token is refreshed before the request`() {
        val h = Harness(); h.link()
        h.time += 604_800_000L - 1_000L
        h.respond = { request -> if (request.url.contains("/oauth2/token")) JSONObject().put("access_token", "simkl_at_new").put("expires_in", 604800).toString()
            else JSONArray().toString() }
        h.api.continueWatching()
        assertEquals("refresh_token", h.requests.first().form!!.toMap()["grant_type"])
        assertEquals("Bearer simkl_at_new", h.requests.last().headers["Authorization"])
    }
}
