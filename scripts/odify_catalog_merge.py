#!/usr/bin/env python3
"""Merge two Odify SQLite chart archives using stable recording IDs.

Source A is the album-enriched USA version; source B contains KR/JP history.
All 10 normalized tables are merged, retaining source-dependent rank editions.
"""
from pathlib import Path
import argparse
import json
import shutil
import sqlite3

TABLES=("artist","recording","album","recording_genre","external_identity",
        "album_track","chart_edition","chart_entry","external_album","release_catalog_queue")

def merge(base,extra,dest):
    base=Path(base);extra=Path(extra);dest=Path(dest)
    dest.parent.mkdir(parents=True,exist_ok=True)
    if base.resolve()==dest.resolve() or extra.resolve()==dest.resolve():
        raise ValueError("Destination must differ from sources")
    shutil.copy2(base,dest)
    con=sqlite3.connect(dest)
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("ATTACH DATABASE ? AS addition",(str(extra),))
    rows={}
    for table in TABLES:
        existing=[x[1] for x in con.execute(f"PRAGMA main.table_info({table})")]
        incoming=[x[1] for x in con.execute(f"PRAGMA addition.table_info({table})")]
        if existing!=incoming:
            raise ValueError("Mismatched schemas "+table)
        before=con.execute(f"SELECT COUNT(*) FROM main.{table}").fetchone()[0]
        columns=",".join('"'+c+'"' for c in existing)
        with con:
            con.execute(f"INSERT OR IGNORE INTO main.{table}({columns})"
                        f" SELECT {columns} FROM addition.{table}")
        rows[table]={"before":before,"total":con.execute(
            f"SELECT COUNT(*) FROM main.{table}").fetchone()[0]}
    con.execute("DETACH DATABASE addition")
    integrity=con.execute("PRAGMA integrity_check").fetchone()[0]
    errors=con.execute("PRAGMA foreign_key_check").fetchall()
    if integrity!="ok" or errors:
        raise RuntimeError(f"Corrupt catalog: {integrity} foreign keys: {errors[:3]}")
    con.close()
    return {"integrity":integrity,"foreign_keys":0,"tables":rows}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--base",required=True)
    p.add_argument("--extra",required=True)
    p.add_argument("--out",required=True)
    a=p.parse_args()
    print("ODIFY_MERGE "+json.dumps(merge(a.base,a.extra,a.out),ensure_ascii=False),flush=True)

if __name__=="__main__":
    main()
