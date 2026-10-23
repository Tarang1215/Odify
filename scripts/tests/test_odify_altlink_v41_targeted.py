import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v41_targeted as target

class TargetedTest(unittest.TestCase):
 def test_query_uses_translated_names_but_does_not_verify(self):
  self.assertIn("Maroon 5 Maps",target.variants(
    {"country":"jp","artist":"マルーン5","title":"マップス"})[0][0])
  self.assertEqual(len(target.variants({"country":"jp",
       "artist":"パスピエ","title":"裏の裏"})),2)

 def test_five_only_and_no_other_rows(self):
  with tempfile.TemporaryDirectory() as p:
   path=Path(p)/"diagnostics_300.csv"
   with path.open("w",encoding="utf-8-sig",newline="") as f:
    w=csv.DictWriter(f,fieldnames=("country","id","artist","title",
       "v4_state","recoverable_review_candidates"))
    w.writeheader()
    for i in range(300):
     if i<5:
      country,artist,title=target.REQUIRED[i]
      w.writerow({"country":country,"id":str(i),"artist":artist,"title":title,
                  "v4_state":"no_eligible_candidate",
                  "recoverable_review_candidates":"0"})
     else:
      w.writerow({"country":"us","id":str(i),"artist":"A","title":"B",
                  "v4_state":"manual_review","recoverable_review_candidates":"0"})
   targets=target.target_rows(Path(p))
   self.assertEqual(len(targets),5)
   data=list(csv.DictReader(path.open(encoding="utf-8-sig")))
   data[0]["title"]="corrupted"
   with path.open("w",encoding="utf-8-sig",newline="") as f:
    w=csv.DictWriter(f,fieldnames=data[0]);w.writeheader();w.writerows(data)
   with self.assertRaises(ValueError):target.target_rows(Path(p))

 def test_rerun_checkpoints_no_duplicate_requests(self):
  fake=[]
  for i,(country,artist,title) in enumerate(target.REQUIRED):
   fake.append({"country":country,"id":str(i),"artist":artist,"title":title})
  calls=[]
  def fetch(base,query,filter_name,timeout):
   calls.append(query)
   return {"status":200,"ms":22,"error":"","candidates":[]}
  with tempfile.TemporaryDirectory() as p:
   with patch.object(target,"target_rows",return_value=fake):
    s1=target.run(Path(p),Path(p)/"out",bases=("https://ok.example",),
      timeout=2,delay=0,fetcher=fetch)
    self.assertEqual(s1["processed"],5)
    self.assertEqual(s1["new_http_requests"],10)
    self.assertEqual(len(calls),10)
    s2=target.run(Path(p),Path(p)/"out",bases=("https://ok.example",),
      timeout=2,delay=0,fetcher=fetch)
    self.assertEqual(len(calls),10)
    rows=list(csv.DictReader((Path(p)/"out"/"five_track_live_audit.csv").open(encoding="utf-8-sig")))
    self.assertTrue(all(x["verified"]=="NO" and x["master_write"]=="NO" for x in rows))

if __name__=="__main__":unittest.main()
