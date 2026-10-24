import json
import os
from pathlib import Path
import re
import unicodedata


def normalized(value: str) -> str:
    return ''.join(character for character in unicodedata.normalize('NFKC', value).casefold()
                   if character.isalnum())


def approved_alias(title: str, info: dict, artist: str | None = None, *, studio_only: bool = False) -> bool:
    path = Path(__file__).with_name('verified_aliases.json')
    if not path.is_file():
        return False
    entries = json.loads(path.read_text(encoding='utf-8'))
    local = Path.home() / 'Music' / 'Odify_Library' / 'MASTER' / 'verified_aliases.json'
    if local.exists():
        entries.extend(json.loads(local.read_text(encoding='utf-8')))
    for entry in entries:
        if studio_only and not entry.get('studio_audio'):
            continue
        if (entry['video_id'] != info.get('id') or normalized(entry['title']) != normalized(title)
                or artist is not None and normalized(entry['artist']) != normalized(artist)):
            continue
        actual_title = info.get('track') or info.get('title') or ''
        actual_artist = info.get('artist') or ''
        identity_matches = (normalized(actual_artist) == normalized(entry['metadata_artist'])
                            if not entry.get('channel_id') else entry['channel_id'] == info.get('channel_id'))
        if (normalized(actual_title) == normalized(entry['metadata_title'])
                and identity_matches
                and entry.get('source', '').startswith('https://')):
            return True
    return False


def base_title(value: str) -> str:
    return re.split(r'\s*(?:\((?:feat\.?|prod\.?)|\bfeat\.)', value, maxsplit=1, flags=re.I)[0].strip()


def catalog_record(row: dict, info: dict) -> dict | None:
    root = Path.home() / 'Music' / 'Odify_Library' / 'MASTER'
    path = Path(os.environ.get('ODIFY_CATALOG_CACHE', root / 'catalog_enrichment.json'))
    if not path.exists():
        return None
    catalog = json.loads(path.read_text(encoding='utf-8'))
    for item in catalog.get(row['Track ID'], {}).get('matches', []):
        if (normalized(item.get('verifiedMasterTitle', '')) != normalized(row['곡명'])
                or normalized(item.get('verifiedMasterArtist', '')) != normalized(row['아티스트'])):
            continue
        actual_title = normalized(base_title(info.get('track') or info.get('title') or ''))
        if actual_title not in {normalized(base_title(item.get(field) or ''))
                                for field in ('trackName', 'localizedTitle')}:
            continue
        metadata_artist = normalized(info.get('artist') or '')
        aliases = [item.get('artistName') or '', item.get('localizedArtist') or '']
        if not any(normalized(artist) and normalized(artist) == metadata_artist for artist in aliases):
            continue
        if abs((item.get('trackTimeMillis', 0) / 1000) - (info.get('duration') or 0)) > 3:
            continue
        return item
    return None


def remember_catalog_alias(row: dict, info: dict, item: dict) -> None:
    path = Path.home() / 'Music' / 'Odify_Library' / 'MASTER' / 'verified_aliases.json'
    entries = json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
    entry = {'artist': row['아티스트'], 'title': row['곡명'], 'video_id': info['id'],
             'metadata_title': info.get('track') or info['title'], 'metadata_artist': info['artist'],
             'source': item.get('localizedSource') or item['trackViewUrl']}
    entries = [existing for existing in entries if existing['video_id'] != entry['video_id']]
    entries.append(entry)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)
