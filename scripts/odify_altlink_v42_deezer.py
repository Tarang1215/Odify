#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4.2-b: independent Deezer + cached Apple/MusicBrainz catalog comparison.

The two initial sources had 503/429 outages. Query a THIRD legitimately
independent public catalog once per track, never rotate past rate blocks.
All results are review-only metadata; no music audio/video downloads, no
MASTER edits, no automatic YouTube match approval.
"""
from __future__ import annotations
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import sys
import time
from urllib.parse import urlencode

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v42_catalog as v42
import odify_altlink_v4_300 as v4
import odify_altlink_v41_review as v41
from odify_link_consensus import norm

SCHEMA="odify-v42-deezer-v1"
HEAD=("country","id","artist","title","v4_state","deezer_http_status",
 "deezer_track_id","deezer_artist_id","deezer_album_id","deezer_artist_name",
 "deezer_track","deezer_album","deezer_seconds","deezer_explicit",
 "apple_track_id","apple_artist_id","apple_album_id","apple_seconds",
 "apple_deezer_title_match","apple_deezer_artist_match",
 "apple_deezer_album_match","apple_deezer_duration_gap",
 "independent_catalog_status","original_v4_url","review_video_url",
 "review_video_channel","review_video_seconds","review_video_duration_gap",
 "version_warning","automatic_approval","master_write",
 "same_catalog_identity_on_multiple_chart_tracks")
TRACK_HINT=("country","id","artist","title","video_url","video_title","channel",
 "seconds","independent_track_length","difference","version_warning",
 "metadata_only","auto_approved")
HEADERS=v42.HEADERS

def parse_deezer(response):
 if not isinstance(response,dict) or isinstance(response.get("error"),dict):return []
 matches=[]
 for r in response.get("data",[])[:25]:
  if not isinstance(r,dict):continue
  artist=r.get("artist") or {}
  album=r.get("album") or {}
  duration=r.get("duration",0)
  matches.append({"provider":"deezer","recording_id":str(r.get("id","")),
   "track_name":r.get("title",""),"artist_name":artist.get("name",""),
   "artist_ids":[str(artist.get("id",""))],
   "artist_names":[artist.get("name","")],"album":album.get("title",""),
   "album_id":str(album.get("id","")),"release_date":"",
   "seconds":int(duration) if isinstance(duration,(int,float)) and 0<duration<3600 else 0,
   "explicitness":"explicit" if r.get("explicit_lyrics") is True else
        "notExplicit" if r.get("explicit_lyrics") is False else "unknown",
   "provider_score":r.get("rank",0)})
 return matches

def deezer_search_query(row):
 return "https://api.deezer.com/search?"+urlencode(
  {"q":v42.safe_text(row["artist"])+" "+v42.simplify_title(row["title"]),
   "limit":25})

def pick_pair(track,apple,deezer):
 a=v42.preferred(track,v42.parse_apple(apple,track))
 d=v42.preferred(track,parse_deezer(deezer))
 if not a or not d:return a,d,"one_or_zero_catalog",None,False,False
 title=v42.title_equivalent(a["track_name"],d["track_name"])
 artist=v42.artist_equivalent(a["artist_name"],d["artist_name"])
 gap=abs(a["seconds"]-d["seconds"]) if a["seconds"] and d["seconds"] else None
 album=bool(a["album"] and d["album"] and norm(a["album"])==norm(d["album"]))
 if title and artist and gap is not None and gap<=7 and     60<=a["seconds"]<=600 and 60<=d["seconds"]<=600:
  state="apple_deezer_consistent_review"
 elif title and artist and (a["seconds"]<60 or d["seconds"]<60):
  state="catalog_short_track_review"
 else:state="apple_deezer_disagree_or_version_review"
 return a,d,state,gap,album,(title and artist)

def version_warning(track,apple,deezer):
 raw=track["title"].casefold()
 expected_clean="clean ver" in raw
 expected_explicit="explicit ver" in raw
 findings=[]
 for provider,x in (("apple",apple),("deezer",deezer)):
  if not x:continue
  e=x.get("explicitness","").casefold()
  if expected_clean and e=="explicit":findings.append(provider+"_explicit_conflicts_clean")
  if expected_explicit and e in ("cleaned","notexplicit"):findings.append(provider+"_nonexplicit_conflicts_explicit")
  if ("live" in raw or "remaster" in raw) and \
    not any(t in x["track_name"].casefold() for t in ("live","remaster")):
   findings.append(provider+"_variant_unspecified")
 return ";".join(findings)

def audit(track,catalog_checkpoint,deezer_record,videos):
 apple_response=catalog_checkpoint["responses"].get("apple")
 a,d,state,gap,album,artist_title=pick_pair(track,apple_response,deezer_record.get("response"))
 vwarn=version_warning(track,a,d)
 if vwarn and state=="apple_deezer_consistent_review":
  state="version_warning_review"
 # Stronger catalog agreement is not evidence of uploader authenticity:
 # no original selection is ever replaced or verified by this method.
 verified_seconds=round((a["seconds"]+d["seconds"])/2) if \
   a and d and state=="apple_deezer_consistent_review" else 0
 candidates=v42.candidate_details(track,videos,verified_seconds,track["v4_choice"])
 compatible=[c for c in candidates if c["duration_evidence"]=="within_7s"
             and not c["version_warning"]]
 original=track["v4_choice"]
 best=compatible[0] if compatible else None
 output={"country":track["country"],"id":track["id"],"artist":track["artist"],
  "title":track["title"],"v4_state":track["v4_state"],
  "deezer_http_status":deezer_record["request"]["status"],
  "deezer_track_id":d["recording_id"] if d else "",
  "deezer_artist_id":d["artist_ids"][0] if d else "",
  "deezer_album_id":d.get("album_id","") if d else "",
  "deezer_artist_name":d["artist_name"] if d else "",
  "deezer_track":d["track_name"] if d else "",
  "deezer_album":d["album"] if d else "",
  "deezer_seconds":d["seconds"] if d else "",
  "deezer_explicit":d["explicitness"] if d else "",
  "apple_track_id":a["recording_id"] if a else "",
  "apple_artist_id":a["artist_ids"][0] if a else "",
  "apple_album_id":a.get("album_id","") if a else "",
  "apple_seconds":a["seconds"] if a else "",
  "apple_deezer_title_match":"YES" if artist_title else "NO",
  "apple_deezer_artist_match":"YES" if artist_title else "NO",
  "apple_deezer_album_match":"YES" if album else "NO",
  "apple_deezer_duration_gap":gap if gap is not None else "",
  "independent_catalog_status":state,"original_v4_url":original,
  "review_video_url":best["candidate_url"] if best else "",
  "review_video_channel":best["channel"] if best else "",
  "review_video_seconds":best["video_seconds"] if best else "",
  "review_video_duration_gap":best["seconds_difference"] if best else "",
  "version_warning":vwarn,"automatic_approval":"NO","master_write":"NO",
  "same_catalog_identity_on_multiple_chart_tracks":"NO"}
 hints=[{"country":track["country"],"id":track["id"],"artist":track["artist"],
  "title":track["title"],"video_url":c["candidate_url"],
  "video_title":c["video_title"],"channel":c["channel"],
  "seconds":c["video_seconds"],"independent_track_length":verified_seconds,
  "difference":c["seconds_difference"],
  "version_warning":c["version_warning"],"metadata_only":"YES",
  "auto_approved":"NO"} for c in compatible[:8]]
 return output,hints

def run(v4_path,v41_path,prior_path,out,targeted_path=None,
        limit=54,delay=1.1,timeout=9,offline=False,requester=v42.http_json):
 targets,grouped=v42.make_inputs(v4_path,v41_path,targeted_path)
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 prior_root=Path(prior_path)
 blocked=False
 query_errors=0
 for i,t in enumerate(targets[:limit],1):
  target=out/"checkpoints"/(t["country"]+"_"+t["id"]+".json")
  previous=prior_root/"checkpoints"/(t["country"]+"_"+t["id"]+".json")
  if not previous.exists():raise ValueError("Missing original Apple cached checkpoint")
  if target.exists():
   record=json.loads(target.read_text(encoding="utf-8"))
   if record.get("schema")!=SCHEMA or record.get("id")!=t["id"]:
    raise ValueError("Cached metadata mismatch")
  elif offline:
   continue
  else:
   url=deezer_search_query(t)
   if blocked:
    payload,status,ms,error=None,0,0,"provider_disabled"
   else:
    payload,status,ms,error=requester(url,timeout)
    if isinstance(payload,dict) and payload.get("error"):
     err=payload["error"]
     error="JSON quota/error: "+str(err)[:120]
     status=429 if isinstance(err,dict) and err.get("code")==4 else 0
     payload=None
    if status in (403,429,503):blocked=True
    elif status!=200:
     query_errors+=1
     if query_errors>=3:blocked=True
    else:query_errors=0
    if delay:time.sleep(delay)
   record={"schema":SCHEMA,"id":t["id"],"country":t["country"],
           "artist":t["artist"],"title":t["title"],
           "request":{"url":url,"status":status,"ms":ms,"error":error},
           "response":payload}
   v4.atomic(target,record)
  print("ODIFY_V42_DEEZER_PROGRESS "+json.dumps({"done":i,"total":len(targets),
    "artist":t["artist"],"status":record["request"]["status"]},ensure_ascii=False),flush=True)
 results=[];hint_rows=[];requests=[]
 for t in targets:
  path=out/"checkpoints"/(t["country"]+"_"+t["id"]+".json")
  if not path.exists():continue
  saved=json.loads(path.read_text(encoding="utf-8"))
  prior=json.loads((prior_root/"checkpoints"/(t["country"]+"_"+t["id"]+".json")).read_text())
  row,hints=audit(t,prior,saved,grouped[(t["country"],t["id"])])
  results.append(row);hint_rows.extend(hints)
  requests.append({"country":t["country"],"id":t["id"],**saved["request"]})
 # More than one chart record can point to the very same official catalog
 # recording (e.g. alternative chart artist credits). They are NOT unique
 # independently verified songs.
 from collections import defaultdict
 pairs=defaultdict(list)
 for row in results:
  if row["independent_catalog_status"]=="apple_deezer_consistent_review" and       row["apple_track_id"] and row["deezer_track_id"]:
   pairs[(row["apple_track_id"],row["deezer_track_id"])].append(row)
 for members in pairs.values():
  if len(members)>1:
   for item in members:item["same_catalog_identity_on_multiple_chart_tracks"]="YES"
 v41.write_rows(out,"v42_apple_deezer_catalog_54.csv",HEAD,results)
 v41.write_rows(out,"v42_independent_duration_video_hints.csv",TRACK_HINT,hint_rows)
 v41.write_rows(out,"v42_deezer_requests.csv",
    ("country","id","url","status","ms","error"),requests)
 summary={"target":54,"processed":len(results),
   "deezer_http_200":sum(x["status"]==200 for x in requests),
   "deezer_http_non_200":sum(x["status"]!=200 for x in requests),
   "two_catalog_same_title_artist_duration":sum(
     x["independent_catalog_status"]=="apple_deezer_consistent_review" for x in results),
   "two_catalog_album_match":sum(x["independent_catalog_status"]=="apple_deezer_consistent_review" and
        x["apple_deezer_album_match"]=="YES" for x in results),
   "unique_apple_deezer_recording_pairs":len(pairs),
   "duplicate_catalog_identity_chart_tracks":sum(
      x["same_catalog_identity_on_multiple_chart_tracks"]=="YES" for x in results),
   "excluded_short_catalog_recordings":sum(
      x["independent_catalog_status"]=="catalog_short_track_review" for x in results),
   "tracks_with_candidate_video_duration_hint":sum(bool(x["review_video_url"]) for x in results),
   "candidate_video_duration_hints":len(hint_rows),
   "version_conflicts_or_unconfirmed":sum(bool(x["version_warning"]) for x in results),
   "unchanged_v4_urls":len(results),"master_modified":False,"auto_approved_tracks":0,
   "caveat":"Two licensed catalogs can corroborate artist/song/duration but do NOT verify YouTube uploader identity, recording audio, rights or availability."}
 v4.atomic(out/"summary_v42_deezer.json",summary)
 print("ODIFY_V42_DEEZER_FINAL "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--v4",required=True,type=Path)
 p.add_argument("--v41",required=True,type=Path)
 p.add_argument("--prior",required=True,type=Path)
 p.add_argument("--targeted",type=Path)
 p.add_argument("--out",required=True,type=Path)
 p.add_argument("--limit",type=int,default=54)
 p.add_argument("--delay",type=float,default=1.1)
 p.add_argument("--timeout",type=float,default=9)
 p.add_argument("--offline",action="store_true")
 a=p.parse_args()
 if not 0<=a.limit<=54 or not .5<=a.delay<=30 or not 1<=a.timeout<=15:
  p.error("Invalid limits")
 run(a.v4,a.v41,a.prior,a.out,a.targeted,a.limit,a.delay,a.timeout,a.offline)
if __name__=="__main__":main()
