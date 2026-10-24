#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4: 300-track, restartable metadata-only Piped pilot.

100 cached V2 baseline tracks + 200 NEW distinct chart tracks, 75 per region.
All paths are artifacts. Never connects to or edits MASTER Google Sheets.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sqlite3
import statistics
import sys
import time
from urllib.parse import urlencode, urlparse
from urllib.error import HTTPError, URLError
from urllib.request import Request,urlopen

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_pilot as v2
import odify_altlink_rerank as v3
from odify_link_consensus import norm

REGIONS=("kr","jp","us","gl")
SCHEMA="v4-300-1"
HEADERS={"User-Agent":"OdifyResearch/1.0 (+https://github.com/Tarang1215/Odify; metadata only)",
         "Accept":"application/json"}
DEFAULT_BASES=("https://api.piped.private.coffee","https://pipedapi.ducks.party")
REPORT_FIELDS=("country","id","artist","title","source_date","source_rank",
 "cached_v2","requests","http_ok","piped_candidates","first_query_v3_eligible",
 "expanded_v4_eligible","v3_choice","v4_choice","v3_state","v4_state",
 "score","review_only","reference_url_modified")
CANDIDATE_FIELDS=("country","id","artist","title","url","video_title","channel","seconds",
 "views","v3_eligible","v4_eligible","v4_score","v4_reasons","v4_selected",
 "source_query")
ATTMPT_FIELDS=("country","id","source","query","filter","endpoint","status",
               "candidate_count","latency_ms","error")

def atomic(path,obj):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+".tmp")
    temp.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding="utf-8")
    temp.replace(path)

def balanced_300(catalog, cached_sample=None):
    """75 per region: preserve 25 original pilot tracks in each region, add 50
    from chart history in a deterministic, region-balanced order, avoiding
    duplicate recordings across the 300-track sample."""
    base=v2.balanced_sample(catalog,per_region=25)
    v2.verify_sample(base)
    if cached_sample is not None and base!=cached_sample:
        raise ValueError("V2 sample changed; refuse to mix checkpoints")
    # V2's 100 rows contain one recording repeated across two countries.
    # Keep the first encounter and refill the other region; 300 really means
    # 300 UNIQUE recording IDs rather than 300 duplicated chart rows.
    chosen=[]
    used=set()
    for row in base:
        if row["id"] not in used:
            chosen.append(row)
            used.add(row["id"])
    with sqlite3.connect(f"file:{Path(catalog).resolve()}?mode=ro",uri=True) as conn:
        conn.row_factory=sqlite3.Row
        for country in REGIONS:
            rows=conn.execute("""
                SELECT DISTINCT r.id,r.title,a.name AS artist,e.as_of AS date,
                       ce.rank,e.provider
                FROM chart_entry ce
                JOIN chart_edition e ON e.id=ce.edition_id
                JOIN recording r ON ce.recording_id=r.id
                JOIN artist a ON r.artist_id=a.id
                WHERE e.country=? AND e.category IN ('songs','monthly-official-source')
                  AND ce.rank BETWEEN 1 AND 100
                ORDER BY e.as_of DESC, ce.rank ASC
            """,(country,)).fetchall()
            # Sample across years rather than filling only with this week's top songs.
            buckets=defaultdict(list)
            for r in rows:
                if r["id"] in used or not norm(r["artist"]) or not norm(r["title"]):
                    continue
                y=int(r["date"][:4])
                period="2026" if y>=2026 else "2020-25" if y>=2020 else "2010s" if y>=2010 else "older"
                buckets[period].append(r)
            extra=75-sum(x["country"]==country for x in chosen)
            wanted={"2026":20,"2020-25":15,"2010s":10,"older":5}
            selected=[]
            for period,target in wanted.items():
                pool=buckets[period]
                if not pool:continue
                # Walk spaced indices then backfill without replacement.
                positions=[round(i*(len(pool)-1)/max(1,target-1)) for i in range(target)]
                for index in positions:
                    r=pool[index]
                    if r["id"] not in used:
                        used.add(r["id"]);selected.append(r)
            if len(selected)<extra:
                for r in rows:
                    if r["id"] in used or not norm(r["artist"]) or not norm(r["title"]):
                        continue
                    used.add(r["id"]);selected.append(r)
                    if len(selected)==extra:break
            if len(selected)<extra:
                raise ValueError(f"{country}: not enough unique chart recordings for 75")
            if len(selected)>extra:selected=selected[:extra]
            for r in selected:
                chosen.append({"country":country,"id":r["id"],
                  "artist":r["artist"],"title":r["title"],"source_date":r["date"],
                  "source_rank":r["rank"],"source_provider":r["provider"],
                  "reference_url":""})
    if len(chosen)!=300 or len({r["id"] for r in chosen})!=300 or any(
        sum(r["country"]==c for r in chosen)!=75 for c in REGIONS):
        raise ValueError("300 distinct recordings or 75/region invariant failed")
    return chosen

