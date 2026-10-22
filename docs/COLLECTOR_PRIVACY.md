# Odify Personal Collector privacy policy

Effective date: 2026-10-22

Odify Personal Collector is a personal Windows tool for maintaining a music
library in the owner's Google Drive. It runs on the owner's PC without an
Odify-hosted server. This policy covers the Windows collector, not other apps
or Google's own services.

## Google data and permissions

After the owner authorizes it, rclone can list and read Drive files, upload
music and lyrics, compare uploaded files, and update existing MP3 artwork.
The `drive` permission technically permits access to all normal Drive files;
the collector's default target is `Odify_Auto_Upload`. That target is a
software setting, not an OAuth security boundary. The collector does not
request Gmail, contacts, calendar or payment information. Its commands use
copy/check, not sync/purge, and do not intentionally delete remote files.

## Local storage and sharing

OAuth credentials and refresh tokens are stored locally in
`%LOCALAPPDATA%\Odify\Collector`, with Windows file access restricted to the
owner and SYSTEM. They are excluded from source control and distribution
archives. rclone's credential obscuring is not encryption. Local collection
state includes track metadata, paths, status reports and a SQLite database.
The owner controls these local files and their retention.

Authorized music/lyrics are sent to the owner's Google Drive. To retrieve
source media, the collector contacts the selected media provider. Optional
artwork and lyric lookup sends artist/title queries to iTunes and LRCLIB;
thumbnail lookup contacts YouTube. These requests expose ordinary network
information to those providers under their own policies. Google credentials
are not shared with these lookup providers. Odify does not sell Google data,
use it for advertising, or send it to an Odify server or AI model training.

## Control and retention

The owner can revoke the app's access in Google Account > Security >
Third-party connections. Deleting the local credentials folder removes the
local token copies but does not by itself revoke a token. Deleting local
collection data does not remove Drive copies. The owner can separately
remove Drive files through Google Drive. Disconnecting does not promise
automatic deletion of existing library files.

## Contact and changes

Questions can be filed at the public project issue tracker:
https://github.com/Tarang1215/Odify/issues
Do not attach credentials, tokens or private files to an issue.
Updates to this policy are published in this repository.
