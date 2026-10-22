# Odify v11 — original-language names and Drive artwork QA

## Verified from actual 15GB Drive test files

- `ODF000003.mp3` (CORTIS — REDRED) has ID3 title, artist and lyrics, but **no APIC image frame**. Its grey note artwork placeholder was expected; Odify cannot extract what is absent.
- `怪獣の花唄.mp3` and `검정치마 - 기다린 만큼, 더 (cover).mp3` both contain JPEG cover data. Their ID3v2.3 APIC frames contain a UTF-16 description plus an extra NUL before `FF D8 FF`. v10 returned the zero-prefixed JPEG, which Android BitmapFactory rejected. V11 normalizes the picture to verified JPEG/PNG magic before decoding.
- Japanese track names may contain both a Korean title and an original Japanese title in parentheses; v11 prefers the original Japanese string when the embedded metadata offers one.
- The verified Korean official release **소연 (SOYEON) — 퇴사할게여 (Narr. 기안84)** has a different English title embedded in the current file. The app maps that precise artist/title match to the official Korean script. Unknown English titles are not blindly machine-translated.

## New behavior

1. First catalog sync extracts bounded audio tags (ID3 MP3 1MiB prefix, M4A 512KiB tail) **before showing the newly loaded song rows**, avoiding short-lived ODF###### placeholders.
2. SQLite v3 migration retries previously failed or missing-art MP3s. Previously generated album art and user-edited song metadata are preserved.
3. For a file **without embedded cover art**, Library → track **⋯ → 앨범커버 직접 지정** opens Android's image picker. Select an image on-device or in Drive, and Odify stores only a scaled thumbnail locally. The original MP3/M4A stays unchanged. Your manual art overrides automated art on later syncs.
4. Track/artist original-language choices are shown from the existing metadata and verified aliases. Under Library → **곡 정보 수정** you can always save your preferred original-language title; user edits win on future syncs.
5. MP3 and M4A duplicates can still be shown using **중복 파일 모두 보기**.

## Install and consent

V11 debug builds side-by-side with v10 under package `com.odify.music.nativepreview.v11.debug`; keep v10 until the Samsung phone playback and album artwork are confirmed. Register this v11 package and the **actual in-app SHA-1** in Google Cloud Android OAuth credentials; do not reuse the previous version's fingerprint. Google Drive API access remains read-only and should not need a paid storage upgrade for this pilot.

## Remaining limits

- The first sync can take longer because it waits for bounded metadata reads before showing the new library.
- For files that have no embedded cover image, there is no guaranteed free external cover source. V11 deliberately avoids scraping unverified/wrong artwork from third-party catalogs; use the explicit picker for such files.
- Original Korean/Japanese names are preferred only if present in source tags/filename or a verified title alias. There is no general automated translation service, so manual correction remains available.
- Device-level UI and playback behavior need real Galaxy QA after successful CI compilation, unit tests and APK signature verification.
