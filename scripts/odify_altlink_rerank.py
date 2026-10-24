#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline V3 ranking of existing Piped/Invidious 100-track JSON checkpoints.

No network calls. No audio downloads. Does not change MASTER, V4, or V2
checkpoints. Reference video IDs are held out from ranking to avoid leakage.

python scripts/odify_altlink_rerank.py \
  --input .local/odify_altlink_pilot_v2 \
  --output .local/odify_altlink_rerank_v3 \
  --master-references .local/MASTER.csv  # optional
"""
from __future__ import annotations
import argparse
import csv
from collections import Counter
import io
import json
from pathlib import Path
import re
import statistics
import unicodedata
from zipfile import ZipFile

from odify_link_consensus import alias, canonical, norm, version_issue

FIELDS = ("country","recording_id","artist","title","video_id","url",
          "video_title","channel","seconds","views","eligible","score",
          "artist_match","title_match","official_evidence","album_match",
          "duration_difference","reasons","selected","reference_id_match")
PROPOSAL_FIELDS = ("country","recording_id","artist","title","album",
                   "current_verified_url","candidate_youtube_url","score",
                   "state","reason","verified","action")

def words(v):
    return unicodedata.normalize("NFKC",str(v or "")).casefold().strip()

def video_id(url):
    good=canonical(str(url or ""))
    if good:return good[-11:]
    return ""

def clean_title(v):
    """Remove only proven display wrappers; never strip variant or remix terms."""
    t=words(v)
    t=re.sub(r"\s*[\[(]\s*(?:official\s+)?(?:audio|music\s+video|lyric\s+video|lyrics?|visuali[sz]er)\s*[\])]", "", t, flags=re.I)
    t=re.sub(r"\s*(?:\|\s*(?:official\s+)?(?:audio|music\s+video|lyrics?)\s*)$", "", t, flags=re.I)
    return t.strip()

def title_match(master,candidate):
    want=norm(clean_title(master))
    actual=norm(clean_title(candidate))
    if not want or not actual:return "none"
    if want==actual:return "exact"
    # Song title must be a whole segment (not "RUN BTS" for "RUN").
    chunks=[x.strip() for x in re.split(r"\s+[-–—|:]\s+",clean_title(candidate))]
    if any(norm(x)==want for x in chunks):return "segment"
    # A full title embedded with an official/lyric marker is still acceptable.
    if len(want)>=6 and actual.endswith(want):
        prefix=actual[:-len(want)]
        if 2<=len(prefix)<=40:return "suffix"
    return "none"

def artist_evidence(track, candidate):
    name=track.get("artist","")
    aliases=set(alias(name))
    for bit in re.split(r"\s*[&＆+,]\s*|\s+(?:feat\.?|ft\.?|featuring|with)\s+",name,flags=re.I):
        if len(norm(bit))>=2:aliases.add(norm(bit))
    for key in ("artist_aliases","아티스트 별칭"):
        for bit in re.split(r"[,;|]",str(track.get(key,"") or "")):
            if len(norm(bit))>=2:aliases.add(norm(bit))
    channel=norm(candidate.get("channel",""))
    vid_title=norm(candidate.get("title",""))
    # A channel title alone is not proof of authenticity; this is metadata evidence.
    return bool(channel and any(len(a)>=2 and
             (a in channel or a in vid_title) for a in aliases))

def version_flags(value):
    raw=words(value)
    flags=set()
    for flag,regex in (
      ("clean",r"\bclean\s*(?:ver(?:sion)?\.?)?\b|클린\s*버전"),
      ("explicit",r"\bexplicit\s*(?:ver(?:sion)?\.?)?\b|19금"),
      ("instrumental",r"\binstrumental\b|\boff\s*vocal\b|\bkaraoke\b|가라오케|カラオケ|반주"),
      ("preview",r"\bpreview\b|\bteaser\b|\bsnippet\b|미리듣기|ティーザー"),
      ("slowed",r"\bslowed\b|\bsped[\s-]?up\b|\bnightcore\b"),
    ):
        if re.search(regex,raw,re.I):flags.add(flag)
    return flags

def rank_candidate(track,c):
    reasons=[]
    title=title_match(track["title"],c["title"])
    artist=artist_evidence(track,c)
    if title=="none":return dict(eligible=False,score=0,artist_match=artist,
        title_match=title,official_evidence=False,album_match=False,
        duration_difference=None,reasons="title_mismatch")
    master={"아티스트":track["artist"],"곡명":track["title"],
            "음원 버전":track.get("version","")}
    issue=version_issue(master,c)
    # A collaborator not listed on a single artist channel is uncertain, not
    # proof of a different version. It can only be proposed for manual review.
    if issue and not issue.startswith("피처링 미확인"):
        return dict(eligible=False,score=0,artist_match=artist,title_match=title,
          official_evidence=False,album_match=False,duration_difference=None,
          reasons="version_rejected: "+issue)
    expect=version_flags(track["title"]+" "+str(track.get("version","")))
    got=version_flags(c["title"]+" "+c.get("description","")[:120])
    if "preview" in got or ("clean" in expect and "explicit" in got) or \
       ("explicit" in expect and "clean" in got):
        return dict(eligible=False,score=0,artist_match=artist,title_match=title,
          official_evidence=False,album_match=False,duration_difference=None,
          reasons="wrong_version_or_preview")
    if ("clean" in expect and "clean" not in got) or \
       ("explicit" in expect and "explicit" not in got):
        reasons.append("requested_version_unconfirmed")
    if issue:reasons.append("collaborator_unconfirmed")
    seconds=int(c.get("seconds") or 0)
    if seconds<60 or seconds>600:
        return dict(eligible=False,score=0,artist_match=artist,title_match=title,
          official_evidence=False,album_match=False,duration_difference=None,
          reasons="duration_out_of_range")
    channel=words(c.get("channel",""))
    label=words(c.get("title",""))
    desc=words(c.get("description",""))
    official=bool(re.search(r"(?:^| )- topic$|official",channel,re.I) or
                  re.search(r"official\s*(?:audio|music\s*video)",label,re.I) or
                  "provided to youtube by" in desc)
    album=norm(track.get("album",""))
    album_match=bool(album and len(album)>=3 and album in norm(desc))
    known_seconds=int(track.get("reference_seconds") or 0)
    difference=abs(seconds-known_seconds) if known_seconds>0 else None
    if difference is not None and difference>30:reasons.append("duration_conflict")
    if not artist:reasons.append("artist_unconfirmed")
    if not official:reasons.append("official_source_unconfirmed")
    if not album_match:reasons.append("album_unconfirmed")
    if difference is None:reasons.append("duration_unconfirmed")
    if title!="exact":reasons.append("title_not_exact")
    score=30+(25 if title=="exact" else 12)+(19 if artist else 0)+ \
          (12 if official else 0)+(8 if album_match else 0)+ \
          (6 if difference is not None and difference<=7 else 0)
    if difference is not None and difference>30:score-=35
    if "requested_version_unconfirmed" in reasons:score-=24
    if "collaborator_unconfirmed" in reasons:score-=12
    # Do not mark a proposal "verified" based on YouTube metadata.
    return dict(eligible=True,score=max(0,min(99,score)),artist_match=artist,
        title_match=title,official_evidence=official,album_match=album_match,
        duration_difference=difference,reasons="; ".join(reasons) or "metadata_only")

def load_master(path):
    """Only exact artist-title keys. Reject ambiguous duplicates and bad links."""
    if not path:return {}
    with Path(path).open(encoding="utf-8-sig",newline="") as fp:
        records=list(csv.DictReader(fp))
    if not records:return {}
    keys={}
    for row in records:
        artist=row.get("아티스트","") or row.get("artist","")
        title=row.get("곡명","") or row.get("title","")
        key=(norm(artist),norm(title))
        if not all(key):continue
        url=canonical(row.get("YouTube 오디오 URL","") or row.get("reference_url",""))
        status=row.get("링크검증","")
        details={
          "album":row.get("앨범","") or row.get("album",""),
          "version":row.get("음원 버전","") or row.get("version",""),
          "artist_aliases":row.get("아티스트 별칭","") or row.get("artist_aliases",""),
          "reference_seconds":row.get("재생시간(초)","") or row.get("reference_seconds",""),
          "reference_url":url if status=="검증완료" else "",
          "existing_url":url}
        if key in keys and keys[key]!=details:
            keys[key]=None # Do not choose arbitrarily among versions/releases
        elif key not in keys:keys[key]=details
    return {k:v for k,v in keys.items() if v is not None}

def read_checkpoints(path):
    path=Path(path)
    if path.is_file() and path.suffix.lower()==".zip":
        with ZipFile(path) as z:
            names=sorted(n for n in z.namelist() if
                         re.search(r"(?:^|/)checkpoints/[^/]+\.json$",n))
            for name in names:
                if z.getinfo(name).file_size>250000:raise ValueError("checkpoint oversized")
                yield json.loads(z.read(name).decode("utf-8"))
    else:
        folder=path/"checkpoints" if (path/"checkpoints").is_dir() else path
        for file in sorted(folder.glob("*.json")):
            if file.stat().st_size>250000:raise ValueError("checkpoint oversized")
            yield json.loads(file.read_text(encoding="utf-8"))

def ranking(track,providers):
    merged={}
    for p in ("piped","invidious"):
        for c in providers.get(p,{}).get("candidates",[]):
            id=video_id(c.get("url",""))
            if not id:continue
            cand={**c,"url":"https://www.youtube.com/watch?v="+id}
            previous=merged.get(id)
            if previous is None or (bool(cand.get("description")),
               int(cand.get("views") or 0)) > \
                (bool(previous.get("description")),int(previous.get("views") or 0)):
                merged[id]=cand
    ranked=[]
    for id,c in merged.items():
        grade=rank_candidate(track,c)
        ranked.append({**c,"video_id":id,**grade})
    ranked.sort(key=lambda v:(v["eligible"],v["score"],
                 v["title_match"]=="exact",v["official_evidence"],
                 v["artist_match"],v["video_id"]),reverse=True)
    best=ranked[0] if ranked and ranked[0]["eligible"] else None
    if not best:return ranked,None,"no_eligible_candidate"
    if not best["artist_match"]:state="artist_review"
    elif "requested_version_unconfirmed" in best["reasons"]:state="version_review"
    elif best["score"]>=86 and best["official_evidence"] and \
         best["album_match"] and best["duration_difference"] is not None and \
         best["duration_difference"]<=7 and best["title_match"]=="exact":
        state="strong_metadata_review"
    else:state="manual_review"
    # Ambiguous top scores should never be silently promoted.
    if len(ranked)>1 and ranked[1]["eligible"] and \
       best["score"]-ranked[1]["score"]<=5:
        state="ambiguous_review"
    return ranked,best,state

def save_csv(path,fields,rows):
    with path.open("w",encoding="utf-8-sig",newline="") as f:
        w=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore")
        w.writeheader();w.writerows(rows)

def run(source,output,master_references=None):
    output=Path(output)
    output.mkdir(parents=True,exist_ok=True)
    references=load_master(master_references)
    audited=[];proposals=[];baselines=[];counts=Counter()
    matched_refs=0;in_candidates=0;same_best=0;refs_without_pick=0
    for rec in read_checkpoints(source):
        t=rec["track"]
        k=(t["country"],t["id"])
        if any(x["country"]==k[0] and x["recording_id"]==k[1]
               for x in proposals):raise ValueError("duplicate checkpoint "+str(k))
        details=references.get((norm(t["artist"]),norm(t["title"])),{})
        enriched={**t,**details}
        if not details:enriched["reference_url"]=t.get("reference_url","")
        ranked,best,state=ranking(enriched,rec["providers"])
        reference=video_id(enriched.get("reference_url",""))
        known={x["video_id"] for x in ranked}
        if reference:
            matched_refs+=1
            in_candidates+=int(reference in known)
            same_best+=int(bool(best) and best["video_id"]==reference)
            refs_without_pick+=int(best is None)
        previous=rec.get("decision",{})
        baselines.append({"country":t["country"],"id":t["id"],
               "old_selected_id":video_id(previous.get("url","")),
               "new_selected_id":best["video_id"] if best else "",
               "old_status":previous.get("status",""),"new_state":state,
               "known_reference_id":reference,
               "old_reference_match":bool(reference and
                    video_id(previous.get("url",""))==reference) if reference else None,
               "new_reference_match":bool(reference and best and
                    best["video_id"]==reference) if reference else None})
        for i,c in enumerate(ranked):
            audited.append({
              "country":t["country"],"recording_id":t["id"],
              "artist":t["artist"],"title":t["title"],"video_id":c["video_id"],
              "url":c["url"],"video_title":c["title"],"channel":c.get("channel",""),
              "seconds":c["seconds"],"views":c.get("views",0),
              "eligible":int(c["eligible"]),"score":c["score"],
              "artist_match":int(c["artist_match"]),"title_match":c["title_match"],
              "official_evidence":int(c["official_evidence"]),
              "album_match":int(c["album_match"]),
              "duration_difference":c["duration_difference"],
              "reasons":c["reasons"],
              "selected":int(i==0 and best is not None),
              "reference_id_match":int(bool(reference) and
                                         c["video_id"]==reference)})
        counts[state]+=1
        existing=enriched.get("existing_url","") or enriched.get("reference_url","")
        proposals.append({
          "country":t["country"],"recording_id":t["id"],"artist":t["artist"],
          "title":t["title"],"album":enriched.get("album",""),
          "current_verified_url":reference and
             "https://www.youtube.com/watch?v="+reference or "",
          "candidate_youtube_url":best["url"] if best else "",
          "score":best["score"] if best else 0,
          "state":state,"reason":best["reasons"] if best else "no safe candidate",
          "verified":"NO",
          "action":"KEEP_EXISTING" if existing else "MANUAL_REVIEW_ONLY"})
    if not proposals:raise ValueError("no checkpoints found")
    # Even unverified existing links must remain untouched.
    save_csv(output/"review_only_proposals_v3.csv",PROPOSAL_FIELDS,proposals)
    save_csv(output/"ranked_candidates_v3.csv",FIELDS,audited)
    save_csv(output/"baseline_comparison_v3.csv",
        ("country","id","old_selected_id","new_selected_id","old_status",
         "new_state","known_reference_id","old_reference_match",
         "new_reference_match"),baselines)
    summary={
      "tracks":len(proposals),"video_candidates":len(audited),
      "eligible_songs":sum(x["state"]!="no_eligible_candidate" for x in proposals),
      "states":dict(sorted(counts.items())),
      "known_verified_reference_tracks":matched_refs,
      "reference_video_in_candidates":in_candidates,
      "reranker_exact_reference_match":same_best,
      "baseline_exact_reference_match":sum(x["old_reference_match"] is True for x in baselines),
      "reference_tracks_without_pick":refs_without_pick,
      "reference_recall":round(in_candidates/matched_refs,4) if matched_refs else None,
      "reranker_exact_id_rate":round(same_best/matched_refs,4) if matched_refs else None,
      "baseline_exact_id_rate":round(sum(x["old_reference_match"] is True for x in baselines)/matched_refs,4) if matched_refs else None,
      "caveat":"Exact video ID agreement is not listening-verified matching accuracy. Ranking never uses reference ID. No MASTER writes or auto-approved URLs."
    }
    (output/"rerank_summary_v3.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    print("ODIFY_RERANK_V3 "+json.dumps(summary,ensure_ascii=False),flush=True)
    return summary

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--master-references",type=Path)
    a=p.parse_args()
    run(a.input,a.output,a.master_references)

if __name__=="__main__":main()
