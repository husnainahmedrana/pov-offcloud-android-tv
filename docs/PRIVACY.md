# POV Rajput privacy

POV Rajput is maintained by Rajput, based on Mark Mateo’s Android TV application and the original POV project by kodifitzwell. This policy describes the current source implementation. The app has no developer operated account service, advertising SDK, analytics SDK, or developer telemetry endpoint. It communicates directly with the services below. Those services receive normal connection information, including your public IP address, and apply their own policies.

## Connections and information sent

| Service | Information used and sent | Purpose |
| --- | --- | --- |
| Offcloud (`offcloud.com`) | Your API key, selected torrent hashes/magnets, cloud request IDs and file choices. | Validate your account, check cached sources, obtain direct cached links, and manage transfers explicitly requested in the app. Cached direct playback does not create a cloud transfer. |
| TorBox (`api.torbox.app`) | Device authorization codes or the API key you enter; an account token; cloud folder and file identifiers; torrent hashes; the source you explicitly select. The download link endpoint requires your TorBox token in its HTTPS query. | Link your own account, display its cloud library, check cached sources, add a selected transfer, and obtain playback links. Browsing source results does not add transfers. |
| WeTrakr (`api.wetrakr.com`, `wetrakr.com`) | POV Rajput's public application Client ID (built in); activation codes; OAuth access/refresh tokens; matched movie/show identifiers and episodes; requested watchlist/playlist and watched-state changes; identified playback progress when scrobbling is enabled. | Connect your account, show watchlists, liked lists, Continue Watching, and Up Next, apply manual library changes, and optionally scrobble playback. The app's public Client ID identifies POV Rajput to WeTrakr; no client secret is bundled. |
| Simkl (`api.simkl.com`, `simkl.com`), when chosen instead of WeTrakr | POV Rajput's public Simkl Client ID (built in); activation codes; OAuth access/refresh tokens; matched movie/show IMDb identifiers and episodes; watchlist and watched-state changes; identified playback progress when scrobbling is enabled. | The same features as WeTrakr except personal and liked lists. Requests count against your own Simkl account's quota; no client secret exists for this app type. |
| TMDB (`api.themoviedb.org`, `image.tmdb.org`) | The TMDB API key or read-access token you enter, list requests (Trending, Popular, Now Playing with your chosen cinema country) and TMDB title identifiers. | Show Home catalogs when you add a key, and map TMDB titles to IMDb identifiers. These requests do not include your debrid or WeTrakr account tokens. Without a key, no TMDB request is made. |
| Google speech recognition (the TV's speech service) | Microphone audio while the voice-search screen is open. | Turn spoken titles into search text. The microphone is not used at any other time; Android asks for permission on first use. |
| Cinemeta (`v3-cinemeta.strem.io`, `cinemeta-catalogs.strem.io`) | Catalog/search requests, search text, and IMDb movie/show identifiers. | Discover titles and obtain descriptions, artwork links, and episode lists. These requests do not include your debrid or WeTrakr account tokens. |
| Kitsu (`kitsu.app`) and community Anime Kitsu metadata (`anime-kitsu.strem.fun`) | Anime search text, catalog, relationship and episode requests, and Kitsu identifiers. | Discover anime and verify catalog mappings. These public requests do not include your debrid or WeTrakr account tokens. Playback identification uses verified catalog mappings. |
| Artwork hosts, including `images.metahub.space`, and hosts named in catalog artwork | Artwork URLs and ordinary HTTPS connection information. | Display title artwork. Artwork requests do not deliberately attach your debrid or WeTrakr account tokens. |
| Your enabled scraper/source providers | The requested IMDb title and, for a series, season and episode. Any provider configuration you include in its URL is sent to that provider. | Obtain source choices. POV does not add debrid or WeTrakr authorization headers to these requests. |
| The selected HTTPS playback host | The resolved playback URL, which may itself contain a temporary authorization value, and video requests made by AndroidX Media3. | Stream the source you select. A direct provider URL can stream from its host rather than TorBox. |

Account information is shared with the service it authorizes for the functions above. Viewing progress and watched status are sent to WeTrakr when connected, scrobbling is enabled, and playback has a matched identity. The app does not send your account tokens or viewing history to a Mark Mateo server. The app does not control third party logging, retention, or subsequent use.

## Information kept on the device

- Account and connection settings are stored in the app's private preferences encrypted using AES-GCM with an Android Keystore key. This includes TorBox tokens, Offcloud keys, the TMDB key, WeTrakr tokens, the last WeTrakr Continue Watching list, the last successfully played link (reused for 24 hours) and source provider addresses. The key is not exported by the app. This is not a guarantee against a compromised device or operating system.
- Recent playback keeps up to 40 records containing title, media identity when available, local source key, playback position, and duration. Failed WeTrakr updates are queued locally for retry. **These playback preferences are private to the app but are not additionally encrypted by POV.** They can contain cloud file titles or viewing history.
- The chosen theme is saved as a normal, nonsecret device preference. Catalog/relationship and artwork caches used by this implementation are held in memory. Playback uses normal Media3 buffering; this preview has no offline video download feature.
- The active playback URL is used in memory and the private player Activity's intent. Recent-position records do not include the resolved URL; a separate encrypted 24-hour link cache is described below. Android manages Activity/process state independently of POV.

The manifest sets `android:allowBackup="false"`. It also supplies explicit exclusion rules for all supported app storage domains: credential protected and device protected files, databases, preferences and root storage, plus app specific external storage. On Android 12 and later, both the standard cloud backup and device transfer sections exclude all of this app data. A separate legacy rules file applies on Android 11 and earlier. This covers encrypted account settings, local playback history, pending WeTrakr updates and appearance preferences through the standard Android backup mechanisms. Android documents why device transfer rules are needed in addition to the manifest flag on some manufacturers' devices. [Android backup configuration](https://developer.android.com/identity/data/autobackup), [Android 12 backup changes](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore).

These settings do not control a manufacturer's nonstandard migration tools, debugging access, a compromised or rooted device, operating system behavior outside the standard backup rules, or separately captured screenshots/logs. The preview build is intended for sideloading and may be debug signed; do not treat a development device as a vault.

## App updates

Manual update checks, and optional startup checks when enabled, contact GitHub for this repository’s published releases. Downloading an update contacts GitHub’s release asset servers. Requests contain no Offcloud, TorBox, or WeTrakr credentials or viewing history. GitHub receives normal network information such as your IP address. Update APKs are held in the app’s private cache and shared with Android’s installer through a temporary read grant.

Continue watching removals are stored locally. They hide the selected movie or episode without deleting its resume position or remote WeTrakr history. New playback restores that item’s visibility.

## Removing information

Settings can disconnect Offcloud, TorBox, WeTrakr and Simkl. Disconnecting invalidates in-flight connection attempts; disconnecting WeTrakr also discards unsent WeTrakr updates through the app UI. Disconnecting does not erase your local recent playback list, delete transfers from TorBox, erase remote WeTrakr history, or revoke every authorization held by a remote service. Manage remote access and account history in those services.

Android's app storage controls can clear POV's local app data, and uninstalling normally removes its local preferences. Reinstalling, restoring, and manufacturer behavior can vary. Never share account tokens, activation codes, private cloud/download URLs, or screenshots of personal viewing history in public bug reports.

## Source and release preparation

Release source packaging uses an explicit file allowlist. TV verification captures, inspection material, tooling, local machine settings, runtime app preferences, and private signing keys are excluded. The offline public-artifact checker examines archive entries and detectable strings; it does not read a TV's account data. A passing static check cannot prove the absence of every secret, inspect visual content in images/video, verify third party behavior, or replace review of the exact files being published.

Report a privacy concern through the issue tracker of the repository distributing your build, with a sanitized description. Do not attach credentials or private playback links. Publication remains a separate review action from preparing a local release bundle.

## Playback cache and library artwork

The app stores up to 40 last-successful playback links in the existing encrypted device store for at most 24 hours. Debrid links are bound to the resolving account and ignored after its credentials change. A failed cached link is discarded before fresh source selection. Signed URLs are never placed in public logs or screenshots.

For cloud-library posters, a cleaned title from the filename is sent to Cinemeta’s public search. Exact matches use the returned artwork; ambiguous results remain unmatched. These artwork hints do not assign a tracking identity.

Cinemeta supplies the public New, Featured and Popular shelves without a tracking account. Voice search is handled by the TV’s installed speech-input provider, which processes microphone input under its own settings and permissions.
