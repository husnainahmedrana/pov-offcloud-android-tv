# POV Rajput 0.2.21

## Faster

- **Home opens instantly.** The app keeps the rows from your last visit and shows them straight away, then refreshes them in the background. New rows replace the saved ones while you are at the top of Home; otherwise they appear on your next visit. Saved rows older than 6 hours, or from a different TMDB key, country or theme, are not used.
- **Posters load about three times faster.** Artwork now downloads over HTTP/2, sharing one connection per image server, eight at a time, with a 40 MB cache on the TV.
- **Cards download less.** The title logo on each card uses a smaller image, cutting artwork data by about a quarter. The feature, details and player keep the large logo.
