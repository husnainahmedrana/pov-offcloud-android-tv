package dev.pov.tv.data

import org.json.JSONObject

data class MediaItemData(
    val imdbId: String,
    val type: String,
    val title: String,
    val year: String = "",
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val runtime: String = "",
    val rating: String = "",
    val episodes: List<EpisodeData> = emptyList()
)

data class EpisodeData(
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String = "",
    val thumbnail: String = "",
    val overview: String = ""
)

data class PlaybackIdentity(
    val imdbId: String,
    val type: String,
    val title: String,
    val season: Int = 0,
    val episode: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imdbId", imdbId).put("type", type).put("title", title)
        .put("season", season).put("episode", episode)

    companion object {
        fun fromJson(json: JSONObject): PlaybackIdentity = PlaybackIdentity(
            json.optString("imdbId"), json.optString("type"), json.optString("title"),
            json.optInt("season"), json.optInt("episode")
        )
    }
}

data class CloudFile(val id: Long, val name: String, val size: Long)
data class CloudFolder(
    val id: Long, val kind: String, val name: String, val ready: Boolean,
    val files: List<CloudFile>
)

data class DeviceAuth(
    val deviceCode: String, val userCode: String, val verificationUrl: String,
    val intervalSeconds: Int, val expiresSeconds: Int
)

data class SourceItem(
    val title: String, val provider: String, val infoHash: String = "",
    val fileIndex: Int? = null, val directUrl: String = "", val cached: Boolean = false,
    val detail: String = "",
    val cachedServices: List<String> = emptyList(),
    val downloadService: String? = null,
    val sizeBytes: Long? = null,
    val qualityHeight: Int? = null,
    /** Request headers the addon requires (Stremio `behaviorHints.proxyHeaders.request`). */
    val headers: Map<String, String> = emptyMap()
)

/** A selected source either streams directly or offers stable TorBox file identities. */
sealed interface PreparedSource {
    val identity: PlaybackIdentity

    data class Direct(val url: String, override val identity: PlaybackIdentity,
                      val headers: Map<String, String> = emptyMap()) : PreparedSource

    data class OffcloudFiles(
        val requestId: String?,
        val files: List<OffcloudFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val accountTag: String
    ) : PreparedSource

    /** Real-Debrid, AllDebrid, Premiumize and Debrid-Link: files whose links are unlocked when one is played. */
    data class Links(
        val service: String,
        val files: List<DebridFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val accountTag: String
    ) : PreparedSource

    data class Files(
        val folder: CloudFolder,
        val files: List<CloudFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val resolvedUrls: Map<Long, String> = emptyMap(),
        val accountTag: String? = null,
        val sourceInfoHash: String? = null
    ) : PreparedSource {
        /** An exact filename can suggest focus, but never hides other available versions. */
        val automaticFile: CloudFile? get() = files.singleOrNull()
    }
}
