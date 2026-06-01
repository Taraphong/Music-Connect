# Music Connect

Android music player for downloaded audio, with Spotify Connect-style local WiFi control.

## MVP scope

- Plays audio found through Android MediaStore.
- Plays MP4 video files found through Android MediaStore.
- Supports Android 5.0+ (`minSdk 21`).
- Foreground media playback service with notification controls.
- MediaSession integration for headset/lock-screen controls.
- Local WiFi control server on fixed port `33387`, advertised with NSD as `_music-connect._tcp`.
- HTTP endpoints:
  - `GET /` remote control page for browser control
  - `GET /status`
  - `GET /tracks`
  - `GET /artwork?id=12345`
  - `POST /control?action=play|pause|next|previous`
  - `POST /control?action=toggle|stop`
  - `POST /play?index=0`
  - `POST /play?id=12345`
  - `POST /seek?positionMs=30000`
  - `POST /volume?level=0.8`
  - `POST /shuffle`
  - `POST /shuffle?enabled=true`
  - `POST /reorder?from=0&to=1`
  - `POST /sleep?minutes=30`
  - `POST /sleep?action=clear`

For MP3/audio files, the app shows embedded artwork when the file contains it. If artwork is missing, the app and browser remote expose a YouTube search link based on the track title and artist.

## Build

Open this folder in Android Studio and sync Gradle.

The project is pinned to Android Gradle Plugin 8.13.0 because the installed SDK is Android API 36.1. Gradle 8.13 or newer is required.
