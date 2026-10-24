"""Non-destructive archive and selected-file Drive transfer. Default: preview only."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import uuid

AUDIO = {'.mp3', '.m4a', '.flac', '.opus', '.aac', '.ogg', '.wav'}


def digest(path, algorithm='sha256'):
    value = hashlib.new(algorithm)
    with Path(path).open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def child(root, relative):
    # Refuse Windows ADS, rooted paths, traversal, symlink/junction escapes.
    name = str(relative).replace('\\', '/')
    parts = PurePosixPath(name).parts
    if not name or ':' in name or name.startswith('/') or '..' in parts:
        raise ValueError('Unsafe relative path')
    root = Path(root).resolve()
    path = root.joinpath(*parts).resolve()
    if path == root or not path.is_relative_to(root):
        raise ValueError('Path escapes root')
    return path


def copy_verified(source, destination, execute=False):
    source, destination = Path(source), Path(destination)
    before = source.stat()
    checksum = digest(source)
    if destination.exists():
        return 'EXISTS_SAME_HASH' if digest(destination) == checksum else 'CONFLICT_SKIP', checksum
    if not execute:
        return 'WOULD_COPY', checksum
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + '.' + uuid.uuid4().hex + '.partial')
    try:
        with source.open('rb') as src, partial.open('xb') as dst:
            shutil.copyfileobj(src, dst, 1024 * 1024)
            dst.flush()
            os.fsync(dst.fileno())
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or digest(partial) != checksum:
            raise ValueError('Source changed or copied checksum differs')
        # Windows rename refuses overwriting a destination created concurrently.
        if os.name != 'nt':
            raise RuntimeError('Archive execution is supported on Windows only')
        os.rename(partial, destination)
        return 'COPIED_VERIFIED', checksum
    finally:
        if partial.exists():
            partial.unlink()  # Only this invocation's randomly named incomplete copy.


def archive(source, root, collection, execute=False):
    source, root = Path(source).resolve(), Path(root).resolve()
    if source == root or source.is_relative_to(root) or root.is_relative_to(source):
        raise ValueError('Archive/source directories must be separate')
    target = child(root / 'Music', collection)
    rows = []
    for file in sorted(source.rglob('*')):
        if not file.is_file() or file.suffix.lower() not in AUDIO:
            continue
        if not file.resolve().is_relative_to(source):
            raise ValueError('Source symlink escapes collection')
        destination = child(target, file.relative_to(source))
        status, checksum = copy_verified(file, destination, execute)
        rows.append({'relative_path': destination.relative_to(root / 'Music').as_posix(),
                     'bytes': file.stat().st_size, 'sha256': checksum, 'status': status})
    return rows


def selection(root):
    root = Path(root)
    manifest = root / 'Logs' / 'drive_selection.csv'
    previous = {}
    if manifest.exists():
        with manifest.open(encoding='utf-8-sig', newline='') as handle:
            for row in csv.DictReader(handle):
                if row['relative_path'] in previous:
                    raise ValueError('Duplicate selection path')
                previous[row['relative_path']] = row
    rows = []
    for file in sorted((root / 'Music').rglob('*')):
        if not file.is_file() or file.suffix.lower() not in AUDIO:
            continue
        relative = file.relative_to(root / 'Music').as_posix()
        safe = child(root / 'Music', relative)
        checksum = digest(safe)
        old = previous.get(relative, {})
        unchanged = old.get('sha256') == checksum
        rows.append({'relative_path': relative, 'file_size_bytes': file.stat().st_size,
                     'sha256': checksum, 'selected': 'YES' if unchanged and old.get('selected') == 'YES' else 'NO',
                     'drive_upload_status': old.get('drive_upload_status', 'NOT_UPLOADED') if unchanged else 'NOT_UPLOADED',
                     'notes': old.get('notes', '')})
    manifest.parent.mkdir(parents=True, exist_ok=True)
    if manifest.exists():
        backup = manifest.with_name('drive_selection.' + uuid.uuid4().hex + '.bak.csv')
        shutil.copyfile(manifest, backup)
    temporary = manifest.with_name(manifest.name + '.' + uuid.uuid4().hex + '.tmp')
    write_csv(temporary, rows, ['relative_path', 'file_size_bytes', 'sha256', 'selected', 'drive_upload_status', 'notes'])
    os.replace(temporary, manifest)
    return rows


def command(args, timeout=900):
    result = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        # Do not print OAuth errors, access tokens or private configuration contents.
        raise RuntimeError('rclone failed (exit %s); check connection privately' % result.returncode)
    return result.stdout


def drive_sync(root, config, folder_id, execute=False, inventory=None):
    if not re.fullmatch(r'[A-Za-z0-9_-]{10,200}', folder_id):
        raise ValueError('Exact Drive folder ID required')
    base = ['rclone', '--config', str(config), '--drive-root-folder-id', folder_id,
            '--retries', '1', '--contimeout', '15s', '--timeout', '45s']
    inventory = inventory if inventory is not None else json.loads(command(base + ['lsjson', 'mydrive:', '--recursive', '--files-only', '--hash']))
    names = {}
    for item in inventory:
        names.setdefault(item['Path'], []).append(item)
    root = Path(root)
    with (root / 'Logs' / 'drive_selection.csv').open(encoding='utf-8-sig', newline='') as handle:
        selected = [row for row in csv.DictReader(handle) if row['selected'] == 'YES']
    if len({row['relative_path'] for row in selected}) != len(selected):
        raise ValueError('Duplicate selected paths')
    receipts = []
    for row in selected:
        if row.get('drive_upload_status', '').startswith('BLOCKED'):
            raise ValueError('Recording review blocks upload: ' + row['relative_path'])
        relative = row['relative_path'].replace('\\', '/')
        file = child(root / 'Music', relative)
        checksum = digest(file)
        if checksum != row['sha256'] or file.stat().st_size != int(row['file_size_bytes']):
            raise ValueError('Selected file changed; refresh selection: ' + relative)
        md5 = digest(file, 'md5')
        candidates = names.get(relative, [])
        if len(candidates) > 1:
            raise ValueError('Ambiguous duplicate Drive path: ' + relative)
        existing = candidates[0] if candidates else None
        if existing and (existing['Size'] != file.stat().st_size or existing.get('Hashes', {}).get('md5') != md5):
            status = 'CONFLICT_SKIP'
        else:
            # Exact same audio elsewhere in the folder: reuse its ID, don't create a duplicate.
            identical = [x for x in inventory if x['Size'] == file.stat().st_size and x.get('Hashes', {}).get('md5') == md5]
            existing = existing or (identical[0] if identical else None)
            status = 'REMOTE_VERIFIED' if existing else 'WOULD_UPLOAD'
            if not existing and execute:
                command(base + ['copyto', str(file), 'mydrive:' + relative, '--immutable', '--checksum'])
                matches = json.loads(command(base + ['lsjson', 'mydrive:' + relative, '--stat', '--hash']))
                existing = matches if isinstance(matches, dict) else matches[0] if len(matches) == 1 else None
                if not existing or existing['Size'] != file.stat().st_size or existing.get('Hashes', {}).get('md5') != md5 or digest(file) != checksum:
                    raise ValueError('Remote verification failed: ' + relative)
                status = 'UPLOADED_VERIFIED'
                existing['Path'] = relative
                inventory.append(existing)
                names.setdefault(relative, []).append(existing)
        receipts.append({'relative_path': relative, 'status': status, 'sha256': checksum,
                         'md5': md5, 'bytes': file.stat().st_size, 'drive_id': (existing or {}).get('ID', '')})
    return receipts


def batch_sync(root, config, folder_id, known_inventories, execute=False):
    """Transfer selected audio in one bounded rclone batch; verify every receipt.

    Inventories may include other existing Odify music folders for cross-folder
    deduplication. They must come from authenticated listings, never title guesses.
    """
    if not re.fullmatch(r'[A-Za-z0-9_-]{10,200}', folder_id):
        raise ValueError('Exact Drive folder ID required')
    root = Path(root)
    base = ['rclone', '--config', str(config), '--drive-root-folder-id', folder_id,
            '--retries', '2', '--contimeout', '15s', '--timeout', '60s']
    remote = json.loads(command(base + ['lsjson', 'mydrive:', '--recursive', '--files-only', '--hash']))
    known = list(remote)
    for path in known_inventories:
        known.extend(json.loads(Path(path).read_text(encoding='utf-8-sig')))
    with (root/'Logs'/'drive_selection.csv').open(encoding='utf-8-sig', newline='') as handle:
        selected = [r for r in csv.DictReader(handle) if r['selected'] == 'YES']
    if len({r['relative_path'] for r in selected}) != len(selected):
        raise ValueError('Duplicate selection path')
    remote_by_path = {}
    for item in remote:
        remote_by_path.setdefault(item['Path'], []).append(item)
    known_by_hash = {}
    for item in known:
        known_by_hash.setdefault((item.get('Hashes', {}).get('sha256'), item['Size']), []).append(item)
    planned, reused, queued_hashes = [], {}, set()
    for row in selected:
        relative = row['relative_path'].replace('\\', '/')
        if '\n' in relative or '\r' in relative:
            raise ValueError('Filename cannot be represented in an rclone file list')
        if row.get('drive_upload_status', '').startswith('BLOCKED'):
            raise ValueError('Recording review blocks upload: ' + relative)
        file = child(root/'Music', relative)
        if digest(file) != row['sha256'] or file.stat().st_size != int(row['file_size_bytes']):
            raise ValueError('Selected audio changed: ' + relative)
        identical = known_by_hash.get((row['sha256'], int(row['file_size_bytes'])), [])
        conflicts = remote_by_path.get(relative, [])
        if len(conflicts) > 1 or conflicts and not any(x in identical for x in conflicts):
            raise ValueError('Existing Drive destination conflicts: ' + relative)
        if identical:
            reused[relative] = identical[0]
        else:
            key = (row['sha256'], int(row['file_size_bytes']))
            if key not in queued_hashes:
                planned.append(relative)
                queued_hashes.add(key)
    run_id = uuid.uuid4().hex[:12]
    file_list = root/'Logs'/('drive_batch_'+run_id+'.txt')
    file_list.write_text(''.join(x+'\n' for x in planned), encoding='utf-8')
    plan_path = root/'Logs'/('drive_batch_'+run_id+'_plan.json')
    plan_path.write_text(json.dumps({'selected':len(selected),'new_files':len(planned),
        'reused_existing':len(reused),'execute':execute,'folder_id':folder_id},indent=2),encoding='utf-8')
    if not execute:
        return [{'relative_path':r['relative_path'], 'status':'REMOTE_VERIFIED' if r['relative_path'] in reused else 'WOULD_UPLOAD'} for r in selected]
    if planned:
        command(base + ['copy', str(root/'Music'), 'mydrive:', '--files-from-raw', str(file_list),
            '--immutable', '--checksum', '--transfers', '4', '--checkers', '8',
            '--stats', '15s', '--stats-one-line', '--log-level', 'INFO',
            '--log-file', str(root/'Logs'/('drive_batch_'+run_id+'.log'))], timeout=7200)
    after = json.loads(command(base + ['lsjson', 'mydrive:', '--recursive', '--files-only', '--hash']))
    index = {}
    for item in after:
        index.setdefault((item.get('Hashes', {}).get('sha256'), item['Size']), []).append(item)
    receipts = []
    for row in selected:
        relative = row['relative_path']
        matches = index.get((row['sha256'], int(row['file_size_bytes'])), [])
        item = matches[0] if matches else reused.get(relative)
        if not item:
            raise ValueError('Remote checksum/size readback failed: ' + relative)
        receipts.append({'relative_path':relative,'sha256':row['sha256'],'bytes':row['file_size_bytes'],
            'drive_id':item['ID'],'status':'REUSED_VERIFIED' if relative in reused else 'UPLOADED_VERIFIED'})
    snapshot = root/'Metadata'/('drive_after_'+run_id+'.json')
    snapshot.parent.mkdir(parents=True,exist_ok=True)
    snapshot.write_text(json.dumps(after,ensure_ascii=False),encoding='utf-8')
    return receipts


def record_receipts(root, receipts):
    """Persist verified transfer state only for the unchanged selected digest."""
    manifest=Path(root)/'Logs'/'drive_selection.csv'
    with manifest.open(encoding='utf-8-sig',newline='') as handle:
        rows=list(csv.DictReader(handle))
    by_path={r['relative_path']:r for r in receipts}
    for row in rows:
        receipt=by_path.get(row['relative_path'])
        if receipt and receipt.get('status') in ('REMOTE_VERIFIED','REUSED_VERIFIED','UPLOADED_VERIFIED'):
            if row['sha256'] != receipt.get('sha256'):
                raise ValueError('Manifest changed since transfer')
            row['drive_upload_status']=receipt['status']
    backup=manifest.with_name('drive_selection.before_receipts.'+uuid.uuid4().hex+'.csv')
    shutil.copyfile(manifest,backup)
    temporary=manifest.with_name(manifest.name+'.'+uuid.uuid4().hex+'.tmp')
    write_csv(temporary,rows)
    os.replace(temporary,manifest)


def write_csv(path, rows, fields=None):
    with Path(path).open('x', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields or (list(rows[0]) if rows else ['status']))
        writer.writeheader()
        writer.writerows(rows)


def reconcile(root, inventories, phone_database=None):
    """Link existing immutable copies by SHA256, never by a filename guess."""
    index = {}
    for inventory in inventories:
        for item in json.loads(Path(inventory).read_text(encoding='utf-8-sig')):
            checksum = item.get('Hashes', {}).get('sha256')
            if checksum:
                index.setdefault(checksum, {})[item['ID']] = item
    phone_ids = set()
    if phone_database:
        import sqlite3
        from contextlib import closing
        with closing(sqlite3.connect(Path(phone_database).resolve().as_uri() + '?mode=ro', uri=True)) as db:
            if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Phone catalog snapshot is invalid')
            phone_ids = {row[0] for row in db.execute('SELECT id FROM tracks')}
    with (Path(root)/'Logs'/'drive_selection.csv').open(encoding='utf-8-sig', newline='') as handle:
        rows = list(csv.DictReader(handle))
    result = []
    for row in rows:
        matches = [x for x in index.get(row['sha256'], {}).values() if x['Size'] == int(row['file_size_bytes'])]
        result.append({'relative_path':row['relative_path'], 'sha256':row['sha256'],
                       'bytes':row['file_size_bytes'], 'status':'REMOTE_VERIFIED' if matches else 'LOCAL_ONLY',
                       'drive_ids':';'.join(x['ID'] for x in matches),
                       'visible_on_phone':any(x['ID'] in phone_ids for x in matches)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('D:/Odify'))
    sub = parser.add_subparsers(dest='action', required=True)
    arch = sub.add_parser('archive')
    arch.add_argument('--source', type=Path, required=True)
    arch.add_argument('--collection', required=True)
    arch.add_argument('--execute', action='store_true')
    sub.add_parser('selection')
    compare = sub.add_parser('reconcile')
    compare.add_argument('--inventory', type=Path, nargs='+', required=True)
    compare.add_argument('--phone-database', type=Path)
    sync = sub.add_parser('sync')
    sync.add_argument('--config', type=Path, required=True)
    sync.add_argument('--folder-id', required=True)
    sync.add_argument('--execute', action='store_true')
    sync.add_argument('--batch', action='store_true')
    sync.add_argument('--known-inventory', type=Path, nargs='*', default=[])
    args = parser.parse_args()
    if not args.root.is_dir():
        parser.error('External archive is not mounted')
    if args.action == 'archive':
        rows = archive(args.source, args.root, args.collection, args.execute)
    elif args.action == 'selection':
        rows = selection(args.root)
    elif args.action == 'reconcile':
        rows = reconcile(args.root, args.inventory, args.phone_database)
    else:
        rows = (batch_sync(args.root,args.config,args.folder_id,args.known_inventory,args.execute)
                if args.batch else drive_sync(args.root, args.config, args.folder_id, args.execute))
    logs = args.root / 'Logs'
    logs.mkdir(exist_ok=True)
    report = logs / (args.action + '_' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '_' + uuid.uuid4().hex[:8] + '.csv')
    write_csv(report, rows)
    if args.action == 'sync' and args.execute:
        record_receipts(args.root,rows)
    print(json.dumps({'action': args.action, 'files': len(rows), 'report': str(report)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
