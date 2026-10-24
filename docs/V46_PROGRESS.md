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
- Final reconciliation finds exact SHA-256/size matches for **750/750 archived
  files** on Drive and indexes all corresponding IDs in the phone's 949-track
  Drive snapshot. This includes the previously existing held English recording;
  byte equality does not approve its identity or release the hold. There are no
  new MASTER uploads or MASTER link/verified-flag edits.

## Android reference refinement

The current source is preview12.16 / versionCode 28. Version 27's interaction and
background changes were accepted on the device; version 28 adds the parser fix below.
The lower navigation is a
rounded glass surface that samples the actual page through a 24dp GPU blur.
An opaque snapshot prevents unblurred original text from showing through; live
coordinate conversion and draw-only frame updates follow scroll and ancestor
translations. Sampling includes extra pixels outside the rounded region to avoid
transparent blur edges. There is no full-screen bitmap readback. Upward finger movement hides it;
downward movement brings it back, linked to the mini-player on one spring. Unclaimed
horizontal gestures switch neighboring tabs with a short directional transition;
album carousels and sliders get first refusal. A Material Surface supplies light content
colors throughout Explore, search, settings and menus. Country and genre selections
use charcoal rather than the default purple. Home uses a shorter artwork hero,
recent album cards with title only and compact personal mixes. Home, Search and
Explore pills share typography, height and rounding. The centered compact player
follows measured title width within 168–216dp, with cover, title and circular
pause control; long titles scroll after 2.2 seconds. The cover's dominant hue
supplies a restrained inner glow and edge shadow, blending over 700ms on track
changes. Bounded local sampling runs off the main thread and caches the result;
neutral covers retain a muted gray glow.
Tap toggles mini-player width; upward swipe opens the full player. Saved playlist tracks have real
46dp cover thumbnails and an explicit overflow menu.
The latest controls use matching 29dp circular artwork and pause surfaces when
compact. Tapping toggles compact/expanded mini widths; upward swipe opens the full
player from either width. Both bottom surfaces and the full player's queue bar
share one animated artwork glow. Taps and holds trigger a restrained 1–2% elastic
render response. Closing now converges the panel center and uniform scale into the
measured mini-player instead of merely fading a nearly full-size rectangle.

Explore's previous matching loop deduplicated the entire catalog once per chart
song. One immutable index is now built per library update on Dispatchers.Default,
then reused across all country charts. Cached chart snapshots prewarm from Home,
stay visible during refresh, and 48 bounded decoded covers stay in memory. Blank
horizontal gestures switch Korea/Japan/US; genre carousels keep their own gestures.
The trailing chart action chooses or creates a playlist. Cover/title tapping plays
owned audio directly. Unavailable chart references can also be added to a playlist.

Lyrics are centered in a wide, transparent viewport over the full blurred album
background, with a small cover/title header. The former square glass panel has
been removed. Timed lyrics highlight the active line and fade at viewport edges.
Without saved/embedded text,
tapping the lyrics control leaves artwork untouched. Holding it opens the edit
menu and existing private LRC/import/search editor. Music bytes remain unchanged.

The player backdrop now uses the actual complete cover with 1.18x expansion,
36dp blur and a continuous dark lower scrim. The intermediate palette gradient
and partial reflection flattened the image and produced visible color bands;
both were removed. Cover tapping opens the full player from either mini width.
Lyrics-body scrolling no longer participates in player collapse; only the header
drags down while lyrics are open. See
`V46_PLAYER_REFERENCE.md` for the reference comparison. Twelve decorative waveforms have separated
phrases, pronounced peaks and very low troughs. Each preset is a single coherent
envelope with restrained internal texture, rather than independent random bar
heights. Its adjacent-height correlation is regression-checked; both edges taper
to near silence. Thin 1.1dp rounded bars distinguish
elapsed white from translucent unplayed bars. These remain decorative seek art.

Slow dragging updates the render transform directly, avoiding a composition-time
read of each drag offset. New touches cancel unfinished spring-back motion. The
player follows the finger at uniform scale and returns toward the measured
mini-player origin. The latest close transition retains portrait proportions and
smoothly blends out the final portion, avoiding the opaque compressed frame that
flashed against the mini-player. Chart selection now plays owned music directly.
Lyrics are an explicit separate search scope: earlier device
acceptance reproduced NEON showing Drowning because the original broad search
matched a lyric substring. A regression test now requires metadata lookup to exclude
that unrelated lyric hit while preserving explicit lyric searching.

