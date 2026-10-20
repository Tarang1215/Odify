# Odify

Odify is an Android-first music app developed as a pinned overlay on [SpotiFLAC Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile), whose MIT license must be retained.

**Pinned upstream:** `21ee30c46d405aa72dd650d0cc6e3da0df831c12` (2026-10-19). App ID: `com.odify.music`. Flutter's internal package name remains `spotiflac_android` for compatibility.

## Get the source

```bash
python3 scripts/assemble.py
cd build/Odify
flutter pub get
flutter analyze
flutter test
```

Use the upstream's pinned Flutter, Java, Rust, NDK and Android SDK versions when building. GitHub Actions is NOT enabled for every commit.

## Changes
- One-tap import of Music/Download/Podcasts shared folders into the existing local library, without duplicating files.
- 60-day on-device listening history augments personalized autoplay, using artists and titles; this is additive to SpotiFLAC's learning model.
- Branding: Odify app label, independent Android applicationId.

## Known limitations
- Samsung Music's private database, user likes, listening history and playlists are not readable through ordinary public storage. Import exported M3U/M3U8 playlists using upstream's existing importer.
- Currently covers only common shared folders; full MediaStore indexing for audio outside those folders is future work.
- Separate favorite-song weight, YouTube cover search, yt-dlp streaming and MP3 export are **not implemented in this phase**.
- No Android APK compiled or device-tested yet; preserve upstream LICENSE and third-party license notices.
