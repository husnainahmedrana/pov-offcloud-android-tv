package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GitHubUpdatesTest {
    private val tag = "v0.2.6-rajput"
    private fun release() = JSONObject().put("tag_name", tag).put("body", "Release notes")
        .put("assets", JSONArray().put(asset(GitHubUpdates.APK_ASSET)).put(asset(GitHubUpdates.MANIFEST_ASSET)))
    private fun asset(name: String) = JSONObject().put("name", name).put("state", "uploaded").put("size", 100)
        .put("browser_download_url", "https://github.com/${GitHubUpdates.REPOSITORY}/releases/download/$tag/$name")
    private fun manifest() = JSONObject().put("schema", 1).put("packageName", GitHubUpdates.UPDATE_PACKAGE)
        .put("versionCode", 12).put("versionName", "0.2.6-rajput").put("sha256", "a".repeat(64))
    private fun rejects(action: () -> Unit) { try { action(); fail("Unsafe update accepted") } catch (_: IllegalStateException) {} }

    @Test fun `only newer compatible stable release is offered`() {
        val candidate = checkNotNull(GitHubUpdates.candidate(release()))
        assertEquals("Release notes", candidate.notes)
        assertNotNull(GitHubUpdates.manifest(manifest(), candidate, 11))
        assertNull(GitHubUpdates.manifest(manifest(), candidate, 12))
        assertNull(GitHubUpdates.manifest(manifest(), candidate, 13))
        assertNull(GitHubUpdates.candidate(release().put("draft", true)))
        assertNull(GitHubUpdates.candidate(release().put("prerelease", true)))
        assertNull(GitHubUpdates.candidate(release().put("assets", JSONArray())))
    }
    @Test fun `manifest must match package version release tag and checksum format`() {
        val candidate = checkNotNull(GitHubUpdates.candidate(release()))
        rejects { GitHubUpdates.manifest(manifest().put("packageName", "dev.pov.tv.offcloud"), candidate, 11) }
        rejects { GitHubUpdates.manifest(manifest().put("versionName", "0.2.7-rajput"), candidate, 11) }
        rejects { GitHubUpdates.manifest(manifest().put("sha256", "bad"), candidate, 11) }
        rejects { GitHubUpdates.manifest(manifest().put("schema", 2), candidate, 11) }
    }
    @Test fun `assets must belong to this repository and have bounded sizes`() {
        val wrong = release()
        wrong.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.org/update.apk")
        rejects { GitHubUpdates.candidate(wrong) }
        val enormous = release()
        enormous.getJSONArray("assets").getJSONObject(0).put("size", Long.MAX_VALUE)
        rejects { GitHubUpdates.candidate(enormous) }
        val duplicate = release()
        duplicate.getJSONArray("assets").put(asset(GitHubUpdates.APK_ASSET))
        assertNull(GitHubUpdates.candidate(duplicate))
    }
    @Test fun `download redirects permit only secure GitHub asset hosts`() {
        assertTrue(GitHubUpdates.allowedDownload(URL("https://release-assets.githubusercontent.com/asset?signature=public-test")))
        listOf("http://github.com/a", "https://github.com.evil.test/a", "https://user@github.com/a",
            "https://github.com:8443/a", "https://github.com/a#fragment", "https://example.org/a").forEach {
            assertFalse(it, GitHubUpdates.allowedDownload(URL(it)))
        }
    }
}
