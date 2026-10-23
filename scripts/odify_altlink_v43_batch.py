#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4.3: 41,416-track read-only manifest, 1,000-song resumable API pilot.

The prior 300 live checkpoints are reused without requests. New songs use the
existing safe V4 search + version-aware reranker. No MASTER or app writes.
Produces a self-contained offline SQLite catalog export for Odify import review.

python scripts/odify_altlink_v43_batch.py --catalog .local/odify_catalog.sqlite3 \
  --v4-cache .local/v4_300 --out .local/v43 --limit 1000
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import statistics
import sys
from urllib.parse import urlparse

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v4_300 as v4
import odify_altlink_rerank as v3
from odify_link_consensus import norm,canonical

REGIONS=("kr","jp","us","gl")
QUOTAS={"kr":300,"jp":300,"us":321,"gl":79}
EXPECTED_CATALOG=41416
SCHEMA="odify-v43-1"
CAT_FIELDS=("recording_id","artist_id","artist","title","country",
 "album","version","release_date","chart_date","chart_rank","chart_provider")
AUDIT_FIELDS=("recording_id","country","artist","title","source",
 "candidate_count","eligible_count","state","proposed_url","proposed_score",
 "existing_url","existing_status","decision","fresh_requests","http_ok",
 "catalog_source","verified","safe_to_auto_import")
def atomic(path,record):
 v4.atomic(path,record)

def catalog_records(path):
 """Read source SQLite in ro mode; never replace or migrate the source file."""
 uri=Path(path).resolve().as_uri()+"?mode=ro"
 con=sqlite3.connect(uri,uri=True)
 con.row_factory=sqlite3.Row
 try:
  assert con.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
  values=con.execute("""SELECT r.id,r.artist_id,a.name AS artist,r.title,
    r.version,r.release_date FROM recording r JOIN artist a
    ON r.artist_id=a.id ORDER BY r.id""").fetchall()
  if len(values)!=EXPECTED_CATALOG or len({x["id"] for x in values})!=EXPECTED_CATALOG:
   raise ValueError("Expected immutable 41,416 unique recording IDs")
  # Latest chart assignment by recording (a recording may chart in many countries).
  chart={}
  for row in con.execute("""SELECT ce.recording_id,e.country,e.as_of,e.provider,ce.rank
      FROM chart_entry ce JOIN chart_edition e ON ce.edition_id=e.id
      ORDER BY e.as_of DESC,ce.rank ASC,e.country ASC"""):
   chart.setdefault(row["recording_id"],dict(row))
  album={}
  for row in con.execute("""SELECT at.recording_id,al.title
     FROM album_track at JOIN album al ON al.id=at.album_id
     ORDER BY at.disc,at.position,al.id"""):
   album.setdefault(row["recording_id"],row["title"])
  result=[]
  for r in values:
   c=chart.get(r["id"],{})
   result.append({"recording_id":r["id"],"artist_id":r["artist_id"],
      "artist":r["artist"],"title":r["title"],
      "country":c.get("country","other"),"album":album.get(r["id"],""),
      "version":r["version"] or "original","release_date":r["release_date"] or "",
      "chart_date":c.get("as_of",""),"chart_rank":c.get("rank",0),
      "chart_provider":c.get("provider","")})
  return result
 finally:con.close()

def import_prior(path):
 if not path:return {}
 source={}
 for rec in v3.read_checkpoints(path):
  if rec.get("schema")!=v4.SCHEMA or not isinstance(rec.get("track"),dict):
   raise ValueError("Unexpected prior V4 checkpoint schema")
  t=rec["track"];rid=t["id"]
  if rid in source:raise ValueError("Prior V4 duplicates recording "+rid)
  source[rid]=rec
 if len(source)!=300:raise ValueError("Need complete 300-track V4 archived checkpoints")
 return source

def release_bin(r):
 year=int((r.get("chart_date") or "0000")[:4])
 if year>=2026:return "2026"
 if year>=2020:return "2020_25"
 if year>=2010:return "2010s"
 return "before_2010"

