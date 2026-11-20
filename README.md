# Turntable

A minimalist, Material 3 single-row **Spotify home screen widget** and companion configuration app for Android. Built with Google AI Studio, Gemini 4 Neon.

Designed to stay strictly locked to Spotify, Turntable adapts its pastel color palette to your currently playing album art and updates instantaneously from Spotify's media session—without requiring a Spotify API key or login.

---

## Features

- **Spotify-Only Media Sync**: Strictly listens to `com.spotify.music` and `com.spotify.lite`. Completely ignores YouTube, browsers, podcasts, and other media apps.
- **Instant Notification Shade Sync**: Reads the song title, artist, album, play/pause state, and decoded album artwork directly from Spotify's active `MediaSession` and notification shade with zero network delay (with Device Broadcast Status as a fallback).
- **Dynamic Material 3 Pastel Colors**: Automatically extracts a soft, contrast-checked pastel palette from each track's cover art, with manual studio presets (**Album Pastel**, **Obsidian**, **Warm Vinyl**, and **Studio Ivory**).
- **Single-Row Adaptive Layout**:
  - Horizontally resizable strictly between **4×1** and **5×1** (height locked to 1 row, rounded corners).
  - Rounded album artwork sized cleanly to widget height with balanced margins.
  - Bundled **Google Sans** typography for song title, artist, and badge.
  - Morphing Play/Pause control (capsule pill when paused, rounded squircle when playing) plus Previous/Next buttons targeted exclusively to Spotify.
  - Tap the widget background to jump straight into Spotify; tap the top-right **Spotify** badge to open the Turntable companion app.
- **Smart Idle-Only Caching**:
  - While Spotify is actively playing, live metadata and album art stay strictly in memory and disk cache is automatically cleaned.
  - When Spotify is paused, stopped, or closed, only the last played track's album art, song name, and artist name are cached so the widget retains your last song across restarts.
- **Clean 2-Tab Companion App**:
  - **Studio**: Live widget preview, one-tap widget pinning, color mode picker, footprint info, and optional album subtitle toggle.
  - **Setup**: One-tap Notification Access setup, Live Background Sync toggle, and Device Broadcast Status guide.

---

## Setup on Device

1. Install the app (`com.shady.turntable`).
2. Open **Turntable** and go to the **Setup** tab.
3. Tap **Grant Access** on **Notification Shade Sync** and enable **Turntable** in Android's *Notification access* settings.
4. *(Optional fallback)* In Spotify, open **Settings → Playback / Devices** and enable **Device Broadcast Status**.
5. Switch to the **Studio** tab and tap **Pin Widget to Home Screen** (or add **Turntable Spotify Player** from your launcher's widget picker).

---

## Tech Stack

- **Language**: Kotlin
- **Companion UI**: Jetpack Compose + Material 3
- **Home Screen Widget**: Native `AppWidgetProvider` + `RemoteViews` with custom bitmap text rendering for Google Sans
- **Media & Sync**: `NotificationListenerService`, `MediaSessionManager`, `MediaController.TransportControls`, `BroadcastReceiver`, and Foreground `dataSync` Service
- **Storage**: Jetpack DataStore Preferences + idle-only disk cache (`cacheDir`)
- **Min / Target SDK**: Android 7.0 (API 24) / API 36

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/<your-username>/<your-repo>.git
   cd <your-repo>
