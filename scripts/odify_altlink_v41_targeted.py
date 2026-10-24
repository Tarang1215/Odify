#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""V4.1 bounded follow-up search for the five unresolved 300-song records.

Uses public Piped metadata endpoints, 2 requests maximum per song, 5 songs.
Resumable by per-recording JSON checkpoint. Never changes MASTER or any source.
"""
from __future__ import annotations
import argparse
import csv
import json
from pathlib import Path
import time
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v41_review as review
import odify_altlink_v4_300 as piped

REQUIRED=(("kr","Shane","없어"),("jp","マルーン5","マップス"),
 ("jp","宇多田ヒカル","First Love (2014 Remastered)"),
 ("jp","=LOVE","劇薬中毒"),("jp","パスピエ","裏の裏"))

def target_rows(input_dir):
 rows=review.load_rows(input_dir,"diagnostics_300.csv")
 targets=[x for x in rows if x["v4_state"]=="no_eligible_candidate"
                 and x["recoverable_review_candidates"]=="0"]
 if len(rows)!=300 or len(targets)!=5:raise ValueError("Expected 300 audit rows and exactly 5 unresolved")
 for region,artist,title in REQUIRED:
  if not any(x["country"]==region and x["artist"]==artist and x["title"]==title
             for x in targets):
   raise ValueError("Unexpected gap sample; do not run live requests")
 return targets

def variants(track):
 artist=review.ARTIST_HINTS.get(track["artist"],track["artist"])
 translated=review.TITLE_HINTS.get((track["country"],track["title"]),track["title"])
 # Korean/JP official names remain in original chart metadata if no
 # verified romanization is available.
 if track["country"]=="jp" and track["artist"]=="宇多田ヒカル":
  translated="First Love 2014 Remastered"
 return [(artist+" "+translated+" official audio","music_songs"),
         ('"'+translated+'" '+artist+" official","videos")]

def run(source,out,bases=piped.DEFAULT_BASES,timeout=7,delay=.8,fetcher=piped.fetch):
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 bases=tuple(piped.check_origin(x) for x in bases)
 targets=target_rows(source)
 health={"disabled":set(),"failures":__import__("collections").defaultdict(int)}
 results=[]
 for row in targets:
  path=out/"checkpoints"/(row["country"]+"_"+row["id"]+".json")
  if path.exists():
   record=json.loads(path.read_text(encoding="utf-8"))
   if record.get("track_id")!=row["id"] or record.get("artist")!=row["artist"]:
    raise ValueError("Checkpoint mismatch; use a new folder")
  else:
   track={"country":row["country"],"id":row["id"],"artist":row["artist"],"title":row["title"]}
   searches=[];found={}
   for query,filter_name in variants(track):
    if len(health["disabled"])==len(bases):break
    items,attempts=piped.search(track,query,filter_name,bases,health,timeout,delay,fetcher)
    searches.extend(attempts)
    for x in items:
     found[x["url"]]=x
   candidates=[]
   for x in found.values():
    r=review.review_candidate(track,{"url":x["url"],"video_title":x["title"],
          "channel":x["channel"],"seconds":x["seconds"],"views":x["views"]})
    if r:candidates.append(r)
   candidates.sort(key=lambda x:(-x["review_score"],x["candidate_url"]))
   record={"track_id":row["id"],"country":row["country"],"artist":row["artist"],
     "title":row["title"],"searches":searches,"distinct_candidates":len(found),
     "review_candidates":candidates,
     "status":"SEARCH_BLOCKED" if health["disabled"] else "REVIEW_ONLY",
     "master_write":"NO","verified":"NO"}
   piped.atomic(path,record)
  results.append(record)
  piped.atomic(out/"progress.json",{"done":len(results),"total":5,
     "http_requests":sum(len(r["searches"]) for r in results),
     "review_songs":sum(bool(r["review_candidates"]) for r in results)})
  print("ODIFY_V41_TARGETED "+json.dumps({"done":len(results),"track":row["title"],
    "requests":len(record["searches"]),"review_candidates":len(record["review_candidates"]),
    "status":record["status"]},ensure_ascii=False),flush=True)
 fields=("country","track_id","artist","title","distinct_candidates",
         "review_candidate_count","http_requests","status","best_url","verified","master_write")
 audit=[]
 for r in results:
  audit.append({"country":r["country"],"track_id":r["track_id"],"artist":r["artist"],
   "title":r["title"],"distinct_candidates":r["distinct_candidates"],
   "review_candidate_count":len(r["review_candidates"]),"http_requests":len(r["searches"]),
   "status":r["status"],"best_url":r["review_candidates"][0]["candidate_url"]
     if r["review_candidates"] else "","verified":"NO","master_write":"NO"})
 review.write_rows(out,"five_track_live_audit.csv",fields,audit)
 summary={"target":5,"processed":len(results),
  "new_http_requests":sum(len(r["searches"]) for r in results),
  "http_200":sum(a["status"]==200 for r in results for a in r["searches"]),
  "http_rejected":sum(a["status"]!=200 for r in results for a in r["searches"]),
  "found_any_metadata_video":sum(r["distinct_candidates"]>0 for r in results),
  "found_new_review_candidates":sum(bool(r["review_candidates"]) for r in results),
  "existing_master_changed":False,"approved_music_matches":0,
  "note":"Provider results and localized title aliases are unverified; URLs require human review."}
 piped.atomic(out/"summary_targeted_v41.json",summary)
 print("ODIFY_V41_TARGETED_FINAL "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--input",required=True,type=Path)
 p.add_argument("--out",required=True,type=Path)
 p.add_argument("--timeout",type=float,default=7)
 p.add_argument("--delay",type=float,default=.8)
 p.add_argument("--base",action="append")
 args=p.parse_args()
 if not 1<=args.timeout<=15 or not .25<=args.delay<=30:
  p.error("timeout 1..15, delay >= .25")
 run(args.input,args.out,tuple(args.base or piped.DEFAULT_BASES),args.timeout,args.delay)
if __name__=="__main__":main()
