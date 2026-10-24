#!/usr/bin/env python3
"""Read-only Odify Piped/Invidious link pilot, 25 songs x KR/JP/US/GL.

No YouTube audio downloaded. No Google MASTER writes. Saves 1 atomic JSON
checkpoint per track and a report after every completed track. Rerun safely.

Run:
 python scripts/odify_altlink_pilot.py --catalog .local/odify_catalog.sqlite3 \
   --out .local/odify_altlink_pilot --workers 3
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
from concurrent.futures import ThreadPoolExecutor,as_completed
import csv
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import statistics
import sys
import threading
import time
from urllib.error import HTTPError,URLError
from urllib.parse import parse_qs,urlencode,urlparse
from urllib.request import Request,urlopen

sys.path.insert(0,str(Path(__file__).resolve().parent))
from odify_link_consensus import decide,eligible,canonical,norm

REGIONS=("kr","jp","us","gl")
PROVIDERS={
 "piped":("https://api.piped.private.coffee",
          "https://pipedapi.kavin.rocks","https://pipedapi.adminforge.de"),
 "invidious":("https://inv.nadeko.net","https://invidious.tiekoetter.com",
              "https://yt.chocolatemoo53.com")
}
VALID_ID=re.compile(r"^[A-Za-z0-9_-]{11}$")
OUTPUT_FIELDS=("country","id","artist","title","source_date","source_rank","reference_url",
               "piped_status","piped_latency_ms","piped_candidates","piped_matched",
               "invidious_status","invidious_latency_ms","invidious_candidates","invidious_matched",
               "unique_candidates","eligible_candidates","consensus_status",
               "selected_url","selected_confidence","reference_video_match","review_reason")
NETWORK_HEADERS={"User-Agent":"OdifyLinkPilot/1.0 (metadata-only; limited research)",
                 "Accept":"application/json"}
ENDPOINT_LOCK=threading.Lock()
ENDPOINT_FAILURES={}
ENDPOINT_DISABLED=set()

def endpoint_disabled(base):
    with ENDPOINT_LOCK:return base in ENDPOINT_DISABLED

def note_endpoint_error(base,reason):
    with ENDPOINT_LOCK:
        ENDPOINT_FAILURES[base]=ENDPOINT_FAILURES.get(base,0)+1
        # 403/429 should not trigger aggressive fallback/retries. Many failed
        # connections also indicate an unavailable public volunteer instance.
        if "HTTP 403" in reason or "HTTP 429" in reason or ENDPOINT_FAILURES[base]>=3:
            ENDPOINT_DISABLED.add(base)

def reset_endpoint_health():
    with ENDPOINT_LOCK:
        ENDPOINT_FAILURES.clear()
        ENDPOINT_DISABLED.clear()

def clock():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")

def query_json(url,timeout=8):
    start=time.perf_counter()
    try:
        with urlopen(Request(url,headers=NETWORK_HEADERS),timeout=timeout) as resp:
            if resp.status!=200:raise RuntimeError("HTTP "+str(resp.status))
            size=resp.headers.get("Content-Length")
            if size and int(size)>3_000_000:raise RuntimeError("oversized response")
            data=resp.read(3_000_001)
            if len(data)>3_000_000:raise RuntimeError("oversized response")
            return json.loads(data),round((time.perf_counter()-start)*1000)
    except HTTPError as ex:
        raise RuntimeError(f"HTTP {ex.code}") from ex
    except (URLError,TimeoutError,OSError) as ex:
        raise RuntimeError(type(ex).__name__+": "+str(ex)[:100]) from ex

def video_id(raw):
    candidate=canonical(raw)
    if candidate:return candidate.rsplit("=",1)[-1]
    if not isinstance(raw,str):return ""
    if VALID_ID.fullmatch(raw):return raw
    parsed=urlparse(raw)
    if parsed.path in ("/watch","/watch/"):
        v=parse_qs(parsed.query).get("v",[""])[0]
        return v if VALID_ID.fullmatch(v) else ""
    if parsed.path.startswith("/shorts/"):
        v=parsed.path.split("/")[2]
        return v if VALID_ID.fullmatch(v) else ""
    return ""

def adapt(provider,payload):
    """Normalize both public API responses to existing consensus schema."""
    records=payload.get("items",[]) if provider=="piped" and isinstance(payload,dict) else payload
    if not isinstance(records,list):raise ValueError("unexpected "+provider+" search response")
    out={}
    for r in records:
        if not isinstance(r,dict):continue
        if provider=="piped":
            if r.get("type") not in ("stream","video",None):continue
            vid=video_id(str(r.get("url","") or r.get("id","")))
            secs=r.get("duration")
            name=r.get("uploaderName") or r.get("uploader") or ""
            views=r.get("views") or 0
        else:
            if r.get("type")!="video":continue
            vid=video_id(r.get("videoId",""))
            secs=r.get("lengthSeconds")
            name=r.get("author") or ""
            views=r.get("viewCount") or 0
        if not vid:continue
        try:seconds=max(0,int(float(secs or 0)))
        except (TypeError,ValueError):seconds=0
        try:views=max(0,int(views))
        except (TypeError,ValueError):views=0
        out[vid]={
          "url":"https://www.youtube.com/watch?v="+vid,
          "title":str(r.get("title") or "")[:500],
          "channel":str(name)[:180],
          "seconds":seconds,
          "views":views,
          "description":str(r.get("description") or "")[:300],
          "source":provider
        }
    return out

def request_provider(provider,track,bases,timeout,interval=0.0):
    q=f"{track['artist']} {track['title']} audio"
    attempt=[]
    skipped=0
    for base in bases:
        if endpoint_disabled(base):
            skipped+=1
            continue
        endpoint=(("/search?"+urlencode({"q":q,"filter":"music_songs"}))
                  if provider=="piped" else
                  ("/api/v1/search?"+urlencode({"q":q,"type":"video","sort_by":"relevance",
                                                 "region":track["country"].upper() if track["country"]!="gl" else "US"})))
        start=time.perf_counter()
        try:
            result,ms=query_json(base.rstrip("/")+endpoint,timeout)
            candidates=adapt(provider,result)
            attempt.append({"base":base,"status":"ok","ms":ms,"candidates":len(candidates)})
            with ENDPOINT_LOCK:ENDPOINT_FAILURES.pop(base,None)
            return {"status":"ok","latency_ms":ms,"candidates":list(candidates.values()),
                    "attempts":attempt}
        except (RuntimeError,ValueError,TypeError,json.JSONDecodeError) as ex:
            ms=round((time.perf_counter()-start)*1000)
            reason=str(ex)[:150]
            attempt.append({"base":base,"status":"error","ms":ms,"reason":reason})
            note_endpoint_error(base,reason)
            # Do not switch hosts to evade explicit rate limits / access blocks.
            if "HTTP 429" in reason or "HTTP 403" in reason:
                return {"status":"blocked","latency_ms":ms,
                        "candidates":[],"attempts":attempt}
        if interval:time.sleep(interval)
    status="unavailable" if skipped==len(bases) and not attempt else "failed"
    return {"status":status,"latency_ms":sum(x["ms"] for x in attempt),
            "candidates":[],"attempts":attempt}

def load_references(path):
    if not path:return {}
    output={}
    with Path(path).open(encoding="utf-8-sig",newline="") as fp:
        for row in csv.DictReader(fp):
            if row.get("링크검증")!="검증완료":continue
            link=canonical(row.get("YouTube 오디오 URL",""))
            if not link:continue
            k=(norm(row.get("아티스트","")),norm(row.get("곡명","")))
            if k[0] and k[1]:output[k]=link
    return output

def balanced_sample(catalog,per_region=25,refs=None):
    refs=refs or {}
    con=sqlite3.connect(str(catalog))
    con.row_factory=sqlite3.Row
    chosen=[]
    try:
        for country in REGIONS:
            selected=[]
            seen=set()
            samples=con.execute("""
                SELECT r.id,r.title,a.name AS artist,e.as_of AS date,ce.rank,
                       e.provider
                FROM chart_entry ce
                JOIN chart_edition e ON ce.edition_id=e.id
                JOIN recording r ON ce.recording_id=r.id
                JOIN artist a ON r.artist_id=a.id
                WHERE e.country=? AND e.category IN
                  ('songs','monthly-official-source')
                AND ce.rank BETWEEN 1 AND 60
                ORDER BY e.as_of DESC,ce.rank ASC
            """,(country,)).fetchall()
            # Stratify by chart year, with some hits from the latest chart.
            bins=defaultdict(list)
            for row in samples:
                if not norm(row["artist"]) or not norm(row["title"]):continue
                y=int(row["date"][:4])
                if y<2000 or (country=="jp" and y<2003):continue
                period=("2026" if y>=2026 else "2020-25" if y>=2020 else
                        "2010s" if y>=2010 else "2000s")
                bins[period].append(row)
            if country=="gl":
                plan=[("2026",per_region)]
            else:
                plan=[("2026",8),("2020-25",7),("2010s",5),("2000s",5)]
            for period,n in plan:
                for row in bins.get(period,[]):
                    if n<=0:break
                    pair=(norm(row["artist"]),norm(row["title"]))
                    if pair in seen:continue
                    seen.add(pair);n-=1;selected.append(row)
            # If unavailable in a historical period, top up from available hits.
            if len(selected)<per_region:
                for row in samples:
                    pair=(norm(row["artist"]),norm(row["title"]))
                    if pair in seen:continue
                    seen.add(pair);selected.append(row)
                    if len(selected)==per_region:break
            if len(selected)!=per_region:
                raise ValueError(f"{country}: need {per_region} chart hits; got {len(selected)}")
            for row in selected:
                key=(norm(row["artist"]),norm(row["title"]))
                chosen.append({"country":country,"id":row["id"],
                    "artist":row["artist"],"title":row["title"],
                    "source_date":row["date"],"source_rank":row["rank"],
                    "source_provider":row["provider"],
                    "reference_url":refs.get(key,"")})
    finally:con.close()
    return chosen

def verify_sample(tracks):
    if len(tracks)!=100:raise ValueError("Pilot must have exactly 100 songs")
    c=Counter(x["country"] for x in tracks)
    if any(c[z]!=25 for z in REGIONS):raise ValueError("must be exactly 25 per region")
    ids=[(x["country"],x["id"]) for x in tracks]
    if len(set(ids))!=100:raise ValueError("duplicate sample")

def process_one(track,piped,invidious,timeout):
    providers={}
    for name,bases in (("piped",piped),("invidious",invidious)):
        providers[name]=request_provider(name,track,bases,timeout)
    merged={}
    for name,response in providers.items():
        for c in response["candidates"]:
            key=video_id(c["url"])
            if key:
                if key not in merged:merged[key]=dict(c)
                elif not merged[key]["description"] and c["description"]:
                    merged[key]["description"]=c["description"]
    master={"아티스트":track["artist"],"곡명":track["title"],"음원 버전":""}
    passed=[x for x in merged.values() if eligible(master,x)[0]]
    _,choice=decide(master,merged)
    best=choice.get("selected") or {}
    reference=video_id(track.get("reference_url",""))
    return {"track":track,"providers":providers,
            "unique_candidates":len(merged),"eligible_candidates":len(passed),
            "decision":{"status":choice["status"],"confidence":choice.get("confidence",0),
                        "url":best.get("url",""),"reason":choice.get("reason","")},
            "reference_exact":(bool(reference) and video_id(best.get("url",""))==reference)
                              if reference else None,
            "finished_at":clock()}

def atomic_json(path,obj):
    path=Path(path)
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_suffix(path.suffix+".tmp")
    tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding="utf-8")
    tmp.replace(path)

def read_completed(folder,tracks):
    found={}
    for track in tracks:
        f=Path(folder)/"checkpoints"/(track["country"]+"_"+track["id"]+".json")
        if not f.exists():continue
        try:
            p=json.loads(f.read_text(encoding="utf-8"))
            if p.get("track")==track and all(n in p.get("providers",{}) for n in PROVIDERS):
                found[(track["country"],track["id"])]=p
        except (OSError,ValueError,KeyError):continue
    return found

def percentiles(values):
    if not values:return {"median_ms":None,"p95_ms":None}
    values=sorted(values)
    return {"median_ms":round(statistics.median(values)),
            "p95_ms":values[min(len(values)-1,int((len(values)-1)*.95))]}

def report(tracks,results):
    by_region={}
    metrics={}
    for region in REGIONS:
        relevant=[r for (c,_),r in results.items() if c==region]
        by_region[region]={
            "target":25,"processed":len(relevant),
            "candidate_found":sum(bool(r["unique_candidates"]) for r in relevant),
            "metadata_eligible":sum(bool(r["eligible_candidates"]) for r in relevant),
            "high_confidence":sum(r["decision"]["status"]=="고신뢰" for r in relevant)}
    for provider in PROVIDERS:
        resp=[r["providers"][provider] for r in results.values()]
        good=[r for r in resp if r["status"]=="ok"]
        reasons=Counter(a.get("reason","") for r in resp for a in r.get("attempts",[])
                        if a.get("reason"))
        metrics[provider]={
            "processed_songs":len(resp),
            "attempted_songs":sum(bool(r.get("attempts")) for r in resp),
            "skipped_unavailable":sum(r["status"]=="unavailable" for r in resp),
            "error_breakdown":dict(reasons.most_common(12)),
            "search_success":len(good),
            "failed":sum(r["status"]=="failed" for r in resp),
            "blocked":sum(r["status"]=="blocked" for r in resp),
            "results_nonempty":sum(bool(r["candidates"]) for r in resp),
            "request_attempts":sum(len(r["attempts"]) for r in resp),
            "latency_all":percentiles([r["latency_ms"] for r in resp]),
            "latency_success":percentiles([r["latency_ms"] for r in good])}
    grounded=[r for r in results.values() if r["reference_exact"] is not None]
    matched=sum(r["reference_exact"] is True for r in grounded)
    return {"updated_at":clock(),"target":len(tracks),"processed":len(results),
            "percent":round(100*len(results)/len(tracks),1),
            "countries":by_region,"providers":metrics,
            "metadata_eligible_songs":sum(bool(r["eligible_candidates"]) for r in results.values()),
            "high_confidence":sum(r["decision"]["status"]=="고신뢰" for r in results.values()),
            "verified_reference_tracks":len(grounded),
            "exact_known_video_id_hits":matched,
            "exact_reference_hit_rate":round(100*matched/len(grounded),1) if grounded else None,
            "accuracy_caveat":"Metadata matching and exact reference-video hit rate are NOT listened-to precision."}

def export_rows(folder,tracks,results):
    out=Path(folder)
    path=out/"audit_100.csv"
    with path.open("w",encoding="utf-8-sig",newline="") as fp:
        w=csv.DictWriter(fp,fieldnames=OUTPUT_FIELDS)
        w.writeheader()
        for t in tracks:
            r=results.get((t["country"],t["id"]))
            if not r:continue
            p=r["providers"];d=r["decision"]
            row={"country":t["country"],"id":t["id"],"artist":t["artist"],
                "title":t["title"],"source_date":t["source_date"],
                "source_rank":t["source_rank"],"reference_url":t["reference_url"],
                "unique_candidates":r["unique_candidates"],
                "eligible_candidates":r["eligible_candidates"],
                "consensus_status":d["status"],"selected_url":d["url"],
                "selected_confidence":d["confidence"],
                "review_reason":d["reason"],
                "reference_video_match":r["reference_exact"]}
            for name in PROVIDERS:
                q=p[name]
                row[f"{name}_status"]=q["status"]
                row[f"{name}_latency_ms"]=q["latency_ms"]
                row[f"{name}_candidates"]=len(q["candidates"])
                row[f"{name}_matched"]=sum(eligible(
                     {"아티스트":t["artist"],"곡명":t["title"],"음원 버전":""},x)[0]
                     for x in q["candidates"])
            w.writerow(row)
    # Proposals are always review-only. No write to MASTER or linked sheet.
    proposals=out/"review_only_proposals.csv"
    with proposals.open("w",encoding="utf-8-sig",newline="") as fp:
        w=csv.writer(fp)
        w.writerow(("country","recording_id","artist","track",
                    "candidate_youtube_url","confidence","state","verified","action"))
        for r in results.values():
            if not r["decision"]["url"]:continue
            t=r["track"]
            w.writerow([t["country"],t["id"],t["artist"],t["title"],
                        r["decision"]["url"],r["decision"]["confidence"],
                        r["decision"]["status"],"NO","MANUAL_REVIEW_ONLY"])

def run(catalog,output,reference_csv=None,workers=3,timeout=7,piped=None,
        invidious=None,limit=100):
    folder=Path(output);folder.mkdir(parents=True,exist_ok=True)
    reset_endpoint_health()
    tracks=balanced_sample(catalog,refs=load_references(reference_csv))
    verify_sample(tracks)
    manifest=folder/"sample_100.json"
    if manifest.exists():
        old=json.loads(manifest.read_text(encoding="utf-8"))
        if old!=tracks:
            raise ValueError("Sample changed; use a new --out to avoid mixed checkpoints")
    else:atomic_json(manifest,tracks)
    piped=tuple(piped or PROVIDERS["piped"])
    invidious=tuple(invidious or PROVIDERS["invidious"])
    for b in (*piped,*invidious):
        u=urlparse(b)
        if u.scheme!="https" or not u.hostname or u.username or u.password:
            raise ValueError("Provider bases must be HTTPS URLs with no credentials")
    complete=read_completed(folder,tracks)
    total=min(max(0,int(limit)),len(tracks))
    pending=[t for t in tracks[:total] if (t["country"],t["id"]) not in complete]
    # Bounded parallelism, no API keys, login or audio extraction.
    print("ODIFY_PILOT started already_done="+str(len(complete))+
          " requests_pending="+str(len(pending)),flush=True)
    with ThreadPoolExecutor(max_workers=max(1,min(int(workers),4))) as pool:
        futures={pool.submit(process_one,t,piped,invidious,timeout):t for t in pending}
        for future in as_completed(futures):
            t=futures[future]
            try:r=future.result()
            except Exception as exc:
                # A failed worker does not silently become an empty "successful search".
                r={"track":t,"providers":{p:{"status":"failed","latency_ms":0,
                    "candidates":[],"attempts":[{"reason":repr(exc)[:150]}]}
                    for p in PROVIDERS},"unique_candidates":0,"eligible_candidates":0,
                    "decision":{"status":"후보부족","confidence":0,"url":"","reason":"worker_error"},
                    "reference_exact":None,"finished_at":clock()}
            atomic_json(folder/"checkpoints"/(t["country"]+"_"+t["id"]+".json"),r)
            complete[(t["country"],t["id"])]=r
            stats=report(tracks,complete)
            atomic_json(folder/"progress.json",stats)
            export_rows(folder,tracks,complete)
            print("ODIFY_PILOT_PROGRESS "+json.dumps({
                "percent":stats["percent"],"done":stats["processed"],"total":100,
                "last_region":t["country"],"last_track":t["title"][:40],
                "piped_ok":stats["providers"]["piped"]["search_success"],
                "invidious_ok":stats["providers"]["invidious"]["search_success"],
                "high_confidence":stats["high_confidence"]
            },ensure_ascii=False),flush=True)
    stats=report(tracks,complete)
    atomic_json(folder/"summary.json",stats)
    export_rows(folder,tracks,complete)
    return stats

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--catalog",type=Path,required=True)
    p.add_argument("--master-references",type=Path)
    p.add_argument("--out",type=Path,default=Path(".local/odify_altlink_pilot"))
    p.add_argument("--workers",type=int,default=3)
    p.add_argument("--timeout",type=float,default=7)
    p.add_argument("--limit",type=int,default=100)
    p.add_argument("--piped",action="append",help="HTTPS Piped API instance (can repeat)")
    p.add_argument("--invidious",action="append",help="HTTPS Invidious API instance (can repeat)")
    a=p.parse_args()
    summary=run(a.catalog,a.out,a.master_references,a.workers,a.timeout,
                a.piped,a.invidious,a.limit)
    print("ODIFY_PILOT_FINAL "+json.dumps(summary,ensure_ascii=False),flush=True)

if __name__=="__main__":main()
