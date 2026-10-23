#!/usr/bin/env python3
"""Import KR Melon 2000-2023 and JP Billboard Japan 2008-2025 archives.

Only artist, title, chart rank, date, album and genre metadata are extracted.
The source repositories contain copyrighted lyrics / other assets: never
copy lyrics, audio or source repository files into Odify output.
Requires local clones at --melon and --billboard-jp.
"""
from __future__ import annotations
import argparse
import csv
from datetime import datetime
import importlib.util
import json
from pathlib import Path
import re

pipeline_file=Path(__file__).with_name("odify_catalog_pipeline.py")
spec=importlib.util.spec_from_file_location("odify_catalog_pipeline",pipeline_file)
catalog=importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)

def progress(label,current,total,detail=""):
    pct=round(current/max(1,total)*100,1)
    print(f"ODIFY_COUNTRY_PROGRESS {label} {current}/{total} ({pct}%) {detail}",flush=True)

def korean_monthly(con,folder,min_year=2000,max_year=2023):
    if not folder or not Path(folder).is_dir():
        raise ValueError("valid --melon repository folder required")
    base=Path(folder)/"melon"/"monthly-chart"
    files=sorted(base.glob("melon-*/melon-*/*.json"))
    if not files:raise ValueError("melon monthly JSON not found")
    by_month={}
    parsed=0
    errors=0
    for i,file in enumerate(files,1):
        if i%1000==0:progress("KR JSON",i,len(files),file.name)
        try:
            content=json.loads(file.read_text(encoding="utf-8-sig"))
            meta=(content.get("info") or [{}])[0]
            year=int(meta["year"]);month=int(meta["month"]);rank=int(meta["rank"])
            if not min_year<=year<=max_year:continue
            artist=str(content.get("artist") or "").strip()
            name=str(content.get("song_name") or "").strip()
            if not artist or not name or rank<1:continue
            key=f"{year:04d}-{month:02d}"
            monthdata=by_month.setdefault(key,{})
            monthdata[rank]={
                "rank":rank,"artist":artist,"title":name,
                "source_id":str(content.get("song_id") or ""),
                "album":str(content.get("album") or ""),
                "genre":str(content.get("genre") or "")
            }
            parsed+=1
        except (KeyError,IndexError,TypeError,ValueError,json.JSONDecodeError):
            errors+=1
    done=0
    for key,rank_map in sorted(by_month.items()):
        year,month=map(int,key.split("-"))
        # Chart month is an explicit monthly chart, not derived.
        lastday=__import__('calendar').monthrange(year,month)[1]
        date=f"{key}-{lastday:02d}"
        with con:
            catalog.snapshot(con,"melon-monthly-research","kr",date,
                list(rank_map.values()),category="monthly-official-source",
                url="https://github.com/EX3exp/Kpop-lyric-datasets",
                rights="Research mirror of Melon monthly ranking; extracted metadata only, no lyrics")
        done+=1
        if done%24==0:progress("KR MONTHS",done,len(by_month),key)
    return {"monthly_charts":done,"ranking_rows":parsed,"invalid_files":errors,
            "range":[min(by_month,default=None),max(by_month,default=None)]}

def billboard_japan(con,folder,min_year=2008,max_year=2025):
    if not folder or not Path(folder).is_dir():
        raise ValueError("valid --billboard-jp repository folder required")
    files=sorted((Path(folder)/"billboard_charts").glob("*/*.csv"),reverse=True)
    if not files:raise ValueError("Japan weekly CSV not found")
    accepted=0;rows=0;months=set();skipped=0
    for i,file in enumerate(files,1):
        m=re.fullmatch(r"(20\d{2})_(\d{2})_(\d{2})",file.stem)
        if not m or not min_year<=int(m[1])<=max_year:continue
        date=f"{m[1]}-{m[2]}-{m[3]}"
        try:datetime.strptime(date,"%Y-%m-%d")
        except ValueError:continue
        chart_id=catalog.stable("chart","billboard-japan-research","jp",date,"songs")
        if con.execute("SELECT 1 FROM chart_edition WHERE id=?",(chart_id,)).fetchone():
            skipped+=1;continue
        items=[]
        with file.open(newline="",encoding="utf-8-sig") as fp:
            for r in csv.DictReader(fp):
                try:
                    rank=int(r.get("rank") or 0)
                    title=(r.get("song_title") or "").strip()
                    artist=(r.get("artist_name") or "").strip()
                    if rank>0 and title and artist:
                        items.append({"rank":rank,"title":title,"artist":artist})
                except ValueError:pass
        if not items:continue
        with con:
            catalog.snapshot(con,"billboard-japan-research","jp",date,items,
                             url="https://github.com/yuta1016/Graduation_Research",
                             rights="Public research mirror of weekly Billboard Japan ranking metadata")
        accepted+=1;rows+=len(items);months.add(date[:7])
        if accepted%100==0:progress("JP WEEKS",accepted,len(files),date)
    completed=0
    for key in sorted(months):
        with con:catalog.rebuild_monthly(con,"billboard-japan-research","jp",key)
        completed+=1
        if completed%24==0:progress("JP MONTHS",completed,len(months),key)
    return {"weekly_charts":accepted,"monthly_derived":completed,
            "ranking_rows":rows,"already_present":skipped,
            "range":[min(months,default=None),max(months,default=None)]}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db",type=Path,default=Path(".local/odify_catalog.sqlite3"))
    p.add_argument("--melon",type=Path)
    p.add_argument("--billboard-jp",type=Path)
    args=p.parse_args()
    con=catalog.connect(args.db)
    outcome={}
    try:
        if args.melon:outcome["kr"]=korean_monthly(con,args.melon)
        if args.billboard_jp:outcome["jp"]=billboard_japan(con,args.billboard_jp)
        print("ODIFY_COUNTRY_COUNTS "+json.dumps(outcome,ensure_ascii=False),flush=True)
    finally:con.close()

if __name__=="__main__":main()
