from __future__ import annotations

import argparse
from contextlib import contextmanager
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from drive_source import refresh_master, drive_request, SPREADSHEET_ID
import music_catalog as worker


AUDIO_EXTENSIONS = {'.mp3', '.m4a', '.flac', '.opus', '.ogg', '.wav', '.aac'}
TRACK_ID = re.compile(r'^ODF\d{6,}$')


def run(arguments: list[str], timeout: int = 180) -> str:
    result = subprocess.run(arguments, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout)[-1500:])
    return result.stdout


def read_master(path: Path) -> list[dict[str, str]]:
    with path.open(encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        if not set(worker.COLUMNS).issubset(reader.fieldnames or []):
            raise ValueError('MASTER is missing required columns.')
        rows = [{key: (value or '').strip() for key, value in row.items() if key}
                for row in reader if (row.get('Track ID') or '').strip()]
    if not rows or any(not TRACK_ID.fullmatch(row['Track ID'])
                       or not row['아티스트'] or not row['곡명'] for row in rows):
        raise ValueError('MASTER contains an invalid track ID, artist or title.')
    if len({row['Track ID'] for row in rows}) != len(rows):
        raise ValueError('MASTER contains duplicate track IDs.')
    return rows


def song_key(row: dict) -> tuple[str, str]:
    return worker.normalize(row['아티스트']), worker.normalize(row['곡명'])


def file_track_id(name: str) -> str | None:
    match = re.match(r'^(ODF\d{6,})(?=$|[\s_.-])', Path(name).stem)
    return match.group(1) if match else None


def drive_inventory(rows: list[dict]) -> dict[str, dict]:
    arguments = ['rclone', 'lsjson', 'mydrive:', '--recursive', '--files-only', '--max-depth', '30']
    for extension in sorted(AUDIO_EXTENSIONS):
        arguments.extend(['--include', '*' + extension])
    files = json.loads(run(arguments))
    names = {worker.normalize(Path(item['Name']).stem): item for item in files if item.get('Size', 0) > 1024}
    inventory = {}
    for item in files:
        track_id = file_track_id(item['Name'])
        if track_id and item.get('Size', 0) > 1024:
            inventory[track_id] = item
    for row in rows:
        track_id = row['Track ID']
        if track_id in inventory:
            continue
        for name in (row['아티스트'] + ' - ' + row['곡명'], row['곡명'] + ' - ' + row['아티스트']):
            item = names.get(worker.normalize(name))
            if item:
                inventory[track_id] = item
                break
        reference = row.get('Drive 파일 URL', '')
        match = re.search(r'drive\.google\.com/file/d/([A-Za-z0-9_-]+)', reference)
        if track_id not in inventory and match:
            metadata = json.loads(drive_request('files/' + match.group(1), parameters={
                'fields': 'id,name,size,mimeType,trashed'}))
            if not metadata.get('trashed') and int(metadata.get('size', 0)) > 1024:
                if Path(metadata['name']).suffix.lower() not in AUDIO_EXTENSIONS:
                    raise ValueError(f'{track_id}: recorded Drive reference is not audio.')
                inventory[track_id] = metadata
    return inventory


def build_plan(rows: list[dict], inventory: dict) -> list[dict]:
    groups = {}
    for row in rows:
        groups.setdefault(song_key(row), []).append(row)
    plan = []
    for group in groups.values():
        present = next((row for row in group if row['Track ID'] in inventory), None)
        primary = present or next((row for row in group if row['링크검증'] == '검증완료'
                                   and worker.validate_video_url(row['YouTube 오디오 URL'])), group[0])
        for row in group:
            state = ('already_on_drive' if present else 'duplicate_of_master' if row is not primary
                     else 'ready' if row['링크검증'] == '검증완료'
                     and worker.validate_video_url(row['YouTube 오디오 URL']) else 'needs_link')
            plan.append({**row, 'collection_status': state,
                         'canonical_id': primary['Track ID'], 'collection_error': ''})
    return sorted(plan, key=lambda row: row['Track ID'])


def source_title(title: str) -> str:
    return worker.recording_title(title)


def candidate_matches(row: dict, info: dict) -> bool:
    if info.get('is_live') or info.get('live_status') in ('is_live', 'is_upcoming', 'was_live'):
        return False
    duration = info.get('duration')
    if not isinstance(duration, (int, float)) or not 60 <= duration <= 900:
        return False
    expected = worker.normalize(source_title(row['곡명']))
    track = worker.normalize(str(info.get('track') or ''))
    title = worker.normalize(str(info.get('title') or ''))
    if not expected or not (expected == track or (len(expected) >= 3 and expected in title)):
        return False
    artist = re.split(r'\s+(?:feat\.?|with)\s+|\s*&\s*|,', row['아티스트'], maxsplit=1, flags=re.I)[0]
    artist = worker.normalize(artist)
    metadata = ' '.join(str(info.get(field) or '') for field in ('artist', 'creator', 'channel', 'uploader'))
    if not artist or artist not in worker.normalize(metadata):
        return False
    original = row['곡명'].casefold()
    candidate = str(info.get('title') or '').casefold()
    for marker in ('karaoke', 'nightcore', 'slowed', 'sped up', 'instrumental', 'cover', 'remix', 'live'):
        if marker in candidate and marker not in original:
            return False
    description = str(info.get('description') or '').casefold()
    return ('auto-generated by youtube' in description
            or str(info.get('channel') or '').casefold().endswith(' - topic')
            or bool(info.get('uploader_verified')) and 'official audio' in candidate)


def resolve_link(row: dict) -> tuple[str, str]:
    candidates = []
    for suffix in ('provided to YouTube', 'official audio'):
        query = f"ytsearch5:{row['아티스트']} {source_title(row['곡명'])} {suffix}"
        listing = json.loads(run([sys.executable, '-m', 'yt_dlp', '--flat-playlist',
                                  '--dump-single-json', '--socket-timeout', '20', query], timeout=120))
        candidates.extend(listing.get('entries') or [])
    seen = set()
    for candidate in candidates:
        video_id = candidate.get('id', '')
        if video_id in seen or not re.fullmatch(r'[A-Za-z0-9_-]{11}', video_id):
            continue
        seen.add(video_id)
        url = 'https://www.youtube.com/watch?v=' + video_id
        try:
            info = json.loads(run([sys.executable, '-m', 'yt_dlp', '--no-playlist',
                                   '--skip-download', '--dump-single-json', '--socket-timeout', '20', url], timeout=120))
        except RuntimeError as error:
            if access_blocked(str(error)):
                raise
            continue
        if candidate_matches(row, info):
            evidence = json.dumps({key: info.get(key) for key in ('id', 'title', 'artist', 'channel', 'duration')}, ensure_ascii=False)
            return url, evidence
    raise ValueError('No unambiguous matching official audio was found; manual link review is required.')


def access_blocked(message: str) -> bool:
    return any(marker in message.casefold() for marker in (
        'not a bot', 'confirm you', 'http error 429', 'too many requests', 'sign in to confirm'))


def upload_track(audio: Path, remote: str) -> str:
    destination = remote.rstrip('/') + '/' + audio.name
    run(['rclone', 'copyto', str(audio), destination, '--immutable', '--retries', '3'], timeout=900)
    expected = hashlib.md5(audio.read_bytes()).hexdigest()
    actual = run(['rclone', 'md5sum', destination], timeout=120).split()[0].casefold()
    if actual != expected:
        raise RuntimeError('Uploaded audio checksum does not match the local file.')
    return destination


def remember_link(database, row: dict, source_url: str, url: str, evidence: str) -> None:
    database.execute('INSERT OR REPLACE INTO resolved_links VALUES (?,?,?,?,?,?)',
                     (row['Track ID'], row['아티스트'], row['곡명'], source_url, url, evidence))
    database.execute("UPDATE tracks SET video_url=?,verified='검증완료' WHERE id=?", (url, row['Track ID']))
    database.commit()
    row['YouTube 오디오 URL'] = url


def download_track(database, row: dict, root: Path, source_url: str) -> None:
    def download():
        worker.download_one(database, (row['Track ID'], row['아티스트'], row['곡명'], row['앨범'],
                            row['장르'], row['YouTube 오디오 URL']), root, True, True,
                            audio_format='mp3', accept_lyric_or_mv=True)

    try:
        download()
    except (RuntimeError, ValueError) as error:
        if access_blocked(str(error)):
            raise
        stale_link = isinstance(error, ValueError) or any(marker in str(error).casefold() for marker in (
            'video unavailable', 'video is not available', 'private video', 'has been removed',
            'requested format is not available'))
        if not stale_link:
            raise
        url, evidence = resolve_link(row)
        if url == row['YouTube 오디오 URL']:
            raise error
        remember_link(database, row, source_url, url, evidence)
        print('LINK RECOVERED', row['Track ID'], flush=True)
        download()


def write_reports(root: Path, plan: list[dict], *, running: bool, detail: str = '') -> dict:
    counts = {}
    for row in plan:
        state = row['collection_status']
        counts[state] = counts.get(state, 0) + 1
    report = {'updated_at': datetime.now(timezone.utc).isoformat(), 'source_spreadsheet': SPREADSHEET_ID,
              'total_tracks': len(plan), 'running': running, 'detail': detail, 'counts': counts,
              'tracks': [{key: row.get(key, '') for key in ('Track ID', '아티스트', '곡명',
                          'collection_status', 'canonical_id', 'collection_error')} for row in plan]}
    temporary = root / 'master_report.tmp'
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(root / 'master_report.json')
    fields = ['Track ID', '연도', '아티스트', '곡명', 'collection_status', 'canonical_id',
              'YouTube 오디오 URL', 'collection_error']
    with (root / 'master_status.csv').open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(plan)
    return report


@contextmanager
def single_instance(root: Path):
    handle = (root / 'collection.lock').open('a+b')
    if not handle.tell():
        handle.write(b'0')
        handle.flush()
    handle.seek(0)
    try:
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        handle.close()
        raise RuntimeError('Another MASTER collection is already running.') from None
    try:
        yield
    finally:
        handle.close()


def collect(args: argparse.Namespace, root: Path) -> int:
    snapshot = root / 'MASTER.csv'
    if args.csv:
        rows = read_master(args.csv)
        snapshot.write_bytes(args.csv.read_bytes())
    else:
        metadata = refresh_master(snapshot)
        print('Live MASTER refreshed:', metadata['modifiedTime'], flush=True)
        rows = read_master(snapshot)
    inventory = drive_inventory(rows)
    plan = build_plan(rows, inventory)
    report = write_reports(root, plan, running=False)
    print(json.dumps({'total_tracks': len(plan), 'counts': report['counts']}, ensure_ascii=False), flush=True)
    if args.plan:
        return 0
    database = worker.connection(root / 'catalog.sqlite3')
    worker.ingest(database, snapshot)
    database.execute('CREATE TABLE IF NOT EXISTS resolved_links (id TEXT PRIMARY KEY, artist TEXT, title TEXT, source_url TEXT, url TEXT, evidence TEXT)')
    database.commit()
    queue = sorted([row for row in plan if row['collection_status'] in ('ready', 'needs_link')],
                   key=lambda row: (row['collection_status'] != 'ready', row['Track ID']))
    if args.limit:
        queue = queue[:args.limit]
    failures = 0
    exit_code = 0
    write_reports(root, plan, running=True)
    try:
        for index, row in enumerate(queue, 1):
            track_id = row['Track ID']
            print(f"[{index}/{len(queue)}] {track_id} {row['아티스트']} - {row['곡명']}", flush=True)
            row['collection_status'] = 'processing'
            write_reports(root, plan, running=True)
            try:
                source_url = row['YouTube 오디오 URL']
                cached = database.execute('SELECT url,evidence FROM resolved_links WHERE id=? AND artist=? AND title=? AND source_url=?',
                                          (track_id, row['아티스트'], row['곡명'], source_url)).fetchone()
                if cached:
                    remember_link(database, row, source_url, *cached)
                elif row['링크검증'] != '검증완료' or not worker.validate_video_url(source_url):
                    remember_link(database, row, source_url, *resolve_link(row))
                stored = database.execute('SELECT status,file_path,sha256 FROM tracks WHERE id=?', (track_id,)).fetchone()
                audio = root / 'Audio' / (track_id + '.mp3')
                valid_local = stored[0] == 'downloaded' and stored[1] == 'Audio/' + audio.name and audio.is_file()
                valid_local = valid_local and stored[2] == hashlib.sha256(audio.read_bytes()).hexdigest()
                if not valid_local:
                    download_track(database, row, root, source_url)
                upload_track(audio, args.remote)
                row['collection_status'] = 'uploaded_verified'
                print('UPLOADED + VERIFIED', track_id, flush=True)
                lyric = root / 'Lyrics' / (track_id + '.lrc')
                if lyric.exists():
                    try:
                        run(['rclone', 'copyto', str(lyric), args.remote.rstrip('/') + '/Lyrics/' + lyric.name,
                             '--immutable', '--retries', '2'], timeout=180)
                    except RuntimeError as error:
                        row['collection_error'] = 'Audio verified; lyrics upload needs retry: ' + str(error)[-300:]
                for alias in plan:
                    if alias['canonical_id'] == track_id and alias['Track ID'] != track_id:
                        alias['collection_status'] = 'already_on_drive_alias'
                failures = 0
            except ValueError as error:
                row['collection_status'] = 'needs_review'
                row['collection_error'] = str(error)[:1000]
                print('REVIEW', track_id, str(error)[:200], flush=True)
            except Exception as error:
                row['collection_status'] = 'failed'
                row['collection_error'] = str(error)[-1200:]
                failures += 1
                print('FAILED', track_id, str(error)[-450:], flush=True)
                if access_blocked(str(error)) or failures >= 5:
                    exit_code = 2
                    print('STOPPED: source access blocked or five consecutive failures; remaining tracks preserved.', flush=True)
                    break
            finally:
                worker.export_status(database, root / 'status.csv')
                write_reports(root, plan, running=True)
    except KeyboardInterrupt:
        exit_code = 130
    finally:
        database.close()
        report = write_reports(root, plan, running=False)
        print(json.dumps(report['counts'], ensure_ascii=False), flush=True)
        print('Report:', root / 'master_report.json', flush=True)
    return exit_code or (2 if any(row['collection_status'] in ('failed', 'needs_review') for row in plan) else 0)


def main() -> int:
    parser = argparse.ArgumentParser(description='Refresh MASTER, skip actual Drive audio, collect all missing tracks.')
    parser.add_argument('--root', type=Path, default=Path.home() / 'Music' / 'Odify_Library' / 'MASTER')
    parser.add_argument('--csv', type=Path, help='Explicit offline snapshot instead of refreshing the live MASTER.')
    parser.add_argument('--remote', default='mydrive:Odify_Auto_Upload')
    parser.add_argument('--limit', type=int, default=0, help='0 means every missing MASTER track.')
    parser.add_argument('--plan', action='store_true', help='Refresh and inventory only; do not download or upload audio.')
    args = parser.parse_args()
    if args.limit < 0 or not re.fullmatch(r'mydrive:[^\r\n]*', args.remote):
        parser.error('Invalid limit or remote destination.')
    args.root = args.root.expanduser().resolve()
    args.root.mkdir(parents=True, exist_ok=True)
    with single_instance(args.root):
        return collect(args, args.root)


if __name__ == '__main__':
    raise SystemExit(main())