def check_origin(base):
    u=urlparse(base)
    if u.scheme!="https" or not u.hostname or u.username or u.password or u.port not in (None,443) or u.path not in ("","/") or u.query or u.fragment:
        raise ValueError("HTTPS hostname only; no port, credentials or path")
    return base.rstrip("/")

def fetch(base,query,filter_name,timeout):
    url=base+"/search?"+urlencode({"q":query,"filter":filter_name})
    started=time.monotonic()
    try:
        with urlopen(Request(url,headers=HEADERS),timeout=timeout) as f:
            if f.status!=200:raise RuntimeError(f"HTTP {f.status}")
            blob=f.read(1_500_001)
            if len(blob)>1_500_000:raise ValueError("oversized JSON")
            data=json.loads(blob)
        adapted=v2.adapt("piped",data)
        return {"status":200,"ms":round((time.monotonic()-started)*1000),
                "error":"","candidates":list(adapted.values())}
    except HTTPError as e:
        return {"status":e.code,"ms":round((time.monotonic()-started)*1000),
                "error":f"HTTP {e.code}","candidates":[]}
    except (URLError,TimeoutError,OSError,ValueError,TypeError,json.JSONDecodeError) as e:
        return {"status":0,"ms":round((time.monotonic()-started)*1000),
                "error":type(e).__name__+": "+str(e)[:110],"candidates":[]}

def search(track, query, filter_name, bases, health,timeout,delay,fetcher=fetch):
    attempts=[]
    for base in bases:
        if base in health["disabled"]:continue
        result=fetcher(base,query,filter_name,timeout)
        attempts.append({"source":"piped","query":query,"filter":filter_name,
         "endpoint":base,"status":result["status"],
         "candidate_count":len(result["candidates"]),"latency_ms":result["ms"],
         "error":result["error"]})
        if delay>0:time.sleep(delay)
        if result["status"]==200:
            health["failures"][base]=0
            return result["candidates"],attempts
        health["failures"][base]+=1
        # Access restrictions are not a signal to rotate IPs/instances.
        if result["status"] in (401,403,429):
            health["disabled"].update(bases)
            break
        if health["failures"][base]>=3:health["disabled"].add(base)
    return [],attempts

def normalize_label(text):
    text=v3.clean_title(text)
    # Japanese quotation punctuation often joins track and artist.
    text=re.sub(r"[『「【](.*?)[』」】]",r" - \1 - ",text)
    return text

def title_match(track,c):
    name=track["title"]
    old=v3.title_match(name,c["title"])
    if old!="none":return old
    v=normalize_label(c["title"])
    goal=norm(v3.clean_title(name))
    if len(goal)<5:return "none"
    # Match a complete segment; not arbitrary substring (RUN vs RUN BTS).
    for piece in re.split(r"\s*[/｜|]\s*|\s+[-–—:]\s+|[『』「」【】]",v):
        if norm(piece)==goal:return "segment"
    # Artist-label prefixes can be stuck to a song title, but require known
    # artist prefix/suffix, not an arbitrary substring.
    fused=norm(v)
    artists=v3.alias(track.get("artist","")) if hasattr(v3,"alias") else []
    if any(fused in (a+goal,goal+a) for a in artists):
        return "artist_joined"
    return "none"

