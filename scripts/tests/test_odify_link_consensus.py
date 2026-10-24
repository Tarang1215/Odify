import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "odify_link_consensus.py"
spec = importlib.util.spec_from_file_location("consensus",path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def item(id,title,seconds,channel):
    return {"url":"https://www.youtube.com/watch?v="+id,"title":title,
            "channel":channel,"seconds":seconds,"description":"","views":100}

class ConsensusTests(unittest.TestCase):
    def setUp(self):
        self.track={"아티스트":"LE SSERAFIM","곡명":"EASY",
                    "음원 버전":"한국어 원곡"}

    def test_unofficial_uploaders_can_form_duration_consensus(self):
        candidates={str(i):item(f"abcdefghi{i:02d}","LE SSERAFIM EASY",
                                165+(i%2),f"Uploader {i}") for i in range(3)}
        result,choice=m.decide(self.track,candidates)
        self.assertEqual("고신뢰",choice["status"])
        self.assertEqual(3,choice["support"])

    def test_english_version_is_rejected_even_same_duration(self):
        a=m.eligible(self.track,item("abcdefghijk","LE SSERAFIM EASY (English ver.)",
                                     165,"LE SSERAFIM"))
        self.assertFalse(a[0])
        self.assertIn("영어판",a[1])

    def test_two_competing_lengths_require_review(self):
        candidates={str(i):item(f"abcdefghi{i:02d}","LE SSERAFIM EASY",
                                165 if i<3 else 215,f"Uploader {i}") for i in range(5)}
        _,choice=m.decide(self.track,candidates)
        self.assertEqual("검토필요",choice["status"])

    def test_same_channel_is_not_independent_voting(self):
        candidates={str(i):item(f"abcdefghi{i:02d}","LE SSERAFIM EASY",
                                165,"same channel") for i in range(5)}
        _,choice=m.decide(self.track,candidates)
        self.assertEqual("검토필요",choice["status"])
        self.assertEqual(1,choice["support"])

    def test_2001_and_2004_remasters_do_not_match(self):
        track={"아티스트":"Fleetwood Mac","곡명":"Dreams - 2001 Remaster",
               "음원 버전":""}
        ok,reason=m.eligible(track,item("abcdefghijk",
                           "Fleetwood Mac Dreams (2004 Remaster)",258,"Fleetwood Mac"))
        self.assertFalse(ok)
        self.assertIn("리마스터 연도",reason)

    def test_verified_link_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as t:
            t=Path(t);master=t/"master.csv"; candidates=t/"candidates.csv"
            with master.open("w",newline="",encoding="utf-8-sig") as fp:
                w=csv.DictWriter(fp,fieldnames=[
                    "Track ID","아티스트","곡명","YouTube 오디오 URL","링크검증"])
                w.writeheader()
                for id,url,status in [
                    ("ODF000001","https://www.youtube.com/watch?v=ABCDEFGHIJK","검증완료"),
                    ("ODF000002","","대기")
                ]:
                    w.writerow({"Track ID":id,"아티스트":"LE SSERAFIM",
                        "곡명":"EASY","YouTube 오디오 URL":url,"링크검증":status})
            with candidates.open("w",newline="",encoding="utf-8-sig") as fp:
                w=csv.DictWriter(fp,fieldnames=[
                    "Track ID","후보 URL","후보 곡명","후보 아티스트","후보 재생시간"])
                w.writeheader()
                for tid in ["ODF000001","ODF000002"]:
                    for i in range(3):
                        w.writerow({"Track ID":tid,"후보 URL":f"https://www.youtube.com/watch?v=abcdefghi{i:02d}",
                            "후보 곡명":"LE SSERAFIM EASY",
                            "후보 아티스트":f"Uploader {i}","후보 재생시간":"165"})
            result=m.run(master,candidates,None,t/"out",True)
            self.assertEqual(1,result["filled"])
            _,rows=m.read_csv(t/"out"/"Odify_MASTER_합의선택.csv")
            self.assertEqual("https://www.youtube.com/watch?v=ABCDEFGHIJK",rows[0]["YouTube 오디오 URL"])
            self.assertEqual("검증완료",rows[0]["링크검증"])
            self.assertEqual("재검토",rows[1]["링크검증"])

if __name__=="__main__":
    unittest.main()
