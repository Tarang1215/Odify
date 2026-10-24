import csv
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v42_catalog as v42

def track(artist="BTS",title="Dynamite",state="ambiguous_review",country="kr",num="abc"):
 return {"country":country,"id":num,"artist":artist,"title":title,"v4_state":state,
         "v4_choice":"https://www.youtube.com/watch?v=abcdefghijk"}

MBID="c4b18211-40b7-4d7c-9df9-e2b5417ca78b"
def mb(artist="BTS",title="Dynamite",seconds=200):
 return {"recordings":[{"id":MBID,"title":title,"length":seconds*1000,
  "artist-credit":[{"name":artist,"artist":{"id":"artist-mbid","name":artist}}],
  "releases":[{"title":"BE","date":"2020-11-20"}]}]}
def apple(artist="BTS",title="Dynamite",seconds=201):
 return {"results":[{"wrapperType":"track","kind":"song","trackId":123,
   "artistId":456,"collectionId":789,"trackName":title,
   "artistName":artist,"collectionName":"BE","trackTimeMillis":seconds*1000,
   "trackExplicitness":"notExplicit","releaseDate":"2020-11-20T12:00:00Z"}]}

class V42Tests(unittest.TestCase):
 def test_independent_identity_duration_provenance(self):
  a=v42.parse_mb(mb(),track())
  b=v42.parse_apple(apple(),track())
  rec,ap,status,tm,am,dt=v42.resolve_catalog(track(),a,b)
  self.assertEqual(status,"two_catalog_consistent_review")
  self.assertEqual(dt,1)
  self.assertEqual(rec["artist_ids"],["artist-mbid"])
  self.assertEqual(ap["album_id"],"789")
  self.assertEqual(ap["seconds"],201)

 def test_one_catalog_does_not_verify(self):
  t=track("아이유","에잇")
  _,_,state,_,_,difference=v42.resolve_catalog(t,[],v42.parse_apple(apple(artist="IU",title="eight"),t))
  self.assertEqual(state,"catalog_identity_unresolved")
  self.assertIsNone(difference)

 def test_titles_artist_alias_explicit_only(self):
  self.assertTrue(v42.name_equivalent("SHINee (샤이니)","샤이니"))
  self.assertFalse(v42.name_equivalent("IU","LUNA"))
  self.assertFalse(v42.name_equivalent("Billie","Billie Eilish"))
  self.assertTrue(v42.artist_equivalent("Post Malone & Swae Lee","Post Malone"))
  self.assertFalse(v42.artist_equivalent("BTS","BTS Army Fan"))

 def test_reject_disagreeing_catalog_duration(self):
  t=track()
  a=v42.parse_mb(mb(seconds=198),t)
  b=v42.parse_apple(apple(seconds=239),t)
  self.assertEqual(v42.resolve_catalog(t,a,b)[2],"catalog_conflict_or_variant_review")

 def test_video_duration_discrepancy_never_auto_approved(self):
  t=track()
  inputs=[{"url":t["v4_choice"],"video_title":"BTS - Dynamite",
    "channel":"BTS - Topic","seconds":"200","v4_eligible":"1"},
   {"url":"https://www.youtube.com/watch?v=ABCDEFGHIJK",
    "video_title":"BTS - Dynamite (Remix)","channel":"BTS",
    "seconds":"201","v4_eligible":"1"}]
  out=v42.candidate_details(t,inputs,200,t["v4_choice"])
  self.assertEqual(out[0]["duration_evidence"],"within_7s")
  self.assertEqual(out[0]["original_url_preserved"],"YES")
  self.assertTrue(all(x["approved"]=="NO" and x["review_only"]=="YES" for x in out))
  self.assertTrue(any(x["version_warning"] for x in out))

 def test_catalog_http_endpoints_safe(self):
  p=v42.queries(track())
  self.assertIn("musicbrainz.org/ws/2/recording",p["musicbrainz"][0])
  self.assertIn("itunes.apple.com/search",p["apple"][0])
  self.assertIn("country=KR",p["apple"][0])

 def test_input_sample_rejects_incorrect_counts(self):
  with tempfile.TemporaryDirectory() as d:
   folder=Path(d)
   for filename,head in [
     ("audit_300_v4.csv",("country","id","artist","title","v4_state")),
     ("candidates_300_v4.csv",("country","id","url","video_title")),
     ("ambiguity_review_39.csv",("country","id")),
     ("new_title_review_candidates.csv",("country","id"))
   ]:
    with (folder/filename).open("w",encoding="utf-8-sig",newline="") as f:
     w=csv.DictWriter(f,fieldnames=head);w.writeheader()
   with self.assertRaises(ValueError):v42.make_inputs(folder,folder,None)

 def test_review_only_never_edits_baseline(self):
  t=track()
  record={"schema":v42.SCHEMA,"responses":{"musicbrainz":mb(),"apple":apple()}}
  c=[{"url":t["v4_choice"],"video_title":"BTS - Dynamite",
       "channel":"BTS - Topic","seconds":"200","v4_eligible":"1"}]
  original=dict(t)
  audit,candidates=v42.audit_song(t,record,c)
  self.assertEqual(audit["automatic_approval"],"NO")
  self.assertEqual(audit["master_write"],"NO")
  self.assertEqual(t,original)
  self.assertTrue(all(c["approved"]=="NO" for c in candidates))

if __name__=="__main__":unittest.main()
