package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URL

class PublicMetadataRedirectTest {
    private val origin = URL("https://v3-cinemeta.strem.io/catalog/movie/top.json")

    @Test fun `Cinemeta live details redirect is accepted`() {
        val details = URL("https://v3-cinemeta.strem.io/meta/series/tt2531336.json")
        val target = Net.publicMetadataRedirect(details, "https://cinemeta-live.strem.io/meta/series/tt2531336.json", 5)
        assertEquals("cinemeta-live.strem.io", target.host)
        assertEquals("/meta/series/tt2531336.json", target.path)
    }

    @Test fun `Cinemeta official catalog redirect is accepted without credentials`() {
        val target = Net.publicMetadataRedirect(origin, "https://cinemeta-catalogs.strem.io/top/catalog/movie/top.json", 5)
        assertEquals("cinemeta-catalogs.strem.io", target.host)
        assertEquals("/top/catalog/movie/top.json", target.path)
        assertNull(target.query)
        assertNull(target.userInfo)
    }

    @Test fun `relative metadata redirect remains within the approved host`() {
        val target = Net.publicMetadataRedirect(origin, "/catalog/movie/top/skip=40.json", 5)
        assertEquals("v3-cinemeta.strem.io", target.host)
        assertEquals("/catalog/movie/top/skip=40.json", target.path)
    }

    @Test fun `redirect cannot downgrade transport leak query credentials or escape official hosts`() {
        val unsupported = listOf(
            "http://cinemeta-catalogs.strem.io/catalog/movie/top.json",
            "https://unrelated.example/catalog/movie/top.json",
            "https://evil.strem.io/catalog/movie/top.json",
            "https://v3-cinemeta.strem.io.evil.example/catalog/movie/top.json",
            "https://secret@v3-cinemeta.strem.io/catalog/movie/top.json",
            "https://cinemeta-catalogs.strem.io/catalog/movie/top.json?token=secret",
            "https://cinemeta-catalogs.strem.io/catalog/movie/top.json#secret",
            "https://v3-cinemeta.strem.io:8443/catalog/movie/top.json"
        )
        unsupported.forEach { address ->
            try {
                Net.publicMetadataRedirect(origin, address, 5)
                fail("Unsupported redirect was accepted")
            } catch (error: ApiException) {
                assertFalse(error.message.orEmpty().contains("secret"))
                assertFalse(error.message.orEmpty().contains("evil"))
            }
        }
    }

    @Test fun `credentialed initial address is rejected before network access`() {
        try {
            Net.publicJson("https://v3-cinemeta.strem.io/catalog/movie/top.json?token=secret")
            fail("Expected rejection")
        } catch (error: ApiException) {
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun `redirect budget and missing location are enforced`() {
        for ((location, budget) in listOf("/catalog/movie/top.json" to 0, "" to 5)) {
            try {
                Net.publicMetadataRedirect(origin, location, budget)
                fail("Invalid redirect was accepted")
            } catch (error: ApiException) {
                assertEquals(0, error.code)
            }
        }
    }
}
