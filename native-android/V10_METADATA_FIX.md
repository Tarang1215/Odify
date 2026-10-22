# Odify v10 — Drive music metadata & duplicate display fix

## Why tracks showed ODF000001 / ODF000004
The auto-upload pipeline uses stable `ODF######` filenames. Odify v9 indexed **filenames** as track titles even though the audio itself already contained title, artist, album, artwork and in some files lyrics.

## Fix
- Download **only a bounded 1MiB MP3 prefix** for ID3v2 `TIT2 / TPE1 / TALB / TCON / APIC / USLT / TXXX`.
- For M4A/MP4, request a **512KiB suffix Range** because `moov/ilst` often lives at EOF. Parse `©nam`, `©ART`, `©alb`, `©gen`, `©lyr`, `covr`. Fall back to the prefix if needed.
- Store extracted title/artist/album/genre/lyrics and a scaled JPEG cover in local SQLite. Never copy the whole song into the offline library for metadata extraction.
- After sync, hydrate four songs at a time with progressive list refresh. On selecting a Drive track, prioritize that track.
- Keep user-edited title/artist/album intact on future syncs, and re-read tags if the underlying Drive file modification timestamp changes.
- Filename fallback strips `ODF###### - ` when a descriptive title is present. Unknown `ODF######.mp3` files still show the ID until tags are successfully read; we do not guess a title.
- Ignore `Odify_Auto_Upload` as a genre. Prefer embedded genre; otherwise recognize only actual genre folder names.
- Collapse identical titles by the same artist in Home, Search and Library by default, without deleting any Drive files. Search/Library provide **중복 포함 보기** to restore alternate files. Distinct artists and song titles remain separate.
- Also include embedded lyrics in local search where present. Lyrics are processed locally and are not sent to any third-party search service.

## Upgrading from v9
V10 is a separate debug package, `com.odify.music.nativepreview.v10.debug`, so v9 data and its APK remain safe. Because CI debug-signing fingerprints may differ, register this exact package and the newly installed APK's actual SHA-1 (shown in Profile) as an Android OAuth client in the same Google Cloud project. No new API or billing resource is needed.

If you want seamless updates without registering new OAuth clients each preview, set a permanent private Android debug signing keystore using the GitHub Actions `ODIFY_DEBUG_KEYSTORE_BASE64` secret (as documented in the v8 setup). Never upload a private signing key to a public repository or chat.

## Validation
- JVM unit tests verify tag parsing and filename fallback.
- GitHub Actions compiles and signs v10 APK.
- Samsung phone Drive OAuth, actual playback and cover display still require on-device field QA.
