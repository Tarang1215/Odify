#!/usr/bin/env python3
"""V4.5 offline, review-only risk triage for Odify's 1000 actual Piped candidates.
Do not equate source metadata or uploader names with verified recordings.
Does not change original SQLite, verified MASTER links, or playback URLs.
"""
import argparse,csv,json,re,sqlite3,unicodedata
from pathlib import Path
from collections import Counter

VARIANTS={
 "cover":r"\bcover\b|カバー|歌ってみた|커버",
 "instrumental":r"\binstrumental\b|\binst\b|カラオケ|노래방|반주",
 "remix":r"\bremix\b|リミックス|리믹스",
 "tempo_edit":r"\bslowed\b|\bsped[ -]?up\b|\bnightcore\b",
 "live":r"\blive\b|ライブ|라이브|공연실황",
}
LYRICS=r"\blyrics?\b|가사|歌詞|歌詞付き|가사영상"
def norm(s):
 return "".join(c for c in unicodedata.normalize("NFKC",s or "").casefold() if c.isalnum())

def flags_for(row,video,known_artists):
 if video is None:return ["selected_video_not_in_app_top5"]
 title=str(video.get("title") or "")
 channel=str(video.get("channel") or "")
 wanted=" ".join([row["title"],row.get("version","")])
 flags=[]
 for key,rx in VARIANTS.items():
  if re.search(rx,title,re.I) and not re.search(rx,wanted,re.I):
   flags.append("unrequested_"+key)
 expected=norm(row["artist"]);actual=norm(channel)
 if actual and expected and actual!=expected:
  if actual in known_artists:flags.append("different_known_artist_uploader")
  else:flags.append("uploader_identity_unconfirmed")
 if re.search(LYRICS,title,re.I) and (not actual or actual!=expected):
  flags.append("lyrics_from_unconfirmed_uploader")
 if video.get("seconds") is not None and int(video["seconds"])<120:
  flags.append("short_recording_check")
 return flags

def assess(db_path,v44_audit,out):
 out=Path(out);out.mkdir(parents=True,exist_ok=True)
 db=sqlite3.connect(Path(db_path).resolve().as_uri()+"?mode=ro",uri=True)
 db.row_factory=sqlite3.Row
 try:
  artists={norm(r[0]) for r in db.execute("SELECT name FROM artist")}
  selected={(r["recording_id"],r["url"]):dict(r) for r in db.execute(
   "SELECT recording_id,url,title,channel,seconds,score FROM source_candidate")}
  tracks={r["id"]:dict(r) for r in db.execute("SELECT id,title,version FROM recording")}
  with Path(v44_audit).open(encoding="utf-8-sig",newline="") as f:
   raw=list(csv.DictReader(f))
  if len(raw)!=1000 or len({r["id"] for r in raw})!=1000:raise ValueError("expected full 1000-row V4.4 audit")
  rows=[]
  for r in raw:
   if not r["new_url"]:continue
   track={**r,**tracks[r["id"]]}
   video=selected.get((r["id"],r["new_url"]))
   flags=flags_for(track,video,artists)
   if r["changed_choice"]=="YES":flags.append("selected_video_changed_v44")
   if r["new_state"] in ("ambiguous_review","artist_review"):
    flags.append("existing_ambiguous_or_artist_review")
   weights={"unrequested_cover":50,"unrequested_instrumental":50,
    "unrequested_remix":45,"unrequested_tempo_edit":40,"unrequested_live":30,
    "different_known_artist_uploader":40,
    "lyrics_from_unconfirmed_uploader":25,"uploader_identity_unconfirmed":8,
    "short_recording_check":5,"selected_video_not_in_app_top5":15,
    "selected_video_changed_v44":10,"existing_ambiguous_or_artist_review":10}
   rows.append({"region":r["country"],"recording_id":r["id"],
    "artist":r["artist"],"track":r["title"],"video_url":r["new_url"],
    "video_title":video["title"] if video else "","channel":video["channel"] if video else "",
    "flags":";".join(flags),"risk_priority":sum(weights.get(f,0) for f in flags),
    "original_state":r["previous_state"],"new_state":r["new_state"],
    "selection_changed":r["changed_choice"],
    "manual_recording_identity":"NOT_VERIFIED",
    "extractor_availability":"NOT_TESTED","existing_master_write":"NO"})
  rows.sort(key=lambda x:(-x["risk_priority"],x["region"],x["artist"],x["track"]))
  with (out/"review_risk_1000.csv").open("w",encoding="utf-8-sig",newline="") as f:
   w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
  counter=Counter(x for r in rows for x in r["flags"].split(";") if x)
  summary={"selected_review_candidates":len(rows),
   "changed_priority_1000":sum(r["selection_changed"]=="YES" for r in rows),
   "flag_counts":dict(counter),"automatic_url_approvals":0,"master_writes":0,
   "caveat":"Heuristic flags mark review priority, not a wrong song or proof of source authenticity."}
  (out/"risk_summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
  print("ODIFY_V45_REVIEW "+json.dumps(summary,ensure_ascii=False),flush=True)
  return summary
 finally:db.close()

if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("--database",required=True);p.add_argument("--audit",required=True);p.add_argument("--out",required=True)
 x=p.parse_args();assess(x.database,x.audit,x.out)
