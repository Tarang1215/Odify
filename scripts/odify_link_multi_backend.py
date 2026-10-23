#!/usr/bin/env python3
"""Odify Piped/Invidious *metadata-only* YouTube video-ID collector.

Does NOT download audio, save transient stream URLs, or edit Google MASTER.
Uses official YouTube search only as a separately configured future fallback.

Example:
  python scripts/odify_link_multi_backend.py --db .local/odify_catalog.sqlite3 \
      --state .local/link_pilot.sqlite3 --limit-per-country 25 \
      --output-dir .local/link_pilot --timeout 7 --delay 1.5

Re-running with the SAME state skips completed song/provider lookups. Failed
backends are retried only with --retry-errors. Results are suggestions, never
confirmed audio-version matches.
"""
from __future__ import annotations
import argparse
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sqlite3
import statistics
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import Request, urlopen

from odify_link_consensus import decide, norm, canonical

DEFAULT_PIPED=("https://pipedapi.kavin.rocks",)
DEFAULT_INVIDIOUS=("https://inv.nadeko.net","https://invidious.tiekoetter.com")
COUNTRIES=("kr","jp","us","gl")
PROVIDERS=("piped","invidious")
STATE_SQL="""
CREATE TABLE IF NOT EXISTS lookups(
 country TEXT NOT NULL, recording_id TEXT NOT NULL, provider TEXT NOT NULL,
 query TEXT NOT NULL, endpoint TEXT NOT NULL DEFAULT '',
 http_status INTEGER, latency_ms INTEGER, success INTEGER NOT NULL DEFAULT 0,
 result_count INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
 updated_at TEXT NOT NULL, PRIMARY KEY(country,recording_id,provider)
);
CREATE TABLE IF NOT EXISTS candidate(
 country TEXT NOT NULL, recording_id TEXT NOT NULL, video_id TEXT NOT NULL,
 source TEXT NOT NULL, title TEXT NOT NULL, channel TEXT NOT NULL,
 seconds INTEGER NOT NULL, views INTEGER NOT NULL DEFAULT 0,
 description TEXT NOT NULL DEFAULT '',
 PRIMARY KEY(country,recording_id,video_id,source)
);
CREATE TABLE IF NOT EXISTS proposal(
 country TEXT NOT NULL, recording_id TEXT NOT NULL PRIMARY KEY,
 artist TEXT NOT NULL,title TEXT NOT NULL,chart_rank INTEGER NOT NULL,
 chart_date TEXT NOT NULL, chart_source TEXT NOT NULL,
 status TEXT NOT NULL,video_id TEXT,confidence REAL NOT NULL,
 eligible_candidates INTEGER NOT NULL DEFAULT 0,
 candidates INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL,
 evaluated_at TEXT NOT NULL
);
"""
CANDIDATE_FIELDS=("country","recording_id","source","video_id","title",
                  "channel","seconds","views","eligible","reject_reason")
RESULT_FIELDS=("country","chart_rank","chart_date","chart_source","recording_id",
               "artist","title","status","video_id","confidence","candidates",
               "eligible_candidates","reason")
ATTEMPT_FIELDS=("country","recording_id","provider","query","endpoint",
                "http_status","latency_ms","success","result_count","error","updated_at")

def utc_now():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()

def open_state(path):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    c=sqlite3.connect(path)
    c.row_factory=sqlite3.Row
    c.executescript(STATE_SQL)
    return c

