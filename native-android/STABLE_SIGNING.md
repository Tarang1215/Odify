# Odify preview signing: permanent package + permanent SHA-1

**Fixed Android OAuth package**: `com.odify.music.nativepreview.v11.debug`

**Fixed SHA-1 for all subsequent debug APKs**: `65:6D:46:5E:5C:72:9B:DF:21:A4:02:6C:76:4D:FB:63:6F:18:99:E5`

The user requested no more package-ID or SHA-1 changes between APK releases. Both are enforced in CI. Only `versionCode`, `versionName` and APK version labeling should change in future builds.

## One-time manual GitHub secret installation

1. Securely download and retain the dedicated **Odify-Preview-Stable-Signing.keystore** and the matching **Odify-Preview-GitHub-Secret-Value.txt** from the user chat's private download links. Neither file is committed to this repository.
2. GitHub → Tarang1215/Odify → **Settings → Secrets and variables → Actions → New repository secret**.
3. Secret name: **`ODIFY_DEBUG_KEYSTORE_BASE64`**. Secret value: the entire single base64 line in **Odify-Preview-GitHub-Secret-Value.txt**. Save.
4. GitHub Actions → **Odify Independent Native Android Preview** → manually rerun the failed pipeline if necessary. The job now REFUSES to create a random Android debug certificate if the secret is missing; it also compares keystore SHA-1 and the final APK's actual signing SHA-1 with the pinned value.
5. Google Cloud → Android OAuth client (in the **existing** Odify project): package `com.odify.music.nativepreview.v11.debug`, SHA-1 `65:6D:46:5E:5C:72:9B:DF:21:A4:02:6C:76:4D:FB:63:6F:18:99:E5`.

## Existing v11 APK is signed with a different certificate

Existing v11 debug APK `com.odify.music.nativepreview.v11.debug` has SHA-1 `F0:F9:0E:4D:4F:18:79:2E:C7:52:60:23:BC:95:CF:50:E7:D4:40:F5`. This was a one-off debug certificate from GitHub runner; its private signing key is unavailable. **It is cryptographically impossible to use that public SHA-1 to generate the same original private key.** The permanent key above therefore replaces it ONCE.

The next permanently signed build uses the **same package name but a different certificate**; Android refuses to install it as an in-place update. Keep backups of relevant app data, and uninstall the existing v11 preview before installing the first stable-signed APK. The other previous side-by-side v10/v9 apps are unaffected. Thereafter all future builds use the exact same package + signing key and support in-place upgrades.

Never commit the .keystore or base64 secret to GitHub, put either in CI logs, or publish them. Keep an offline backup. This signing key is for personal DEBUG previews only, not production Play Store releases.

## Requirements for future AI/coding updates
- Never modify `defaultConfig.applicationId` or `buildTypes.debug.applicationIdSuffix`.
- Never remove required signing secret validation or the signed-certificate check.
- For subsequent versions, increment versionCode/Name and keep package and signer unchanged.
- If the secret is missing or wrong, CI fails fast by design. Do not bypass this by regenerating a debug keystore.
