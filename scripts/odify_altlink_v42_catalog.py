#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4.2 independent catalog audit for V4 39 ambiguous + V4.1 15 review-only songs.

MusicBrainz & Apple iTunes Search provide independent artist/recording identifiers,
albums, release dates, version labels and track lengths. These catalogs CANNOT verify
YouTube upload ownership, availability, or audio identity. All outputs are read-only,
review-only; no MASTER or V4/V4.1 checkpoint writes.

Example:
 python scripts/odify_altlink_v42_catalog.py --v4 .local/safe \
   --v41 .local/review --targeted .local/targeted --out .local/v42
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import csv
import io
import json
from pathlib import Path
import re
import sys
import time
import unicodedata
from urllib.error import HTTPError,URLError
from urllib.parse import urlencode
from urllib.request import Request,urlopen
from zipfile import ZipFile

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v41_review as v41
import odify_altlink_v4_300 as v4
import odify_altlink_rerank as v3
from odify_link_consensus import norm

SCHEMA="odify-v42-catalog-v1"
REGIONS={"kr":"KR","jp":"JP","us":"US","gl":"US"}
USER_AGENT="Odify-Research-CatalogAudit/1.0 (https://github.com/Tarang1215/Odify)"
HEADERS={"User-Agent":USER_AGENT,"Accept":"application/json"}
FIELDS=("country","id","artist","title","original_state","catalog_status",
 "musicbrainz_recording_id","musicbrainz_artist_ids","musicbrainz_artist_credit",
 "musicbrainz_album","musicbrainz_duration_s","apple_track_id","apple_artist_id",
 "apple_collection_id","apple_artist_name","apple_album",
 "apple_duration_s","apple_explicitness","catalog_title_consistent",
 "catalog_artist_consistent","catalog_duration_agreement_s",
 "audited_video_candidates","duration_compatible_video_candidates",
 "catalog_version_conflicts","proposed_review_url","review_reason",
 "automatic_approval","master_write")
CAND_FIELDS=("country","id","artist","title","source","candidate_url","video_title",
 "channel","video_seconds","v4_eligible","catalog_duration_seconds",
 "seconds_difference","duration_evidence","version_warning","artist_evidence",
 "original_url_preserved","review_priority","review_only","approved")
RAW_FIELDS=("country","id","provider","status","latency_ms","error",
 "requested_query","matches","provider_id","source_url")

def load_csv(path,name):
 path=Path(path)
 if path.is_file() and path.suffix.lower()==".zip":
  with ZipFile(path) as z:
   names=[x for x in z.namelist() if x==name or x.endswith("/"+name)]
   if len(names)!=1:raise ValueError("expected one "+name)
   return list(csv.DictReader(io.StringIO(z.read(names[0]).decode("utf-8-sig"))))
 with (path/name).open(encoding="utf-8-sig",newline="") as f:
  return list(csv.DictReader(f))

def safe_text(v):
 return unicodedata.normalize("NFKC",str(v or "")).strip()

def simplify_title(v):
 value=safe_text(v)
 value=re.sub(r"\s*[\[(](?:feat\.?|ft\.?|with|featuring)[^()\[\]]*[\])]\s*"," ",value,flags=re.I)
 value=re.sub(r"\s*[-–—]\s*(?:clean|explicit)\s*ver(?:sion)?\.?\s*$","",value,flags=re.I)
 return value.strip()

def name_equivalent(want,found):
 a=norm(want);b=norm(found)
 if not a or not b:return False
 if a==b:return True
 # A known alias explicitly written INSIDE the source name is valid spelling
 # evidence (e.g. "SHINee (샤이니)"). Do not infer transliteration.
 vals=[v.strip() for v in re.split(r"[\(\)（）]",safe_text(want)) if norm(v)]
 return len(vals)>=2 and any(norm(v)==b for v in vals)

def artist_equivalent(want,found):
 if name_equivalent(want,found):return True
 # Only allow an explicitly credited part, not substring of an unrelated name.
 bits=re.split(r"\s+(?:feat\.?|featuring|ft\.?)\s+|\s*[&,＋＆]\s*",
     safe_text(want),flags=re.I)
 return len(bits)>=2 and any(name_equivalent(x,found) for x in bits)

def title_equivalent(want,found):
 if name_equivalent(simplify_title(want),simplify_title(found)):return True
 # Title with a named master recording and suffix may still require version review
 # (e.g. master title "Seven (feat. Latto) - Clean Ver.").
 return False

