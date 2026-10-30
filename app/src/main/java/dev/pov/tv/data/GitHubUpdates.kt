package dev.pov.tv.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Public release data only. No provider/account credentials are used by this client. */
class GitHubUpdates(private val context: Context) {
    data class Candidate(val tag: String, val notes: String, val apkUrl: String, val manifestUrl: String, val size: Long)
    data class Release(val candidate: Candidate, val code: Long, val version: String, val sha256: String)
    val apkFile get() = File(context.cacheDir, "updates/POV-Rajput-update.apk")

    fun latest(): Release? {
        val json = try { Net.json("https://api.github.com/repos/$REPOSITORY/releases/latest") }
            catch (error: ApiException) { if (error.code == 404) return null else throw error }
        val candidate = candidate(json) ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        transfer(candidate.manifestUrl, 64 * 1024L) { buffer, count -> bytes.write(buffer, 0, count) }
        return manifest(JSONObject(bytes.toString("UTF-8")), candidate, installedCode())
    }

    fun download(release: Release, progress: (Long, Long) -> Unit): File {
        val file = apkFile
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create update storage." }
        val partial = File(file.parentFile, "download.part")
        try {
            var received = 0L
            var last = 0L
            partial.outputStream().use { output ->
                transfer(release.candidate.apkUrl, MAX_APK) { buffer, count ->
                    output.write(buffer, 0, count); received += count
                    val now = System.nanoTime()
                    if (now - last > 150_000_000L) { progress(received, release.candidate.size); last = now }
                }
            }
            check(received == release.candidate.size) { "The APK download is incomplete. Try again." }
            validate(partial, release)
            if (file.exists()) check(file.delete()) { "Cannot replace the previous update." }
            check(partial.renameTo(file)) { "Cannot save the update." }
            progress(received, received)
            return file
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    fun validate(file: File, release: Release) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(hex(digest.digest()) == release.sha256) { "Update checksum did not match. Download it again." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("The downloaded APK is invalid.")
        check(archive.packageName == context.packageName) { "The update belongs to a different app." }
        check(code(archive) == release.code && archive.versionName == release.version && release.code > code(installed)) {
            "This APK is not a newer compatible version."
        }
        check(archive.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            "Debug APKs cannot be installed through the release updater."
        }
        val currentSigners = signers(installed)
        check(currentSigners.isNotEmpty() && signers(archive) == currentSigners) { "The update signing certificate does not match this app." }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
    @Suppress("DEPRECATION")
    fun installedCode(): Long = code(context.packageManager.getPackageInfo(context.packageName, 0))

    companion object {
        const val REPOSITORY = "husnainahmedrana/pov-rajput-android-tv"
        private const val MAX_APK = 200L * 1024 * 1024
        internal const val APK_ASSET = dev.pov.tv.BuildConfig.UPDATE_APK
        internal const val MANIFEST_ASSET = dev.pov.tv.BuildConfig.UPDATE_MANIFEST
        internal const val UPDATE_PACKAGE = dev.pov.tv.BuildConfig.UPDATE_PACKAGE
        @Suppress("DEPRECATION")
        private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        internal fun candidate(json: JSONObject): Candidate? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?"))) return null
            val assets = json.optJSONArray("assets") ?: return null
            fun asset(name: String): JSONObject? = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .singleOrNull { it.optString("name") == name && it.optString("state") == "uploaded" }
            // The TV and phone apps each read their own APK and manifest from the same release.
            val apk = asset(APK_ASSET) ?: return null
            val manifest = asset(MANIFEST_ASSET) ?: return null
            fun url(asset: JSONObject, name: String): String {
                val expected = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
                check(asset.optString("browser_download_url") == expected) { "Unexpected release asset address." }
                return expected
            }
            val size = apk.optLong("size")
            check(size in 1..MAX_APK) { "Release APK size is invalid." }
            return Candidate(tag, json.optString("body").take(12_000), url(apk, APK_ASSET), url(manifest, MANIFEST_ASSET), size)
        }
        internal fun manifest(json: JSONObject, candidate: Candidate, installed: Long): Release? {
            check(json.optInt("schema") == 1 && json.optString("packageName") == UPDATE_PACKAGE) { "Incompatible update information." }
            val code = json.getLong("versionCode")
            val version = json.getString("versionName")
            val checksum = json.getString("sha256").lowercase(java.util.Locale.ROOT)
            check(candidate.tag == "v$version" && code > 0 && checksum.matches(Regex("[0-9a-f]{64}"))) { "Invalid update information." }
            return if (code > installed) Release(candidate, code, version, checksum) else null
        }
        internal fun allowedDownload(address: URL): Boolean = address.protocol == "https" && address.userInfo == null &&
            address.port in listOf(-1, 443) && address.ref == null && address.host in setOf(
                "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        private fun transfer(address: String, maximum: Long, consume: (ByteArray, Int) -> Unit) {
            var target = URL(address)
            repeat(6) { hop ->
                check(allowedDownload(target)) { "The update download address is not trusted." }
                val connection = target.openConnection() as HttpsURLConnection
                connection.useCaches = false  // release checks and APK downloads always come fresh
                connection.connectTimeout = 15_000; connection.readTimeout = 25_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "POV-Rajput-Updater")
                try {
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        check(hop < 5) { "Too many update download redirects." }
                        target = URL(target, connection.getHeaderField("Location") ?: error("Missing download redirect."))
                    } else {
                        check(status == 200) { "GitHub download failed ($status). Try again later." }
                        check(connection.contentLengthLong <= maximum) { "Update download is too large." }
                        var size = 0L
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val count = input.read(buffer); if (count < 0) break
                                size += count; check(size <= maximum) { "Update download is too large." }
                                consume(buffer, count)
                            }
                        }
                        return
                    }
                } finally { connection.disconnect() }
            }
            error("Update download failed.")
        }
    }
}
