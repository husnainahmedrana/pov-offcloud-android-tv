# POV Rajput 0.2.25

## Playback fixes

- **Direct links that redirect now play.** Links that redirect between HTTPS and HTTP (common with file hosts) stopped with `ERROR_CODE_IO_BAD_HTTP_STATUS`. The player now follows them, like Stremio does.
- **Sources that need request headers now appear and play.** Some addons send streams that only work with a Referer, cookie or User-Agent (Stremio passes these through its local proxy). POV used to hide these sources; it now lists them and sends the headers itself.
- Header-bound links have short-lived cookies, so they are not reused by "resume last stream". That title searches for fresh sources instead.