def grade_v4(track,c):
    # Apply version and uploader safety to the ORIGINAL title before any
    # structural title repair. Never let a repaired "Artist - Song" string
    # strip "Instrumental", "Live", "Cover", "Remix", etc.
    master={"아티스트":track["artist"],"곡명":track["title"],
            "음원 버전":track.get("version","")}
    original_issue=v3.version_issue(master,c)
    if original_issue and not original_issue.startswith("피처링 미확인"):
        return dict(eligible=False,score=0,artist_match=False,
          title_match="none",official_evidence=False,album_match=False,
          duration_difference=None,reasons="version_rejected_original: "+original_issue)
    # "Unofficial Audio" contains the substring "official audio". It must
    # not be scored as official; for original-release links exclude it.
    if re.search(r"\bunofficial\b",str(c.get("title") or ""),re.I):
        return dict(eligible=False,score=0,artist_match=False,
          title_match="none",official_evidence=False,album_match=False,
          duration_difference=None,reasons="unofficial_upload")
    # V4.4 regression: original studio tracks must never be replaced by
    # covers, instrumental fan edits, remixes, slowed versions or karaoke.
    # Cross-script suffixes are common in KR/JP search results.
    headline=str(c.get("title") or "")
    wanted=str(track.get("title") or "")+" "+str(track.get("version") or "")
    variant_patterns={
      "cover":r"\bcover\b|カバー|歌ってみた|커버",
      "instrumental":r"\binstrumental\b|\binst\.?\b|カラオケ|노래방|반주",
      "remix":r"\bremix\b|リミックス|리믹스",
      "speed_edit":r"\bslowed\b|\bsped[ -]?up\b|\bnightcore\b|속도조절",
      "live":r"\blive\b|ライブ|라이브|공연실황",
    }
    for variant,pattern in variant_patterns.items():
      if re.search(pattern,headline,re.I) and not re.search(pattern,wanted,re.I):
        return dict(eligible=False,score=0,artist_match=False,
           title_match="none",official_evidence=False,album_match=False,
           duration_difference=None,reasons="non_original_variant:"+variant)
    base=v3.rank_candidate(track,c)
    old_match=base["title_match"]
    improved=title_match(track,c)
    if not base["eligible"] and base["reasons"]=="title_mismatch" and improved!="none":
        # Re-use V3 safety checks by passing the track as a segment match,
        # but NEVER use this provisional title in the actual audit row.
        proposed={**c,"title":track["artist"]+" - "+track["title"]}
        base=v3.rank_candidate(track,proposed)
        if base["eligible"]:
            # The proxy title is ONLY a way to re-run strict version/time
            # checks; its injected artist must not create positive evidence.
            real_artist=v3.artist_evidence(track,c)
            if base["artist_match"] and not real_artist:
                base["score"]=max(0,base["score"]-19)
                base["reasons"]+="; original_artist_unconfirmed"
            base["artist_match"]=real_artist
            base["title_match"]=improved
            base["reasons"]+="; v4_structural_title"
            base["score"]=max(0,base["score"]-6)
    if base["eligible"]:
        # An exact title on an unrelated user's channel is not strong
        # enough to outrank a full song from the named artist. V4 initially
        # overweighted exact-title fan uploads; make the bonus conditional.
        if base["title_match"]=="exact" and base["artist_match"]:
            base["score"]=min(99,base["score"]+3)
            base["reasons"]+="; artist_and_exact_title"
        label=str(c.get("title") or "")
        chan=str(c.get("channel") or "")
        # A promotional PV/opening sequence can mention the right artist and
        # song while being a short excerpt. Retain as review candidate but
        # prefer normal full-track results.
        promo=bool(re.search(r"(?<![a-z])pv(?![a-z])|trailer|teaser|preview|"
              r"opening\s+movie|ending\s+movie|公開記念|予告|ティーザー",label,re.I))
        if promo:
            base["score"]=max(0,base["score"]-26)
            base["reasons"]+="; promotional_clip"
        # A channel or uploader claiming to be Official is not evidence that
        # a lyric video is the original artist upload. Penalize metadata-only.
        if re.search(r"\blyrics?\b|가사|歌詞",label,re.I) and not re.search(
              r"-\s*topic\s*$",chan,re.I):
            base["score"]=max(0,base["score"]-12)
            base["reasons"]+="; lyrics_require_artist_review"
        # User-created lyric videos are searchable fallback candidates but
        # are not official releases by virtue of quoting a whole title.
        lyrics=bool(re.search(r"lyrics?|가사|歌詞",label,re.I))
        if lyrics and not (re.search(r"-\s*topic\s*$",chan,re.I) or
                          re.search(r"official",chan,re.I)):
            base["score"]=max(0,base["score"]-17)
            base["reasons"]+="; non_official_lyrics"
        # V3 official evidence is not proof; an uploader named "Official" alone
        # is an easily forged signal, and no candidate is auto approved.
        description=str(c.get("description") or "").casefold()
        topic=bool(re.search(r"-\s*topic\s*$",str(c.get("channel") or ""),re.I))
        distributor="provided to youtube by" in description
        if topic or distributor:
            base["score"]=min(99,base["score"]+3)
            base["reasons"]+="; topic_or_distributor_claim"
        if (not base["artist_match"]) and track["country"] in ("kr","jp"):
            base["reasons"]+="; multilingual_artist_manual_review"
        if (str(c.get("title") or "").count("|")>=2):
            base["score"]=max(0,base["score"]-5)
    return base

