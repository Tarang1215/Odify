# Odify V4.6 — 2026-10-24

V4.6 is the Windows verification/archive workflow. The checked-out Android source
started on `feature/odify-v12-master-db`, was updated to remote baseline `8304e79`,
and incorporates the existing compact-navigation work from `afdce92`. Current work
is isolated on `codex/odify-v46-personal-music`. Existing working files were retained.

## Results

- Reused the original V4.6 ZIP from Downloads and all 22 checkpointed observations
  from `D:\Odify\Logs`. Resume made **zero additional extraction requests**.
- Reviewed all 22 against artist identity, title, version and measured duration:
  **6 metadata matches, 13 requiring review, 3 rejected covers/instrumentals**.
  Metadata matches remain unapproved for playback; no MASTER verified flag or
  link was edited. Collaborators missing from metadata remain review cases.
- Loaded the existing Drive catalog of **41,416 recordings** read-only and
  linked all 22 observations to unique recordings in a separate review SQLite DB.
  This catalog export has no `source_candidate` table: candidate-index membership
  is explicitly unavailable, not inferred. The downloaded original is unchanged.
- Copied **552 personal audio files + 198 collector files = 750 files** into
  `D:\Odify\Music\Personal` and `D:\Odify\Music\MASTER`. Every copy passed
  SHA-256 verification. Originals were retained; conflicting files are never
  overwritten. Embedded artwork, lyrics and tags remain byte-identical.
- Reconciled archive SHA-256/size with existing Drive IDs and the connected
  Galaxy S25 Ultra's read-only catalog snapshot: **202 archived files already
  existed on Drive and were indexed on the phone before the requested upload;
  548 were local only**. The initial phone snapshot indexed 213 Drive files.
  A bounded authenticated
  65,536-byte read of an existing Drive MP3 returned a valid ID3 header.
- `ODF000201`: MASTER expects LE SSERAFIM / EASY, but the collected source
  metadata is EASY (English ver.). The archive copy is retained and its selection
  status is `BLOCKED_VERSION_MISMATCH`; it was **not reuploaded**. The old
  collector status `uploaded_verified` alone was insufficient evidence.
- New Drive selections default to `NO`. The user subsequently selected **all
  552 personal audio files** for Drive. The digest-bound selection manifest
  records that scope; the held MASTER recording remains excluded. Transfer
  **552/552 personal files now have verified remote IDs and SHA-256/size checks:
  547 newly uploaded files and 5 identical existing files reused.** A 15-minute
  process limit stopped the first batch after 499 files; resume reused those
  plus the 5 pre-existing files and uploaded only the remaining 48. The batch
  timeout is now 2 hours. Drive reported 8,671,753,609 bytes free after transfer.
  Existing Drive files and sharing are preserved.

## Implementation

`tools/v46/run_v46.ps1` resumes both probe sets and emits a uniquely named identity
report and optional SQLite overlay. `odify_pc_probe_v46.ps1` now uses case-sensitive
YouTube ID comparisons, a 90-second process timeout and preserves manual evidence.

`odify_review.py` keeps extraction availability separate from recording identity.
`odify_probe_overlay.py` records that identity decision in a review-only database.
Neither tool writes MASTER or enables playback approval.

`odify_library.py` implements verified archive copies, digest-bound selection,
immutable Drive transfers, remote size/MD5 verification and SHA-256 reconciliation.
It pins an exact Drive folder ID, reuses identical files already in that folder,
refuses conflicts, traversal and changed selections, and blocks held recordings.
Token/config contents are not printed. Transfer receipts contain observed Drive IDs.

The collector now rejects substring artist/title matches, missing collaborators,
language-version mismatches and altered recordings; it protects existing audio
before downloading or tagging it.

Android requires a verified MASTER video identity before linking owned audio.
It validates exact HTTPS video URLs and refuses ambiguous MASTER matches. Drive
indexing includes the existing named Windows upload root, deduplicates folder
visits and retains file IDs. No Google OAuth scope or signing rule was weakened.

## Repeatable commands

Run from the repository root. Use the installed Python executable if `python`
resolves to the Windows Store alias.

```powershell
# Checkpoint reuse; new review artifacts only. Add -RetryFailures only if needed.
./tools/v46/run_v46.ps1 -Root D:\Odify -Python python `
  -Catalog D:\Odify\Metadata\V46\odify_catalog.sqlite3

# Future archive preview; add --execute to perform verified copies.
python tools/v46/odify_library.py --root D:\Odify archive `
  --source '<existing-music-folder>' --collection Personal

# Refresh selection: new/changed audio resets to NO, existing manifest is backed up.
python tools/v46/odify_library.py --root D:\Odify selection

# Transfer preview for selected=YES rows only. An exact existing folder ID is required.
# Add --execute for immutable transfer with remote checksum readback.
python tools/v46/odify_library.py --root D:\Odify sync `
  --config "$env:LOCALAPPDATA\Odify\Collector\rclone.conf" `
  --folder-id '<existing-folder-id>'

# For a large selection, a single batch uses four concurrent transfers.
# Known inventories from other Odify music folders avoid cross-folder duplicates.
python tools/v46/odify_library.py --root D:\Odify sync --batch `
  --config "$env:LOCALAPPDATA\Odify\Collector\rclone.conf" `
  --folder-id '<existing-folder-id>' --execute
```

Use the Python selection/transfer path for the digest-bound workflow. The original
numbered V4.6 launchers remain available for their original preview/staging tasks.
Local `DriveUpload` staging alone is not a cloud upload.

## Verification and remaining limits

Executed local Python regressions for legacy assembly, collector, V4.6 and retagging;
Windows storage tests exercised dry run, verified copy, resume and conflict refusal.
Android JVM tests: **24 passed**, including URL/identity, recommendation diversity,
offline classification and all 12 decorative seek patterns. Kotlin
application compilation passed. Updated instrumentation source was compiled
separately; it was not executed on the phone.

Local APK packaging correctly stopped because the local certificate differs from
the required fixed SHA-1. The existing GitHub signed workflow has the correct key:
its identity validation passed for build 40073379082. That build is producing
versionCode 16 / preview12.4, newer than installed version 14. No signing guard is
bypassed. Before any device update, a 68,185,088-byte private archive of existing
app files, settings and databases was saved to `D:\Odify\Backups`. Device installation
and UI acceptance results will be recorded after inspecting the signed artifact.

The refreshed remote scripts exposed five Windows file-lock cleanup errors: one
read-only production SQLite context and four test connection handles were not
closed. Those handles were fixed; all **97 existing script tests passed**, along
with 11 legacy assembly tests, 15 collector tests and 14 V4.6 tests. The original
PowerShell storage smoke test passed too. No network requests are used by these
Python regression suites.

Evidence is in `D:\Odify\Logs`, `D:\Odify\Metadata\V46` and the local ignored
`.local/v46` folder. `master_protection_hashes.json` records unchanged MASTER hashes.
An opt-in GitHub workflow, `odify-v46-verify.yml`, runs the Windows regressions; it
has no automatic push schedule and has not been dispatched.
