#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odify V4.1: diagnose 19 no-match and 39 ambiguous cached V4 tracks.

Inputs are the immutable V4 300-song safety-audit artifacts. No API calls,
no MASTER writes, no automatic URL approval. Proposed JP/KR translations and
aliases stay *unverified review hints* until a trusted source approves them.
"""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import csv
import io
import json
from pathlib import Path
import re
import sys
import unicodedata
from zipfile import ZipFile

sys.path.insert(0,str(Path(__file__).resolve().parent))
import odify_altlink_v4_300 as v4
import odify_altlink_rerank as v3
from odify_link_consensus import norm

# Human-readable alternate spelling proposals; NOT verified identity records.
# Never use these to auto-approve candidate IDs or override prior decisions.
TITLE_HINTS={
 ("kr","지독하게"):"Severely",
 ("kr","너 아님 안돼"):"Gotta Be You",
 ("jp","サンタ・テル・ミー"):"Santa Tell Me",
 ("jp","マップス"):"Maps",
 ("jp","ブルー・カラー・ジェーン"):"Blue Collar Jane",
 ("jp","ダズント・ミーン・エニシング〜あなたがいないなら"):"Doesn't Mean Anything",
 ("jp","ナイン・イン・ジ・アフターヌーン〜恋するタイミング"):"Nine in the Afternoon",
 ("gl","해금"):"Haegeum",
}
ARTIST_HINTS={
 "아이유":"IU","리센느":"RESCENE","양홍원":"Young B",
 "머무르":"MEOMURU","ヨルシカ":"Yorushika",
 "福山雅治":"Masaharu Fukuyama","米津玄師":"Kenshi Yonezu",
 "宇多田ヒカル":"Hikaru Utada","Official髭男dism":"Official Hige Dandism",
 "アリアナ・グランデ":"Ariana Grande","マルーン5":"Maroon 5",
 "アリシア・キーズ":"Alicia Keys","パニック!アット・ザ・ディスコ":"Panic! At The Disco",
 "パスピエ":"PASSEPIED","애프터스쿨":"After School",
}
# Existing test discovery must remain safely conservative.
BLOCKED=re.compile(
 r"\b(?:unofficial|fanmade|reaction|instrumental|karaoke|cover|remix|"
 r"sped[\s-]?up|slowed|nightcore|teaser|preview|mashup|rehearsal|"
 r"dance\s*practice|trailer)\b|カバー|歌ってみた|"
 r"노래방|커버|ティーザー", re.I)
BAD_LENGTH=(60,600)
AUDIT_FIELDS=("country","id","artist","title","v4_state","candidates",
 "v4_eligible","recoverable_review_candidates","best_review_url","best_review_title",
 "best_review_channel","diagnostic","approved","master_write")
RECOVER_FIELDS=("country","id","artist","title","candidate_url","video_title",
 "channel","seconds","match_type","artist_confirmed_in_original_metadata",
 "artist_hint_proposed_only","official_claim_only","version_safety","review_score",
 "review_required","auto_apply")
AMBIG_FIELDS=("country","id","artist","title","baseline_selected_url",
 "top_candidate_url","top_candidate_title","runner_up_url","runner_up_title",
 "distinct_video_ids","independent_channels","same_channel_similar_uploads",
 "review_category","master_write")
ALIAS_FIELDS=("region","source_artist","candidate_alias","status","provenance")

def normalized(s):
 return norm(unicodedata.normalize("NFKC",str(s or "")))

def load_rows(path,name):
 path=Path(path)
 if path.is_file() and path.suffix.lower()==".zip":
  with ZipFile(path) as z:
   matches=[n for n in z.namelist() if n==name or n.endswith("/"+name)]
   if len(matches)!=1:raise ValueError("Expected one "+name)
   return list(csv.DictReader(io.StringIO(z.read(matches[0]).decode("utf-8-sig"))))
 with (path/name).open(encoding="utf-8-sig",newline="") as f:
  return list(csv.DictReader(f))

def write_rows(folder,name,fields,rows):
 file=Path(folder)/name
 temp=file.with_name(file.name+".tmp")
 with temp.open("w",encoding="utf-8-sig",newline="") as f:
  w=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore");w.writeheader();w.writerows(rows)
 temp.replace(file)

def base_track(row):
 return {"country":row["country"],"id":row["id"],"artist":row["artist"],"title":row["title"]}

def title_modes(track,headline):
 """Propose review-only matches without replacing original title or artist."""
 required=normalized(track["title"])
 title=normalized(headline)
 sources=[("title_substring",required)]
 nofeat=re.sub(r"\s*\((?:feat\.?|ft\.?|with|featuring)[^)]*\)","",track["title"],flags=re.I)
 if normalized(nofeat)!=required:
  sources.append(("featuring_variant_pending",normalized(nofeat)))
 alt=TITLE_HINTS.get((track["country"],track["title"]))
 if alt:sources.append(("translated_title_pending",normalized(alt)))
 for label,needle in sources:
  # A long whole song name embedded inside "Artist - Song (extra metadata)"
  # can be suggested, but never accepted as a verified original.
  if len(needle)>=5 and needle in title:
   return label
 return ""

def review_candidate(track,row):
 original={"url":row["url"],"title":row["video_title"],"channel":row["channel"],
           "seconds":int(row["seconds"] or 0),"views":int(row["views"] or 0),
           "description":""}
 match_type=title_modes(track,original["title"])
 if not match_type:return None
 if not BAD_LENGTH[0]<=original["seconds"]<=BAD_LENGTH[1]:return None
 if BLOCKED.search(original["title"]):return None
 if re.search(r"\bunofficial\b",original["channel"],re.I):return None
 master={"아티스트":track["artist"],"곡명":track["title"],"음원 버전":""}
 issue=v3.version_issue(master,original)
 if issue and not issue.startswith("피처링 미확인"):return None
 # Requested Live/Remaster/Clean variants must be confirmed by the candidate.
 expect=v3.version_flags(track["title"])
 found=v3.version_flags(original["title"])
 if ("clean" in expect or "explicit" in expect) and not expect.issubset(found):
  return None
 if re.search(r"\blive\b|라이브",track["title"],re.I) and not re.search(
    r"\blive\b|라이브|【LIVE】",original["title"],re.I):
  return None
 artist=v3.artist_evidence(track,original)
 proposed_alias=ARTIST_HINTS.get(track["artist"],"")
 alias_unapproved=bool(proposed_alias and normalized(proposed_alias) in normalized(
    original["channel"]+" "+original["title"]))
 official=bool(re.search(r"-\s*topic\b|official\s*(?:audio|music|video)|\bofficial\b",
                  original["channel"]+" "+original["title"],re.I))
 score=15+(18 if match_type=="title_substring" else 9)
 score+=18 if artist else 0
 score+=7 if official else 0
 score+=4 if alias_unapproved else 0
 if issue:score-=8
 if re.search(r"\blyrics?\b|가사|歌詞|歌割り",original["title"],re.I):score-=9
 if re.search(r"\b(?:pv|opening|mv)\b|【MV】|fullmv|animation",original["title"],re.I):score-=4
 # Never call this a precision probability or proof of official distribution.
 return {"country":track["country"],"id":track["id"],"artist":track["artist"],
   "title":track["title"],"candidate_url":original["url"],"video_title":original["title"],
   "channel":original["channel"],"seconds":original["seconds"],"match_type":match_type,
   "artist_confirmed_in_original_metadata":"YES" if artist else "NO",
   "artist_hint_proposed_only":"YES" if alias_unapproved else "NO",
   "official_claim_only":"YES" if official else "NO",
   "version_safety":"collaborator_review" if issue else "filtered",
   "review_score":max(0,score),"review_required":"YES","auto_apply":"NO"}

def classify_ambiguous(row,candidates):
 valid=[x for x in candidates if x["v4_eligible"]=="1"]
 valid.sort(key=lambda x:(int(x["v4_score"]),x["url"]),reverse=True)
 top=valid[0] if valid else {}
 second=valid[1] if len(valid)>1 else {}
 unique={x["url"] for x in valid}
 channels={normalized(x["channel"]) for x in valid if normalized(x["channel"])}
 samechannel=[]
 for i,x in enumerate(valid):
  for y in valid[i+1:]:
   if normalized(x["channel"])==normalized(y["channel"]) and \
      normalized(x["video_title"])==normalized(y["video_title"]) and \
      abs(int(x["seconds"] or 0)-int(y["seconds"] or 0))<=2:
    samechannel.extend((x["url"],y["url"]))
 evidence="same_uploader_duplicates" if samechannel else "distinct_candidates_unresolved"
 if any("version" in (x["v4_reasons"] or "") for x in valid[:4]):
  evidence="version_sensitive_unresolved"
 return {"country":row["country"],"id":row["id"],"artist":row["artist"],
 "title":row["title"],"baseline_selected_url":row["v4_choice"],
 "top_candidate_url":top.get("url",""),
 "top_candidate_title":top.get("video_title",""),
 "runner_up_url":second.get("url",""),
 "runner_up_title":second.get("video_title",""),
 "distinct_video_ids":len(unique),"independent_channels":len(channels),
 "same_channel_similar_uploads":len(set(samechannel)),
 "review_category":evidence,"master_write":"NO"}

def run(source,output):
 audit=load_rows(source,"audit_300_v4.csv")
 candidates=load_rows(source,"candidates_300_v4.csv")
 if len(audit)!=300 or len({(x["country"],x["id"]) for x in audit})!=300:
  raise ValueError("Need 300 unique archived audit rows, no silent partial runs")
 by_key=defaultdict(list)
 for c in candidates:by_key[(c["country"],c["id"])].append(c)
 if not candidates:raise ValueError("Empty candidate archive")
 output=Path(output);output.mkdir(parents=True,exist_ok=True)
 diagnostic=[];recoveries=[];ambiguous=[]
 for row in audit:
  track=base_track(row)
  pool=by_key[(row["country"],row["id"])]
  approved=[]
  if row["v4_state"]=="no_eligible_candidate":
   approved=[x for x in (review_candidate(track,item) for item in pool) if x]
   approved.sort(key=lambda x:(-x["review_score"],x["candidate_url"]))
   recoveries.extend(approved)
  if row["v4_state"]=="ambiguous_review":
   ambiguous.append(classify_ambiguous(row,pool))
  main=approved[0] if approved else {}
  diagnostic.append({"country":row["country"],"id":row["id"],"artist":row["artist"],
    "title":row["title"],"v4_state":row["v4_state"],
    "candidates":len(pool),"v4_eligible":row["expanded_v4_eligible"],
    "recoverable_review_candidates":len(approved),
    "best_review_url":main.get("candidate_url",""),
    "best_review_title":main.get("video_title",""),
    "best_review_channel":main.get("channel",""),
    "diagnostic":("localized_or_embedded_title_review" if approved else
      "search_or_metadata_gap") if row["v4_state"]=="no_eligible_candidate" else
      ("conflicting_uploads_review" if row["v4_state"]=="ambiguous_review" else "unchanged"),
    "approved":"NO","master_write":"NO"})
 alias_rows=[{"region":"kr/jp","source_artist":k,"candidate_alias":v,
               "status":"PENDING_HUMAN_REVIEW","provenance":"seeded_hypothesis_not_verified"}
              for k,v in sorted(ARTIST_HINTS.items())]
 write_rows(output,"diagnostics_300.csv",AUDIT_FIELDS,diagnostic)
 write_rows(output,"new_title_review_candidates.csv",RECOVER_FIELDS,recoveries)
 write_rows(output,"ambiguity_review_39.csv",AMBIG_FIELDS,ambiguous)
 write_rows(output,"artist_aliases_pending_review.csv",ALIAS_FIELDS,alias_rows)
 write_rows(output,"master_proposals_no_write.csv",
   ("country","id","artist","title","existing_v4_url","action","verified"),
   ({"country":r["country"],"id":r["id"],"artist":r["artist"],
     "title":r["title"],"existing_v4_url":r["v4_choice"],
     "action":"NO_WRITE","verified":"NO"} for r in audit))
 no=[x for x in diagnostic if x["v4_state"]=="no_eligible_candidate"]
 summary={
   "processed":len(audit),
   "baseline_no_eligible_tracks":len(no),
   "baseline_ambiguous_tracks":len(ambiguous),
   "no_eligible_tracks_with_safe_new_review_candidates":sum(
      x["recoverable_review_candidates"]>0 for x in no),
   "review_candidate_urls_for_no_eligible":len(recoveries),
   "unresolved_no_eligible_tracks":sum(
      x["recoverable_review_candidates"]==0 for x in no),
   "ambiguous_same_uploader_duplicate_groups":sum(
      x["same_channel_similar_uploads"]>0 for x in ambiguous),
   "pending_alias_suggestions":len(alias_rows),
   "newly_verified_songs":0,"existing_v4_selection_changed":0,
   "master_modified":False,"network_calls":0,
   "caveats":"Review candidates and translation/artist alias hints are unverified. All initial V4 selections are preserved. No automated accuracy improvement claim."
 }
 (output/"summary_v41.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
 print("ODIFY_V41_DIAGNOSTICS "+json.dumps(summary,ensure_ascii=False),flush=True)
 return summary

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument("--input",required=True,type=Path)
 p.add_argument("--output",required=True,type=Path)
 args=p.parse_args()
 run(args.input,args.output)
if __name__=="__main__":main()