def merge(providers):
    found={}
    origins={}
    for provider,record in providers.items():
        for item in record.get("candidates",[]):
            url=v3.video_id(item.get("url",""))
            if not url:continue
            old=found.get(url)
            if old is None or (bool(item.get("description")),
              int(item.get("views") or 0)) > (bool(old.get("description")),
              int(old.get("views") or 0)):
                found[url]=item
            origins.setdefault(url,set()).add(provider)
    return found,origins

def rank_v4(track,providers,baseline_url=""):
    merged,origins=merge(providers)
    ranked=[]
    for vid,item in merged.items():
        score=grade_v4(track,item)
        ranked.append({**item,**score,"video_id":vid,
                       "source_query":";".join(sorted(origins[vid]))})
    ranked.sort(key=lambda x:(x["eligible"],x["score"],x["artist_match"],
             x["title_match"]=="exact",x["video_id"]),reverse=True)
    best=next((x for x in ranked if x["eligible"]),None)
    if not best:return ranked,None,"no_eligible_candidate"
    # An additional weak second-search hit must not displace an eligible
    # existing first-query choice. Keep the original pick unless the new
    # upload has *independent* album+duration+official metadata evidence.
    # This is NOT reference ground truth and never auto-verifies any URL.
    pinned=next((x for x in ranked if x["eligible"] and
                 x["url"]==baseline_url),None) if baseline_url else None
    retained=False
    if pinned is not None and best["url"]!=pinned["url"]:
        independent=(best["official_evidence"] and best["album_match"] and
              best["duration_difference"] is not None and
              best["duration_difference"]<=7 and
              best["score"]>=pinned["score"]+12)
        if not independent:
            best=pinned
            retained=True
    if retained:
        return ranked,best,"baseline_retained_review"
    eligible=[x for x in ranked if x["eligible"]]
    if len(eligible)>1 and best["score"]-eligible[1]["score"]<=5:
        return ranked,best,"ambiguous_review"
    if not best["artist_match"]:return ranked,best,"artist_review"
    if "requested_version_unconfirmed" in best["reasons"]:
        return ranked,best,"version_review"
    if best["score"]>=88 and best["official_evidence"] and best["album_match"] and best["duration_difference"] is not None and best["duration_difference"]<=7:
        return ranked,best,"strong_metadata_review"
    return ranked,best,"manual_review"

def process(track,bases,health,timeout,delay,max_queries=2,cached=None,fetcher=fetch):
    # V2 cached checkpoint counts as previously observed search, NEVER as a
    # new HTTP success or fresh latency sample.
    previous=list(cached.get("providers",{}).get("piped",{}).get("candidates",[])) if cached else []
    providers={"piped":{"candidates":previous}}
    old_ranked,old_best,old_state=v3.ranking(track,providers)
    prior_valid=bool(old_best)
    attempts=[]
    newly_fetched=[]
    q=f"{track['artist']} {track['title']} audio"
    if not cached and any(b not in health["disabled"] for b in bases):
        found,attempt=search(track,q,"music_songs",bases,health,timeout,delay,fetcher)
        attempts.extend(attempt)
        newly_fetched.extend(found)
    first_providers={"piped":{"candidates":previous+newly_fetched}}
    first_ranked,first_best,first_state=v3.ranking(track,first_providers)
    # Only spend a second request where matching is difficult; do not
    # exhaust volunteer instances with unconditional duplicate searches.
    if max_queries>=2 and any(b not in health["disabled"] for b in bases) and (not first_best or
          first_state in ("artist_review","ambiguous_review")):
        alt=f'{track["artist"]} "{track["title"]}" official audio'
        found,attempt=search(track,alt,"videos",bases,health,timeout,delay,fetcher)
        attempts.extend(attempt)
        newly_fetched.extend(found)
    providers["piped"]["candidates"]=previous+newly_fetched
    ranked,best,state=rank_v4(track,providers,
                                 first_best.get("url","") if first_best else "")
    return {"schema":SCHEMA,"track":track,"cached_v2":bool(cached),
      "attempts":attempts,"providers":providers,
      "first_v3":{"state":first_state,"eligible":sum(x["eligible"] for x in first_ranked),
        "selected_url":first_best.get("url","") if first_best else ""},
      "v4":{"state":state,"eligible":sum(x["eligible"] for x in ranked),
       "selected_url":best["url"] if best else "",
       "score":best["score"] if best else 0},
      "original_v2_candidate_count":len(previous),
      "fetched_candidate_count":len(newly_fetched),"finished":datetime.now(timezone.utc).isoformat()}

