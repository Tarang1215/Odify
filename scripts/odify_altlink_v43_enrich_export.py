#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Safely enrich V4.3 app-ready SQLite with real album/artist identity relations.

Reads the immutable source catalog and a V4.3 link export; creates a new
database. No changes to either input or Google MASTER. Only recorded source
album membership is exported; no fabricated discographies.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import shutil
import sqlite3

def audit(source,links,out,expected=41416):
 src=Path(source).resolve();before=Path(links).resolve();out=Path(out).resolve()
 if out in (src,before):raise ValueError("output must be a NEW file")
 if out.exists():raise FileExistsError("refuse to overwrite existing export")
 tmp=out.with_name(out.name+".building")
 if tmp.exists():tmp.unlink()
 srcdb=sqlite3.connect(src.as_uri()+"?mode=ro",uri=True)
 linksdb=sqlite3.connect(before.as_uri()+"?mode=ro",uri=True)
 tmpdb=None
 try:
  if srcdb.execute("SELECT COUNT(*) FROM recording").fetchone()[0]!=expected:
   raise ValueError("Source count differs")
  if linksdb.execute("SELECT COUNT(*) FROM recording").fetchone()[0]!=expected:
   raise ValueError("App catalog count differs")
  if linksdb.execute("SELECT COUNT(*) FROM link_state WHERE verified!=0 OR automatic_applied!=0").fetchone()[0]:
   raise ValueError("App already has automatically approved proposals: refuse")
  # backup() is a consistent SQLite snapshot, not a filesystem copy of
  # possibly WAL-backed database state.
  tmpdb=sqlite3.connect(tmp)
  linksdb.backup(tmpdb)
  tmpdb.execute("PRAGMA foreign_keys=ON")
  tmpdb.executescript("""
  CREATE TABLE IF NOT EXISTS album_track (
   album_id TEXT NOT NULL REFERENCES album(id),
   recording_id TEXT NOT NULL REFERENCES recording(id),
   disc INTEGER NOT NULL DEFAULT 1,position INTEGER NOT NULL DEFAULT 0,
   PRIMARY KEY(album_id,recording_id,disc,position));
  CREATE INDEX IF NOT EXISTS idx_album_track_recording ON album_track(recording_id);
  CREATE TABLE IF NOT EXISTS external_recording_identity (
    provider TEXT NOT NULL,external_id TEXT NOT NULL,
    recording_id TEXT NOT NULL REFERENCES recording(id),
    source TEXT NOT NULL DEFAULT 'catalog_original',
    verified INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(provider,external_id));
  CREATE INDEX IF NOT EXISTS idx_external_recording_local
    ON external_recording_identity(recording_id);
  """)
  originals=srcdb.execute("SELECT id,artist_id,title FROM album ORDER BY id").fetchall()
  tmpdb.executemany("INSERT INTO album(id,artist_id,title) VALUES(?,?,?)",originals)
  albumtracks=srcdb.execute("SELECT album_id,recording_id,disc,position FROM album_track").fetchall()
  tmpdb.executemany("INSERT INTO album_track VALUES (?,?,?,?)",albumtracks)
  identities=srcdb.execute("SELECT provider,ext_id,recording_id FROM external_identity").fetchall()
  tmpdb.executemany("INSERT INTO external_recording_identity(provider,external_id,recording_id) VALUES(?,?,?)",identities)
  tmpdb.commit()
  catalog_count=tmpdb.execute("SELECT COUNT(*) FROM recording").fetchone()[0]
  album_count=tmpdb.execute("SELECT COUNT(*) FROM album").fetchone()[0]
  membership_count=tmpdb.execute("SELECT COUNT(*) FROM album_track").fetchone()[0]
  identity_count=tmpdb.execute("SELECT COUNT(*) FROM external_recording_identity").fetchone()[0]
  errors=tmpdb.execute("PRAGMA foreign_key_check").fetchall()
  if errors:raise ValueError("Foreign key errors: "+str(errors[:5]))
  if tmpdb.execute("PRAGMA integrity_check").fetchone()[0]!="ok":
   raise ValueError("Enriched SQLite corrupted")
  if catalog_count!=expected or album_count!=len(originals) or \
     membership_count!=len(albumtracks) or identity_count!=len(identities):
   raise ValueError("Export count mismatch")
  if tmpdb.execute("SELECT COUNT(*) FROM link_state WHERE verified!=0 OR automatic_applied!=0").fetchone()[0]:
   raise ValueError("Unverified links modified")
  stats={"catalog_recordings":catalog_count,
      "real_album_records":album_count,
      "real_album_track_memberships":membership_count,
      "external_recording_identity_rows":identity_count,
      "candidate_rows":tmpdb.execute("SELECT COUNT(*) FROM source_candidate").fetchone()[0],
      "link_states":dict(tmpdb.execute("SELECT status,COUNT(*) FROM link_state GROUP BY status").fetchall()),
      "source_unchanged":True,"master_modified":False,"verified_proposal_count":0,
      "note":"Album memberships and Apple IDs copied from existing catalog, not automatically guessed. Video links are review-only."}
  tmpdb.close();tmpdb=None
  out.parent.mkdir(parents=True,exist_ok=True)
  tmp.replace(out)
  return stats
 finally:
  if tmpdb is not None:tmpdb.close()
  srcdb.close();linksdb.close()
  if tmp.exists():tmp.unlink()

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--catalog",required=True,type=Path)
 p.add_argument("--links",required=True,type=Path)
 p.add_argument("--out",required=True,type=Path)
 a=p.parse_args()
 report=audit(a.catalog,a.links,a.out)
 print("ODIFY_V43_APP_EXPORT "+json.dumps(report,ensure_ascii=False))
if __name__=="__main__":main()
