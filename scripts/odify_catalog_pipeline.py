#!/usr/bin/env python3
"""Odify metadata-first chart/album catalog. Standard library only.

No music/audio downloads. SQLite tracks are deduplicated across countries and
dates, but chart appearances are preserved. Data is sourced/dated and kept
separate from playback links and the existing Google Sheets MASTER.

Commands:
  python scripts/odify_catalog_pipeline.py init --db odify_catalog.sqlite3
  python scripts/odify_catalog_pipeline.py chart-feed --db odify_catalog.sqlite3 --countries kr,jp,us,gb
  python scripts/odify_catalog_pipeline.py import-chart --db odify_catalog.sqlite3 --csv licensed_history.csv
  python scripts/odify_catalog_pipeline.py expand-albums --db odify_catalog.sqlite3 --limit 25
  python scripts/odify_catalog_pipeline.py export --db odify_catalog.sqlite3 --out-dir public_catalog
  python scripts/odify_catalog_pipeline.py summary --db odify_catalog.sqlite3

Historical CSV columns: source,country,chart_date,rank,artist,title
Optional:source_id,version,album,album_id,source_url,license.
Chart importing is an ingestion format only, NOT a permission to copy any
proprietary historical chart into a distributed app.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import csv
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import unicodedata
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

USER_AGENT="OdifyCatalog/0.1 (https://github.com/Tarang1215/Odify)"
COUNTRIES=("kr","jp","us","gb","de","fr","ca","au")
KIND_RE=re.compile(
    r"(?:[\(\[\- ]+(?:english|eng|japanese|jp|korean|kr)\s*(?:ver\.?|version)\b"
    r"|[\(\[\- ]+\b(?:live|remix|acoustic|instrumental|piano cover|cover|demo|remaster(?:ed)?|slowed|sped up)\b"
    r"|[\(\[\- ]+(?:19|20)\d{2}\s+remaster)",
    re.I
)
SCHEMA="""
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS artist(
 id TEXT PRIMARY KEY,name TEXT NOT NULL,normalized TEXT NOT NULL,
 mbid TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS artist_norm ON artist(normalized);
CREATE TABLE IF NOT EXISTS recording(
 id TEXT PRIMARY KEY,artist_id TEXT NOT NULL REFERENCES artist(id),
 title TEXT NOT NULL, normalized TEXT NOT NULL,
 version TEXT NOT NULL DEFAULT 'original',
 first_seen TEXT DEFAULT CURRENT_TIMESTAMP,
 mbid TEXT, release_date TEXT,
 UNIQUE(artist_id,normalized,version)
);
CREATE INDEX IF NOT EXISTS recording_artist ON recording(artist_id);
CREATE TABLE IF NOT EXISTS external_identity(
 provider TEXT NOT NULL,ext_id TEXT NOT NULL,
 recording_id TEXT NOT NULL REFERENCES recording(id),
 PRIMARY KEY(provider,ext_id)
);
CREATE TABLE IF NOT EXISTS album(
 id TEXT PRIMARY KEY,artist_id TEXT REFERENCES artist(id),
 title TEXT NOT NULL,normalized TEXT NOT NULL,version TEXT NOT NULL DEFAULT 'original',
 release_date TEXT, mbid TEXT,UNIQUE(artist_id,normalized,version)
);
CREATE TABLE IF NOT EXISTS album_track(
 album_id TEXT NOT NULL REFERENCES album(id),
 recording_id TEXT NOT NULL REFERENCES recording(id),
 disc INTEGER DEFAULT 1,position INTEGER NOT NULL,
 PRIMARY KEY(album_id,disc,position)
);
CREATE TABLE IF NOT EXISTS chart_edition(
 id TEXT PRIMARY KEY,provider TEXT NOT NULL,country TEXT NOT NULL,
 as_of TEXT NOT NULL,category TEXT NOT NULL DEFAULT 'songs',
 source_url TEXT,rights_note TEXT,ingested_at TEXT DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(provider,country,as_of,category)
);
CREATE TABLE IF NOT EXISTS chart_entry(
 edition_id TEXT NOT NULL REFERENCES chart_edition(id),
 rank INTEGER NOT NULL,recording_id TEXT NOT NULL REFERENCES recording(id),
 source_song_id TEXT,PRIMARY KEY(edition_id,rank)
);
CREATE INDEX IF NOT EXISTS entries_song ON chart_entry(recording_id);
CREATE TABLE IF NOT EXISTS external_album(
 provider TEXT NOT NULL,ext_id TEXT NOT NULL,album_id TEXT REFERENCES album(id),
 status TEXT NOT NULL DEFAULT 'pending',updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(provider,ext_id)
);
CREATE TABLE IF NOT EXISTS release_catalog_queue(
 provider TEXT NOT NULL,ext_id TEXT NOT NULL,market TEXT,
 status TEXT NOT NULL DEFAULT 'pending',last_attempt TEXT,
 PRIMARY KEY(provider,ext_id,market)
);
"""

def norm(text: str) -> str:
    t=unicodedata.normalize("NFKC",str(text or "")).casefold()
    return "".join(ch for ch in t if ch.isalnum())

def stable(*values):
    return hashlib.sha256("|".join(map(str,values)).encode()).hexdigest()[:24]

def utc_now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()

def version_label(name,album=""):
    text=(name or "")+" "+(album or "")
    m=KIND_RE.search(text)
    if m:
        v=norm(m.group().lstrip("([ -"))
        return v if v else "original"
    return "original"

def connect(path):
    path=Path(path)
    path.parent.mkdir(parents=True,exist_ok=True)
    con=sqlite3.connect(path)
    con.row_factory=sqlite3.Row
    con.executescript(SCHEMA)
    return con

def artist(con,name):
    key=norm(name)
    if not key: raise ValueError("empty artist")
    a=con.execute("SELECT id FROM artist WHERE normalized=?",(key,)).fetchone()
    if a:return a["id"]
    aid=stable("artist",key)
    con.execute("INSERT OR IGNORE INTO artist(id,name,normalized) VALUES(?,?,?)",
                (aid,name,key))
    return aid

def recording(con,name,artist_name,version=None,source=None,source_id=None,
              album="",release_date=None):
    if source and source_id:
        prior=con.execute(
            "SELECT recording_id FROM external_identity WHERE provider=? AND ext_id=?",
            (source,str(source_id))).fetchone()
        if prior:return prior["recording_id"]
    aid=artist(con,artist_name)
    title=norm(name)
    if not title:raise ValueError("empty track")
    edition=norm(version) if version else version_label(name,album)
    if not edition:edition="original"
    prior=con.execute("SELECT id FROM recording WHERE artist_id=? AND normalized=? AND version=?",
                      (aid,title,edition)).fetchone()
    rid=prior["id"] if prior else stable("recording",aid,title,edition)
    con.execute("INSERT OR IGNORE INTO recording(id,artist_id,title,normalized,version,release_date)"
                " VALUES(?,?,?,?,?,?)",(rid,aid,name,title,edition,release_date))
    if source and source_id:
        con.execute("INSERT OR IGNORE INTO external_identity(provider,ext_id,recording_id)"
                    " VALUES(?,?,?)",(source,str(source_id),rid))
    return rid

def album(con,name,artist_name,release_date=None,version=None):
    aid=artist(con,artist_name);key=norm(name)
    ver=norm(version) if version else version_label(name)
    a=con.execute("SELECT id FROM album WHERE artist_id=? AND normalized=? AND version=?",
                  (aid,key,ver)).fetchone()
    if a:return a["id"]
    album_id=stable("album",aid,key,ver)
    con.execute("INSERT OR IGNORE INTO album(id,artist_id,title,normalized,version,release_date)"
                " VALUES(?,?,?,?,?,?)",(album_id,aid,name,key,ver,release_date))
    return album_id

def snapshot(con,source,country,date,entries,category="songs",url=None,rights=None):
    """Idempotent chart snapshot. Never collapse rank occurrences across markets."""
    country=country.lower().strip()
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}",date):raise ValueError("chart_date must YYYY-MM-DD")
    if not source or len(country)!=2:raise ValueError("provider/country missing")
    sid=stable("chart",source,country,date,category)
    con.execute("INSERT INTO chart_edition(id,provider,country,as_of,category,source_url,rights_note)"
                " VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                " source_url=excluded.source_url,rights_note=excluded.rights_note,ingested_at=CURRENT_TIMESTAMP",
                (sid,source,country,date,category,url,rights))
    con.execute("DELETE FROM chart_entry WHERE edition_id=?",(sid,))
    encountered=set()
    for item in entries:
        rank=int(item["rank"])
        if rank<=0 or rank in encountered:raise ValueError(f"duplicate/bad rank {rank}")
        encountered.add(rank)
        aid=recording(con,item["title"],item["artist"],item.get("version"),
                      source="apple" if source=="apple-rss" else None,
                      source_id=item.get("source_id") if source=="apple-rss" else None,
                      album=item.get("album",""))
        con.execute("INSERT INTO chart_entry(edition_id,rank,recording_id,source_song_id)"
                    " VALUES(?,?,?,?)",(sid,rank,aid,item.get("source_id")))
        if source=="apple-rss" and item.get("source_id"):
            con.execute("INSERT OR IGNORE INTO release_catalog_queue(provider,ext_id,market)"
                        " VALUES('apple',?,?,)",(str(item["source_id"]),country))
    return sid

def http_json(url,timeout=20):
    with urlopen(Request(url,headers={"User-Agent":USER_AGENT,"Accept":"application/json"}),
                 timeout=timeout) as response:
        return json.load(response)

def rss_feed(country):
    # Open Apple marketing RSS endpoint: country-localized and currently ranked.
    country=country.lower()
    url=f"https://rss.marketingtools.apple.com/api/v2/{country}/music/most-played/100/songs.json"
    raw=http_json(url)
    feed=raw["feed"]
    records=[]
    for rank,entry in enumerate(feed.get("results",[]),1):
        name=entry.get("name","")
        artist_name=entry.get("artistName","")
        if name and artist_name:
            records.append({"rank":rank,"artist":artist_name,"title":name,
                            "source_id":str(entry.get("id") or ""),
                            "album":entry.get("albumName","")})
    if not records:raise ValueError(f"RSS empty for {country}, do not overwrite existing data")
    return url,feed.get("updated",""),records

def feed_date(raw):
    from email.utils import parsedate_to_datetime
    try:return parsedate_to_datetime(raw).strftime("%Y-%m-%d")
    except (ValueError,TypeError):return datetime.now(timezone.utc).strftime("%Y-%m-%d")

def import_csv(con,file):
    with Path(file).open(newline="",encoding="utf-8-sig") as f:
        rows=list(csv.DictReader(f))
    required={"source","country","chart_date","rank","artist","title"}
    if not rows or not required.issubset(rows[0]):
        raise ValueError("CSV needs source,country,chart_date,rank,artist,title")
    buckets={}
    for row in rows:
        key=(row["source"],row["country"],row["chart_date"])
        buckets.setdefault(key,[]).append(row)
    for (source,country,date),entries in buckets.items():
        one=entries[0]
        snapshot(con,source,country,date,entries,url=one.get("source_url"),
                 rights=one.get("license"))
    return len(buckets),len(rows)

def artist_list(con,who,limit=100):
    q=norm(who)
    artists=con.execute("SELECT * FROM artist WHERE normalized LIKE ? LIMIT 6",
                        (f"%{q}%",)).fetchall()
    out=[]
    for a in artists:
        tracks=con.execute("""
        SELECT r.id,r.title,r.version,
               COUNT(ce.rank) AS chart_appearances,
               COALESCE(MAX(101-ce.rank),0) AS best_points,
               COUNT(DISTINCT cd.country) AS chart_countries
        FROM recording r
        LEFT JOIN chart_entry ce ON ce.recording_id=r.id
        LEFT JOIN chart_edition cd ON cd.id=ce.edition_id
        WHERE r.artist_id=?
        GROUP BY r.id ORDER BY chart_appearances DESC,best_points DESC LIMIT ?
        """,(a["id"],limit)).fetchall()
        albums=con.execute("SELECT id,title,release_date FROM album WHERE artist_id=?"
                           " ORDER BY release_date DESC LIMIT 100",(a["id"],)).fetchall()
        out.append({"artist":{"id":a["id"],"name":a["name"]},
                    "tracks":[dict(t) for t in tracks],
                    "albums":[dict(x) for x in albums]})
    return out

def top_current(con,country,limit=100):
    e=con.execute("SELECT * FROM chart_edition WHERE country=? AND category='songs'"
                  " ORDER BY as_of DESC,ingested_at DESC LIMIT 1",(country,)).fetchone()
    if not e:return {"country":country,"entries":[],"as_of":None,"source":None}
    tracks=con.execute("""
       SELECT ce.rank,r.id AS recording_id,r.title,r.version,a.name AS artist
       FROM chart_entry ce JOIN recording r ON r.id=ce.recording_id
       JOIN artist a ON a.id=r.artist_id
       WHERE ce.edition_id=? ORDER BY ce.rank LIMIT ?
    """,(e["id"],limit)).fetchall()
    return {"country":country,"as_of":e["as_of"],"source":e["provider"],
            "source_url":e["source_url"],"entries":[dict(x) for x in tracks]}

def expand_apple(con,limit=30,delay=3.5):
    """Apple ID lookup -> album ID -> all album tracks; 3.5s minimum/req."""
    rows=con.execute(
       "SELECT ext_id,market FROM release_catalog_queue WHERE status='pending'"
       " ORDER BY rowid LIMIT ?",(limit,)).fetchall()
    expanded=0;calls=0
    for entry in rows:
        songid,market=entry["ext_id"],entry["market"]
        time.sleep(delay if calls else 0)
        try:
            lookup=http_json("https://itunes.apple.com/lookup?"+
                             urlencode({"id":songid,"country":market,"entity":"song"}))
            calls+=1
            song=next((x for x in lookup.get("results",[])
                       if x.get("wrapperType")=="track" and
                       str(x.get("trackId"))==songid),None)
            if not song or not song.get("collectionId"):
                con.execute("UPDATE release_catalog_queue SET status='not_found',"
                            "last_attempt=? WHERE ext_id=? AND market=?",
                            (utc_now(),songid,market))
                con.commit();continue
            album_id=str(song["collectionId"])
            found=con.execute("SELECT album_id FROM external_album WHERE provider='apple' AND ext_id=?",
                              (album_id,)).fetchone()
            if found and found["album_id"]:
                con.execute("UPDATE release_catalog_queue SET status='done',last_attempt=?"
                            " WHERE ext_id=? AND market=?",(utc_now(),songid,market))
                con.commit();continue
            time.sleep(delay)
            album_data=http_json("https://itunes.apple.com/lookup?"+
                                 urlencode({"id":album_id,"country":market,"entity":"song"}))
            calls+=1
            info=next((x for x in album_data.get("results",[])
                       if x.get("wrapperType")=="collection"),song)
            gid=album(con,info.get("collectionName") or song["collectionName"],
                      info.get("artistName") or song["artistName"],
                      (info.get("releaseDate") or "")[:10])
            tracks=[x for x in album_data.get("results",[])
                    if x.get("wrapperType")=="track" and x.get("kind")=="song"]
            for track in tracks:
                rid=recording(con,track["trackName"],track["artistName"],
                              source="apple",source_id=track.get("trackId"),
                              album=track.get("collectionName",""),
                              release_date=(track.get("releaseDate") or "")[:10])
                con.execute("INSERT OR REPLACE INTO album_track(album_id,recording_id,disc,position)"
                            " VALUES(?,?,?,?)",
                            (gid,rid,track.get("discNumber",1),track.get("trackNumber",1)))
            con.execute("INSERT OR REPLACE INTO external_album(provider,ext_id,album_id,status)"
                        " VALUES('apple',?,?,'done')",(album_id,gid))
            con.execute("UPDATE release_catalog_queue SET status='done',last_attempt=?"
                        " WHERE ext_id=? AND market=?",(utc_now(),songid,market))
            con.commit();expanded+=1
        except (HTTPError,URLError,KeyError,ValueError,TimeoutError) as e:
            # Preserve retryability on network/rate errors; resume next run.
            print(f"defer {market}/{songid}: {e}")
            break
    return expanded,calls

def export(con,where,countries):
    output=Path(where);output.mkdir(parents=True,exist_ok=True)
    stats={}
    for table in ("artist","recording","album","album_track","chart_edition","chart_entry"):
        stats[table]=con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    for country in countries:
        latest=top_current(con,country)
        (output/f"trending_{country}.json").write_text(
            json.dumps(latest,ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    (output/"manifest.json").write_text(
        json.dumps({"generated_at":utc_now(),"countries":list(countries),
                    "stats":stats,"scope":"metadata only; playback links separate"},
                   ensure_ascii=False,indent=2),encoding="utf-8")
    return stats

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=("init","chart-feed","import-chart",
                                          "expand-albums","export","summary","artist"))
    parser.add_argument("--db",type=Path,default=Path(".local/odify_catalog.sqlite3"))
    parser.add_argument("--countries",default="kr,jp,us,gb,de")
    parser.add_argument("--csv",type=Path)
    parser.add_argument("--out-dir",type=Path,default=Path(".local/odify_public"))
    parser.add_argument("--limit",type=int,default=25)
    parser.add_argument("--name",default="")
    args=parser.parse_args()
    con=connect(args.db)
    try:
        countries=[x.lower().strip() for x in args.countries.split(",") if x.strip()]
        if args.command=="chart-feed":
            for country in countries:
                try:
                    url,updated,entries=rss_feed(country)
                    with con:
                        snapshot(con,"apple-rss",country,feed_date(updated),entries,
                                 url=url,rights="Apple marketing RSS - review reuse terms")
                    print(f"{country}: {len(entries)} ranked chart entries")
                except (HTTPError,URLError,ValueError,KeyError) as e:
                    print(f"{country}: fetch failed {e}, preserved any existing snapshot")
        elif args.command=="import-chart":
            if not args.csv:parser.error("--csv required")
            print(import_csv(con,args.csv));con.commit()
        elif args.command=="expand-albums":
            print({"albums_expanded":expand_apple(con,args.limit)})
        elif args.command=="artist":
            print(json.dumps(artist_list(con,args.name,args.limit),ensure_ascii=False,indent=2))
        elif args.command=="export":
            print(export(con,args.out_dir,countries))
        else:
            print(export(con,args.out_dir,countries) if args.command=="summary"
                  else "Database initialized")
    finally:con.close()

if __name__=="__main__":main()