def load_cached(path):
    if path is None:return {},None
    results=list(v3.read_checkpoints(path))
    old={}
    for r in results:
        t=r["track"]
        old[(t["country"],t["id"])]=r
    if len(old)!=100:raise ValueError("V2 reference should contain 100 checkpoint files")
    # Manifest regenerated from DB must be exact for safe joins, but
    # the V2 sample's order can be read from original JSON sample_100.json.
    manifest=Path(path)/"sample_100.json"
    if not manifest.exists():raise ValueError("missing V2 sample_100.json")
    return old,json.loads(manifest.read_text(encoding="utf-8"))

def result_stats(results):
    rows=list(results.values())
    valid=lambda r:bool(r["v4"]["eligible"])
    attempts=[a for r in rows for a in r.get("attempts",[])]
    succeeded=[a for a in attempts if a["status"]==200]
    latencies=sorted(a["latency_ms"] for a in succeeded)
    return {"target":300,"processed":len(rows),
      "cached_v2_songs":sum(r["cached_v2"] for r in rows),
      "fresh_search_songs":sum(not r["cached_v2"] for r in rows),
      "fresh_http_attempts":len(attempts),
      "fresh_http_successes":len(succeeded),
      "http_status_counts":dict(sorted(Counter(str(a["status"]) for a in attempts).items())),
      "latency_success_median_ms":round(statistics.median(latencies)) if latencies else None,
      "latency_success_p95_ms":latencies[int(.95*(len(latencies)-1))] if latencies else None,
      "first_query_v3_eligible_songs":sum(r["first_v3"]["eligible"]>0 for r in rows),
      "v4_eligible_songs":sum(valid(r) for r in rows),
      "v4_states":dict(sorted(Counter(r["v4"]["state"] for r in rows).items())),
      "by_country":{c:{"processed":sum(r["track"]["country"]==c for r in rows),
        "v4_eligible":sum(r["track"]["country"]==c and valid(r) for r in rows)}
        for c in REGIONS},
      "verified_music_matches":None,
      "master_modified":False,
      "caveat":"Fresh HTTP success, V3/V4 eligibility and listening-verified music accuracy are distinct. V3 first query vs V4 expanded candidates is NOT a controlled algorithm-only comparison."}

def export(folder,results):
    folder=Path(folder)
    reports=[];candidates=[];attempts=[];proposals=[]
    for r in sorted(results.values(),key=lambda x:(REGIONS.index(x["track"]["country"]),x["track"]["id"])):
        t=r["track"];p=r["providers"]
        ranked,best,state=rank_v4(t,p,r["first_v3"]["selected_url"])
        first=r["first_v3"]
        reports.append(dict(country=t["country"],id=t["id"],artist=t["artist"],
           title=t["title"],source_date=t["source_date"],source_rank=t["source_rank"],
           cached_v2=int(r["cached_v2"]),requests=len(r["attempts"]),
           http_ok=sum(a["status"]==200 for a in r["attempts"]),
           piped_candidates=len(ranked),first_query_v3_eligible=first["eligible"],
           expanded_v4_eligible=r["v4"]["eligible"],v3_choice=first["selected_url"],
           v4_choice=r["v4"]["selected_url"],v3_state=first["state"],v4_state=state,
           score=r["v4"]["score"],review_only="YES",reference_url_modified="NO"))
        for a in r["attempts"]:attempts.append({"country":t["country"],"id":t["id"],**a})
        for c in ranked:
            candidates.append({"country":t["country"],"id":t["id"],
               "artist":t["artist"],"title":t["title"],"url":c["url"],
               "video_title":c["title"],"channel":c.get("channel",""),
               "seconds":c["seconds"],"views":c.get("views",0),
               "v3_eligible":int(v3.rank_candidate(t,c)["eligible"]),
               "v4_eligible":int(c["eligible"]),"v4_score":c["score"],
               "v4_reasons":c["reasons"],"v4_selected":int(c is best),
               "source_query":c["source_query"]})
        proposals.append({"country":t["country"],"id":t["id"],
             "artist":t["artist"],"title":t["title"],
             "candidate_url":r["v4"]["selected_url"],"review_status":state,
             "verified":"NO","action":"MANUAL_REVIEW_ONLY"})
    def csvwrite(filename,fields,rows):
        path=folder/filename
        temp=path.with_name(path.name+".tmp")
        with temp.open("w",newline="",encoding="utf-8-sig") as f:
            writer=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore")
            writer.writeheader();writer.writerows(rows)
        temp.replace(path)
    csvwrite("sample_300.csv",tuple(results[next(iter(results))]["track"].keys()),
             [r["track"] for r in results.values()])
    csvwrite("audit_300_v4.csv",REPORT_FIELDS,reports)
    csvwrite("candidates_300_v4.csv",CANDIDATE_FIELDS,candidates)
    csvwrite("attempts_300_v4.csv",ATTMPT_FIELDS,attempts)
    csvwrite("proposals_review_only_300.csv",
       ("country","id","artist","title","candidate_url","review_status","verified","action"),proposals)
    summary=result_stats(results)
    summary["unique_video_candidates_per_track_sum"]=sum(r["piped_candidates"] for r in reports)
    atomic(folder/"summary_300_v4.json",summary)
    return summary

