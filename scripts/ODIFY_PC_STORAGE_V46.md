# Odify V4.6: Windows external storage and safe PC link verification

Scope: user's external Toshiba volume **D:\Odify** (exFAT, ~742 GB free as pictured on 2026-10-24). This is user-side path only; GitHub Actions cannot browse or write it.

## Windows deployment and local status

The user has run the one-time folder-creation PowerShell command, not necessarily the new probe. The downloadable V4.6 local kit consists of:
- \`01_probe_12.cmd\`: KR4 + JP4 + US4 existing candidate IDs, metadata extraction with current yt-dlp; no cookies/media download; checkpoints per result into \`D:\Odify\Logs\odify_probe_12_results.csv\`.
- \`02_probe_10_pairs.cmd\`: five previously disputed recordings with before/after candidate links; \`D:\Odify\Logs\odify_probe_pairs_results.csv\`.
- \`03_check_D_drive.cmd\`: inventory and volume capacity into \`D:\Odify\Logs\odify_storage_inventory.csv\`.
- \`04_make_drive_selection.cmd\`: choose which local Music files should have a **separate copy** staged for optional future Drive upload; new entries default \`selected=NO\`.
- \`05_preview_drive_stage.cmd\`: stage dry-run only, SHA256 comparisons.
- \`06_execute_drive_stage.cmd\`: explicit \`YES\` confirmation, bounded 200 files, SHA256 verified copies to \`D:\Odify\DriveUpload\`; does not upload to Google Drive.
- \`07_existing_music_preview.cmd\`: copies nothing, previews existing Desktop/YouTube_MP3 migration.
- \`08_probe_summary.cmd\`: summarizes observation results, does not approve a YouTube recording.

The separate PC ZIP is delivered in the chat, not automatically installed to user's D: or built as an APK. It includes readme, source PowerShell scripts, KR/JP/US input CSVs, and original risk review queue.

**External D drive is not cloud:** It remains unplugged/offline when unavailable, and Google Drive gets only selectively uploaded content. Source files are never deleted. ExFAT should be safely ejected and should not be treated as a sole independent backup.

## Import observation into Odify review catalog (future after user sends results)

\`\`\`sh
python scripts/odify_pc_probe_ingest_v46.py \
  --catalog odify_catalog_links_v43_enriched.sqlite3 \
  --pc-results odify_probe_12_results.csv odify_probe_pairs_results.csv \
  --out odify_v46_review_observations.sqlite3
\`\`\`

The importer joins exact normalized artist+title to **one** catalog recording ID only; if multiple IDs match, association remains null. It checks canonical YouTube IDs and refuses overwriting the output. Produces a **new** SQLite with \`pc_probe\` (not added to source catalog), keeping \`approved_for_playback=0\` for all rows.

## Crucial state separation

- Search candidate found is not a verified song.
- \`yt-dlp\` audio formats present means metadata extraction succeeded in Windows; it does **not** mean actual media downloaded or the recording matches.
- Successful prior one-track M4A download was on user's Windows PC, not GitHub and not all 41,416 tracks.
- Manually verified artist/version identity must remain separate from extractor status and from access rights.
- The production Android APK and Google MASTER have not been modified by V4.6.

Security: never request/export login cookies, credentials, video authorization tokens or private drive file IDs into GitHub artifacts. Do not automate mass downloading based on unverified search results. Test requests are bounded and resumable.

Validation: \`.github/workflows/odify-v46-pc-import-tests.yml\` checks read-only source protection and rejects automatic approval, https://github.com/Tarang1215/Odify/actions/runs/40066334771 . PowerShell commands in the local kit require real Windows execution to fully validate; a Windows native smoke test has not yet been run in CI.
