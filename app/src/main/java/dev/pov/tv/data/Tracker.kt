package dev.pov.tv.data

/**
 * A watch-tracking service: WeTrakr or Simkl. One is chosen in Settings and supplies Home's Continue Watching,
 * watchlists and Up Next, receives scrobbles, and is the target of watchlist and watched actions.
 */
interface Tracker {
    val name: String
    /** Personal and liked lists. Simkl has no list API yet, so its screens hide them. */
    val supportsLists: Boolean get() = true

    fun connected(): Boolean
    /** Changes whenever the linked account changes, so cached rows and queued scrobbles never cross accounts. */
    fun accountRevision(): String
    fun disconnect()
    fun startDeviceAuth(): DeviceAuth
    fun pollDeviceAuth(auth: DeviceAuth): Boolean
    fun cancelDeviceAuth(auth: DeviceAuth? = null)

    fun scrobblingEnabled(): Boolean
    fun scrobbleRevision(): String
    fun setScrobblingEnabled(enabled: Boolean)
    /** A deliberate new play lifts the block set by [removeContinueWatching]. */
    fun allowPlayback(identity: PlaybackIdentity)
    fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String,
                           expectedScrobbleRevision: String = scrobbleRevision()): Boolean
    /** Replays a queued pause/stop saved while offline; [timestamp] is when it happened. */
    fun restoreProgress(identity: PlaybackIdentity, percent: Double, action: String, timestamp: String, revision: String,
                        expectedScrobbleRevision: String = scrobbleRevision()): Boolean

    fun continueWatching(): List<Pair<PlaybackIdentity, Double>>
    fun removeContinueWatching(identity: PlaybackIdentity, revision: String = accountRevision())
    fun nextEpisodes(): List<PlaybackIdentity>
    fun watchlist(type: String): List<MediaItemData>
    fun setWatchlist(identity: PlaybackIdentity, add: Boolean)
    fun markWatched(identity: PlaybackIdentity)

    fun lists(): List<Pair<String, String>>
    fun listItems(id: String): List<MediaItemData>
    fun removeListItem(listId: String, identity: PlaybackIdentity)
    fun likedLists(): List<Pair<String, String>>
    fun likedListItems(id: String): List<MediaItemData>
    fun likedListPreview(id: String): List<MediaItemData>
}

/** The tracker chosen in Settings. WeTrakr stays the default for existing installs. */
object Trackers {
    const val KEY = "tracker.service"
    const val SIMKL = "simkl"
    const val WETRAKR = "wetrakr"
    fun selected(store: SecureStore) = if (store.get(KEY) == SIMKL) SIMKL else WETRAKR
    fun select(store: SecureStore, id: String) = store.put(KEY, if (id == SIMKL) SIMKL else WETRAKR)
    /** Each service keeps its own offline scrobble queue. */
    fun outboxKey(id: String) = if (id == SIMKL) "simkl.pending" else "wetrakr.pending"
}
