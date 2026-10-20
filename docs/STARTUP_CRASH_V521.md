# Odify Android launch crash — 5.2.1 migration and release verification

Updated: 2026-10-20. This document distinguishes **source fixes**, **APK verification**, and **Galaxy device testing**. A successful compilation alone does not prove that an app remains open on the device.

## Reported issue

A signed Odify Release test APK using a custom Flutter overlay over the previous Mornye/SpotiFLAC 5.2.0 core exits immediately when opened on Galaxy S25 Ultra. It also displays upstream forced-update messaging. **No device crash stack trace has yet been shared; therefore the exact original exception cannot be identified conclusively.**

## Changes

1. Pin official Mornye **v5.2.1**, upstream commit `8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f`. This release adds Android startup attachment coordination, imported with the rest of the audited source rather than selectively pasting only a few lines.
2. To avoid vendor drift, Odify **owns a reviewed source snapshot** in branch `vendor/mornye-v5.2.1`, commit `191fd35e1d9cf147a54312453c39e717408d0454`. `scripts/assemble.py` clones **Odify's own repo** and checks out that fixed commit, then verifies `ODIFY_VENDOR_BASE.txt` contains the verified original SHA. A moving tag or upstream main branch can no longer affect builds.
3. `config/upstream_lock.json` and `scripts/assemble_v521.py` require the source snapshot SHA, original version and Odify package IDs. Changing base is a conscious reviewed migration, not automatic.
4. Upstream namespaced `package:mornye` and `com.zarz.mornye`; keep integration seams that add Odify MediaStore, YouTube Discover, artwork, library cards, player queue and Drive sync without copying old 5.2.0 native startup files into the new release.
5. Disable inherited vendor update prompts and upgrade tour during Odify startup. Version `0.9.0+170` and optional future release checks are **Odify-owned**; `ODIFY_ENABLE_UPDATER` defaults off. Vendor updates do not mandate replacing Odify.
6. Added offline test for correct boot-update guard generation and retained immutable-source tests. CI builds ARM64 Debug and Release and verifies signatures, Odify ID/version, embedded Flutter AOT and Mornye Rust ELF libraries. These are static/package tests, **not Android UI smoke tests**.

## Safe Android crash capture (Windows 11 + Galaxy)

The repository has a private-data-aware PowerShell script at `scripts/collect_android_startup.ps1`. It does not reinstall/uninstall Odify or delete music and saves the report **locally**.

1. Install Android SDK Platform Tools (`adb`) and enable USB debugging on the Galaxy; approve the USB authorization.
2. Keep the crashing Odify installed. Run from the root of the repo in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/collect_android_startup.ps1 -Package com.odify.music -WaitSeconds 12
```

For debug package `com.odify.music.debug`, change `-Package` accordingly.
3. The log text is written to the Desktop. Review/redact personal paths, filenames, emails or account information before sharing only the relevant `FATAL EXCEPTION`, `SIGSEGV`, `UnsatisfiedLinkError` or `FlutterJNI` portion.

**Do not delete existing Odify** just to get diagnostic logs. A new CI test-signing certificate may be incompatible with previously installed `com.odify.music` even though package names match. Without a stable private release keystore, in-place upgrades are not guaranteed and deleting the app may erase its internal playlists/likes, cached metadata, and settings. The `com.odify.music.debug` variant is a separate app but likewise has separate storage.

## Acceptance criteria before saying startup is fixed

- The same APK that GitHub reports has passed verified native ELF/signature checks installs on Galaxy S25 Ultra with appropriate Android 16 permissions.
- Launch from launcher: first frame visible, home interactive, no app process death after at least 30 seconds.
- Verify repeat launch after force-stop and device restart, and with/without network connection and empty/large music library.
- Switch Home ↔ Library ↔ Player; play local MP3 with embedded cover; Next/Previous respond without `FATAL EXCEPTION`.
- Distinguish a real fatal crash from deliberate app closure/background restrictions (PID + crash buffer).
- If crash still happens, investigate **actual device stack trace**; do not attribute it to upstream version or rendering without evidence.

## Deferred steps

- A stable, user-controlled release signing key must be configured securely before promising APK-in-place updates. Never commit private keystores, Google OAuth client secrets, access tokens or device logs to the public repository.
- Google OAuth remains unconfigured until the user registers the Odify package and stable signing SHA.
- No Trove/Supabase services are used.
