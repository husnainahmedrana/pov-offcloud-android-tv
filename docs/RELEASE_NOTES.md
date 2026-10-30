# POV Rajput 0.3.3

## Simkl support

- **Choose WeTrakr or Simkl** in **Settings → Tracking → Service**. The menu, Home's Continue Watching, Up Next and watchlists follow your choice, and each service keeps its own sign-in.
- **Sign in with a code or QR**, no setup: POV Rajput has its own registered Simkl app built in. Scanning the QR code is enough, since it already includes the code.
- Simkl gets scrobbling (progress while you watch; finishing 90% or more marks it watched), watchlist add/remove, mark watched and offline progress replay.
- Simkl has no API for personal or liked lists yet, so those stay WeTrakr-only. Removing a title from your Simkl watchlist only works for titles you haven't watched, because Simkl's removal would also erase watch history.

## Watched indicators

- **A ✓ badge on everything you've watched**: movie and show cards, Top 10 posters and each watched episode in the episode list. It works with WeTrakr and Simkl.
- Marking something watched shows its badge at once, and a finished video gets its badge a few seconds after you close the player.
- Your watched history is saved on the device, so badges appear instantly; the app only downloads it again when WeTrakr or Simkl reports a change.

## Fixes

- **Signing in on the phone** (WeTrakr or Simkl) now works when you approve the code in the phone's own browser: the app keeps checking while you're away and checks again the moment you come back.
- Switching between WeTrakr and Simkl in Settings no longer jumps back to the top of the page.

## Flix only

- The Editorial theme has been retired; everyone now uses the Flix look. The Theme setting is gone, and Editorial users open in Flix automatically.
