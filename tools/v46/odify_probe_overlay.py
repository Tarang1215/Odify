#!/usr/bin/env python3
"""Odify V4.6: import PC yt-dlp observations into NEW review-only SQLite overlay.

Does not modify original catalog, Google MASTER, source_candidate or verified flags.
Can run later using Python 3.10+ after user has tested the 12+10 candidates on PC.
"""
from __future__ import annotations
import argparse
import csv
import json
import math
from pathlib import Path
import re
import sqlite3
import unicodedata
from urllib.parse import urlparse,parse_qs
from odify_review import evaluate

ALLOWED={'audio_formats_present','no_audio_only_formats','video_id_mismatch','extractor_failed','probe_exception'}
URL=re.compile(r'^https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})$')

def norm(s):
    return ''.join(ch for ch in unicodedata.normalize('NFKC',s or '').casefold() if ch.isalnum())

def report(catalog, files, output):
    catalog=Path(catalog).resolve();out=Path(output).resolve()
    if not catalog.exists():raise FileNotFoundError(catalog)
    if out.exists():raise FileExistsError('Refuse overwriting existing overlay; choose a new file')
    if out==catalog:raise ValueError('Source and output must differ')
    src=sqlite3.connect(catalog.as_uri()+'?mode=ro',uri=True)
    src.row_factory=sqlite3.Row
    try:
        catalog_rows=src.execute('''SELECT r.id,a.name artist,r.title FROM recording r JOIN artist a ON a.id=r.artist_id''').fetchall()
        if len(catalog_rows)!=41416:raise ValueError('Expected 41416 source recordings')
        lookup={}
        for r in catalog_rows:
            k=norm(r['artist']),norm(r['title'])
            lookup.setdefault(k,[]).append(r['id'])
        indexed = src.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='source_candidate'").fetchone() is not None
        known={ (r[0],r[1]) for r in src.execute('SELECT recording_id,url FROM source_candidate') } if indexed else set()
    finally:src.close()
    rows=[]
    for file in files:
        with Path(file).open(encoding='utf-8-sig',newline='') as stream:
            for row in csv.DictReader(stream):
                key=row.get('probe_key') or ''
                if not key:raise ValueError('Expected checkpointed V4.6 probe CSV with probe_key')
                match=URL.fullmatch(row.get('url',''))
                if not match or match.group(1)!=row.get('video_id'):
                    raise ValueError('Invalid URL/video ID in PC result')
                status=row.get('extractor_status','')
                if status not in ALLOWED:raise ValueError('Unrecognized extractor status: '+status)
                ids=lookup.get((norm(row.get('artist','')),norm(row.get('track',''))),[])
                rid=ids[0] if len(ids)==1 else None
                in_source=rid is not None and (rid,row['url']) in known
                flags=[]
                if len(ids)==0:flags.append('artist_title_not_in_catalog')
                elif len(ids)>1:flags.append('multiple_recording_ids_no_guess')
                elif not indexed:flags.append('candidate_index_unavailable')
                elif not in_source:flags.append('not_in_top_five_candidate_index')
                if status=='video_id_mismatch':flags.append('youtube_id_changed')
                if row.get('variant','') not in ('positive_baseline',''):
                    flags.append('manual_version_review')
                try:
                    delta=float(row.get('length_difference_seconds') or 'nan')
                    if not math.isfinite(delta):raise ValueError('Non-finite duration')
                    if delta>7:flags.append('duration_diff_over_7sec')
                except ValueError:flags.append('invalid_duration_measurement')
                identity=evaluate(row)
                flags.append('identity_decision:'+identity['identity_decision'])
                flags.extend(filter(None,identity['identity_reasons'].split(';')))
                rows.append((key,rid,row['url'],row.get('region',''),row.get('artist',''),
                 row.get('track',''),row.get('variant',''),status,int(row.get('audio_formats') or 0),
                 row.get('preferred_format',''),row.get('extracted_title',''),row.get('extracted_channel',''),
                 row.get('manual_same_recording') or 'PENDING',';'.join(flags),
                 row.get('verified_at','')))
    if len({r[0] for r in rows})!=len(rows):raise ValueError('Duplicate probe_key across result files')
    out.parent.mkdir(parents=True,exist_ok=True)
    con=sqlite3.connect(out)
    try:
        con.execute('''CREATE TABLE probe (
        probe_key TEXT PRIMARY KEY,recording_id TEXT,video_url TEXT NOT NULL,
        region TEXT,artist TEXT,title TEXT,variant TEXT,extractor_status TEXT,
        audio_formats INTEGER,preferred_format TEXT,extracted_title TEXT,
        extracted_channel TEXT,manual_same_recording TEXT,review_flags TEXT,
        tested_at TEXT,approved_for_playback INTEGER NOT NULL DEFAULT 0)''')
        con.executemany('INSERT INTO probe VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)',rows)
        con.commit()
        if con.execute('SELECT COUNT(*) FROM probe WHERE approved_for_playback!=0').fetchone()[0]:
            raise ValueError('Unsafe automatic approval')
        result={'imported':len(rows),'extractable':sum(r[7]=='audio_formats_present' for r in rows),
         'linked_to_unique_catalog_recording':sum(r[1] is not None for r in rows),
         'matching_indexed_candidate':sum(r[1] is not None and indexed and 'not_in_top_five_candidate_index' not in r[13] for r in rows),
         'candidate_index_available':indexed,
         'identity_decisions':{decision:sum('identity_decision:'+decision in r[13] for r in rows)
                               for decision in ('METADATA_MATCH','REVIEW','REJECT')},
         'manual_review_flags':sum(bool(r[13]) for r in rows),
         'approved_for_playback':0,'original_db_modified':False,'master_modified':False,
         'note':'yt-dlp extraction is not identity or streaming validation.'}
        (out.parent/(out.stem+'_summary.json')).write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
        return result
    finally:con.close()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--catalog',required=True,type=Path)
    p.add_argument('--pc-results',required=True,type=Path,nargs='+')
    p.add_argument('--out',required=True,type=Path)
    a=p.parse_args()
    print(json.dumps(report(a.catalog,a.pc_results,a.out),ensure_ascii=False))