def build_manifest(records,prior):
 byid={x["recording_id"]:x for x in records}
 if len(byid)!=EXPECTED_CATALOG:raise ValueError("Catalog cardinality changed")
 chosen=[]
 used=set()
 # Original 300 sample: deterministic region/id ordering avoids depending
 # on archive zip internal ordering or historical checkpoint sequencing.
 for country in REGIONS:
  saved=sorted((r for r in prior.values() if r["track"]["country"]==country),
                key=lambda r:r["track"]["id"])
  if len(saved)!=75:raise ValueError("Original baseline not 75/region: "+country)
  for r in saved:
   t=r["track"];record=byid.get(t["id"])
   if not record or t["artist"]!=record["artist"] or t["title"]!=record["title"]:
    raise ValueError("Baseline track changed or missing: "+t["id"])
   if t["id"] in used:raise ValueError("Baseline duplicate")
   chosen.append({**record,"country":country,"source":"v4_cache"})
   used.add(t["id"])
 for country in REGIONS:
  quota=QUOTAS[country]-75
  if quota<0:raise ValueError("Baseline region exceeds quota")
  available=[x for x in records if x["country"]==country and x["recording_id"] not in used]
  groups=defaultdict(list)
  for x in available:groups[release_bin(x)].append(x)
  # For broad catalog coverage include historical music rather than
  # selecting only the newest popular chart. Pseudorandom but deterministic.
  for label,group in groups.items():
   group.sort(key=lambda r:hashlib.sha256((country+":"+r["recording_id"]).encode()).hexdigest())
  weights={"2026":.3,"2020_25":.4,"2010s":.2,"before_2010":.1}
  selected=[]
  for label,weight in weights.items():
   target=int(round(quota*weight))
   for item in groups.get(label,[])[:target]:
    if item["recording_id"] not in used:
     used.add(item["recording_id"]);selected.append({**item,"source":"new"})
  if len(selected)<quota:
   fallback=sorted((r for r in available if r["recording_id"] not in used),
    key=lambda r:hashlib.sha256((country+":"+r["recording_id"]).encode()).hexdigest())
   for x in fallback[:quota-len(selected)]:
    used.add(x["recording_id"]);selected.append({**x,"source":"new"})
  if len(selected)<quota:raise ValueError(country+" has too few new catalog recordings")
  # If rounded bin targets overshot quota, deterministic truncate and unmark.
  for x in selected[quota:]:used.remove(x["recording_id"])
  chosen.extend(selected[:quota])
 if len(chosen)!=1000 or len({x["recording_id"] for x in chosen})!=1000:
  raise ValueError("1k manifest uniqueness failed")
 if Counter(x["country"] for x in chosen)!=Counter(QUOTAS):
  raise ValueError("1k region sample is not quota-balanced")
 return chosen

def track_for_v4(x):
 return {"country":x["country"],"id":x["recording_id"],
   "artist":x["artist"],"title":x["title"],"album":x["album"],
   "version":x["version"],"source_date":x["chart_date"],
   "source_rank":x["chart_rank"],"source_provider":x["chart_provider"],
   "reference_url":""}

def master_protection(path):
 """Match only unambiguous exact artist/title tuples. Do not edit MASTER."""
 if path is None:return {}
 with Path(path).open(encoding="utf-8-sig",newline="") as fp:
  rows=list(csv.DictReader(fp))
 protected={}
 for row in rows:
  k=(norm(row.get("아티스트","")),norm(row.get("곡명","")))
  if not all(k):continue
  raw=row.get("YouTube 오디오 URL","")
  url=canonical(raw)
  if not url:continue
  status=row.get("링크검증","")
  previous=protected.get(k)
  value=(url,status)
  if previous is not None and previous!=value:
   protected[k]=("","AMBIGUOUS_MASTER_DUPLICATE")
  elif previous is None:protected[k]=value
 return protected

