#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rerank archived 300-track V4 checkpoint candidates WITHOUT new API calls.

No reference VIDEO IDs used to choose ranking, no sheet writes, no downloads.
"""
from __future__ import annotations
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v4_300 as v4
import odify_altlink_rerank as v3

DIFF_FIELDS=("country","id","artist","title","first_v3_url",
             "original_v4_url","fixed_v4_url","original_v4_state",
             "fixed_v4_state","original_has_candidate","fixed_has_candidate",
             "change","verified","master_action")

def rerank(source,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    checkpoints=list(v3.read_checkpoints(source))
    if len(checkpoints)!=300:
        raise ValueError(f"Expected 300 archived checkpoints, got {len(checkpoints)}")
    grouped={};diff=[]
    for row in checkpoints:
        if row.get("schema")!=v4.SCHEMA:
            raise ValueError("checkpoint schema mismatch")
        t=row["track"]
        key=t["country"],t["id"]
        if key in grouped:raise ValueError("duplicated country/id")
        ranked,best,state=v4.rank_v4(t,row["providers"],row["first_v3"]["selected_url"])
        fresh={**row,
          "v4":{"state":state,"eligible":sum(x["eligible"] for x in ranked),
                "selected_url":best["url"] if best else "",
                "score":best["score"] if best else 0}}
        grouped[key]=fresh
        old=row["v4"];new=fresh["v4"]
        diff.append({
            "country":t["country"],"id":t["id"],"artist":t["artist"],"title":t["title"],
            "first_v3_url":row["first_v3"]["selected_url"],
            "original_v4_url":old["selected_url"],"fixed_v4_url":new["selected_url"],
            "original_v4_state":old["state"],"fixed_v4_state":new["state"],
            "original_has_candidate":int(old["eligible"]>0),
            "fixed_has_candidate":int(new["eligible"]>0),
            "change":"different_video_id" if old["selected_url"]!=new["selected_url"] else "same_video_id",
            "verified":"NO","master_action":"NO_WRITE"})
    with (output/"v4_safety_delta_300.csv").open("w",encoding="utf-8-sig",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=DIFF_FIELDS)
        writer.writeheader();writer.writerows(diff)
    report=v4.export(output,grouped)
    report["original_v4_eligible_songs"]=sum(x["v4"]["eligible"]>0 for x in checkpoints)
    report["fixed_v4_eligible_songs"]=sum(x["v4"]["eligible"]>0 for x in grouped.values())
    report["video_id_changed_count"]=sum(x["change"]=="different_video_id" for x in diff)
    report["fixed_selection_vs_first_v3_diff"]=sum(
         x["fixed_v4_url"]!=x["first_v3_url"] for x in diff)
    report["rerank_mode"]="no_network_cached_300; original checkpoints unchanged"
    v4.atomic(output/"summary_v4_safety_review.json",report)
    print("ODIFY_V4_300_SAFE "+json.dumps(report,ensure_ascii=False),flush=True)
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input",required=True,type=Path)
    parser.add_argument("--out",required=True,type=Path)
    args=parser.parse_args()
    rerank(args.input,args.out)
if __name__=="__main__":main()