def http_json(url,timeout=9):
 start=time.monotonic()
 try:
  with urlopen(Request(url,headers=HEADERS),timeout=timeout) as resp:
   if resp.status!=200:raise RuntimeError("HTTP "+str(resp.status))
   blob=resp.read(1_500_001)
   if len(blob)>1_500_000:raise ValueError("Oversized catalog response")
   return json.loads(blob),200,round((time.monotonic()-start)*1000),""
 except HTTPError as e:
  return None,e.code,round((time.monotonic()-start)*1000),"HTTP "+str(e.code)
 except (URLError,TimeoutError,OSError,ValueError,RuntimeError) as e:
  return None,0,round((time.monotonic()-start)*1000),str(e)[:120]

def queries(row):
 title=simplify_title(row["title"])
 artist=safe_text(row["artist"])
 mb='recording:"'+re.sub(r'([\\"])',r'\\\1',title)+'" AND artist:"'+re.sub(r'([\\"])',r'\\\1',artist)+'"'
 return {
 "musicbrainz":("https://musicbrainz.org/ws/2/recording/?"+urlencode(
  {"query":mb,"fmt":"json","limit":12}),mb),
 "apple":("https://itunes.apple.com/search?"+urlencode(
  {"term":artist+" "+title,"country":REGIONS[row["country"]],
   "entity":"song","media":"music","limit":25}),artist+" "+title)
 }

def parse_mb(response,track):
 out=[]
 for r in (response or {}).get("recordings",[])[:12]:
  if not isinstance(r,dict):continue
  credit=r.get("artist-credit",[])
  artists=[x.get("artist",{}) for x in credit if isinstance(x,dict) and isinstance(x.get("artist"),dict)]
  credits=[x.get("name","") for x in credit if isinstance(x,dict)]
  length=r.get("length")
  seconds=round(length/1000) if isinstance(length,(int,float)) and 0<length<3600000 else 0
  releases=r.get("releases") or []
  out.append({"provider":"musicbrainz","recording_id":r.get("id",""),
   "track_name":r.get("title",""),"artist_name":" & ".join(credits),
   "artist_ids":[x.get("id","") for x in artists],
   "artist_names":[x.get("name","") for x in artists],
   "album":(releases[0].get("title","") if releases else ""),
   "release_date":(releases[0].get("date","") if releases else ""),
   "seconds":seconds,"disambiguation":r.get("disambiguation",""),
   "provider_score":r.get("score",0)})
 return out

def parse_apple(response,track):
 out=[]
 for r in (response or {}).get("results",[])[:25]:
  if r.get("wrapperType")!="track" or r.get("kind")!="song":continue
  millis=r.get("trackTimeMillis")
  seconds=round(millis/1000) if isinstance(millis,(int,float)) and millis>0 else 0
  out.append({"provider":"apple","recording_id":str(r.get("trackId") or ""),
   "track_name":r.get("trackName",""),"artist_name":r.get("artistName",""),
   "artist_ids":[str(r.get("artistId") or "")],"artist_names":[r.get("artistName","")],
   "album":r.get("collectionName",""),"album_id":str(r.get("collectionId") or ""),
   "release_date":r.get("releaseDate",""),"seconds":seconds,
   "explicitness":r.get("trackExplicitness",""),"provider_score":0})
 return out

def matches_metadata(track,r):
 t=title_equivalent(track["title"],r["track_name"])
 a=artist_equivalent(track["artist"],r["artist_name"]) or any(
    artist_equivalent(track["artist"],name) for name in r["artist_names"])
 return t,a

def preferred(track,items):
 graded=[]
 for x in items:
  match_title,match_artist=matches_metadata(track,x)
  if not match_title:continue
  # An alternative title/artist in a foreign script is not silently equated.
  if not match_artist:continue
  score=40+20*match_artist+10*bool(x["album"])+4*bool(x["seconds"])
  # Prefer recording releases that are not obviously a re-recorded/live version,
  # unless the master itself specifically asks for it.
  raw=(x["track_name"]+" "+x.get("disambiguation","")).casefold()
  expected=(track["title"]+" "+track.get("version","")).casefold()
  if re.search(r"\blive\b|\bremaster(?:ed)?\b|\binstrumental\b",raw) and not re.search(
       r"\blive\b|\bremaster(?:ed)?\b|\binstrumental\b",expected):
   score-=40
  graded.append((score,x))
 graded.sort(key=lambda z:(z[0],z[1]["recording_id"]),reverse=True)
 return graded[0][1] if graded else None