def export_database(catalog_path,destination,manifest,results,master=None):
 """Standalone read-only-for-the-app export. Never alters input catalog."""
 catalog=catalog_records(catalog_path)
 mapped={x["recording_id"]:x for x in catalog}
 path=Path(destination)
 tmp=path.with_suffix(".tmp")
 if tmp.exists():tmp.unlink()
 con=sqlite3.connect(tmp)
 try:
  con.executescript("""
    PRAGMA journal_mode=DELETE;
    PRAGMA foreign_keys=ON;
    CREATE TABLE artist (
      id TEXT PRIMARY KEY,name TEXT NOT NULL);
    CREATE TABLE album (
      id TEXT PRIMARY KEY,title TEXT NOT NULL,artist_id TEXT);
    CREATE TABLE recording (
      id TEXT PRIMARY KEY,artist_id TEXT NOT NULL REFERENCES artist(id),
      title TEXT NOT NULL,album TEXT,version TEXT,release_date TEXT,
      country TEXT,chart_date TEXT);
    CREATE TABLE source_candidate (
      recording_id TEXT NOT NULL REFERENCES recording(id),
      video_id TEXT NOT NULL,url TEXT NOT NULL,title TEXT,
      channel TEXT,seconds INTEGER,score INTEGER,metadata_eligible INTEGER,
      selected_for_review INTEGER,review_only INTEGER NOT NULL DEFAULT 1,
      PRIMARY KEY(recording_id,video_id));
    CREATE TABLE link_state (
      recording_id TEXT PRIMARY KEY REFERENCES recording(id),
      status TEXT NOT NULL DEFAULT 'unprocessed',
      proposal_url TEXT,existing_master_url TEXT,
      existing_master_status TEXT,reason TEXT,
      source TEXT,verified INTEGER NOT NULL DEFAULT 0,
      automatic_applied INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE search_attempt (
      recording_id TEXT NOT NULL REFERENCES recording(id),
      ordinal INTEGER NOT NULL,provider TEXT,status INTEGER,
      latency_ms INTEGER,endpoint TEXT,error TEXT,
      PRIMARY KEY(recording_id,ordinal));
    CREATE INDEX idx_recording_artist ON recording(artist_id);
    CREATE INDEX idx_recording_country ON recording(country);
    CREATE INDEX idx_state_status ON link_state(status);
    CREATE INDEX idx_candidates_selected ON source_candidate(selected_for_review);
  """)
  artists={r["artist_id"]:r["artist"] for r in catalog}
  con.executemany("INSERT INTO artist VALUES (?,?)",sorted(artists.items()))
  con.executemany("INSERT INTO recording VALUES (?,?,?,?,?,?,?,?)",
   ((r["recording_id"],r["artist_id"],r["title"],r["album"],r["version"],
     r["release_date"],r["country"],r["chart_date"]) for r in catalog))
  p=master or {}
  for row in catalog:
   rid=row["recording_id"];key=(norm(row["artist"]),norm(row["title"]))
   protected=p.get(key,("",""))
   cached=results.get(rid)
   status="unprocessed";proposal="";reasons="";source=""
   if cached:
    status=cached["v4"]["state"]
    proposal=cached["v4"].get("selected_url","")
    reasons=cached["v4"].get("reason","") or status
    source=cached.get("v43_source","")
    if cached.get("v43_result_status")=="provider_unavailable":
     status="provider_unavailable"
   # Existing URL always takes precedence. Ambiguous duplicate references
   # must be reviewed rather than guess which link belongs to this track.
   if protected[1]=="AMBIGUOUS_MASTER_DUPLICATE":
    status="master_identity_conflict";proposal=""
   elif protected[0]:
    status="existing_verified_protected" if protected[1]=="검증완료" else "existing_link_protected"
   con.execute("INSERT INTO link_state VALUES (?,?,?,?,?,?,?,?,?)",
      (rid,status,proposal,protected[0],protected[1],reasons,source,0,0))
   if not cached:continue
   t=track_for_v4({**row,"country":cached["track"]["country"]})
   ranked,best,_=v4.rank_v4(t,cached["providers"],
      cached["first_v3"].get("selected_url",""))
   # Cap stored links to 5 per song. Full raw candidates live in immutable
   # per-song checkpoint files, not in the app-facing database.
   keep=ranked[:5]
   if best and all(x["video_id"]!=best["video_id"] for x in keep):
    keep[-1]=best
   for x in keep:
    con.execute("INSERT OR IGNORE INTO source_candidate VALUES (?,?,?,?,?,?,?,?,?,?,?)",
      (rid,x["video_id"],x["url"],x["title"],x.get("channel",""),
       int(x.get("seconds") or 0),int(x["score"]),int(x["eligible"]),
       int(bool(best) and best["video_id"]==x["video_id"]),1))
   for i,a in enumerate(cached.get("v43_attempts",[])):
    con.execute("INSERT INTO search_attempt VALUES (?,?,?,?,?,?,?)",
     (rid,i,a.get("source","piped"),int(a.get("status") or 0),
      int(a.get("latency_ms") or 0),a.get("endpoint",""),a.get("error","")))
  con.commit()
  if con.execute("PRAGMA integrity_check").fetchone()[0]!="ok":raise ValueError("export database invalid")
  if con.execute("SELECT COUNT(*) FROM recording").fetchone()[0]!=EXPECTED_CATALOG:raise ValueError("export missing recordings")
  if con.execute("SELECT COUNT(*) FROM link_state WHERE automatic_applied!=0 OR verified!=0").fetchone()[0]:
   raise ValueError("unsafe link statuses!")
 finally:con.close()
 tmp.replace(path)
 return path