Explore now has 100 real ranked entries each for Korea, Japan and the US. Korea's
public Top 100 playlist works despite the marketing endpoint failure, so no Melon
fallback is currently needed. Provider identity is retained in metadata rather than
a prominent header. All three bundled snapshots have 100 distinct IDs. Play and
shuffle queue the available owned recordings. Saving preserves all 100 references,
including pending ones that can resolve on later Drive sync. No unreviewed source
is substituted. Byte-identical local and Drive copies collapse in presentation,
including the observed Hype Boy duplicate, without removing either source file.

## Background chart collection

`tools/v46/odify_charts.py` ran against `D:\Odify\Charts\20261024`; its current
source-download pause and verified counts are recorded below.
The latest three official public Top 100 snapshots contain 300 rank appearances
and 297 distinct recordings. Round-robin processing first reuses exact artist/title
matches in the owned catalog; otherwise original studio identity and measured
duration must pass review before download. All new audio is confined to the dated
Charts folder. Uploads use immutable transfers and remote MD5/size readback.
Uncertain identities remain HELD_REVIEW; access challenges or repeated transport
failures pause the job. MASTER links, audio and verification flags are untouched.

`publish_chart_index.py --watch` separately publishes immutable, checksum-verified
identity snapshots for completed/reused files. Android reads the newest manifest
per folder during normal sync and links chart IDs to owned Drive IDs. On-device
sync confirmed 44 chart aliases and 961 Drive tracks, preserving the original 949.
At 20:51 KST the collector remained running: 37 reused, 13 newly uploaded and
verified, 61 held for review, 1 in link review and 185 queued. The live truth is
`chart_status.json`/`.csv`, not this timestamped count. The publisher checkpoint
and collector receipts remain under that same dated folder. The jobs can continue
independently of the foreground Android work.
At 20:59 one age-gated recording stopped the original worker at 38 reused and
18 new verified uploads. The resolver now holds that recording without attempting
authentication, while site-wide bot challenges and rate limits still stop the job.
Two regression tests cover that distinction (20 V4.6 tests total). Collection was
resumed at 21:02; the held recording is not retried and the remaining queue continues.
At 21:07 a site-wide YouTube bot challenge paused new source requests after
19 verified new uploads and 39 reuses. No cookies, challenge bypass or repeat
extraction was attempted. A network-free owned-file pass reused 20 more queued
recordings, then published a verified 78-entry Drive identity index. At 21:14 the
297 unique recordings are: **59 reused, 19 new uploaded/verified, 75 held, 144
queued**. Source collection is paused, not running or complete. `--reuse-only`
can make further progress from cached metadata without contacting the source.
An additional regression ensures it preserves holds and alternate versions;
the current V4.6 suite has **21 passing tests**.

The user's requested fast link preparation processed all 297 unique recordings:
78 already connected, 217 with search candidates, one search miss and one age
restriction skipped. These candidates are explicitly **unverified**, in
`Links/chart_links.csv`/`.json`; none became MASTER links or playback approvals.
Search and inspected metadata are now cached for 24 hours. Alias queries run only
after the first query fails, flat-result durations prune wrong candidates, and the
same inspected metadata is revalidated locally instead of being fetched twice.
The chart-only `--official-visuals` option now also accepts an official lyric/MV
when the channel is verified, the owner and complete cleaned title match exactly,
and runtime is within two seconds. Live, alternate-language and wrong-owner
versions remain rejected. MASTER defaults are unchanged. A fresh metadata probe
matched Tate McRae / Housewife's verified artist lyric video (209 seconds against
208.81 seconds); this probe alone is not a download receipt.

`Run-ChartCollection.ps1` in the dated external folder is the user-facing launcher;
its source is `tools/v46/Collect-ChartMusic.ps1`. UTF-8 BOM preserves the Korean
repository path in Windows PowerShell 5.1. Initial preflight incorrectly required
the tool environment's private Drive config on the user's host. The corrected
default is **download-only**, with no rclone/config requirement; `-Upload` opts into
the pre-existing immutable Drive workflow. An unavailable private catalog is
optional too. Download receipts use DOWNLOADED_VERIFIED and SHA-256 separately
from UPLOADED_VERIFIED. Age restrictions remain skipped; uncertain originals stay
held. Windows PowerShell preflight and a credential-free mock transfer pass.
The external launcher was updated with the new flag. The user's already-running
process retains its older code until restarted; it was left running. A subsequent
filesystem read independently SHA-256-verified eight new MP3s (43,049,953 bytes).
At that checkpoint: 59 reused, 19 uploaded/verified, 8 downloaded/verified, 120 held,
1 under link review and 90 queued. These are timestamped observations, not final
completion counts; local-only downloads are not claimed as Drive uploads.

