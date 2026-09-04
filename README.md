# Spoty

Spoty is an Android Spotify client built with Jetpack Compose and Material 3 on top of a Rust
[librespot](https://github.com/librespot-org/librespot) core bridged through JNI. The Rust side
handles authentication, Spotify Connect and audio streaming; the Kotlin side owns the UI, playback
service, widgets and settings. The interface is Spanish-first with an English fallback.

> [!NOTE]
> Spoty is not affiliated with Spotify, Google or librespot. Using a third-party client may be
> against the Spotify Terms of Service. Use at your own risk.

## Requirements

- A Spotify **Premium** account (free accounts cannot stream through librespot).
- Android 8.0 (API 26) or newer, arm64-v8a or armeabi-v7a.

## Features

- Playback of tracks, albums, playlists, artists, podcasts and your library, with queue and radio.
- Acts as a Spotify Connect device, so other Spotify apps can hand playback over to it.
- Synced lyrics in the full-screen player.
- Home screen widgets for playback control and quick access.
- Backup and restore of preferences, gestures and queues.
- Dynamic Material 3 theming that follows the system palette.

## Building

Prerequisites:

- Android Studio with the Android SDK, platform-tools and NDK r29 (`29.0.13113456`).
- JDK 21 in `JAVA_HOME`.
- Rust toolchain (1.90.0) via [rustup](https://rustup.rs/), plus `cargo-ndk` and the Android targets:

  ```bash
  cargo install cargo-ndk
  rustup target add aarch64-linux-android armv7-linux-androideabi
  ```

- `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME` set in the environment.
- Spotify app credentials from the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard/create)
  with redirect URI `http://127.0.0.1:5588/account/login`, copied into `keystore.properties`
  (see `keystore.properties.example`).

Steps:

1. Clone the repository with submodules: `git clone --recurse-submodules <repo-url>`.
2. Build the Rust core. Without it the app will not start.
   - Linux / WSL2: `./buildLibrespot.sh`
   - Windows: `Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process; .\build-librespot.ps1`
   - Manual: run `cargo ndk -t arm64-v8a --platform 21 build --release` (and `-t armeabi-v7a`) inside
     `rust/librespot-ffi`, then copy each `liblibrespot_ffi.so` into `app/src/main/jniLibs/<abi>/`.
3. Build the app from Android Studio or with `./gradlew assembleDebug`.

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for module details and troubleshooting.

## License

Spoty is released under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full text.
