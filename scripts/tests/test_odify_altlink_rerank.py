import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest
from zipfile import ZipFile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_rerank as m

VIDEO_A="https://www.youtube.com/watch?v=abcdefghijk"
VIDEO_B="https://www.youtube.com/watch?v=ABCDEFGHIJK"

def track(artist="아이유", title="Blueming",**kwargs):
    return {"country":"kr","id":"r1","artist":artist,"title":title,
            "source_date":"2019-12-31","source_rank":1,**kwargs}

def candidate(url=VIDEO_A,title="Blueming",channel="IU",seconds=195,**kw):
    return {"url":url,"title":title,"channel":channel,"seconds":seconds,
            "description":"","views":1500,**kw}

class TestOfflineV3(unittest.TestCase):
    def test_romanized_artist_candidate_kept_only_for_review(self):
        info=m.rank_candidate(track(),candidate())
        self.assertTrue(info["eligible"])
        self.assertFalse(info["artist_match"])
        self.assertIn("artist_unconfirmed",info["reasons"])
        _,best,state=m.ranking(track(),{"piped":{"candidates":[candidate()]}})
        self.assertEqual(best["url"],VIDEO_A)
        self.assertEqual(state,"artist_review")

    def test_verified_alias_provides_metadata_match_not_verification(self):
        t=track(artist_aliases="IU")
        info=m.rank_candidate(t,candidate())
        self.assertTrue(info["artist_match"])
        _,best,state=m.ranking(t,{"piped":{"candidates":[candidate()]}})
        self.assertEqual(state,"manual_review")
        self.assertLess(best["score"],86)

    def test_remix_cover_karaoke_and_sped_up_not_eligible(self):
        t=track("BTS","Dynamite")
        for changed in ("Dynamite Remix","Dynamite - Sped Up Version",
                        "Dynamite (Instrumental)","Dynamite Live"):
            with self.subTest(changed):
                c=candidate(title=changed,channel="BTS")
                self.assertFalse(m.rank_candidate(t,c)["eligible"])
        c=candidate(title="Dynamite",channel="Karaoke backing track")
        self.assertFalse(m.rank_candidate(t,c)["eligible"])

    def test_requested_clean_version_never_confuses_explicit(self):
        t=track("Jung Kook","Seven - Clean Ver.")
        explicit=candidate(title="Seven - Explicit Ver.",channel="Jung Kook")
        self.assertFalse(m.rank_candidate(t,explicit)["eligible"])

    def test_short_title_exact_required(self):
        t=track("BTS","RUN")
        self.assertEqual(m.title_match("RUN","Run BTS"),"none")
        self.assertEqual(m.title_match("RUN","BTS - RUN"),"segment")
        self.assertFalse(m.rank_candidate(t,candidate(title="Run BTS",channel="BTS"))["eligible"])

    def test_rank_no_reference_leakage(self):
        t=track("BTS","Dynamite")
        a=candidate(title="Dynamite",channel="BTS",url=VIDEO_A)
        b=candidate(title="Dynamite",channel="BTS - Topic",url=VIDEO_B)
        providers={"piped":{"candidates":[a,b]}}
        selected1=m.ranking(t,providers)[1]["url"]
        selected2=m.ranking({**t,"reference_url":VIDEO_A},providers)[1]["url"]
        self.assertEqual(selected1,selected2)
        self.assertEqual(selected1,VIDEO_B)

    def test_master_reference_only_verified_and_duplicate_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"MASTER.csv"
            p.write_text("아티스트,곡명,앨범,YouTube 오디오 URL,링크검증\n"
                         "아이유,Blueming,Love poem,"+VIDEO_A+",검증완료\n"
                         "BTS,Dynamite,BE,"+VIDEO_B+",재검토\n",encoding="utf-8-sig")
            refs=m.load_master(p)
            self.assertEqual(refs[(m.norm("아이유"),m.norm("Blueming"))]["reference_url"],VIDEO_A)
            self.assertFalse(refs[(m.norm("BTS"),m.norm("Dynamite"))]["reference_url"])
            self.assertEqual(refs[(m.norm("BTS"),m.norm("Dynamite"))]["existing_url"],VIDEO_B)
            with p.open("a",encoding="utf-8") as f:f.write("아이유,Blueming,Another Album,"+VIDEO_B+",검증완료\n")
            refs2=m.load_master(p)
            self.assertNotIn((m.norm("아이유"),m.norm("Blueming")),refs2)

    def test_zip_offline_read_only_and_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            rec={"track":track(),"providers":{"piped":{"candidates":[candidate()]},
                 "invidious":{"candidates":[]}},"decision":{"status":"후보부족","url":""}}
            zipfile=folder/"saved.zip"
            with ZipFile(zipfile,"w") as f:
                f.writestr("checkpoint_100/checkpoints/kr_r1.json",
                           json.dumps(rec,ensure_ascii=False))
            master=folder/"MASTER.csv"
            master.write_text("아티스트,곡명,YouTube 오디오 URL,링크검증\n"
                              "아이유,Blueming,"+VIDEO_A+",검증완료\n",encoding="utf-8-sig")
            before=master.read_bytes()
            result=m.run(zipfile,folder/"output",master)
            self.assertEqual(master.read_bytes(),before)
            self.assertEqual(result["known_verified_reference_tracks"],1)
            self.assertEqual(result["reference_video_in_candidates"],1)
            self.assertEqual(result["reranker_exact_reference_match"],1)
            output=list(csv.DictReader((folder/"output"/"review_only_proposals_v3.csv").open(encoding="utf-8-sig")))
            self.assertEqual(output[0]["action"],"KEEP_EXISTING")
            self.assertEqual(output[0]["verified"],"NO")
            self.assertFalse(any(k.startswith("MASTER") for k in (x.name for x in (folder/"output").iterdir())))

if __name__=="__main__":unittest.main()
