# POV Rajput for Android TV

### By Rajput

[![Android APKs](https://github.com/husnainahmedrana/pov-rajput-android-tv/actions/workflows/apks.yml/badge.svg)](https://github.com/husnainahmedrana/pov-rajput-android-tv/actions/workflows/apks.yml)
[![Latest release](https://img.shields.io/github/v/release/husnainahmedrana/pov-rajput-android-tv?label=release)](https://github.com/husnainahmedrana/pov-rajput-android-tv/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Android TV 8.0+](https://img.shields.io/badge/Android%20TV-8.0%2B-3DDC84)

**A native Android TV app for streaming your Offcloud and TorBox cloud, with a cinematic *Flix* interface, TMDB or Cinemeta catalogs, WeTrakr tracking and a fast Media3 player.** Kodi is not required.

![Flix home: a living backdrop, rotating feature and Top 10 shelves](docs/screenshots/home.jpg)

**[⬇ Download the latest APK](https://github.com/husnainahmedrana/pov-rajput-android-tv/releases/latest)** · [Setup guide](docs/OFFCLOUD.md) · [Release notes](docs/RELEASE_NOTES.md) · [Build and release](docs/BUILD_AND_RELEASE.md)

This is **Rajput's Android TV edition**, forked from [MM-482/pov-android-tv](https://github.com/MM-482/pov-android-tv). The original Android TV port was created by **Mark Mateo**, based on the original POV Kodi add-on by **[kodifitzwell](https://github.com/kodifitzwell/repo)**. Their work, attribution and GPL-3.0 license are preserved.

## Highlights

- **Flix interface** — a living backdrop that follows whatever you focus, title logos, a feature that rotates through the Top 10 every five seconds, numbered Top 10 shelves and a menu that stays hidden until you need it.
- **Smooth on modest TV hardware** — shelves draw only what is on screen, artwork is sized to its card and decoded off the UI thread, and the backdrop is a single pre-shaded image. Measured on an onn 4K box, janky frames fell from 55% to under 10%.
- **TMDB or Cinemeta catalogs** — add your own free TMDB key for Trending, Popular and *Now Playing in Cinemas* (in your chosen country) movies, plus Trending and Popular shows. Without a key, Home uses Cinemeta.
- **Play that just works** — Play searches every enabled source provider, checks your debrid caches in parallel and starts the best playable link, resuming where you left off. Continue Watching resumes instantly from the last link (kept 24 hours).
- **A player built for the remote** — one-second precision seeking that accelerates the longer you hold, an *Ends at* clock, a Stremio-style loading splash, Stop and Browse, audio and subtitle menus and volume boost.
- **Sensible defaults** — bold subtitles on a transparent background, 3% above the bottom, English subtitles and audio.
- **Your library as posters** — Offcloud and TorBox downloads appear as artwork cards matched from their filenames.
- **Voice search** — speak a title; the words appear as you talk and results open when you stop.
- **Private by design** — every key, token and Client ID is entered on the TV and stored with Android Keystore encryption. Nothing personal is bundled in the source or APK.

## Screenshots

Captured on a 55" TV in the app's public preview mode, which shows public catalogs only.

| Shelves follow your focus | Movie details |
| --- | --- |
| ![Focused shelf with the feature showing the focused title](docs/screenshots/home-shelves.jpg) | ![Movie details pinned to the bottom with the backdrop open above](docs/screenshots/details-movie.jpg) |

| Show details and episodes | Hidden menu |
| --- | --- |
| ![Show details with IMDb badge, genre chips, season tabs and episode stills](docs/screenshots/details-show.jpg) | ![Menu opened from the left edge over Home](docs/screenshots/menu.jpg) |

| Search | Settings |
| --- | --- |
| ![Full-screen search with voice search](docs/screenshots/search.jpg) | ![Settings grouped into cards with values on the right](docs/screenshots/settings.jpg) |

| Playback, subtitles and services | Themes |
| --- | --- |
| ![Playback, subtitle, audio and streaming-service settings](docs/screenshots/settings-services.jpg) | ![Flix and Editorial themes](docs/screenshots/appearance.jpg) |

![Animated POV launch splash](docs/screenshots/splash.jpg)

## Getting started

1. Download `POV-Rajput-release.apk` from [Releases](https://github.com/husnainahmedrana/pov-rajput-android-tv/releases/latest) and sideload it on Android TV 8.0 or later. The launcher name is **POV Rajput** (package `dev.pov.tv.rajput`).
2. **Settings → Debrid Services**: connect **Offcloud**, **TorBox** (sign in with a code or paste an API key), **Real-Debrid**, **AllDebrid**, **Premiumize** or **Debrid-Link** with your own API keys. Real-Debrid, AllDebrid and Debrid-Link have no up-front cache check, so the app tries the first 7 links when you play and plays the first cached one.
3. **Settings → Debrid Services → Source providers**: add your preferred HTTPS Stremio-compatible add-ons, each with a name you choose.
4. *Optional* — **Settings → Home & appearance → TMDB**: paste your free TMDB API key or read-access token from [themoviedb.org](https://www.themoviedb.org/settings/api), then pick a **Cinema country**.
5. *Optional* — **Settings → WeTrakr**: enter your own registered Client ID and approve the activation code at **https://wetrakr.com/activate**.

No API key, token, Client ID or account is included in the app. Every value you enter stays encrypted on your TV.

## Using the app

### Home and navigation

- The menu is hidden. Press **Left** from the first item of a row (or **Back**) to open it; it stops at its first and last entries.
- From any menu page, **Back** returns to Home with the menu open; **Back** again asks to leave the app.
- **Hold Back for one second** anywhere — even during playback — to open **Settings**.
- Returning to Home from the menu is instant: the page you left is kept for 15 minutes unless something that affects it changes.
- Rows end at **See all**, which opens a four-column grid of the shelf.

### Playing

| Where you start | What happens |
| --- | --- |
| **Continue Watching** on Home | Resumes instantly from the last link, when it is less than 24 hours old |
| **Play** anywhere else (details, feature, any shelf) | Searches fresh links, plays the first playable one and resumes your saved position |
| Long-press a Continue Watching card | **Open Media** or **Remove from Continue Watching** |

With **Auto-play first result** on (Settings → Playback), Play starts the best cached or direct link in your sort order; the list stays open behind it so another link is one Back away. Sources above your TV's decoder limit (for example 8K on a 4K TV) are labelled, moved last and never auto-played.

### Player

- **Seeking** — tap Left/Right for one second. Hold to keep going: one-second steps for the first minute, then 10 s, 30 s, 1 min and 2 min jumps. The preview shows the target time and offset; the video jumps when you release.
- **Ends at** — the local finish time sits in the top-right corner with the controls.
- **Loading and buffering** — the title's logo pulses over its blurred artwork, like Stremio.
- **Stop** ends playback and returns; **Browse** keeps the video playing in the mini player.
- **Audio, Subtitles and Boost** — choose tracks, subtitle style, size and position, and boost quiet audio.

### Search and voice

Open **Search** from the menu, type with the on-screen keyboard (Enter searches straight away), or choose **Voice search**. Voice search listens inside the app with the TV's speech service, shows your words as you speak and searches when you stop. Android asks for microphone access the first time.

### Library

**Library** shows your Offcloud and TorBox downloads as poster cards, with artwork matched conservatively from each filename. Artwork matches never become tracking identities without your confirmation.

## Cached playback and debrid

All connected debrid services are checked together, with cache lookups batched and run in parallel. A source cached on both uses your **Debrid priority** order; a source cached only on the other service plays through it automatically. Cached failures never silently start an uncached download, and uncached sources always ask before adding a cloud download.

Source cards show quality, provider-reported size and release badges (DV, HDR, WEB-DL, REMUX, codecs). **Source sorting** orders by quality and then size; unknown values appear last.

Offcloud cached sources resolve directly without adding a dashboard entry. TorBox resolves direct links and removes newly created cached entries before playback.

## WeTrakr

Enter your own registered Client ID, approve the code at **wetrakr.com/activate**, and WeTrakr supplies Home's Continue Watching (shown instantly from your last visit, then refreshed), watchlists, liked lists and Up Next. **Scrobbling** and **Liked lists on Home** are switches in Settings. Tokens are stored with Android Keystore encryption; library reads are cached and server retry limits are respected. API reference: [api.wetrakr.com](https://api.wetrakr.com/).

## Updates

**Settings → Updates → Check for updates** downloads published stable releases from this repository, verifying the checksum, package, version and signing certificate. Accounts and settings are kept. **Check on startup** is a switch and defaults to off.

## Privacy

- API keys (Offcloud, TorBox, TMDB), the WeTrakr Client ID and tokens, and source-provider addresses are entered on the TV and encrypted with Android Keystore.
- Nothing personal is included in the repository or release APKs.
- The microphone is used only while the voice-search screen is open.

See [PRIVACY.md](docs/PRIVACY.md).

## Building and releases

Pushes to `main`, pull requests and manual runs build APKs after unit tests and Android lint. Tags such as `v0.2.14-rajput` publish the **signed release APK**, checksums, update metadata, license and release notes automatically. Signing uses private GitHub Actions secrets; the key is never committed. Developers can install a debug build beside the release with `-PpovAppIdSuffix=.preview`. See [build and release instructions](docs/BUILD_AND_RELEASE.md).

## Source and acknowledgments

**Thank you to Mark Mateo for creating and sharing the standalone Android TV application that makes this fork possible.** His work provides this edition's core TV foundation, remote navigation, catalog and anime browsing, Media3 playback and the original TorBox and Trakt integrations. Rajput's edition builds on that foundation; it does not claim authorship of the original Android TV application.

- **Rajput** — this edition: the Flix interface, performance work, Offcloud, WeTrakr and TMDB integrations, playback and subtitle improvements, branding and release automation.
- **Mark Mateo / MM-482** — original standalone Android TV application. [Upstream source](https://github.com/MM-482/pov-android-tv); fork base [`c5a64e9`](https://github.com/MM-482/pov-android-tv/commit/c5a64e92232fa90889507114721e736c37450f2d), with upstream history retained.
- **kodifitzwell** — original POV Kodi add-on and POV visual identity. [Original project](https://github.com/kodifitzwell/repo).
- **[WeTrakr](https://wetrakr.com/) and [alberdev](https://www.reddit.com/user/alberdev/), its developer** — tracking service, API, official logo and developer access that made this integration possible.
- **[TMDB](https://www.themoviedb.org/)** — this product uses the TMDB API but is not endorsed or certified by TMDB.
- **Cinemeta**, **Kitsu**, **Android Open Source Project / AndroidX Media3** and **JetBrains / Kotlin**.

This project is not endorsed by Offcloud, TorBox, WeTrakr, TMDB, Trakt, Kodi or the upstream authors. Product names and artwork remain with their respective owners. See the in-app open-source notices and the [original project documentation](docs/UPSTREAM_README.md).

## License

GNU General Public License version 3. See [LICENSE](LICENSE). Source accompanies each release through this repository and GitHub's source archives. Original notices and credits are retained.
