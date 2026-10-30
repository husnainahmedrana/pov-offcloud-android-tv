# POV Rajput 0.3.4

## Faster startup

- **The app opens about three times faster** (around 3 seconds instead of 10 on a TV with a long Continue Watching list). Saved settings are now decrypted once instead of on every read.
- Continue Watching shows your 30 most recently paused titles, newest first.

## Continue Watching stays up to date

- **Closing the player refreshes Continue Watching**, so the row shows where you stopped. It updates in place without moving your selection.
- Removing a title from Continue Watching moves the selection to the newer title on its left.

## Bitrate

- **Each link shows its estimated bitrate** (for example ≈18 Mbps) beside its size, worked out from the file size and the title's runtime.
- **Auto-play limit by bitrate.** Settings → Playback → Auto-play limit now has a **Highest bitrate** (25 Mbps by default) for movies and episodes alike, with separate **Movie** (20 GB) and **Episode** (5 GB) size fallbacks for when the runtime isn't known.