def sample_catalog(db,limit=25):
    """25 spaced chart ranks per region; deterministic, independent of RNG."""
    con=sqlite3.connect(f"file:{Path(db).resolve()}?mode=ro",uri=True)
    con.row_factory=sqlite3.Row
    samples=[]
    seen_ids=set()
    for country in COUNTRIES:
        # Latest genuinely published songs, not our own monthly-derived charts.
        providers=("listenbrainz-sitewide",) if country=="gl" else ("apple-rss",)
        ed=con.execute(
            "SELECT id,provider,as_of FROM chart_edition WHERE country=?"
            " AND provider=? AND category='songs' ORDER BY as_of DESC LIMIT 1",
            (country,providers[0])).fetchone()
        if not ed:
            raise RuntimeError(f"No latest chart for {country}; do not fabricate sample")
        rows=con.execute("""
          SELECT ce.rank,ce.recording_id,r.title,a.name AS artist
          FROM chart_entry ce JOIN recording r ON r.id=ce.recording_id
          JOIN artist a ON a.id=r.artist_id
          WHERE ce.edition_id=? ORDER BY ce.rank""",(ed["id"],)).fetchall()
        if len(rows)<limit:raise RuntimeError(f"{country}: only {len(rows)} songs, expected >= {limit}")
        # 25 evenly spaced positions in TOP 100 rather than all top 25.
        indices=[round(i*(len(rows)-1)/max(1,limit-1)) for i in range(limit)]
        for idx in indices:
            # A hit can chart in US, KR, JP and global at once. Reserve one
            # replacement per region so pilot means 100 DISTINCT songs.
            alternatives=sorted(range(len(rows)),key=lambda i:(abs(i-idx),i))
            picked=next((rows[i] for i in alternatives if rows[i]["recording_id"] not in seen_ids),None)
            if picked is None:
                raise RuntimeError(f"{country}: not enough cross-region distinct songs")
            song=picked
            seen_ids.add(song["recording_id"])
            samples.append(dict(country=country,recording_id=song["recording_id"],
                title=song["title"],artist=song["artist"],chart_rank=song["rank"],
                chart_date=ed["as_of"],chart_source=ed["provider"]))
    con.close()
    return samples

def safe_origin(value):
    origin=(value or "").strip().rstrip("/")
    u=urlparse(origin)
    if u.scheme!="https" or not u.hostname or u.username or u.password or u.port not in (None,443):
        raise ValueError("instance base must be an HTTPS hostname without credentials or custom port")
    if u.path not in ("","/") or u.query or u.fragment:
        raise ValueError("instance base must not have a path/query")
    return origin

def fetch_json(url,timeout):
    started=time.perf_counter()
    request=Request(url,headers={"User-Agent":"Odify-LinkResearch/1.0 (github.com/Tarang1215/Odify)",
                                  "Accept":"application/json"})
    try:
        with urlopen(request,timeout=timeout) as r:
            if r.status!=200:raise HTTPError(url,r.status,"non-200",r.headers,None)
            blob=r.read(1_500_001)
            if len(blob)>1_500_000:raise ValueError("JSON response exceeds 1.5 MB")
            data=json.loads(blob)
            return data,r.status,round((time.perf_counter()-started)*1000)
    except Exception as e:
        status=getattr(e,"code",None)
        if not isinstance(status,int):status=None
        return None,status,round((time.perf_counter()-started)*1000),str(e)[:240]

def parse_video_id(url):
    if isinstance(url,str) and re.fullmatch(r"[A-Za-z0-9_-]{11}",url):return url
    if not url:return ""
    # Piped uses /watch?v=ID, youtube watch, or bare IDs.
    parsed=urlparse(url)
    video=parse_qs(parsed.query).get("v",[""])[0]
    if not video and parsed.path.startswith("/watch/"):video=parsed.path.rsplit("/",1)[-1]
    return video if re.fullmatch(r"[A-Za-z0-9_-]{11}",video) else ""

