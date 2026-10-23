#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Resumable multi-upload duration-consensus linker. No audio download.

Example:
  python scripts/odify_link_consensus.py --master MASTER.csv \
    --candidates 링크검색_v2_후보.csv \
    --metadata 링크선택_v2_메타데이터.json --apply --output-dir review

Also accepts the review CSV from scripts/odify_link_candidates.py.
Without --apply, only rankings/patch suggestions are exported. Even with
--apply, existing nonblank URLs remain untouched, and new entries are
marked 재검토, NOT 검증완료. Never rewrites Google Sheets directly.
"""
from __future__ import annotations
import argparse
import csv
import json
import math
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlparse, parse_qs

VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
VERSION = (
    (r"\b(?:english|eng|en)\s*(?:ver\.?|version|edit)\b|영어\s*버전", "영어판"),
    (r"\b(?:japanese|jpn|jp)\s*(?:ver\.?|version)\b|일본어\s*버전", "일본어판"),
    (r"\b(?:korean|kor)\s*(?:ver\.?|version)\b|한국어\s*버전", "한국어판"),
    (r"\bremix\b|\brework\b|리믹스", "리믹스"),
    (r"\bsped[ -]?up\b|\bslowed\b|\bnightcore\b|\b432\s*hz\b", "변속"),
    (r"\bcover\b|\bkaraoke\b|\binstrumental\b|커버|カバー", "커버/반주"),
    (r"\b(?:live|concert|stage|fancam|dance practice|performance)\b|라이브|ライブ", "라이브"),
    (r"\b(?:demo|acoustic|piano)\b", "다른 편곡"),
    (r"\b(?:19\d{2}|20\d{2})\s*remaster(?:ed)?\b|\bremaster\b", "리마스터"),
)
UNSAFE = {t for _,t in VERSION}

def norm(value):
    value = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return "".join(ch for ch in value if ch.isalnum())

def alias(artist):
    main = re.split(r"\s+(?:feat\.?|ft\.?|featuring)\s+|\s+[&＆+]\s+|\s+(?:and|with)\s+|\s*,\s*",
                    artist or "", maxsplit=1, flags=re.I)[0]
    return list(dict.fromkeys(x for part in [main, re.sub(r"\([^)]*\)","",main)] +
                            re.findall(r"\(([^)]{2,})\)",main)
                              if (x := norm(part)) and len(x)>=2))

def canonical(url):
    parsed=urlparse((url or "").strip())
    if parsed.scheme!="https": return ""
    host=(parsed.hostname or "").lower()
    if host in ("youtube.com","www.youtube.com","music.youtube.com","m.youtube.com"):
        video=parse_qs(parsed.query).get("v",[""])[0]
    elif host=="youtu.be":
        video=parsed.path.strip("/").split("/")[0]
    else:
        return ""
    return "https://www.youtube.com/watch?v="+video if VIDEO_ID.fullmatch(video) else ""

def duration(raw):
    try:
        if isinstance(raw,str) and ":" in raw:
            vals=[int(x) for x in raw.split(":")]
            return sum(x*60**i for i,x in enumerate(reversed(vals))) if len(vals)<=3 else 0
        v=float(raw)
        return round(v) if math.isfinite(v) and 0<v<=21600 else 0
    except (TypeError,ValueError):
        return 0

def read_csv(path):
    with Path(path).open(newline="",encoding="utf-8-sig") as fp:
        reader=csv.DictReader(fp)
        return reader.fieldnames, list(reader)

def version_issue(master,candidate):
    expected=(master.get("곡명","")+" "+master.get("음원 버전","")).casefold()
    title=(candidate["title"] or "").casefold()
    expected_tags={name for p,name in VERSION if re.search(p,expected,re.I)}
    found={name for p,name in VERSION if re.search(p,title,re.I)}
    bad=sorted((found&UNSAFE)-expected_tags)
    if bad: return "별도 버전: "+", ".join(bad)
    a=re.findall(r"\b(?:19|20)\d{2}\s*remaster",expected)
    b=re.findall(r"\b(?:19|20)\d{2}\s*remaster",title)
    if a and b and norm(a[0])!=norm(b[0]):return "리마스터 연도 불일치"
    parts=re.split(r"\s+[&＆+]\s+|\s*,\s*|\s+(?:and|with|feat\.?|ft\.?)\s+",
                   master.get("아티스트",""),flags=re.I)
    hay=norm(title+" "+(candidate.get("description") or "")[:320])
    for partner in parts[1:]:
        if len(norm(partner))>=4 and norm(partner) not in hay:
            return "피처링 미확인: "+partner
    return ""

def eligible(master,c):
    issue=version_issue(master,c)
    if issue:return False,issue
    track=norm(master.get("곡명",""))
    title=norm(c["title"])
    if not track or track not in title:
        shorter=norm(re.split(r"\s*[\(\[]",master.get("곡명",""))[0])
        if len(shorter)<5 or shorter not in title:return False,"곡명 불일치"
    if not any(x in title+norm(c["channel"]) for x in alias(master.get("아티스트",""))):
        return False,"아티스트 불일치"
    if not 60<=c["seconds"]<=600:return False,"영상 길이 비정상"
    return True,""

def metadata_json(path):
    if not path:return {}
    raw=json.loads(Path(path).read_text(encoding="utf-8-sig"))
    items=raw.get("selections",[]) if isinstance(raw,dict) else raw
    idx={}
    for item in items:
        meta=item.get("metadata") or {}
        url=canonical(item.get("url","") or meta.get("url",""))
        if url:
            idx[url]={"description":meta.get("description") or "",
                      "views":int(meta.get("view_count") or 0),
                      "seconds":duration(meta.get("duration"))}
    return idx

def load_candidates(path,detail):
    head,rows=read_csv(path)
    v2="후보 URL" in head
    if not v2 and "후보 YouTube URL" not in head:
        raise ValueError("V2 또는 YouTube API 후보 CSV만 지원합니다")
    grouped=defaultdict(dict)
    for row in rows:
        track=row.get("Track ID","").strip()
        url=canonical(row.get("후보 URL","") if v2 else row.get("후보 YouTube URL",""))
        if not track.startswith("ODF") or not url:continue
        extra=detail.get(url,{})
        rec={"url":url,
             "title":(row.get("후보 곡명","") if v2 else row.get("영상 제목","")) or "",
             "channel":(row.get("후보 아티스트","") if v2 else row.get("채널","")) or "",
             "seconds":duration(row.get("후보 재생시간","") if v2 else row.get("재생시간(초)","")) or extra.get("seconds",0),
             "description":extra.get("description",""),
             "views":int(row.get("조회수") or extra.get("views",0))}
        grouped[track][url]=rec
    return grouped

def decide(master,candidates,tolerance=2,min_sources=3):
    classified=[]
    for c in candidates.values():
        ok,reason=eligible(master,c)
        classified.append({**c,"ok":ok,"reason":reason})
    valid=[c for c in classified if c["ok"]]
    if not valid:return classified,{"status":"후보부족","reason":"일치 후보 없음","confidence":0}
    def count_unique(items):
        return len({norm(x["channel"]) or x["url"] for x in items})
    clusters=sorted(
        [(count_unique([c for c in valid if abs(c["seconds"]-center)<=tolerance]),
          center,[c for c in valid if abs(c["seconds"]-center)<=tolerance])
         for center in sorted({c["seconds"] for c in valid})],
        key=lambda x:(x[0],len(x[2])) ,reverse=True)
    count,center,cluster=clusters[0]
    competitor=max([n for n,d,_ in clusters if abs(d-center)>2*tolerance] or [0])
    total=count_unique(valid)
    def preference(c):
        title=c["title"]
        audio=bool(re.search(r"official\s*audio|topic|audio|음원|音源",title,re.I))
        distributor="provided to youtube by" in c["description"].lower()
        return (audio,distributor,math.log10(1+c["views"]),-abs(c["seconds"]-center),c["url"])
    selected=sorted(cluster,key=preference,reverse=True)[0]
    score=min(99,round(50+7*min(count,5)+12*count/total+
                       min(5,math.log10(1+selected["views"])),1))
    agree=(count>=min_sources and count>=competitor+2 and count/total>=.45 and score>=78)
    state="고신뢰" if agree else "검토필요"
    reason=f"독립 채널 {count}/{total}, 길이 {center}±{tolerance}초, 다른 시간대 {competitor}개"
    for c in classified:
        c["in_cluster"]=c["ok"] and abs(c["seconds"]-center)<=tolerance
    return classified,{"status":state,"reason":reason,"selected":selected,
                       "seconds":center,"support":count,"competitor":competitor,"confidence":score}

def save(path,head,rows):
    with Path(path).open("w",newline="",encoding="utf-8-sig") as fp:
        writer=csv.DictWriter(fp,fieldnames=head,extrasaction="ignore")
        writer.writeheader();writer.writerows(rows)

def run(master,candidates,metadata,output_dir,apply=False,tolerance=2,min_sources=3):
    columns,rows=read_csv(master)
    if not {"Track ID","아티스트","곡명","YouTube 오디오 URL","링크검증"}.issubset(columns):
        raise ValueError("MASTER 필수 열이 없습니다")
    details=metadata_json(metadata)
    groups=load_candidates(candidates,details)
    directory=Path(output_dir);directory.mkdir(parents=True,exist_ok=True)
    audits=[];ranked=[];patch=[];counts=Counter()
    for row in rows:
        code=row.get("Track ID","")
        classified,choice=decide(row,groups.get(code,{}),tolerance,min_sources)
        best=choice.get("selected",{})
        for item in classified:
            ranked.append({"Track ID":code,"후보 URL":item["url"],
                           "영상 제목":item["title"],"채널":item["channel"],
                           "재생시간(초)":item["seconds"],
                           "적격":"Y" if item["ok"] else "N",
                           "배제 이유":item["reason"],
                           "합의시간대":"Y" if item.get("in_cluster") else "N",
                           "선택":"Y" if item["url"]==best.get("url") else "N"})
        current=canonical(row.get("YouTube 오디오 URL",""))
        status=row.get("링크검증","")
        if current and status=="검증완료":state="기존검증보호"
        elif current:state="기존링크보호"
        elif choice["status"]=="고신뢰":
            state="고신뢰제안" if not apply else "자동입력"
            if apply:
                row["YouTube 오디오 URL"]=best["url"]
                row["링크검증"]="재검토"
                if "링크 자동화 상태" in columns:
                    row["링크 자동화 상태"]="재생시간 합의로 자동입력(청취 미검증)"
                if "자동화 메모" in columns:
                    row["자동화 메모"]=choice["reason"]
                patch.append({"Track ID":code,"아티스트":row["아티스트"],
                              "곡명":row["곡명"],"추가할 URL":best["url"],
                              "링크검증":"재검토","근거":choice["reason"],
                              "신뢰점수":choice["confidence"]})
        else:state="후보검토" if best else "후보부족"
        counts[state]+=1
        audits.append({"Track ID":code,"아티스트":row.get("아티스트",""),
                       "곡명":row.get("곡명",""),"기존 URL":current,
                       "원래 링크검증":status,"제안 URL":best.get("url",""),
                       "결과":state,"합의초":choice.get("seconds",0),
                       "독립채널":choice.get("support",0),
                       "경쟁채널":choice.get("competitor",0),
                       "신뢰점수":choice["confidence"],"근거":choice["reason"]})
    save(directory/"Odify_MASTER_합의선택.csv",columns,rows)
    save(directory/"Odify_구글시트반영_패치.csv",
         ["Track ID","아티스트","곡명","추가할 URL","링크검증","근거","신뢰점수"],patch)
    save(directory/"Odify_508곡_선택결과.csv",
         ["Track ID","아티스트","곡명","기존 URL","원래 링크검증","제안 URL","결과",
          "합의초","독립채널","경쟁채널","신뢰점수","근거"],audits)
    save(directory/"Odify_후보별점검.csv",
         ["Track ID","후보 URL","영상 제목","채널","재생시간(초)","적격",
          "배제 이유","합의시간대","선택"],ranked)
    return {"tracks":len(rows),"candidate_rows":len(ranked),
            "filled":len(patch),"counts":dict(counts),"output":str(directory)}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--master",required=True,type=Path)
    p.add_argument("--candidates",required=True,type=Path)
    p.add_argument("--metadata",type=Path)
    p.add_argument("--output-dir",type=Path,default=Path("odify_review"))
    p.add_argument("--apply",action="store_true")
    p.add_argument("--tolerance",type=int,default=2)
    p.add_argument("--min-sources",type=int,default=3)
    a=p.parse_args()
    if not 0<=a.tolerance<=10 or not 2<=a.min_sources<=10:
        p.error("tolerance=0..10, min-sources=2..10")
    print(json.dumps(run(a.master,a.candidates,a.metadata,a.output_dir,
                         a.apply,a.tolerance,a.min_sources),ensure_ascii=False,indent=2))
if __name__=="__main__":
    main()
