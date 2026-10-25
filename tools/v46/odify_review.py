"""Classify checkpointed link observations without editing MASTER or approving playback."""
from __future__ import annotations

import argparse
import csv
import json
import math
import re
import unicodedata
from pathlib import Path


def norm(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value or '').casefold() if c.isalnum())


# Display-name aliases only. These do not establish ownership or official status.
ARTISTS = [
    ['AKMU (악뮤)', 'AKMU', '악뮤'], ['방탄소년단', 'BTS'],
    ['米津玄師', 'Kenshi Yonezu', 'Kenshi Yonezu  米津玄師'],
    ['宇多田ヒカル', 'Hikaru Utada'], ['머무르', 'MEOMURU'],
    ['Machine Gun Kelly', 'mgk'], ['虹のコンキスタドール', 'Niji no Conquistador'],
    ['애프터스쿨', 'After School'], ['신용재 (2F)', '신용재', 'Sin Yong Jae'],
]


def aliases(name):
    key = norm(name)
    for group in ARTISTS:
        if key in {norm(x) for x in group}:
            return {norm(x) for x in group}
    return {key} if key else set()


def evaluate(row):
    reasons = []
    url_id = re.fullmatch(r'https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})', row.get('url', ''))
    if not url_id or url_id[1] != row.get('video_id') or row.get('extracted_id') != row.get('video_id'):
        reasons.append('video_id_mismatch')
    title = row.get('extracted_title', '')
    expected = row.get('track', '')
    # Word boundaries keep "Olive" / "deliver" from being classified as live.
    for marker in ('cover', 'instrumental', 'karaoke', 'nightcore', 'slowed', 'sped up', 'remix', 'live',
                   '커버', '라이브', 'カバー', '歌ってみた', '弾いてみた'):
        pattern = re.escape(marker) if not marker.isascii() else r'(?<!\w)' + re.escape(marker) + r'(?!\w)'
        if re.search(pattern, title, re.I) and not re.search(pattern, expected, re.I):
            reasons.append('different_version:' + marker)
    channel = re.sub(r'\s+-\s+Topic$', '', row.get('extracted_channel', ''), flags=re.I)
    parts = re.split(r'\s+&\s+|\s+feat\.?\s+|\s+with\s+', row.get('artist', ''), flags=re.I)
    matching = sum(norm(channel) in aliases(part) for part in parts)
    if not matching:
        reasons.append('artist_identity_unconfirmed')
    elif matching != len(parts):
        reasons.append('collaborator_identity_unconfirmed')
    # Parenthesized translations can corroborate a title, never an arbitrary substring.
    titles = [title] + re.findall(r'\(([^()]*)\)', title)
    titles.append(re.sub(r'\s*\([^()]*\)', '', title).strip())
    if norm(expected) not in {norm(t) for t in titles}:
        reasons.append('title_identity_unconfirmed')
    try:
        actual, wanted = float(row.get('extracted_seconds', '')), float(row.get('expected_seconds', ''))
        if not math.isfinite(actual) or not math.isfinite(wanted) or actual <= 0 or wanted <= 0:
            raise ValueError()
        if abs(actual - wanted) > 7:
            reasons.append('duration_diff_over_7sec')
    except (TypeError, ValueError):
        reasons.append('duration_unconfirmed')
    try:
        extractable = row.get('extractor_status') == 'audio_formats_present' and int(row.get('audio_formats', 0)) > 0
    except ValueError:
        extractable = False
    if not extractable:
        reasons.append('not_extractable')
    rejected = any(r.startswith('different_version:') or r == 'video_id_mismatch' for r in reasons)
    decision = 'REJECT' if rejected else 'REVIEW' if reasons else 'METADATA_MATCH'
    return {**row, 'identity_decision': decision, 'identity_reasons': ';'.join(reasons),
            'approved_for_playback': 0, 'master_modified': False}


def review(files, output):
    rows, keys = [], set()
    for file in files:
        with Path(file).open(encoding='utf-8-sig', newline='') as handle:
            for row in csv.DictReader(handle):
                key = row.get('probe_key')
                if not key or key in keys:
                    raise ValueError('Missing or duplicate probe_key')
                keys.add(key)
                rows.append(evaluate(row))
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # New report on every run: existing observations and reports stay intact.
    with output.open('x', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]) if rows else ['probe_key'])
        writer.writeheader()
        writer.writerows(rows)
    return {decision: sum(r['identity_decision'] == decision for r in rows)
            for decision in ('METADATA_MATCH', 'REVIEW', 'REJECT')}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, nargs='+', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(review(args.results, args.out), ensure_ascii=False))
