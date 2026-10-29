# Sonora for Android

An unofficial Android port of [Sonora](https://github.com/sonorahq/sonora), the native music
streaming client built with Rust and GPUI.

GPUI doesn't run on Android, so this port keeps Sonora's Rust engine — the `music` crate that does
the streaming, decoding and playback — and puts a native Kotlin/Jetpack Compose app on top of it.

## Download

Every push to `main` builds an APK on GitHub Actions and publishes it under
[Releases → Latest build](../../releases/tag/latest). It runs on arm64 phones with Android 8.0 or
newer.

## Sources

| Source | Sign-in | Notes |
| --- | --- | --- |
| Local files | Pick music folders | Needs the "Music and audio" permission |
| Subsonic / Navidrome | Server, username, password | Plain `http://` servers on your network work too |
| YouTube Music | Guest, or Google sign-in in a WebView | Guest mode has no library |
| Spotify | Browser login | Needs Spotify Premium (librespot) |

Apple Music, Tidal and Deezer from the desktop app are left out: they depend on DRM that isn't
available to this engine on Android.

## How it's put together

```
core/            Rust bridge (UniFFI) over Sonora's engine: sign-in, browse, search, playback
vendor/          Sonora's music, storage and widevine crates, copied from upstream
bindgen/         uniffi-bindgen, which generates the Kotlin bindings at build time
android/         Kotlin/Compose app, media session service, notification controls
```

- Audio comes straight out of Rust through `cpal`'s AAudio backend.
- A media3 `MediaSessionService` mirrors the player so Android shows the notification,
  lock-screen and headset controls, and keeps playback alive in the background.
- The queue, shuffle, repeat and radio autoplay live in Kotlin (`PlayerController`).

Vendored from Sonora at commit `b525b2a702ed81035a2439c29da1ca8158a8ee21`.

## Building locally

You need the Android SDK and NDK, Rust (the version in `rust-toolchain.toml`) with the
`aarch64-linux-android` target, `cargo-ndk`, and Gradle 8.11.

```sh
cargo ndk -t arm64-v8a --platform 26 -o android/app/src/main/jniLibs build --release -p sonora-core
cargo run -p uniffi-bindgen -- generate --library target/aarch64-linux-android/release/libsonora_core.so \
  --language kotlin --no-format --out-dir android/app/src/main/java
gradle -p android assembleRelease
```

The workflow in `.github/workflows/android.yml` does exactly this.

The APK is signed with the key in `android/app/sonora.keystore` so each build installs over the
last one. It's a convenience key for personal builds; don't use it for anything you publish.

## License

GPL-3.0-or-later, like Sonora. See `COPYING`. Not affiliated with Spotify, Google, or the services
it plays from.
