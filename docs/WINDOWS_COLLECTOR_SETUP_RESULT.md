# Windows collector setup result

Verified on the user's Windows PC on 2026-10-22 (Asia/Seoul).

## Completed

- Reused the existing `odify-512404` project. Its Billing page explicitly says
  no billing account is linked. No trial, card registration or billing link was created.
- Enabled Google Drive API. Configured External OAuth consent as
  `Odify Personal Collector`, connected the public privacy policy, and switched
  to Production with the user's explicit approval, avoiding the Testing-only
  seven-day refresh-grant limit. This is a personal unverified app, not a claim
  of completed Google verification or unlimited token lifetime.
- Created the Desktop OAuth client `Odify Windows rclone` with approval.
  Declared only the `drive` scope for this existing-file collector.
- Completed Google authorization on the PC and saved the isolated rclone config
  under `%LOCALAPPDATA%\Odify\Collector`. Directory ACL inspection confirmed
  only the current user and SYSTEM. Credentials are excluded from Git and ZIP.
- Confirmed connection reuse in Windows PowerShell 5.1 without another login.
- The connected Drive had `Odify_Music_Test` with seven files but no
  `Odify_Auto_Upload` folder. Created the missing upload destination.
  Existing test music was left unchanged.
- Uploaded a generated temporary text marker, downloaded it, compared SHA-256,
  and removed only that marker. All steps passed.
- Tested the actual launcher with `-DryRun`: dependencies were found, saved
  authorization was reused, and the 152-track snapshot (151 verified links)
  passed validation. The original CSV SHA-256 is unchanged:
  `eec407aeb1f6f27e1a98b593679bb9ee5f57522260798f0ae2b7cc70a5acee3e`.

## Source and package changes

`windows-collector` imports the Desktop batch into the existing collector branch.
The installer imports OAuth JSON, creates an isolated remote without rclone's
interactive questions, finds WinGet/Python executable paths, and stops on
dependency failures. Repeat collection does not reinstall dependencies.
Saved tokens are not replaced merely because a network probe fails.
The worker is bundled, so the runner no longer downloads Python source.
`scripts/package_windows_collector.ps1` uses an explicit file allowlist;
`scripts/verify_collector_connection.ps1` reproduces the live marker check.

Three offline batch tests passed, including real worker ingestion and resume-state
preservation. ZIP creation and launcher checks passed with Windows PowerShell 5.1.
The two catalog-worker unit tests also passed, for five offline tests in total.

## Limits

No fresh YouTube audio extraction or full 152-track collection was run as part
of this setup. The upload test proves Google Drive read/write and integrity,
not source-media availability. The earlier six-track skip scenario is a simulated
test, not an assertion that six files exist in the new upload destination.
The Android app's OAuth/signing configuration is unchanged.

## References

- https://rclone.org/drive/#making-your-own-client-id
- https://developers.google.com/workspace/drive/api/guides/limits