def parse_candidates(provider,body):
    if provider=="piped":
        entries=body.get("items",[]) if isinstance(body,dict) else []
    else:
        entries=body if isinstance(body,list) else []
    out={}
    for item in entries[:75]:
        if not isinstance(item,dict):continue
        if provider=="piped":
            video=parse_video_id(item.get("url",""))
            label=item.get("title","")
            channel=item.get("uploaderName","")
            seconds=int(item.get("duration") or 0)
            views=int(item.get("views") or 0)
            desc=item.get("shortDescription","") or ""
        else:
            if item.get("type") not in ("video",None):continue
            video=parse_video_id(item.get("videoId",""))
            label=item.get("title","")
            channel=item.get("author","")
            seconds=int(item.get("lengthSeconds") or 0)
            views=int(item.get("viewCount") or 0)
            desc=item.get("description","") or ""
        if video and label and 1<=seconds<=7200:
            out[video]={
                "video_id":video,"url":f"https://www.youtube.com/watch?v={video}",
                "title":str(label)[:300],"channel":str(channel)[:200],
                "seconds":seconds,"views":max(0,views),"description":str(desc)[:320],
                "source":provider
            }
    return list(out.values())

def query_for(song):
    return f"{song['artist']} {song['title']} audio"

def provider_urls(provider,instances,query):
    for base in instances:
        if provider=="piped":
            yield base+"/search?"+urlencode({"q":query,"filter":"music_songs"})
        else:
            yield base+"/api/v1/search?"+urlencode({"q":query,"type":"video","sort_by":"relevance"})

def lookup(song,provider,instances,timeout,request):
    query=query_for(song)
    results=[]
    errors=[]
    total_latency=0
    last_status=None
    last_endpoint=""
    for url in provider_urls(provider,instances,query):
        last_endpoint=url.split("?")[0]
        response=request(url,timeout)
        if len(response)==4:
            _,last_status,latency,error=response
            errors.append(f"{urlparse(url).hostname}: {error}")
        else:
            body,last_status,latency=response
            try:
                results=parse_candidates(provider,body)
                total_latency+=latency
                return dict(provider=provider,query=query,endpoint=last_endpoint,
                            status=last_status,latency=total_latency,success=1,
                            candidates=results,error="")
            except (ValueError,TypeError,AttributeError) as e:
                errors.append(f"{urlparse(url).hostname}: {str(e)[:130]}")
        total_latency+=latency
        # Fail over only on network/server failures; don't force extra requests
        # when instance returned a legitimate (possibly empty) search result.
        if last_status in (400,401,403,404,429):
            break
    return dict(provider=provider,query=query,endpoint=last_endpoint,
                status=last_status,latency=total_latency,success=0,
                candidates=[],error="; ".join(errors)[:400])

def persist_lookup(con,song,result):
    with con:
        con.execute("INSERT OR REPLACE INTO lookups("
           "country,recording_id,provider,query,endpoint,http_status,latency_ms,"
           "success,result_count,error,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
           (song["country"],song["recording_id"],result["provider"],
            result["query"],result["endpoint"],result["status"],
            result["latency"],result["success"],len(result["candidates"]),
            result["error"],utc_now()))
        for candidate in result["candidates"]:
            con.execute("INSERT OR REPLACE INTO candidate VALUES(?,?,?,?,?,?,?,?,?)",
                (song["country"],song["recording_id"],candidate["video_id"],
                 result["provider"],candidate["title"],candidate["channel"],
                 candidate["seconds"],candidate["views"],candidate["description"]))

