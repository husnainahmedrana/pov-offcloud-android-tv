# POV Rajput 0.2.26

## Works without a debrid service

- **Addons are searched even with no debrid service connected.** Previously the sources page stopped at "Connect a debrid service", so direct-link (HTTP) addons were never asked for links. Without debrid, the page now lists the direct links your addons return. Torrent sources still need a debrid service and are left out; if only torrents are found, the page says so.
- **Plain HTTP direct links are accepted.** Addon links starting with `http://` used to be dropped; they now appear and play. Debrid links remain HTTPS-only.