def report(manifest,results):
 counted=[r for r in manifest if r["recording_id"] in results]
 responses=[a for r in results.values() for a in r.get("v43_attempts",[])]
 successes=[a["latency_ms"] for a in responses if a.get("status")==200]
 returns={"catalog_total":EXPECTED_CATALOG,"sample_target":len(manifest),
  "processed":len(results),"reused_prior_300":sum(r.get("v43_source")=="v4_cache" for r in results.values()),
  "fresh_searched":sum(r.get("v43_source")=="live" for r in results.values()),
  "provider_unavailable":sum(r.get("v43_result_status")=="provider_unavailable" for r in results.values()),
  "metadata_eligible":sum(r["v4"].get("eligible",0)>0 for r in results.values()),
  "states":dict(Counter(r["v4"]["state"] for r in results.values())),
  "regions":{region:{"target":sum(x["country"]==region for x in manifest),
    "processed":sum(x["country"]==region and x["recording_id"] in results for x in manifest),
    "eligible":sum(x["country"]==region and x["recording_id"] in results and results[x["recording_id"]]["v4"].get("eligible",0)>0 for x in manifest)}
    for region in REGIONS},
  "new_http_attempts":len(responses),
  "new_http_ok":sum(a.get("status")==200 for a in responses),
  "http_failures":dict(Counter(str(a.get("status")) for a in responses if a.get("status")!=200)),
  "http_success_median_ms":round(statistics.median(successes)) if successes else None,
  "http_success_p95_ms":sorted(successes)[int(.95*(len(successes)-1))] if successes else None,
  "master_modified":False,"auto_approved":0,
  "caveat":"Metadata eligible is not verified audio. Existing 300 archived API observations are excluded from new HTTP metrics. Neither MASTER nor Android app modified."}
 return returns

def save_csv(file,fields,rows):
 with Path(file).open("w",encoding="utf-8-sig",newline="") as f:
  w=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore")
  w.writeheader();w.writerows(rows)