def choose(con,song):
    records=con.execute("SELECT * FROM candidate WHERE country=? AND recording_id=?",
                        (song["country"],song["recording_id"])).fetchall()
    grouped={}
    for r in records:
        candidate=dict(url=f"https://www.youtube.com/watch?v={r['video_id']}",
                       title=r["title"],channel=r["channel"],seconds=r["seconds"],
                       views=r["views"],description=r["description"])
        # Two services showing the SAME video do NOT count as independent uploaders.
        old=grouped.get(candidate["url"])
        if old is None or (len(candidate["description"]),candidate["views"])>(
            len(old["description"]),old["views"]):
            grouped[candidate["url"]]=candidate
    master={"아티스트":song["artist"],"곡명":song["title"],
            "음원 버전":""}
    classified,decision=decide(master,grouped)
    valid=sum(1 for c in classified if c["ok"])
    selection=decision.get("selected",{})
    found=con.execute("SELECT success FROM lookups WHERE country=? AND recording_id=?",
                      (song["country"],song["recording_id"])).fetchall()
    all_failed=bool(found) and not any(x["success"] for x in found)
    if all_failed: status="provider_unavailable"
    elif decision["status"]=="고신뢰":status="high_confidence_unverified"
    elif selection:status="manual_review"
    else:status="no_eligible_match"
    record=dict(song,status=status,video_id=selection.get("url","").split("v=")[-1]
                if selection else "",confidence=float(decision.get("confidence") or 0),
                candidates=len(grouped),eligible_candidates=valid,
                reason=decision["reason"])
    with con:
        con.execute("INSERT OR REPLACE INTO proposal VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (song["country"],song["recording_id"],song["artist"],
                     song["title"],song["chart_rank"],song["chart_date"],
                     song["chart_source"],status,record["video_id"],
                     record["confidence"],valid,len(grouped),record["reason"],
                     utc_now()))
    return record,classified

def current_stats(con,sample,completed):
    attempts=[dict(x) for x in con.execute("SELECT * FROM lookups")]
    counts={country:sum(1 for s in completed if s["country"]==country)
            for country in COUNTRIES}
    total=len(sample)
    result={
        "total":total,"completed":len(completed),
        "percent":round(100*len(completed)/max(1,total),1),
        "per_country":counts,
        "attempts":len(attempts),
        "successful_requests":sum(x["success"] for x in attempts),
        "failed_requests":sum(not x["success"] for x in attempts),
        "updated_at":utc_now()
    }
    return result

def write_json(path,record):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding="utf-8")
    tmp.replace(path)

