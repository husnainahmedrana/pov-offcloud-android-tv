package dev.pov.tv

import android.content.Context
import android.util.Log
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Posters, backdrops and logos. HTTP/2 multiplexes every image to one server over a single connection, so a
 * screen of cards waits for the server once instead of once per card, and the disk cache serves repeat visits.
 * Account and catalog requests keep using [dev.pov.tv.data.Net]; nothing with credentials goes through here.
 */
internal object ArtworkClient {
    private const val CACHE_BYTES = 40L * 1024 * 1024
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var logTimings = false

    fun init(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            // The previous HttpURLConnection cache is no longer read; free its space on small TV storage.
            File(context.cacheDir, "artwork").takeIf { it.exists() }?.deleteRecursively()
            logTimings = context.packageName.endsWith(".preview")
            client = OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "artwork-http"), CACHE_BYTES))
                .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 8 })
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun bytes(address: String): ByteArray {
        val http = checkNotNull(client) { "Artwork client is not ready." }
        require(address.startsWith("https://")) { "Artwork must use HTTPS." }
        val started = System.nanoTime()
        http.newCall(Request.Builder().url(address).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Artwork request failed (${response.code}).")
            val bytes = response.body?.bytes() ?: throw IOException("Artwork response was empty.")
            if (logTimings) Log.d("PovArtwork", "${(System.nanoTime() - started) / 1_000_000}ms " +
                "${response.protocol} ${if (response.cacheResponse != null && response.networkResponse == null) "disk" else "net"} ${bytes.size / 1024}KB " +
                "${response.request.url.host} ${response.request.url.pathSegments.take(3).joinToString("/")}")
            return bytes
        }
    }
}
