# POV Rajput 0.3.6

## Fix

- **More links play with sound, on phones and TVs.** Links whose audio is Dolby Digital (DD), Dolby Digital Plus (DD+), DTS or TrueHD failed on devices without a decoder for that format, which is most phones and many TVs for DTS and TrueHD. The player now decodes these in software with FFmpeg when the device can't. Your device's own decoders and passthrough to a soundbar or receiver are still tried first, so links that already played behave exactly as before.

Thanks to the FFmpeg project and Jellyfin's prebuilt Media3 FFmpeg decoder.
