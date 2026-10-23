#!/usr/bin/env python3
"""Offline V4.4 safety regrade of the original 1000 actual Piped checkpoint files.

Same candidates and original first-query baseline; no new API calls or MASTER writes.
Never equate metadata eligibility with a verified or yt-dlp-downloadable audio source.
"""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v4_300 as v4

FLAGS=("non_original_variant:cover","non_original_variant:instrumental",
       "non_original_variant:remix","non_original_variant:speed_edit",
       "non_original_variant:live")
HEAD=("country","id","artist","title","previous_state","new_state",
      "previous_url","new_url","previous_eligible_count","new_eligible_count",
      "previous_selection_variant_rejected","changed_choice","review_only",
      "master_write")
def run(original,out):
 original=Path(original);out=Path(out);out.mkdir(parents=True,exist_ok=True)
 if not (original/"checkpoints").exists():raise ValueError("1000 checkpoints missing")
 files=sorted((original/"checkpoints").glob("*.json"))
 if len(files)!=1000:raise ValueError("Must process all 1000 real checkpoints")
 rows=[];changes=[];countries=Counter();newstates=Counter()
 for f in files:
  r=json.loads(f.read_text(encoding="utf-8"))
  if r.get("v43_schema")!="odify-v43-1":raise ValueError("Unexpected V43 schema")
  t=r["track"]
  ranked,best,state=v4.rank_v4(t,r["providers"],
      r["first_v3"].get("selected_url",""))
  old_url=r["v4"].get("selected_url","")
  now=best["url"] if best else ""
  old_item=next((c for c in r["providers"]["piped"]["candidates"] if c["url"]==old_url),None)
  eliminated=False
  if old_item:
   grade=v4.grade_v4(t,old_item)
   eliminated=(not grade["eligible"] and grade["reasons"].startswith("non_original_variant:"))
  row={"country":t["country"],"id":t["id"],"artist":t["artist"],"title":t["title"],
    "previous_state":r["v4"]["state"],"new_state":state,
    "previous_url":old_url,"new_url":now,
    "previous_eligible_count":r["v4"].get("eligible",0),
    "new_eligible_count":sum(c["eligible"] for c in ranked),
    "previous_selection_variant_rejected":"YES" if eliminated else "NO",
    "changed_choice":"YES" if old_url!=now else "NO",
    "review_only":"YES","master_write":"NO"}
  rows.append(row);newstates[state]+=1;countries[t["country"]]+=1
  if row["changed_choice"]=="YES":changes.append(row)
 def output(name,items):
  with (out/name).open("w",encoding="utf-8-sig",newline="") as f:
   w=csv.DictWriter(f,fieldnames=HEAD);w.writeheader();w.writerows(items)
 output("v44_audit_1000.csv",rows)
 output("v44_changed_candidates_review_only.csv",changes)
 summary={"sample":1000,
   "original_metadata_eligible_songs":sum(int(x["previous_eligible_count"])>0 for x in rows),
   "new_metadata_eligible_songs":sum(int(x["new_eligible_count"])>0 for x in rows),
   "old_selected_non_original_version_rejected":sum(x["previous_selection_variant_rejected"]=="YES" for x in rows),
   "candidate_choices_changed":len(changes),
   "candidate_choices_unchanged":1000-len(changes),
   "new_states":dict(newstates),"countries":dict(countries),
   "automatic_approvals":0,"master_modified":False,"new_api_calls":0,
   "note":"Review-only candidate changes. No audio listening, verified YouTube attribution or yt-dlp stream test."}
 (out/"v44_summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
 print("ODIFY_V44_RESULT "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary
if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("--input",required=True,type=Path);p.add_argument("--out",required=True,type=Path)
 a=p.parse_args();run(a.input,a.out)