The user subsequently explicitly replaced the official-owner rule with a quick
chart-only selection policy: match title and approximately equal duration, reject
live/concert/altered versions, prefer official uploads, then lyric uploads. The
launcher now uses `--quick-match`; MASTER defaults and links remain unchanged.
Search links are selected in one batch, with four concurrent audio transfers.
Full metadata is not redundantly fetched before each transfer. Actual downloaded
audio duration is checked again. Chart artwork is embedded, with a YouTube
thumbnail fallback; LRCLIB enrichment is deferred entirely.

Final collection: **236 uploaded/remote-size-and-MD5 verified, 60 existing files
reused, one age-gated recording skipped** (Lil Baby / Right On). All 236 local
upload files were independently rehashed against their receipts and checked for
embedded APIC covers. A verified 296-entry chart-to-Drive index was published.
Hype Boy reuses the preserved personal Drive file: its missing artist tag had
prevented chart matching despite a matching title and 179.064-second phone file.
The new index also preserves known metadata from byte-identical cloud copies
when the offline copy is preferred. Unique exact-title personal recordings with
missing artist tags can resolve; known conflicting artists and ambiguous titles
remain excluded. 퇴사할게여 (Narr. 기안84) is uploaded with artwork and indexed.

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
Android JVM tests: **31 passed**, including URL/identity, recommendation diversity,
offline classification and all 12 decorative seek patterns. Kotlin
application compilation passed. The signed instrumentation suite was executed on
the connected Galaxy S25 Ultra: **3 passed**, covering actual file/content playback,
duplicate queue identity, background/locked playback, playlist persistence and
bounded authenticated-source retry behavior.

Local APK packaging correctly stopped because the local certificate differs from
the required fixed SHA-1. The existing GitHub signed workflow has the correct key:
its identity validation passed for build 40073379082. Its signed versionCode 16 /
preview12.4 APK was installed with an in-place update over version 14. No signing
guard was bypassed. Before the update, a 68,185,088-byte private archive of existing
app files, settings and databases was saved to `D:\Odify\Backups`. All three country
feeds appeared on the device, a real 15-track mix was saved into Library, and an
owned Drive track played successfully. The refined versionCode 17 APK from build
40075287355 was installed in place and its three on-device regression tests passed.
The final coherent-waveform refinement was signed by build 40075614717 and installed
in place. Its APK SHA-256 is
`7cba8e04df1c9a7ffa230938e6735cde2c6dee50c39dfa6b997fdd34f1913662`.
The fixed certificate SHA-1 is
`656d465e5c729bdf21a4026c764dfb636f1899e5`. After updating, SQLite integrity passes,
all 949 previous Drive IDs remain, and the saved 15-track mix persists. After the
user physically unlocked the device, screenshots confirmed the edge-attached bar,
white Explore titles, real playlist cover thumbnails, blurred artwork and coherent
waveform silhouette. A 3-second slow drag closed to the mini-player; three intermediate
screenshots confirmed uniform artwork proportions. The bounded measurement interval
recorded 414 additional frames and 2 additional janky frames (~0.48%). This is one
device observation, not a general performance guarantee. An earlier reset-only
capture reported zero frames and was discarded. A shorter downward drag sprang
back to the open player, while a full drag returned to the mini-player. Tapping the
waveform midpoint sought the actual 3:12 track to approximately 1:38. Offline Library
displayed the 552 local files separately from the 949 Drive entries. VersionCode 18
from build 40086943879 was installed with the fixed signer. NEON now returns zero
metadata results in the owned catalog; the explicit Lyrics scope returns Drowning
as the observed lyric match. Selecting NEON from Explore again resets Lyrics to
music metadata and excludes Drowning, as verified in a repeated device sequence.
VersionCode 19 was signed by build 40087649813 and installed in place. The
mini-player origin is measured at the
actual tap, so a moving navigation layer cannot leave a stale expansion position.
VersionCode 20 (preview12.8), signed by build 40090049765, added Top 100 playback,
the first GPU backdrop and compact island. Its APK SHA-256 is
`2c15710843d71ed8dfbf7d009d686cef9befc73470d30f6c5a28b4a166f10348`.
Three selective on-device tests passed: 100 unique entries per bundled country,
playlist/reference persistence and authenticated-source retry behavior. The
locking playback test was not repeated during unlocked visual review. VersionCode
21's opaque blur fix, live position sampling and wider scrolling-title island pass
28 local JVM tests and Android-test Kotlin compilation. Build 40091412411 signed
and installed that version in place; APK SHA-256 is
`d2f5e87b657a9f8c5dc878eb642b3c90c467380e77d748b5a767d3301b10f4e4`.
Its three selective device tests passed. Screenshots show the navigation blurring
the underlying artwork without retaining sharp original text. Direct chart-song
tapping starts the selected owned song; shuffle works, and saving a Top 100 adds
a separate playlist while the original 15-track mix persists. All 949 original
Drive IDs remain in the 966-track device snapshot. The compact title is visible
and a full-sized title scrolls when too long. VersionCode 22 adds bounded organic
width and album-color glow; its 28 JVM tests and Android-test compilation pass.
It also feathers only the closing player's rim, preserving sharp central artwork
and text. Source `1ff1612` was signed by build 40092999445 and installed as
versionCode 22; APK SHA-256 is
`32f6e533f41122c1aeb48ecca24845a8303568ebd7fd7dc4efe5a8646f6f5f87`.
Its three selective device tests passed, and a slow 3-second drag collapsed to the
mini-player with the softened rim visible in three captured frames. That bounded
interval added 462 frames and 3 janky frames (~0.65%). Actual screenshots show the
red cover supplying a subdued red compact-player glow and circular pause control.
The preceding preview12.9 slow-drag interval added 647
rendered frames and one janky frame; this bounded observation is not a general
performance guarantee. The latest source `0513f91` adds the requested indexed
country navigation, updated gestures, lyrics glass/editor and shared color; its
30 local JVM tests and Android-test Kotlin compilation pass. Build 40095427430
installed versionCode 23; device checks confirmed both mini tap directions,
compact upward expansion and lyrics long-press/editor without saving edits.
VersionCode 24, source `31bbab7`, build 40096749857, was installed with the fixed
signer; APK SHA-256 is
`3846e3000c7ec57af994846088857af9049155ed9e2d60d6cfbea30c301aa46c`.
Its three selective device tests passed, and a fresh actual country swipe moved
Korea Top 100 to Japan Top 100. Touch-down now changes the glass deformation
immediately instead of waiting for release. VersionCode 25's new reference player
passes 30 JVM tests and instrumentation source compilation; signed build
40098301676 installed that version. The user's subsequent feedback exposed a
lyrics-parent drag conflict and flattened color bands. Build 40100635492 signed
source `6e5c2f7` and installed versionCode 27 / preview12.15 in place; APK SHA-256:
`6829b78bd12c1639cfb576c5ba4b32645794775f480fcda170c73ddb02cb9439`.
Its three selective device tests passed. It includes the untagged-file playback
fix, isolated lyrics scroll, cover-to-full tap and actual-image backdrop.
Actual screenshots show the cover's blue/brown spatial shapes behind both modes,
without the old palette bands. During the user's one-minute acceptance window,
lyrics-body swipes in both directions retained fullscreen and both compact and
wide mini cover taps opened fullscreen. Final device data contains 1,185 Drive
tracks and all 296 chart aliases, including both reported songs. All 949 original
Drive IDs, two playlists and the 15-track daily mix remain.

