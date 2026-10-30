package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LastPlayedLinksTest {
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example")
    private class Fixture {
        val data = mutableMapOf("offcloud.key" to "fixture-offcloud", "torbox.token" to "fixture-torbox")
        var time = 1_000_000L
        val links = LastPlayedLinks({ data[it].orEmpty() }, { key, value -> data[key] = value }, { time })
    }
    @Test fun `last successful link survives a new owner and expires at exactly 24 hours`() {
        val f = Fixture(); f.links.save(movie, "https://media.example.invalid/video?signature=fixture", "media:one", "offcloud")
        val reopened = LastPlayedLinks({ f.data[it].orEmpty() }, { key, value -> f.data[key] = value }, { f.time })
        f.time += 86_399_999L
        assertEquals("media:one", reopened.find(movie)?.key)
        f.time++
        assertNull(reopened.find(movie))
    }
    @Test fun `file name and size are kept with the link`() {
        val f = Fixture(); f.links.save(movie, "https://media.example.invalid/video", "key", "offcloud", name = "Example.2026.2160p.WEB-DL.mkv", size = 8_000_000_000L)
        val reopened = LastPlayedLinks({ f.data[it].orEmpty() }, { key, value -> f.data[key] = value }, { f.time })
        assertEquals("Example.2026.2160p.WEB-DL.mkv", reopened.find(movie)?.name)
        assertEquals(8_000_000_000L, reopened.find(movie)?.size)
        f.links.save(movie, "https://media.example.invalid/other", "key", "offcloud")
        assertNull(reopened.find(movie)?.size)
    }
    @Test fun `plain HTTP add-on links and their request headers are kept`() {
        val f = Fixture(); f.links.save(movie, "http://media.example.invalid/video", "key", null, headers = mapOf("Referer" to "https://site.example.invalid/"))
        assertEquals("https://site.example.invalid/", f.links.find(movie)?.headers?.get("Referer"))
    }
    @Test fun `credentials must still belong to the resolving service`() {
        val f = Fixture(); f.links.save(movie, "https://media.example.invalid/video", "key", "torbox")
        f.data["offcloud.key"] = "other-offcloud"
        assertNotNull(f.links.find(movie))
        f.data["torbox.token"] = "other-torbox"
        assertNull(f.links.find(movie))
        f.data["torbox.token"] = ""
        assertNull(f.links.find(movie))
    }
    @Test fun `account change while a URL is opening cannot save it under the new account`() {
        val f = Fixture(); val scope = f.links.scope("offcloud")
        f.data["offcloud.key"] = "different-account"
        f.links.save(movie, "https://media.example.invalid/video", "key", "offcloud", scope)
        assertNull(f.links.find(movie))
    }
    @Test fun `episodes and media types cannot reuse one anothers links`() {
        val f = Fixture(); val one = movie.copy(type = "series", season = 1, episode = 1)
        f.links.save(one, "https://media.example.invalid/one", "one", null)
        assertNull(f.links.find(movie)); assertNull(f.links.find(one.copy(episode = 2)))
        assertNotNull(f.links.find(one))
    }
    @Test fun `replacement and failed link eviction affect only the selected item`() {
        val f = Fixture(); val other = movie.copy(imdbId = "tt7654321")
        f.links.save(movie, "https://media.example.invalid/old", "old", null)
        f.links.save(other, "https://media.example.invalid/other", "other", null)
        f.links.save(movie, "https://media.example.invalid/new", "new", null)
        assertEquals("new", f.links.find(movie)?.key)
        f.links.remove(movie)
        assertNull(f.links.find(movie)); assertNotNull(f.links.find(other))
    }
    @Test fun `invalid URLs and clock rollback never replay a stored link`() {
        val f = Fixture()
        for (url in listOf("https://user:secret@media.example.invalid/video", "file:///video")) {
            f.links.save(movie, url, "key", null); assertNull(f.links.find(movie))
        }
        f.links.save(movie, "https://media.example.invalid/video", "key", null)
        f.time--
        assertNull(f.links.find(movie))
    }
    @Test fun `corrupt cache does not break source search`() {
        val f = Fixture(); f.data["playback.last_links"] = "invalid-json"
        assertNull(f.links.find(movie))
        f.links.save(movie, "https://media.example.invalid/video", "key", null)
        assertNotNull(f.links.find(movie))
    }
}