def resolve_catalog(track,mb_records,apple_records):
 mb=preferred(track,mb_records)
 ap=preferred(track,apple_records)
 consistent_title=bool(mb and ap and
     title_equivalent(mb["track_name"],ap["track_name"]))
 consistent_artist=bool(mb and ap and
     artist_equivalent(mb["artist_name"],ap["artist_name"]))
 # Two independent catalog durations refer to the same recorded version only
 # when their named song and artist agree.
 dur_diff=abs(mb["seconds"]-ap["seconds"]) if mb and ap and \
     mb["seconds"] and ap["seconds"] and consistent_title and consistent_artist else None
 if dur_diff is not None and dur_diff<=8:
  state="two_catalog_consistent_review"
 elif mb and ap:
  state="catalog_conflict_or_variant_review"
 elif mb or ap:
  state="single_catalog_review"
 else:state="catalog_identity_unresolved"
 return mb,ap,state,consistent_title,consistent_artist,dur_diff

def candidate_details(track,rows,seconds,baseline_url):
 outputs=[]
 for x in rows:
  video_seconds=int(x.get("seconds") or 0)
  delta=abs(video_seconds-seconds) if seconds else None
  if delta is None:dur="unconfirmed"
  elif delta<=7:dur="within_7s"
  elif delta<=20:dur="8_to_20s_review"
  else:dur="over_20s_conflict"
  headline=x.get("video_title","")
  master={"아티스트":track["artist"],"곡명":track["title"],
          "음원 버전":track.get("version","")}
  issue=v3.version_issue(master,{"title":headline,"channel":x.get("channel",""),
      "description":""})
  if re.search(r"\bunofficial\b",headline,re.I):issue="unofficial"
  elif re.search(r"\bpreview\b|\bteaser\b",headline,re.I):issue="short_preview"
  title=norm(track["title"])
  chan=norm(x.get("channel",""))
  art=v3.artist_evidence(track,{"title":headline,"channel":x.get("channel","")})
  # Metadata-only ordering; always review. Strong video duration alone
  # is never enough to approve uploader/recording rights.
  priority=40+(20 if dur=="within_7s" else 6 if dur=="8_to_20s_review" else 0)
  priority+=(12 if art else 0)
  priority+=(10 if x.get("v4_eligible","0")=="1" else 0)
  if issue:priority-=32
  if dur=="over_20s_conflict":priority-=23
  out={"country":track["country"],"id":track["id"],"artist":track["artist"],
       "title":track["title"],"source":x.get("source","v4"),
       "candidate_url":x.get("url",""),"video_title":headline,
       "channel":x.get("channel",""),"video_seconds":video_seconds,
       "v4_eligible":x.get("v4_eligible","0"),
       "catalog_duration_seconds":seconds or "",
       "seconds_difference":delta if delta is not None else "",
       "duration_evidence":dur,"version_warning":issue or "",
       "artist_evidence":"text_only" if art else "unconfirmed",
       "original_url_preserved":"YES" if out_url(x)==baseline_url else "NO",
       "review_priority":priority,"review_only":"YES","approved":"NO"}
  outputs.append(out)
 outputs.sort(key=lambda x:(-x["review_priority"],x["candidate_url"]))
 return outputs

def out_url(x):
 return x.get("url","") or x.get("candidate_url","")

def make_inputs(v4_path,v41_path,targeted_path=None):
 base=load_csv(v4_path,"audit_300_v4.csv")
 all_c=load_csv(v4_path,"candidates_300_v4.csv")
 a=load_csv(v41_path,"ambiguity_review_39.csv")
 recover=load_csv(v41_path,"new_title_review_candidates.csv")
 # Same 300-sample references only; reject new or fabricated track IDs.
 bykey={(x["country"],x["id"]):x for x in base}
 if len(bykey)!=300 or len(base)!=300:raise ValueError("Need exactly 300 unique V4 records")
 ambiguous={(x["country"],x["id"]) for x in a}
 new={(x["country"],x["id"]) for x in recover}
 if len(ambiguous)!=39 or len(new)!=14 or ambiguous&new:
  raise ValueError("Unexpected V4.1 39+14 review split")
 grouped=defaultdict(list)
 for c in all_c:
  key=c["country"],c["id"]
  if key not in bykey:raise ValueError("Candidate does not belong to sample")
  if key in ambiguous:
   grouped[key].append({**c,"source":"v4"})
 for c in recover:
  key=c["country"],c["id"]
  if key not in bykey:raise ValueError("Recovered candidate not in sample")
  grouped[key].append({"country":c["country"],"id":c["id"],
   "artist":c["artist"],"title":c["title"],"url":c["candidate_url"],
   "video_title":c["video_title"],"channel":c["channel"],
   "seconds":c["seconds"],"v4_eligible":"0","source":"v41_recovered"})
 if targeted_path:
  targeted=load_csv(targeted_path,"five_track_live_audit.csv")
  if len(targeted)!=5:raise ValueError("Targeted V41 artifact must have 5 tracks")
  for c in targeted:
   key=(c["country"],c["track_id"])
   if c["best_url"]:
    if key in new or key in ambiguous or key not in bykey:raise ValueError("Target duplicate")
    new.add(key)
    grouped[key].append({"country":c["country"],"id":c["track_id"],
     "artist":c["artist"],"title":c["title"],"url":c["best_url"],
     "video_title":"","channel":"","seconds":"0","v4_eligible":"0",
     "source":"v41_targeted"})
 targets=[bykey[k] for k in sorted(ambiguous|new)]
 if len(targets)!=54:raise ValueError("Expected exactly 54 target songs (39+15)")
 return targets,grouped

