#!/usr/bin/env python3
"""Supplement Odify JP 2003-2008 with Oricon weekly singles metadata.

Input: sichengchen/oricon public/data/oricon_singles.csv
Personal noncommercial catalog metadata only. Preserve source provenance.
This is ORICON singles (sales), not Billboard Japan (different chart criteria).
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import csv
from datetime import datetime
import importlib.util
from pathlib import Path

root=Path(__file__).parent
spec=importlib.util.spec_from_file_location("catalog",root/"odify_catalog_pipeline.py")
catalog=importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)
PROVIDER="oricon-weekly-research"
SOURCE="https://github.com/sichengchen/oricon/blob/main/public/data/oricon_singles.csv"

def ingest(con,csvfile:Path,min_year=2003,max_year=2008,monthly=True):
    charts=defaultdict(dict)
    bad=0
    with Path(csvfile).open(newline="",encoding="utf-8-sig") as fp:
        reader=csv.DictReader(fp)
        if not {"chart_date","rank","title","artist"}.issubset(reader.fieldnames or []):
            raise ValueError("Unexpected Oricon CSV schema")
        for row in reader:
            try:
                date=row["chart_date"]
                dt=datetime.strptime(date,"%Y-%m-%d")
                if not min_year<=dt.year<=max_year:continue
                rank=int(row["rank"])
                if rank<1:continue
                name=(row["title"] or "").strip()
                singer=(row["artist"] or "").strip()
                if not catalog.norm(name) or not catalog.norm(singer):continue
                charts[date].setdefault(rank,{"rank":rank,"title":name,"artist":singer})
            except (ValueError,KeyError,TypeError):
                bad+=1
    processed=0;months=set()
    for date,ranking in sorted(charts.items(),reverse=True):
        chart_id=catalog.stable("chart",PROVIDER,"jp",date,"songs")
        if con.execute("SELECT 1 FROM chart_edition WHERE id=?",(chart_id,)).fetchone():
            continue
        with con:
            catalog.snapshot(con,PROVIDER,"jp",date,list(ranking.values()),
                url=SOURCE,rights="Oricon personal research mirror; not Billboard Japan")
        processed+=1
        months.add(date[:7])
        if processed%50==0:
            print(f"ODIFY_PROGRESS ORICON JP {processed}/{len(charts)} weeks "
                  f"{round(100*processed/max(1,len(charts)),1)}% {date}",flush=True)
    derived=0
    if monthly:
        for ym in sorted(months):
            with con:
                derived+=catalog.rebuild_monthly(con,PROVIDER,"jp",ym)
    print(f"ODIFY_ORICON_COUNT weeks_new={processed} months={len(months)} "
          f"ranking_entries_derived={derived} bad={bad}",flush=True)
    return {"weeks_new":processed,"months_recomputed":len(months),
            "month_rank_rows":derived,"bad_rows":bad}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--csv",type=Path,required=True)
    p.add_argument("--db",type=Path,default=Path(".local/odify_catalog.sqlite3"))
    p.add_argument("--min-year",type=int,default=2003)
    p.add_argument("--max-year",type=int,default=2008)
    args=p.parse_args()
    if args.min_year<2003 or args.max_year>2008 or args.min_year>args.max_year:
        p.error("Supported Japan Oricon range: 2003-2008")
    con=catalog.connect(args.db)
    try:ingest(con,args.csv,args.min_year,args.max_year)
    finally:con.close()

if __name__=="__main__":
    main()