This inspection also found embedded lyrics with a timestamp only on the title
header and an otherwise untimed body. The old parser silently hid the body.
Version 28 treats incomplete timing as plain lyrics, removes timestamp/metadata
headers from that presentation, and keeps all actual text without inventing sync.
The new regression raises the JVM total to 32; instrumentation source compilation
also passes. Source `7b58ebf` is signed by build 40101755523.
VersionCode 28 / preview12.16 is installed with the fixed certificate. APK SHA-256:
`cd35780fab85477ed13e9952c278c98a4eea02c4f1c80e4891aa210d333b2d69`.
All three selective device tests pass against it. Screenshots confirm the complete
untimed body now appears; subsequent up/down swipes visibly scroll that body while
both fullscreen and lyrics mode remain active. One earlier acceptance snapshot
was inconsistent with the immediately subsequent screen; the repeated capture
stores separate direction-specific hierarchies and screenshots and passes both.
The final APK is also saved without overwriting an older build at
`D:\Odify\Android\Odify-preview12.16-v28-cd35780f.apk`.

The refreshed remote scripts exposed five Windows file-lock cleanup errors: one
read-only production SQLite context and four test connection handles were not
closed. Those handles were fixed; all **97 existing script tests passed**, along
with 11 legacy assembly tests, 16 collector tests and 28 V4.6 tests. The original
PowerShell storage smoke test passed too. No network requests are used by these
Python regression suites.

Evidence is in `D:\Odify\Logs`, `D:\Odify\Metadata\V46` and the local ignored
`.local/v46` folder. `master_protection_hashes.json` records unchanged MASTER hashes.
An opt-in GitHub workflow, `odify-v46-verify.yml`, runs the Windows regressions; it
has no automatic push schedule and has not been dispatched.
