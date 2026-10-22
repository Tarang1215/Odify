# Odify Windows collector

This upgrades the existing Desktop 152-track ZIP. The original CSV and runner
are included with the repository's collection worker, so execution no longer
downloads Python source dynamically.

## One-time connection

1. In project `odify-512404`, enable Drive API, configure External consent, and
   create the Desktop client `Odify Windows rclone`. Download its JSON once.
   Do not enable billing or register a trial.
2. Double-click `install_and_connect.cmd`. It finds that JSON in Downloads;
   an explicit path can also be passed to `setup_drive.ps1 -OAuthClientJson`.
3. Approve Google access yourself in the browser. No interactive `rclone config`
   questions are needed. Do not send passwords or tokens in chat.
4. Use `run_first_2026.bat` for subsequent collection/resume runs.

The batch repairs existing Drive MP3 artwork, requiring the `drive` scope.
Upload-only `drive.file` cannot see files uploaded by the previous client.
The Android app's separate `drive.appdata` configuration is not modified.

External consent in Testing expires Drive refresh grants after seven days.
For a pilot add the personal account as a test user; for ongoing personal use,
switch consent to Production. Google may show an unverified-app warning;
the user handles any approval. Publishing consent does not publish music or
credentials. Personal use exceptions and Google requirements still apply;
this is not a claim of completed Google verification.

## Credentials and repeat runs

Credentials stay under `%LOCALAPPDATA%\Odify\Collector`, outside the ZIP and
repository. Its directory ACL permits the Windows user and SYSTEM. rclone
obscuring is not encryption. The isolated config does not overwrite other
remotes. Existing authorization is checked first; login opens only if needed.
`setup_drive.ps1 -Reconnect` renews it, keeping a private config backup.
`setup_drive.ps1 -CheckOnly` performs a read-only connection check.
`-NoBrowser` prints a local authorization link for an assisted setup.
The launcher sets `RCLONE_CONFIG` and refreshes PATH for installed WinGet tools.
It also finds Python's Scripts directory, so yt-dlp works without a new terminal.
Setup creates the `Odify_Auto_Upload` destination if it is absent, leaving
existing files in `Odify_Music_Test` unchanged.

No background task, GitHub workflow, purchase or billing account is created.
Dependencies install through WinGet/PyPI only if missing. An installer needing
new legal agreements stops for user intervention. The existing collection
behavior, SQLite resume state, and 152-track CSV are preserved.

## Offline verification

    py -m unittest discover -s windows-collector -p "test_*.py" -v
    py windows-collector/run_2026.py --dry-run

Offline tests simulate uploads; they do not prove live Google authorization,
fresh YouTube extraction or collection of all tracks.

References:
- https://rclone.org/drive/#making-your-own-client-id
- https://rclone.org/commands/rclone_config_create/
- https://developers.google.com/workspace/drive/api/guides/limits