def audit_song(track,checkpoint,candidates):
 mb_records=parse_mb(checkpoint["responses"].get("musicbrainz"),track)
 apple_records=parse_apple(checkpoint["responses"].get("apple"),track)
 mb,ap,status,title_ok,artist_ok,delta=resolve_catalog(track,mb_records,apple_records)
 # An individual catalog duration can suggest candidates, but cannot
 # confer strong two-catalog agreement without independent corroboration.
 common_seconds=round((mb["seconds"]+ap["seconds"])/2) if \
    status=="two_catalog_consistent_review" else 0
 candidates=candidate_details(track,candidates,common_seconds,track["v4_choice"])
 candidate_best=next((x for x in candidates if x["duration_evidence"]=="within_7s"
       and not x["version_warning"]),None)
 stats={"country":track["country"],"id":track["id"],"artist":track["artist"],
  "title":track["title"],"original_state":track["v4_state"],"catalog_status":status,
  "musicbrainz_recording_id":mb["recording_id"] if mb else "",
  "musicbrainz_artist_ids":"|".join(mb["artist_ids"]) if mb else "",
  "musicbrainz_artist_credit":mb["artist_name"] if mb else "",
  "musicbrainz_album":mb["album"] if mb else "",
  "musicbrainz_duration_s":mb["seconds"] if mb else "",
  "apple_track_id":ap["recording_id"] if ap else "",
  "apple_artist_id":ap["artist_ids"][0] if ap else "",
  "apple_collection_id":ap.get("album_id","") if ap else "",
  "apple_artist_name":ap["artist_name"] if ap else "",
  "apple_album":ap["album"] if ap else "",
  "apple_duration_s":ap["seconds"] if ap else "",
  "apple_explicitness":ap.get("explicitness","") if ap else "",
  "catalog_title_consistent":"YES" if title_ok else "NO",
  "catalog_artist_consistent":"YES" if artist_ok else "NO",
  "catalog_duration_agreement_s":delta if delta is not None else "",
  "audited_video_candidates":len(candidates),
  "duration_compatible_video_candidates":sum(x["duration_evidence"]=="within_7s" for x in candidates),
  "catalog_version_conflicts":sum(bool(x["version_warning"]) for x in candidates),
  "proposed_review_url":candidate_best["candidate_url"] if candidate_best else "",
  "review_reason":"two_catalog_duration_hint_unverified_upload" if candidate_best else
       "no_independently_corroborrated_youtube_candidate",
  "automatic_approval":"NO","master_write":"NO"}
 return stats,candidates

