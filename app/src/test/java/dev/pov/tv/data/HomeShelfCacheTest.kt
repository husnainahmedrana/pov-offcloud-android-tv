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
class HomeShelfCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private var clock = 1_000_000L
    private val shelves = listOf(CatalogApi.HomeShelf("Popular Movies", "movie",
        listOf(MediaItemData("tt1234567", "movie", "Movie", "2026", "https://p", "https://b", "Plot", listOf("Drama"), "100 min", "7.5"))))

    private fun cache() = HomeShelfCache(folder.root.resolve("home.json")) { clock }

    @Test fun `saved rows come back the same`() {
        cache().save("rev", shelves)
        assertEquals(shelves, cache().load("rev"))
        assertTrue(HomeShelfCache.sameRows(shelves, cache().load("rev")!!))
    }

    @Test fun `rows are not used after six hours or under other catalog settings`() {
        cache().save("rev", shelves)
        assertNull(cache().load("other"))
        clock += HomeShelfCache.MAX_AGE_MS
        assertNull(cache().load("rev"))
    }

    @Test fun `a failed catalog is never remembered`() {
        cache().save("rev", shelves + CatalogApi.HomeShelf("Trending", "movie", emptyList(), unavailable = true))
        assertNull(cache().load("rev"))
    }
}
