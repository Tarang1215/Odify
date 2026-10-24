#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4.3+ scalable 41,416-recording checkpointed batch runner.

Runs a bounded slice of the full deterministic queue: --start N --size K.
Only metadata search, no audio download/MASTER edit/app source edit.
The initial 1000 tracks reuse the verified V4.3 sample ordering.
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import csv
import hashlib
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v43_batch as v43
import odify_altlink_v4_300 as v4

SCHEMA="odify-v43-full-queue-1"
def full_manifest(records,prior):
 pilot=v43.build_manifest(records,prior)
 used={x["recording_id"] for x in pilot}
 remains=[x for x in records if x["recording_id"] not in used]
 # Stable global ordering; app can address every queue index. We do not
 # reassign an ID when a chart updates: manifest SHA protects the batch.
 region_order={"kr":0,"jp":1,"us":2,"gl":3,"other":4}
 remains.sort(key=lambda r:(region_order.get(r["country"],5),
      hashlib.sha256((r["country"]+":"+r["recording_id"]).encode()).hexdigest()))
 result=pilot+[{**x,"source":"future"} for x in remains]
 if len(result)!=v43.EXPECTED_CATALOG or len(
   {x["recording_id"] for x in result})!=v43.EXPECTED_CATALOG:
  raise ValueError("full manifest cardinality mismatch")
 return result

def plan(catalog,cache,out,start,size,timeout=7,delay=.8,max_queries=1,
        bases=v4.DEFAULT_BASES,fetcher=v4.fetch,dry_run=False):
 if start<0 or size<1 or size>1000 or start>=v43.EXPECTED_CATALOG or \
    start+size>v43.EXPECTED_CATALOG:
  raise ValueError("Only bounded 1..1000 slices within 0..41416 supported")
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 prior=v43.import_prior(cache)
 catalog_list=v43.catalog_records(catalog)
 manifest=full_manifest(catalog_list,prior)
 sha=hashlib.sha256(Path(catalog).read_bytes()).hexdigest()
 manifest_ids=[x["recording_id"] for x in manifest]
 invariant={"schema":SCHEMA,"catalog_sha256":sha,
           "ordered_id_sha256":hashlib.sha256(
             "\n".join(manifest_ids).encode()).hexdigest(),
           "count":len(manifest)}
 settings=out/"queue_identity.json"
 if settings.exists() and json.loads(settings.read_text())!=invariant:
  raise ValueError("Queue identity changed; refuse mixing old checkpoints")
 if not settings.exists():
  v43.atomic(settings,invariant)
  v43.save_csv(out/"full_catalog_41416_queue.csv",
     ("recording_id","country","artist_id","artist","title","album",
      "version","chart_date","chart_rank","source"),manifest)
 chosen=manifest[start:start+size]
 batch=out/"batches"/f"{start:05d}_{start+size:05d}"
 batch.mkdir(parents=True,exist_ok=True)
 config={"identity":invariant["ordered_id_sha256"],"start":start,"size":size,
      "queries":max_queries,"bases":list(bases)}
 if (batch/"settings.json").exists() and json.loads((batch/"settings.json").read_text())!=config:
  raise ValueError("Batch request settings changed")
 if not (batch/"settings.json").exists():v43.atomic(batch/"settings.json",config)
 if dry_run:
  result={"source_count":len(manifest),"start":start,"end_exclusive":start+size,
    "slice_count":len(chosen),"first_id":chosen[0]["recording_id"],
    "last_id":chosen[-1]["recording_id"],"network_requests":0,
    "master_modified":False}
  v43.atomic(batch/"plan.json",result)
  return result
 health={"disabled":set(),"failures":defaultdict(int)}
 counts=Counter()
 for i,t in enumerate(chosen,1):
  file=batch/"checkpoints"/(t["country"]+"_"+t["recording_id"]+".json")
  if file.exists():
   r=json.loads(file.read_text(encoding="utf-8"))
   if r.get("v43_schema")!=v43.SCHEMA or r["track"]["id"]!=t["recording_id"]:
    raise ValueError("Mismatched existing checkpoint")
  elif t["recording_id"] in prior:
   base=prior[t["recording_id"]]
   r={**base,"v43_schema":v43.SCHEMA,"v43_source":"v4_cache",
     "v43_attempts":[],"v43_result_status":"cached"}
   v43.atomic(file,r)
  else:
   base=v4.process(v43.track_for_v4(t),bases,health,timeout,delay,
      max_queries=max_queries,fetcher=fetcher)
   calls=base.get("attempts",[])
   r={**base,"v43_schema":v43.SCHEMA,"v43_source":"live",
      "v43_attempts":calls,
      "v43_result_status":"searched" if any(a.get("status")==200 for a in calls)
        else "provider_unavailable"}
   v43.atomic(file,r)
  counts[r["v43_result_status"]]+=1
  if i%10==0 or i==len(chosen):
   v43.atomic(batch/"progress.json",{
     "processed":i,"slice_target":len(chosen),
     "statuses":dict(counts),"master_modified":False})
   print("ODIFY_FULL_QUEUE_PROGRESS "+json.dumps({
      "processed":i,"target":len(chosen),"statuses":dict(counts)},ensure_ascii=False),flush=True)
  if len(health["disabled"])==len(bases) and t["recording_id"] not in prior:
   print("ODIFY_FULL_QUEUE_STOP all public API instances unavailable",flush=True)
   break
 report={"start":start,"size":size,"catalog_size":len(manifest),
   "processed":sum((batch/"checkpoints"/(x["country"]+"_"+x["recording_id"]+".json")).exists() for x in chosen),
   "statuses":dict(counts),"existing_MASTER_edited":False,
   "url_verified_or_autoapproved":0,
   "caveat":"Full queue exists but only a bounded slice is processed each run. Metadata-eligible is not listening-verified."}
 v43.atomic(batch/"summary.json",report)
 return report

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--catalog",required=True,type=Path)
 p.add_argument("--v4-cache",required=True,type=Path)
 p.add_argument("--out",required=True,type=Path)
 p.add_argument("--start",required=True,type=int)
 p.add_argument("--size",type=int,default=1000)
 p.add_argument("--dry-run",action="store_true")
 p.add_argument("--max-queries",type=int,default=1)
 p.add_argument("--timeout",type=float,default=7)
 p.add_argument("--delay",type=float,default=.8)
 args=p.parse_args()
 if not 1<=args.max_queries<=2 or not 0<=args.delay<=30 or not 1<=args.timeout<=20:
  p.error("Invalid bounds")
 print("ODIFY_V43_FULL_QUEUE "+json.dumps(plan(args.catalog,args.v4_cache,args.out,
    args.start,args.size,args.timeout,args.delay,args.max_queries,
    dry_run=args.dry_run),ensure_ascii=False),flush=True)
if __name__=="__main__":main()