def export(con,sample,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    def csv_file(name,fields,rows):
        with (output/name).open("w",newline="",encoding="utf-8-sig") as fp:
            w=csv.DictWriter(fp,fieldnames=fields,extrasaction="ignore")
            w.writeheader();w.writerows(rows)
    attempted=[dict(x) for x in con.execute("SELECT * FROM lookups ORDER BY country,recording_id,provider")]
    proposals=[dict(x) for x in con.execute("SELECT * FROM proposal ORDER BY country,chart_rank")]
    candidates=[]
    for a in con.execute("SELECT * FROM candidate ORDER BY country,recording_id,source"):
        d=dict(a)
        song=next((s for s in sample if s["country"]==d["country"] and s["recording_id"]==d["recording_id"]),None)
        if song:
            master={"아티스트":song["artist"],"곡명":song["title"],"음원 버전":""}
            from odify_link_consensus import eligible
            ok,reason=eligible(master,dict(url="",title=d["title"],channel=d["channel"],
                                          seconds=d["seconds"],views=d["views"],
                                          description=d["description"]))
            d["eligible"]=int(ok);d["reject_reason"]=reason
        candidates.append(d)
    csv_file("pilot_100_sample.csv",tuple(sample[0].keys()),sample)
    csv_file("pilot_provider_attempts.csv",ATTEMPT_FIELDS,attempted)
    csv_file("pilot_link_proposals.csv",RESULT_FIELDS,proposals)
    csv_file("pilot_candidates.csv",CANDIDATE_FIELDS,candidates)
    summary=current_stats(con,sample,proposals)
    summary["status_counts"]={s:sum(x["status"]==s for x in proposals)
        for s in sorted({x["status"] for x in proposals})}
    summary["providers"]={}
    for provider in PROVIDERS:
        arr=[x for x in attempted if x["provider"]==provider]
        success=[x for x in arr if x["success"]]
        summary["providers"][provider]={
            "attempted":len(arr),"succeeded":len(success),
            "failed":len(arr)-len(success),
            "success_rate_percent":round(100*len(success)/max(1,len(arr)),1),
            "median_latency_ms":round(statistics.median(x["latency_ms"] for x in arr)) if arr else None,
            "p95_latency_ms":sorted(x["latency_ms"] for x in arr)[min(len(arr)-1,int(.95*len(arr)))] if arr else None,
            "candidate_videos":sum(x["result_count"] for x in arr),
            "errors_by_type":{k:sum(bool(x["error"]) and k in x["error"] for x in arr)
                for k in ("HTTP","timed out","Forbidden","Connection","SSL","429")}
        }
    summary["matching_quality"]={
        "eligible_candidate_coverage_percent":round(100*sum(x["eligible_candidates"]>0 for x in proposals)/max(1,len(proposals)),1),
        "high_confidence_unverified":sum(x["status"]=="high_confidence_unverified" for x in proposals),
        "manually_verified_accuracy_percent":None,
        "reason":"Video metadata matching is not verified listening ground truth; cannot claim accuracy."
    }
    summary["master_modified"]=False
    summary["notes"]="Read-only test; suggestions not applied to Google MASTER."
    write_json(output/"pilot_summary.json",summary)
    write_json(output/"progress.json",summary)
    return summary

def run(args,request=fetch_json):
    sample=sample_catalog(args.db,args.limit_per_country)
    state=open_state(args.state)
    try:
        pp=tuple(safe_origin(s) for s in args.piped_instances.split(",") if s.strip())
        iv=tuple(safe_origin(s) for s in args.invidious_instances.split(",") if s.strip())
        assert pp and iv
        attempts_this_run=0
        for idx,song in enumerate(sample,1):
            for provider,instances in (("piped",pp),("invidious",iv)):
                old=state.execute("SELECT * FROM lookups WHERE country=? AND recording_id=? AND provider=?",
                                  (song["country"],song["recording_id"],provider)).fetchone()
                if old and (old["success"] or not args.retry_errors):
                    continue
                value=lookup(song,provider,instances,args.timeout,request)
                persist_lookup(state,song,value)
                attempts_this_run+=1
                # The provider itself is expensive; avoid hammering shared instances.
                if args.delay>0:time.sleep(args.delay)
            current,_=choose(state,song)
            count=state.execute("SELECT COUNT(*) FROM proposal").fetchone()[0]
            status={"done":count,"total":len(sample),"percent":round(100*count/len(sample),1),
                    "current_country":song["country"],"current_artist":song["artist"],
                    "current_title":song["title"],"current_rank":song["chart_rank"],
                    "status":current["status"],"requests_this_run":attempts_this_run,
                    "timestamp":utc_now()}
            write_json(Path(args.output_dir)/"progress.json",status)
            print("ODIFY_LINK_PROGRESS "+json.dumps(status,ensure_ascii=False),flush=True)
            # Do not hold a single public-instance runner open indefinitely.
            if args.max_songs_this_run and idx>=args.max_songs_this_run:break
        return export(state,sample,args.output_dir)
    finally:state.close()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db",type=Path,default=Path(".local/odify_catalog.sqlite3"))
    p.add_argument("--state",type=Path,default=Path(".local/odify_link_provider_state.sqlite3"))
    p.add_argument("--output-dir",type=Path,default=Path(".local/link_pilot"))
    p.add_argument("--limit-per-country",type=int,default=25)
    p.add_argument("--max-songs-this-run",type=int,default=0)
    p.add_argument("--timeout",type=float,default=7)
    p.add_argument("--delay",type=float,default=1.5)
    p.add_argument("--retry-errors",action="store_true")
    p.add_argument("--piped-instances",default=",".join(DEFAULT_PIPED))
    p.add_argument("--invidious-instances",default=",".join(DEFAULT_INVIDIOUS))
    args=p.parse_args()
    if not 1<=args.limit_per_country<=100: p.error("limit-per-country: 1..100")
    if not 1<=args.timeout<=25 or not 0<=args.delay<=30:p.error("Invalid timeout/delay")
    print(json.dumps(run(args),ensure_ascii=False,indent=2),flush=True)

if __name__=="__main__":
    main()
