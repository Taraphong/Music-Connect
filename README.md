# Music Connect

Android music player for downloaded audio, with Spotify Connect-style local WiFi control.

## MVP scope

- Plays audio found through Android MediaStore.
- Plays MP4 video files found through Android MediaStore.
- Supports Android 5.0+ (`minSdk 21`).
- Foreground media playback service with notification controls.
- MediaSession integration for headset/lock-screen controls.
- Local WiFi control server on fixed port `33387`, advertised with NSD as `_music-connect._tcp`.
- Browser remote control over local WiFi.

## Build

Open this folder in Android Studio and sync Gradle.

The project is pinned to Android Gradle Plugin 8.13.0 because the installed SDK is Android API 36.1. Gradle 8.13 or newer is required.
