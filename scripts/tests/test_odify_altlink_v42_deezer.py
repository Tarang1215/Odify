import sys,unittest,tempfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v42_deezer as dz
import odify_altlink_v42_catalog as orig

def tr(name="BTS",title="Dynamite"):
 return {"country":"us","id":"a","artist":name,"title":title,"v4_state":"ambiguous_review",
         "v4_choice":"https://www.youtube.com/watch?v=abcdefghijk"}
def apple():
 return {"results":[{"wrapperType":"track","kind":"song","trackId":12,
    "artistId":34,"collectionId":56,"trackName":"Dynamite",
    "artistName":"BTS","collectionName":"BE",
    "trackTimeMillis":201000,"trackExplicitness":"notExplicit"}]}
def deezer():
 return {"data":[{"id":22,"title":"Dynamite","duration":199,
   "explicit_lyrics":False,"artist":{"id":77,"name":"BTS"},
   "album":{"id":99,"title":"BE"}}]}

class CatalogTests(unittest.TestCase):
 def test_deezer_independent_album_artist_track_ids(self):
  p=dz.parse_deezer(deezer())
  self.assertEqual(p[0]["recording_id"],"22")
  self.assertEqual(p[0]["artist_ids"],["77"])
  self.assertEqual(p[0]["album_id"],"99")
  a,d,state,gap,album,both=dz.pick_pair(tr(),apple(),deezer())
  self.assertEqual(state,"apple_deezer_consistent_review")
  self.assertEqual(gap,2)
  self.assertTrue(album)
  self.assertTrue(both)
 def test_album_diff_does_not_impersonate_identity(self):
  response=deezer()
  response["data"][0]["album"]["title"]="Different Release"
  _,_,state,gap,album,_=dz.pick_pair(tr(),apple(),response)
  self.assertEqual(state,"apple_deezer_consistent_review")
  self.assertFalse(album)
 def test_false_artist_not_counted_as_agreement(self):
  response=deezer()
  response["data"][0]["artist"]["name"]="BTS Army Tribute"
  _,_,state,_,_,_=dz.pick_pair(tr(),apple(),response)
  self.assertEqual(state,"one_or_zero_catalog")
 def test_version_conflict_halts_high_confidence(self):
  t=tr("Jung Kook","Seven (Explicit Ver.)")
  ap={"track_name":"Seven","explicitness":"cleaned"}
  self.assertIn("conflicts_explicit",dz.version_warning(t,ap,None))
 def test_video_metadata_never_autoapproved(self):
  t=tr()
  checkpoint={"responses":{"apple":apple()}}
  current={"request":{"status":200},"response":deezer()}
  candidates=[{"url":t["v4_choice"],"video_title":"Dynamite",
      "channel":"BTS - Topic","seconds":"201","v4_eligible":"1"}]
  row,hints=dz.audit(t,checkpoint,current,candidates)
  self.assertEqual(row["independent_catalog_status"],"apple_deezer_consistent_review")
  self.assertEqual(row["master_write"],"NO")
  self.assertEqual(row["automatic_approval"],"NO")
  self.assertEqual(len(hints),1)
  self.assertEqual(hints[0]["auto_approved"],"NO")
 def test_http_200_error_payload_is_not_music(self):
  self.assertEqual(dz.parse_deezer({"error":{"code":4,"message":"limit"}}),[])
  self.assertEqual(dz.parse_deezer({"data":[]}),[])

if __name__=="__main__":unittest.main()
