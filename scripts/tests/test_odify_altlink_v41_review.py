import csv
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v41_review as v41

def row(title,seconds=180,channel="Artist - Topic",url="https://www.youtube.com/watch?v=ABCDEFGHIJK"):
 return {"url":url,"video_title":title,"channel":channel,"seconds":str(seconds),
         "views":"200","v4_eligible":"0","v4_score":"0","v4_reasons":"title_mismatch"}
def track(artist="2NE1",title="너 아님 안돼",country="kr"):
 return {"country":country,"artist":artist,"title":title,"id":"track1"}

class V41Tests(unittest.TestCase):
 def test_embedded_original_title_is_review_only(self):
  r=v41.review_candidate(track(),row("2NE1 - 너 아님 안돼 (GOTTA BE YOU) M/V",240,"2NE1"))
  self.assertIsNotNone(r)
  self.assertEqual(r["match_type"],"title_substring")
  self.assertEqual(r["auto_apply"],"NO")
  self.assertEqual(r["review_required"],"YES")

 def test_translation_proposals_never_count_as_verified(self):
  t=track("アリアナ・グランデ","サンタ・テル・ミー","jp")
  r=v41.review_candidate(t,row("Santa Tell Me",200,"Ariana Grande - Topic"))
  self.assertIsNotNone(r)
  self.assertEqual(r["match_type"],"translated_title_pending")
  self.assertEqual(r["artist_hint_proposed_only"],"YES")
  self.assertEqual(r["artist_confirmed_in_original_metadata"],"NO")

 def test_remix_karaoke_unofficial_and_short_preview_filtered(self):
  t=track("Agust D","해금","gl")
  for title in ("Haegeum (Remix)","Haegeum karaoke",
                "Haegeum (UnOfficial Audio)","Haegeum - slowed"):
   with self.subTest(title=title):
    self.assertIsNone(v41.review_candidate(t,row(title,180,"Agust D")))
  self.assertIsNone(v41.review_candidate(t,row("Haegeum",31,"Agust D")))

 def test_unverified_original_title_does_not_bypass_live_version(self):
  t=track("BTS","Blood Sweat & Tears - Live","gl")
  self.assertIsNone(v41.review_candidate(t,row("Blood Sweat & Tears studio",215,"BTS")))
  self.assertIsNone(v41.review_candidate(t,row("Blood Sweat & Tears - Live",45,"BTS")))
  live=v41.review_candidate(t,row("Blood Sweat & Tears - Live",213,"BTS"))
  self.assertIsNotNone(live)
  self.assertEqual(live["auto_apply"],"NO")

 def test_ambiguity_same_channel_is_not_independence(self):
  t={"country":"us","id":"test","artist":"Singer","title":"Song",
     "v4_choice":"https://www.youtube.com/watch?v=ABCDEFGHIJK"}
  candidates=[{**row("Song",200,"Singer - Topic", "https://www.youtube.com/watch?v=ABCDEFGHIJK"),"v4_eligible":"1","v4_score":"77"},
    {**row("Song",201,"Singer - Topic", "https://www.youtube.com/watch?v=abcdefghijk"),"v4_eligible":"1","v4_score":"76"}]
  result=v41.classify_ambiguous(t,candidates)
  self.assertEqual(result["independent_channels"],1)
  self.assertEqual(result["review_category"],"same_uploader_duplicates")
  self.assertEqual(result["master_write"],"NO")

 def test_full_300_artifact_is_required(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)
   for name,fields in (("audit_300_v4.csv",("country","id","artist","title","v4_state","expanded_v4_eligible","v4_choice")),
                       ("candidates_300_v4.csv",("country","id","url","video_title","seconds"))):
    with (p/name).open("w") as f:
     w=csv.DictWriter(f,fieldnames=fields);w.writeheader()
     w.writerow({k:"x" for k in fields})
   with self.assertRaises(ValueError):v41.run(p,p/"output")

if __name__=="__main__":unittest.main()
