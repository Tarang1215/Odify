import csv
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v4_300 as v4
import odify_altlink_pilot as v2

URL="https://www.youtube.com/watch?v=abcdefghijk"
def make_candidate(title="Song KR0",artist="Singer KR0",channel=None,url=URL):
    return {"url":url,"title":title,"channel":channel or artist+" - Topic",
            "seconds":185,"views":1200,"description":""}

class V4Tests(unittest.TestCase):
    def make_db(self,at):
        db=at/"catalog.sqlite3"
        con=sqlite3.connect(db)
        con.executescript("""
            CREATE TABLE artist (id TEXT PRIMARY KEY,name TEXT);
            CREATE TABLE recording (id TEXT PRIMARY KEY,title TEXT,artist_id TEXT);
            CREATE TABLE chart_edition (id TEXT PRIMARY KEY,country TEXT,
              as_of TEXT,category TEXT,provider TEXT);
            CREATE TABLE chart_entry (edition_id TEXT,recording_id TEXT,rank INTEGER);
        """)
        for c in v4.REGIONS:
            con.execute("INSERT INTO chart_edition VALUES(?,?,?,?,?)",
                        ("e"+c,c,"2026-10-20","songs","test"))
            for n in range(100):
                rid="r_"+c+"_"+str(n)
                if c=="jp" and n==0:rid="r_kr_0"
                elif c=="jp" and n==1:rid="r_kr_1"
                else:
                    a="a_"+c+"_"+str(n)
                    con.execute("INSERT INTO artist VALUES(?,?)",(a,"Singer "+c.upper()+str(n)))
                    con.execute("INSERT INTO recording VALUES(?,?,?)",
                                (rid,"Song "+c.upper()+str(n),a))
                con.execute("INSERT INTO chart_entry VALUES(?,?,?)",("e"+c,rid,n+1))
        con.commit();con.close()
        return db

    def test_300_unique_four_regions_with_duplicates_in_old_100(self):
        with tempfile.TemporaryDirectory() as tmp:
            db=self.make_db(Path(tmp))
            tracks=v4.balanced_300(db)
            self.assertEqual(len(tracks),300)
            self.assertEqual(len(set(x["id"] for x in tracks)),300)
            self.assertEqual({k:sum(x["country"]==k for x in tracks)
                              for k in v4.REGIONS},
                             dict.fromkeys(v4.REGIONS,75))
            orig=v2.balanced_sample(db)
            self.assertEqual(v4.balanced_300(db,orig),tracks)
            with self.assertRaises(ValueError):
                v4.balanced_300(db,[])

    def test_multilingual_artist_is_never_automatically_verified(self):
        t={"artist":"아이유","title":"Blueming","country":"kr"}
        rec={"url":URL,"title":"Blueming","channel":"IU - Topic",
             "seconds":199,"views":777,"description":""}
        grade=v4.grade_v4(t,rec)
        self.assertTrue(grade["eligible"])
        self.assertFalse(grade["artist_match"])
        _,best,status=v4.rank_v4(t,{"piped":{"candidates":[rec]}})
        self.assertEqual(status,"artist_review")
        self.assertEqual(best["url"],URL)

    def test_cjk_segment_title(self):
        t={"artist":"米津玄師","title":"Lemon","country":"jp"}
        rec={"url":URL,"title":"米津玄師「Lemon」(Official Audio)",
             "channel":"米津玄師 - Topic","seconds":252,"views":10000,"description":""}
        self.assertTrue(v4.grade_v4(t,rec)["eligible"])

    def test_reject_wrong_version(self):
        t={"artist":"BTS","title":"Dynamite","country":"kr"}
        for label in ("Dynamite remix","Dynamite (live)","Dynamite instrumental",
                      "Dynamite slowed + reverb"):
            with self.subTest(label=label):
                rec=make_candidate(label,"BTS")
                self.assertFalse(v4.grade_v4(t,rec)["eligible"])

    def test_regression_instrumental_unofficial_and_exact_title_priority(self):
        japanese={"artist":"米津玄師 & 宇多田ヒカル","title":"JANE DOE","country":"jp"}
        instrumental={"url":URL,"title":"米津玄師, 宇多田ヒカル「JANE DOE」(Instrumental)",
                      "channel":"Abism","seconds":237,"views":700,"description":""}
        self.assertFalse(v4.grade_v4(japanese,instrumental)["eligible"])
        english={"artist":"Drake & Don Toliver","title":"Solar Eclipse","country":"us"}
        unofficial=make_candidate("Solar Eclipse - Drake & Don Toliver (Unofficial Audio)",
                                  "Music Guy")
        self.assertFalse(v4.grade_v4(english,unofficial)["eligible"])
        exact=make_candidate("IRIS OUT","Kenshi Yonezu")
        unrelated=make_candidate("Opening Movie - IRIS OUT - Main theme: Kenshi Yonezu",
                                 "Kenshi Yonezu and MAPPA")
        original={"artist":"米津玄師","title":"IRIS OUT","country":"jp"}
        self.assertGreater(v4.grade_v4(original,exact)["score"],
                           v4.grade_v4(original,unrelated)["score"])

    def test_structural_title_repair_does_not_inject_artist_match(self):
        track={"artist":"米津玄師 & 宇多田ヒカル","title":"JANE DOE","country":"jp"}
        candidate={"url":URL,
          "title":"Chainsaw Man Reze Arc | 「JANE DOE」Official Ending Theme - Kenshi Yonezu x Utada Hikaru",
          "channel":"S A C","seconds":237,"views":700,"description":""}
        grade=v4.grade_v4(track,candidate)
        self.assertTrue(grade["eligible"])
        self.assertFalse(grade["artist_match"])
        self.assertIn("original_artist_unconfirmed",grade["reasons"])

    def test_baseline_choice_is_preserved_without_independent_evidence(self):
        track={"artist":"米津玄師 & 宇多田ヒカル","title":"JANE DOE","country":"jp"}
        old={"url":URL,"title":"JANE DOE","channel":"Kenshi Yonezu",
             "seconds":236,"views":1200,"description":""}
        other={"url":"https://www.youtube.com/watch?v=ABCDEFGHIJK",
               "title":"JANE DOE / 米津玄師, 宇多田ヒカル  Rock Style",
               "channel":"J-RePOP","seconds":208,"views":1200,"description":""}
        _,selected,status=v4.rank_v4(track,{"piped":{"candidates":[old,other]}},URL)
        self.assertEqual(selected["url"],URL)
        self.assertEqual(status,"baseline_retained_review")

    def test_baseline_rejected_if_original_is_unofficial(self):
        t={"artist":"BTS","title":"Dynamite","country":"kr"}
        untrusted={"url":URL,"title":"Dynamite (Unofficial Audio)",
                   "channel":"Fan","seconds":189,"views":200,"description":""}
        correct={"url":"https://www.youtube.com/watch?v=ABCDEFGHIJK",
                 "title":"Dynamite","channel":"BTS","seconds":189,
                 "views":1000,"description":""}
        _,selected,_=v4.rank_v4(t,{"piped":{"candidates":[untrusted,correct]}},URL)
        self.assertEqual(selected["url"],correct["url"])

    def test_review_clip_and_fan_lyrics_downranked(self):
        japan={"artist":"米津玄師 & 宇多田ヒカル","title":"JANE DOE","country":"jp"}
        clip={"url":URL,"title":"劇場版『チェンソーマン レゼ篇』公開記念PV／Ending Theme:米津玄師, 宇多田ヒカル「JANE DOE」",
              "channel":"MAPPA CHANNEL","seconds":99,"views":100,"description":""}
        studio={"url":URL,"title":"JANE DOE","channel":"Kenshi Yonezu",
                "seconds":236,"views":100,"description":""}
        self.assertLess(v4.grade_v4(japan,clip)["score"],
                        v4.grade_v4(japan,studio)["score"])
        kr={"artist":"머무르","title":"꿈꾸던 어른이 되었나요?","country":"kr"}
        lyric={"url":URL,"title":"꿈꾸던 어른이 되었나요? - 머무르 [가사/Lyrics]",
               "channel":"태초의 고막","seconds":206,"views":100,"description":""}
        recording={"url":URL,"title":"꿈꾸던 어른이 되었나요?","channel":"MEOMURU",
                   "seconds":208,"views":100,"description":""}
        self.assertLess(v4.grade_v4(kr,lyric)["score"],
                        v4.grade_v4(kr,recording)["score"])

    def test_one_search_plus_adaptive_second(self):
        t={"country":"kr","id":"x","artist":"BTS","title":"Dynamite"}
        calls=[]
        def request(base,query,filter_name,timeout):
            calls.append((query,filter_name))
            if filter_name=="music_songs":
                return {"status":200,"ms":13,"error":"","candidates":[]}
            return {"status":200,"ms":18,"error":"",
                    "candidates":[make_candidate("Dynamite","BTS")]}
        health={"disabled":set(),"failures":__import__("collections").defaultdict(int)}
        r=v4.process(t,("https://music.example",),health,3,0,
                   max_queries=2,fetcher=request)
        self.assertEqual(len(calls),2)
        self.assertEqual(r["v4"]["state"],"manual_review")
        self.assertEqual(r["v4"]["eligible"],1)
        self.assertEqual(len(r["attempts"]),2)

    def test_rate_limited_instance_stops_all_backends(self):
        t={"country":"jp","id":"x","artist":"A","title":"Song"}
        calls=[]
        def request(base,query,filter_name,timeout):
            calls.append(base)
            return {"status":429,"ms":42,"error":"HTTP 429","candidates":[]}
        health={"disabled":set(),"failures":__import__("collections").defaultdict(int)}
        r=v4.process(t,("https://one.example","https://two.example"),health,3,0,
                     fetcher=request)
        self.assertEqual(calls,["https://one.example"])
        self.assertEqual(health["disabled"],{"https://one.example","https://two.example"})
        self.assertEqual(r["v4"]["state"],"no_eligible_candidate")

    def test_run_resume_skips_all_completed(self):
        with tempfile.TemporaryDirectory() as tmp:
            at=Path(tmp);db=self.make_db(at)
            calls=[]
            def fetcher(base,query,filter_name,timeout):
                calls.append(query)
                return {"status":200,"ms":12,"error":"",
                    "candidates":[make_candidate("Song KR0","Singer KR0")]}
            a=v4.run(db,at/"out",None,limit=3,max_queries=1,
                     timeout=1,delay=0,bases=("https://safe.example",),fetcher=fetcher)
            self.assertEqual(a["processed"],3)
            self.assertEqual(len(calls),3)
            b=v4.run(db,at/"out",None,limit=3,max_queries=1,
                     timeout=1,delay=0,bases=("https://safe.example",),fetcher=fetcher)
            self.assertEqual(b["processed"],3)
            self.assertEqual(len(calls),3)
            props=list(csv.DictReader((at/"out"/"proposals_review_only_300.csv").open(encoding="utf-8-sig")))
            self.assertEqual(len(props),3)
            self.assertTrue(all(row["action"]=="MANUAL_REVIEW_ONLY" and row["verified"]=="NO" for row in props))

if __name__=="__main__":unittest.main()