def run(catalog,folder,cached_v2=None,limit=300,max_queries=2,timeout=6,delay=.8,bases=DEFAULT_BASES,fetcher=fetch):
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    bases=tuple(check_origin(x) for x in bases)
    if not bases:raise ValueError("No Piped endpoints")
    old,old_manifest=load_cached(cached_v2)
    tracks=balanced_300(catalog,old_manifest)
    manifest=folder/"sample_300.json"
    if manifest.exists() and json.loads(manifest.read_text(encoding="utf-8"))!=tracks:
        raise ValueError("sample mismatch; choose new output folder")
    if not manifest.exists():atomic(manifest,tracks)
    settings={"schema":SCHEMA,"bases":list(bases),"max_queries":max_queries}
    config=folder/"settings.json"
    if config.exists() and json.loads(config.read_text())!=settings:
        raise ValueError("collector settings mismatch; choose new output folder")
    if not config.exists():atomic(config,settings)
    health={"disabled":set(),"failures":defaultdict(int)}
    results={}
    for t in tracks:
        path=folder/"checkpoints"/(t["country"]+"_"+t["id"]+".json")
        if path.exists():
            try:
                data=json.loads(path.read_text(encoding="utf-8"))
                if data["schema"]==SCHEMA and data["track"]==t:
                    results[(t["country"],t["id"])]=data
            except (ValueError,KeyError):pass
    count=min(max(0,limit),300)
    for i,t in enumerate(tracks[:count],1):
        key=t["country"],t["id"]
        if key in results:continue
        r=process(t,bases,health,timeout,delay,max_queries=max_queries,cached=old.get(key),fetcher=fetcher)
        results[key]=r
        atomic(folder/"checkpoints"/(key[0]+"_"+key[1]+".json"),r)
        stats=result_stats(results)
        atomic(folder/"progress.json",stats)
        print("ODIFY_V4_300_PROGRESS "+json.dumps({
          "done":len(results),"target":300,"country":t["country"],
          "last_title":t["title"][:45],
          "v4_eligible":stats["v4_eligible_songs"],
          "v3_initial_eligible":stats["first_query_v3_eligible_songs"],
          "fresh_http_attempts":stats["fresh_http_attempts"]},ensure_ascii=False),flush=True)
    if not results:raise ValueError("No processed checkpoints")
    return export(folder,results)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--catalog",required=True,type=Path)
    p.add_argument("--out",required=True,type=Path)
    p.add_argument("--cached-v2",type=Path)
    p.add_argument("--limit",type=int,default=300)
    p.add_argument("--max-queries",type=int,default=2)
    p.add_argument("--timeout",type=float,default=6)
    p.add_argument("--delay",type=float,default=.8)
    p.add_argument("--base",action="append")
    a=p.parse_args()
    if not 0<=a.limit<=300 or not 1<=a.max_queries<=2 or not 0<=a.delay<=30 or not 1<=a.timeout<=30:
        p.error("invalid bounds")
    result=run(a.catalog,a.out,a.cached_v2,a.limit,a.max_queries,a.timeout,
               a.delay,tuple(a.base) if a.base else DEFAULT_BASES)
    print("ODIFY_V4_300_FINAL "+json.dumps(result,ensure_ascii=False),flush=True)
if __name__=="__main__":main()
