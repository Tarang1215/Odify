#!/usr/bin/env python3
"""Odify V4.6 PC yt-dlp metadata probe -> a NEW review-only SQLite overlay.

The original 41,416-recording catalog, MASTER, download list and app SQLite
are never edited. Metadata-only extraction does not approve a recording.
"""
from __future__ import annotations
import argparse,csv,json,math,re,sqlite3,unicodedata,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'/'v46'))
from odify_review import evaluate

URL=re.compile(r"^https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})$")
ALLOWED={"audio_formats_present","no_audio_only_formats","video_id_mismatch","extractor_failed","probe_exception"}

def norm(s):
    return "".join(c for c in unicodedata.normalize("NFKC",s or "").casefold() if c.isalnum())

def import_observations(catalog,pc_results,out):
    source=Path(catalog).resolve();out=Path(out).resolve()
    if not source.exists():raise FileNotFoundError(source)
    if out==source or out.exists():raise ValueError("New, unused output path required")
    src=sqlite3.connect(source.as_uri()+"?mode=ro",uri=True)
    try:
        if src.execute("SELECT COUNT(*) FROM recording").fetchone()[0]!=41416:
            raise ValueError("Expected 41,416 original recordings")
        idmap={}
        for rid,artist,title in src.execute(
            "SELECT r.id,a.name,r.title FROM recording r JOIN artist a ON r.artist_id=a.id"):
            idmap.setdefault((norm(artist),norm(title)),[]).append(rid)
        has_index=src.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='source_candidate'").fetchone() is not None
        indexed=set(src.execute("SELECT recording_id,url FROM source_candidate").fetchall()) if has_index else set()
    finally:src.close()
    records=[]
    for item in pc_results:
        with Path(item).open(encoding="utf-8-sig",newline="") as f:
            for r in csv.DictReader(f):
                key=r.get("probe_key","")
                m=URL.fullmatch(r.get("url",""))
                if not key or not m or m.group(1)!=r.get("video_id"):
                    raise ValueError("Invalid probe key, URL or video ID")
                state=r.get("extractor_status","")
                if state not in ALLOWED:raise ValueError("Unexpected probe status")
                matches=idmap.get((norm(r.get("artist","")),norm(r.get("track",""))),[])
                rid=matches[0] if len(matches)==1 else None
                flags=[]
                if not matches:flags.append("artist_title_unmatched")
                elif len(matches)>1:flags.append("ambiguous_recording_ids")
                elif not has_index:flags.append("candidate_index_unavailable")
                elif (rid,r["url"]) not in indexed:flags.append("candidate_not_in_top5")
                if state=="video_id_mismatch":flags.append("youtube_id_changed")
                if r.get("variant") not in ("positive_baseline",None,""):
                    flags.append("manual_version_review")
                try:
                    delta=float(r.get("length_difference_seconds") or 'nan')
                    if not math.isfinite(delta):raise ValueError('Non-finite duration')
                    if abs(delta)>7:
                        flags.append("duration_difference_over_7s")
                except ValueError:flags.append("duration_parse_error")
                identity=evaluate(r)
                flags.append('identity_decision:'+identity['identity_decision'])
                flags.extend(filter(None,identity['identity_reasons'].split(';')))
                records.append((key,rid,r["url"],r.get("region",""),
                    r.get("artist",""),r.get("track",""),r.get("variant",""),
                    state,int(r.get("audio_formats") or 0),r.get("preferred_format",""),
                    r.get("extracted_title",""),r.get("extracted_channel",""),
                    r.get("manual_same_recording") or "PENDING",
                    ";".join(flags),r.get("verified_at","")))
    if len({r[0] for r in records})!=len(records):
        raise ValueError("Duplicate observations across result CSVs")
    out.parent.mkdir(parents=True,exist_ok=True)
    con=sqlite3.connect(out)
    try:
        con.execute("""CREATE TABLE pc_probe(
            probe_key TEXT PRIMARY KEY,recording_id TEXT,video_url TEXT NOT NULL,
            region TEXT,artist TEXT,title TEXT,variant TEXT,
            extractor_status TEXT,audio_formats INTEGER,preferred_format TEXT,
            extracted_title TEXT,extracted_channel TEXT,
            manual_same_recording TEXT,review_flags TEXT,tested_at TEXT,
            approved_for_playback INTEGER NOT NULL DEFAULT 0)""")
        con.executemany("INSERT INTO pc_probe VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",records)
        con.commit()
        assert con.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
        assert con.execute("SELECT count(*) FROM pc_probe WHERE approved_for_playback != 0").fetchone()[0]==0
        summary={"observations":len(records),
           "pc_audio_formats_present":sum(r[7]=="audio_formats_present" for r in records),
           "unambiguous_catalog_id":sum(r[1] is not None for r in records),
           "candidate_index_available":has_index,
           "identity_decisions":{decision:sum('identity_decision:'+decision in r[13] for r in records)
                                 for decision in ('METADATA_MATCH','REVIEW','REJECT')},
           "review_flagged":sum(bool(r[13]) for r in records),
           "auto_approved":0,"original_sqlite_modified":False,
           "note":"yt-dlp formats, identity and file transfer are distinct; review only"}
    finally:con.close()
    (out.parent/(out.stem+"_summary.json")).write_text(
        json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    return summary

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--catalog",type=Path,required=True)
    p.add_argument("--pc-results",type=Path,nargs="+",required=True)
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args()
    print(json.dumps(import_observations(a.catalog,a.pc_results,a.out),ensure_ascii=False))

if __name__=="__main__":main()
