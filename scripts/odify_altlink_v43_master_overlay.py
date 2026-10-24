#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read-only MASTER snapshot import for a NEW Odify V4.3 app catalog copy.

Protects known Google MASTER URLs by exact unique artist/song matching ONLY;
never guesses transliterations, duplicates, or media versions. The source
MASTER CSV and unannotated app DB remain byte-for-byte untouched.
"""
from __future__ import annotations
from collections import Counter,defaultdict
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sqlite3
import unicodedata
from urllib.parse import urlparse,parse_qs

def norm(v):
 return "".join(ch for ch in unicodedata.normalize("NFKC",str(v or "")).casefold() if ch.isalnum())

def canon(u):
 p=urlparse((u or "").strip())
 if p.scheme!="https" or p.hostname not in ("www.youtube.com","youtube.com","m.youtube.com","music.youtube.com","youtu.be"):
  return ""
 vid=(p.path.strip("/").split("/")[0] if p.hostname=="youtu.be" else
      parse_qs(p.query).get("v",[""])[0])
 if len(vid)!=11 or any(not (x.isalnum() or x in "_-") for x in vid):return ""
 return "https://www.youtube.com/watch?v="+vid

def load_master(path):
 with Path(path).open(encoding="utf-8-sig",newline="") as f:
  rows=list(csv.DictReader(f))
 grouped=defaultdict(list)
 for row in rows:
  artist=row.get("아티스트","")
  title=row.get("곡명","")
  if not artist or not title:continue
  url=canon(row.get("YouTube 오디오 URL",""))
  if not url:continue
  grouped[norm(artist),norm(title)].append({
    "url":url,"status":row.get("링크검증",""),
    "track_id":row.get("Track ID","")})
 return grouped

def apply(input_db,master_csv,output_db,expected=41416):
 src=Path(input_db).resolve()
 master=Path(master_csv).resolve()
 out=Path(output_db).resolve()
 if out in (src,master):raise ValueError("new output file required")
 if out.exists():raise FileExistsError("Refuse to overwrite")
 tmp=out.with_name(out.name+".building")
 if tmp.exists():tmp.unlink()
 srcconn=sqlite3.connect(src.as_uri()+"?mode=ro",uri=True)
 dest=None
 try:
  if srcconn.execute("SELECT count(*) FROM recording").fetchone()[0]!=expected:
   raise ValueError("Wrong catalog size")
  dest=sqlite3.connect(tmp)
  srcconn.backup(dest)
  tracks=defaultdict(list)
  for rid,artist,title in dest.execute(
      "SELECT r.id,a.name,r.title FROM recording r JOIN artist a ON r.artist_id=a.id"):
   tracks[norm(artist),norm(title)].append(rid)
  original=load_master(master)
  stats=Counter()
  conflicts=[]
  for key,links in original.items():
   ids=tracks.get(key,[])
   stats["master_unique_artist_title_keys"]+=1
   if not ids:
    stats["master_key_not_found"]+=1;continue
   if len(ids)!=1:
    stats["ambiguous_catalog_identity"]+=1
    conflicts.append({"artist_normalized":key[0],"title_normalized":key[1],
      "reason":"multiple_catalog_recording_ids","master_urls":len(links),
      "catalog_recording_ids":"|".join(ids)})
    continue
   unique={(x["url"],x["status"]) for x in links}
   if len(unique)!=1:
    stats["ambiguous_master_urls_or_status"]+=1
    conflicts.append({"artist_normalized":key[0],"title_normalized":key[1],
      "reason":"master_duplicate_conflict","master_urls":len(links),
      "catalog_recording_ids":"|".join(ids)})
    continue
   url,status=next(iter(unique))
   state=("existing_verified_protected" if status=="검증완료" else
       "existing_link_protected")
   dest.execute("""UPDATE link_state SET
     status=?,existing_master_url=?,existing_master_status=?,
     reason=CASE WHEN reason IS NULL OR reason='' THEN 'MASTER snapshot protected'
                 ELSE reason || '; MASTER snapshot protected' END
     WHERE recording_id=?""",(state,url,status,ids[0]))
   stats["protected_master_links"]+=1
   if status=="검증완료":stats["protected_verified_links"]+=1
   else:stats["protected_unverified_links"]+=1
  if dest.execute("SELECT COUNT(*) FROM link_state WHERE verified!=0 OR automatic_applied!=0").fetchone()[0]:
   raise ValueError("Automatic verify/apply not permitted")
  dest.commit()
  if dest.execute("PRAGMA integrity_check").fetchone()[0]!="ok":
   raise ValueError("Output DB corrupted")
  dest.close();dest=None
  out.parent.mkdir(parents=True,exist_ok=True)
  tmp.replace(out)
  cf=out.with_name(out.stem+"_master_identity_conflicts.csv")
  with cf.open("w",encoding="utf-8-sig",newline="") as f:
   w=csv.DictWriter(f,fieldnames=("artist_normalized","title_normalized",
     "reason","master_urls","catalog_recording_ids"))
   w.writeheader();w.writerows(conflicts)
  return {"catalog_size":expected,**dict(stats),
   "review_conflicts":len(conflicts),"source_master_modified":False,
   "source_db_modified":False,"auto_applied":0,
   "caveat":"Only one-to-one exact normalized artist+song names protected; unmatched MASTER keys need source-confirmed aliases."}
 finally:
  if dest is not None:dest.close()
  srcconn.close()
  if tmp.exists():tmp.unlink()

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--input",required=True,type=Path)
 p.add_argument("--master",required=True,type=Path)
 p.add_argument("--out",required=True,type=Path)
 a=p.parse_args()
 result=apply(a.input,a.master,a.out)
 print("ODIFY_V43_MASTER_OVERLAY "+json.dumps(result,ensure_ascii=False))
if __name__=="__main__":main()
