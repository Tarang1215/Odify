# Windows collector

Existing collection worker imported from the user's tested local kit. It reads
MASTER snapshots, checks recording identity, tags newly collected audio and
verifies immutable Drive uploads. Existing audio is protected before tagging or
downloading. Removed sources are reviewed through the existing recovery path;
access challenges are not bypassed.

Prefer `tools/v46/odify_library.py` for archiving and synchronizing already-owned
audio. It uses exact Drive folder IDs, per-file digest selections and full remote
verification, with no source deletion or audio tag edits.

For live MASTER refresh, set `ODIFY_MASTER_SHEET_ID` and `RCLONE_CONFIG` privately.
The spreadsheet ID and OAuth credentials are intentionally not embedded in this
repository. An existing CSV can be supplied with `--csv` instead. No new cloud
catalog or competing MASTER is created.

Tests: `python -m unittest discover -s windows-collector -v`.