def run(catalog,cache,out,limit=1000,master=None,timeout=7,delay=.8,
        max_queries=2,bases=v4.DEFAULT_BASES,fetcher=v4.fetch,
        database_export=True):
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 bases=tuple(v4.check_origin(x) for x in bases)
 prior=import_prior(cache)
 catalog_list=catalog_records(catalog)
 manifest=build_manifest(catalog_list,prior)
 fingerprint=hashlib.sha256(Path(catalog).read_bytes()).hexdigest()
 identity={"schema":SCHEMA,"catalog_sha256":fingerprint,
  "sample_ids":[x["recording_id"] for x in manifest],
  "bases":list(bases),"max_queries":max_queries}
 config_path=out/"manifest_identity.json"
 if config_path.exists() and json.loads(config_path.read_text())!=identity:
  raise ValueError("Source DB or sampling settings changed: use new output folder")
 if not config_path.exists():
  atomic(config_path,identity)
  save_csv(out/"sample_1000.csv",CAT_FIELDS,manifest)
  atomic(out/"catalog_counts.json",dict(Counter(x["country"] for x in catalog_list)))
 protected=master_protection(master)
 health={"disabled":set(),"failures":defaultdict(int)}
 results={}
 for row in manifest:
  path=out/"checkpoints"/(row["country"]+"_"+row["recording_id"]+".json")
  if not path.exists():continue
  old=json.loads(path.read_text(encoding="utf-8"))
  if old.get("v43_schema")!=SCHEMA or old["track"]["id"]!=row["recording_id"]:
   raise ValueError("Checkpoint mismatch: "+str(path))
  results[row["recording_id"]]=old
 for idx,row in enumerate(manifest[:min(limit,1000)],1):
  rid=row["recording_id"]
  if rid in results:continue
  if rid in prior:
   cached=prior[rid]
   # Prior archived result is copied, not queried again.
   data={**cached,"v43_schema":SCHEMA,"v43_source":"v4_cache",
      "v43_attempts":[],"v43_result_status":"cached"}
  else:
   t=track_for_v4(row)
   data=v4.process(t,bases,health,timeout,delay,
     max_queries=max_queries,fetcher=fetcher)
   status=[x.get("status") for x in data.get("attempts",[])]
   failed=(not status or not any(s==200 for s in status))
   data={**data,"v43_schema":SCHEMA,"v43_source":"live",
      "v43_attempts":data.get("attempts",[]),
      "v43_result_status":"provider_unavailable" if failed else "searched"}
  results[rid]=data
  atomic(out/"checkpoints"/(row["country"]+"_"+rid+".json"),data)
  if len(results)%10==0 or idx==min(limit,1000):
   summary=report(manifest,results)
   atomic(out/"progress.json",summary)
   print("ODIFY_V43_PROGRESS "+json.dumps({"processed":len(results),
      "target":len(manifest),"fresh_searched":summary["fresh_searched"],
      "eligible":summary["metadata_eligible"],
      "blocked":summary["provider_unavailable"]},ensure_ascii=False),flush=True)
  # In the face of provider outage, do not fabricate 700 completed searches.
  if len(health["disabled"])==len(bases) and rid not in prior:
   print("ODIFY_V43_API_STOP blocked or unavailable; resumable checkpoint state",flush=True)
   break
 summary=report(manifest,results)
 audit=[]
 for row in manifest:
  rid=row["recording_id"];r=results.get(rid)
  if not r:continue
  key=(norm(row["artist"]),norm(row["title"]))
  prior_link,prior_status=protected.get(key,("",""))
  decision="KEEP_EXISTING" if prior_link or prior_status=="AMBIGUOUS_MASTER_DUPLICATE" else "REVIEW_ONLY"
  audit.append({"recording_id":rid,"country":row["country"],"artist":row["artist"],
   "title":row["title"],"source":r["v43_source"],
   "candidate_count":len(v4.merge(r["providers"])[0]),
   "eligible_count":r["v4"].get("eligible",0),
   "state":r["v4"]["state"],"proposed_url":r["v4"].get("selected_url",""),
   "proposed_score":r["v4"].get("score",0),
   "existing_url":prior_link,"existing_status":prior_status,"decision":decision,
   "fresh_requests":len(r["v43_attempts"]),
   "http_ok":sum(a.get("status")==200 for a in r["v43_attempts"]),
   "catalog_source":"sqlite_41416",
   "verified":"NO","safe_to_auto_import":"NO"})
 summary["master_protected_in_sample"]=sum(bool(x["existing_url"]) for x in audit)
 summary["pending_review"]=sum(x["decision"]=="REVIEW_ONLY" for x in audit)
 save_csv(out/"audit_1000.csv",AUDIT_FIELDS,audit)
 if database_export:export_database(catalog,out/"odify_catalog_links_v43.sqlite3",manifest,results,protected)
 atomic(out/"summary_v43.json",summary)
 print("ODIFY_V43_FINAL "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--catalog",required=True,type=Path)
 p.add_argument("--v4-cache",required=True,type=Path)
 p.add_argument("--out",required=True,type=Path)
 p.add_argument("--master-references",type=Path)
 p.add_argument("--limit",type=int,default=1000)
 p.add_argument("--max-queries",type=int,default=2)
 p.add_argument("--timeout",type=float,default=7)
 p.add_argument("--delay",type=float,default=.8)
 p.add_argument("--base",action="append")
 a=p.parse_args()
 if not 0<=a.limit<=1000 or not 1<=a.max_queries<=2 or not 0<=a.delay<=30 or not 1<=a.timeout<=20:
  p.error("invalid bounds")
 run(a.catalog,a.v4_cache,a.out,a.limit,a.master_references,
     a.timeout,a.delay,a.max_queries,tuple(a.base) if a.base else v4.DEFAULT_BASES)
if __name__=="__main__":main()
