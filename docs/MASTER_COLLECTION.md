# Live MASTER collection

The Windows launcher now reads the current `MASTER` tab from
`1T5b4z_db5IXUg0lMiy2nNJzed382bvkIiPZkiLmHwaE` on every run. It exports
the workbook through the existing Drive grant, selects the exact named tab,
and retains a local CSV snapshot. No Sheets API or source-sheet writes are used.

At inspection on 2026-10-22, MASTER contained 509 tracks: 154 link-verified,
353 waiting and two needing review. Six recorded Drive file IDs were checked
against actual audio. After exact artist/title grouping, the first plan had
148 ready tracks, 354 requiring link discovery and one duplicate alias.
These are a time-specific plan, not fixed limits or completion claims.

## Error fixes

The screenshot's pip output came from an older extracted launcher. yt-dlp was
installed in Python's Scripts folder but was not on that process's PATH.
Both collection workers and the 2026 probe now use `sys.executable -m yt_dlp`.
The launcher resolves `sys.executable` once and invokes it directly: launching
Python through the Windows app execution alias was also virtualizing AppData,
so that Python and its rclone children could not see the real private config.
Direct interpreter invocation was verified to read the existing config.

## Processing

- Actual Drive audio and live recorded file IDs take precedence over Sheet
  completion labels. Exact artist/title aliases are grouped conservatively.
- Existing verified URLs are processed first; blank/unverified URLs are searched
  and validated against artist, title, duration, version and official audio.
- Search results that do not confidently match are kept for review.
- If a previously verified video has been removed or no longer matches, the
  collector searches for a validated replacement and caches it locally.
- Each MP3 is tagged, uploaded separately, and checked against its remote MD5.
- SQLite preserves local downloads and discovered links for resume. Reports
  distinguish presence on Drive, remote-verified uploads, reviews and failures.
- A Windows file lock prevents simultaneous MASTER jobs. Source access blocks
  or five consecutive operational failures stop a run without consuming the
  rest of the queue. No credentials, cookies or bot-challenge bypass is added.

One actual missing track, `ODF000006` (ATEEZ - BAD), was collected, uploaded and
remotely verified during implementation. Full-run progress is written under
`Music/Odify_Library/MASTER/master_report.json` on the user's PC.
Automatic discovery was also checked on `ODF000081`: the blank link for
황가람's 나는 반딧불 resolved to a matching auto-generated studio recording.
Sixteen offline tests passed across the batch and core worker, including
named-tab export, removal of the 152-track ceiling, artist/version matching,
module execution without a yt-dlp PATH entry, remote hash mismatch handling,
removed-link recovery and stopping rather than bypassing source access challenges.

Run `run_master_all.bat` to collect every missing track, or invoke
`run_collector.ps1 -DryRun` for a live plan and `-Limit 1` for a single-track test.
The old launcher name also uses the full MASTER. `-Mode 2026` explicitly selects
the old fixed snapshot. Existing music and the Google Sheet are preserved.