def run(v4_path,v41_path,out,targeted_path=None,limit=54,offline=False,timeout=9,mb_delay=1.2,apple_delay=1.0,requester=http_json):
 targets,bykey=make_inputs(v4_path,v41_path,targeted_path)
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 settings={"schema":SCHEMA,"target_count":len(targets),"mb_delay":mb_delay,
       "apple_delay":apple_delay}
 settings_file=out/"settings.json"
 if settings_file.exists() and json.loads(settings_file.read_text())!=settings:
  raise ValueError("Settings mismatch; avoid mixing checkpoints")
 if not settings_file.exists():v4.atomic(settings_file,settings)
 errors=defaultdict(int)
 for i,track in enumerate(targets[:limit],1):
  path=out/"checkpoints"/(track["country"]+"_"+track["id"]+".json")
  if path.exists():
   record=json.loads(path.read_text(encoding="utf-8"))
   if record.get("schema")!=SCHEMA or record.get("artist")!=track["artist"] or record.get("title")!=track["title"]:
    raise ValueError("Checkpoint changed; refuse stale reference")
  elif offline:
   continue
  else:
   requests={};responses={}
   for name,(url,q) in queries(track).items():
    if errors[name]>=2:
     requests[name]={"status":0,"ms":0,"error":"provider_disabled",
                     "query":q,"url":url}
     responses[name]=None
     continue
    payload,status,ms,error=requester(url,timeout)
    requests[name]={"status":status,"ms":ms,"error":error,
                    "query":q,"url":url}
    responses[name]=payload if status==200 else None
    if status in (403,429,503):errors[name]=2
    elif status!=200:errors[name]+=1
    else:errors[name]=0
    if name=="musicbrainz" and mb_delay:time.sleep(mb_delay)
    if name=="apple" and apple_delay:time.sleep(apple_delay)
   record={"schema":SCHEMA,"id":track["id"],"country":track["country"],
     "artist":track["artist"],"title":track["title"],
     "requests":requests,"responses":responses}
   v4.atomic(path,record)
  print("ODIFY_V42_PROGRESS "+json.dumps({"done":i,"target":len(targets),
   "artist":track["artist"],"title":track["title"],
   "providers":{k:v["status"] for k,v in record["requests"].items()}},
    ensure_ascii=False),flush=True)
 records=[]
 for track in targets:
  path=out/"checkpoints"/(track["country"]+"_"+track["id"]+".json")
  if path.exists():
   records.append((track,json.loads(path.read_text(encoding="utf-8"))))
 rows=[];candidates=[];requests=[]
 for track,rec in records:
  audit,cands=audit_song(track,rec,bykey[(track["country"],track["id"])])
  rows.append(audit);candidates.extend(cands)
  for provider,req in rec["requests"].items():
   requests.append({"country":track["country"],"id":track["id"],
     "provider":provider,"status":req["status"],"latency_ms":req["ms"],
     "error":req["error"],"requested_query":req["query"],
     "matches":len((rec["responses"].get(provider) or {}).get(
          "recordings" if provider=="musicbrainz" else "results",[])),
     "provider_id":audit["musicbrainz_recording_id"] if provider=="musicbrainz" else audit["apple_track_id"],
     "source_url":req["url"]})
 v41.write_rows(out,"v42_catalog_identity_54.csv",FIELDS,rows)
 v41.write_rows(out,"v42_video_duration_review.csv",CAND_FIELDS,candidates)
 v41.write_rows(out,"v42_external_requests.csv",RAW_FIELDS,requests)
 summary={"target":len(targets),"processed":len(rows),
 "ambiguous_processed":sum(x["original_state"]=="ambiguous_review" for x in rows),
 "recovered_processed":sum(x["original_state"]=="no_eligible_candidate" for x in rows),
 "both_catalogs_agree_on_artist_title_duration":sum(x["catalog_status"]=="two_catalog_consistent_review" for x in rows),
 "single_catalog_metadata":sum(x["catalog_status"]=="single_catalog_review" for x in rows),
 "two_catalog_conflicts":sum(x["catalog_status"]=="catalog_conflict_or_variant_review" for x in rows),
 "identity_unresolved":sum(x["catalog_status"]=="catalog_identity_unresolved" for x in rows),
 "tracks_with_duration_compatible_video_hints":sum(x["duration_compatible_video_candidates"]>0 for x in rows),
 "catalog_requests":len(requests),
 "catalog_http_ok":sum(x["status"]==200 for x in requests),
 "catalog_http_failed":sum(x["status"]!=200 for x in requests),
 "candidate_review_rows":len(candidates),
 "previous_v4_selection_changes":0,
 "automatic_approvals":0,"master_changed":False,
 "caveat":"Neither catalog metadata nor matching video length proves licensed upload or identical audio. No track or URL is automatically verified."}
 v4.atomic(out/"summary_v42.json",summary)
 print("ODIFY_V42_FINAL "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--v4",required=True,type=Path)
 p.add_argument("--v41",required=True,type=Path)
 p.add_argument("--targeted",type=Path)
 p.add_argument("--out",required=True,type=Path)
 p.add_argument("--limit",type=int,default=54)
 p.add_argument("--offline",action="store_true")
 p.add_argument("--timeout",type=float,default=9)
 p.add_argument("--mb-delay",type=float,default=1.2)
 p.add_argument("--apple-delay",type=float,default=1.0)
 a=p.parse_args()
 if not 0<=a.limit<=54 or not 1<=a.timeout<=15 or not 1.1<=a.mb_delay<=30 or not .5<=a.apple_delay<=30:
  p.error("Invalid safety bounds")
 run(a.v4,a.v41,a.out,a.targeted,a.limit,a.offline,a.timeout,a.mb_delay,a.apple_delay)
if __name__=="__main__":main()
