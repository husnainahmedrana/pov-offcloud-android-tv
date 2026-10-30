package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchedCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `saved history belongs to one service and account`() {
        val cache = WatchedCache(folder.newFile("watched.json"))
        val state = WatchedState(setOf("tt1375666"), setOf("tt0903747"), setOf(WatchedState.key("tt0412142", 5, 17)))
        cache.save(WatchedCache.Entry("simkl", "account-1", "stamp", state))
        assertEquals(state, cache.load("simkl", "account-1")?.state)
        assertEquals("stamp", cache.load("simkl", "account-1")?.stamp)
        assertNull(cache.load("wetrakr", "account-1"))
        assertNull(cache.load("simkl", "account-2"))
    }

    @Test fun `a manual mark adds the movie or episode`() {
        val marked = WatchedState.EMPTY.with(PlaybackIdentity("tt0412142", "series", "House", 5, 18))
        assertTrue(marked.episode("tt0412142", 5, 18))
        assertTrue(WatchedState.EMPTY.with(PlaybackIdentity("tt1375666", "movie", "Inception")).movie("tt1375666"))
    }
}
