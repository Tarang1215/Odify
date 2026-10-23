import sys,unittest,tempfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v43_full_queue as full
from test_odify_altlink_v43_batch import V43Tests

class FullQueueTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.source=V43Tests.records
  cls.prior=V43Tests.prior
 def test_unique_every_recording_and_stable_first1000(self):
  first=full.v43.build_manifest(self.source,self.prior)
  all=full.full_manifest(self.source,self.prior)
  self.assertEqual(len(all),41416)
  self.assertEqual([r["recording_id"] for r in all[:1000]],
     [r["recording_id"] for r in first])
  self.assertEqual(len(set(r["recording_id"] for r in all)),41416)
  self.assertEqual([r["recording_id"] for r in all],
     [r["recording_id"] for r in full.full_manifest(self.source,self.prior)])
 def test_dry_run_queue_1000_to_2000_no_http_and_stable(self):
  with tempfile.TemporaryDirectory() as d:
   dir=Path(d)
   input=dir/"immutable.sqlite3";input.write_text("source")
   with patch.object(full.v43,"catalog_records",return_value=self.source),patch.object(
        full.v43,"import_prior",return_value=self.prior):
    a=full.plan(input,dir/"cache",dir/"output",1000,1000,dry_run=True)
    self.assertEqual(a["network_requests"],0)
    self.assertEqual(a["slice_count"],1000)
    self.assertEqual(a["start"],1000)
    again=full.plan(input,dir/"cache",dir/"output",1000,1000,dry_run=True)
    self.assertEqual(a,again)
   self.assertTrue((dir/"output"/"full_catalog_41416_queue.csv").exists())
 def test_reject_boundary_and_oversized_batch(self):
  for start,size in [(-1,100),(41416,1),(41400,100),(1000,1001)]:
   with self.subTest(start=start,size=size):
    with self.assertRaises(ValueError):full.plan("catalog","cache","out",start,size,dry_run=True)

if __name__=="__main__":unittest.main()
