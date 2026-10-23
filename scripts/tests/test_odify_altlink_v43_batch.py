import csv
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v43_batch as v43
import odify_altlink_v4_300 as v4

URL="https://www.youtube.com/watch?v=abcdefghijk"

def fake_source():
 records=[]
 for i in range(v43.EXPECTED_CATALOG):
  country=("gl" if i<79 else "kr" if i<5079 else "jp" if i<29079 else
            "us" if i<41390 else "other")
  records.append({"recording_id":f"r{i:05d}","artist_id":f"a{i%125:03d}",
    "artist":f"Artist {i%125}","title":f"Song {i}","country":country,
    "album":"","version":"original","release_date":"",
    "chart_date":"2024-01-01","chart_rank":i%100+1,"chart_provider":"synthetic"})
 return records

def prior_for(records):
 archived={}
 for country in v43.REGIONS:
  for r in [x for x in records if x["country"]==country][:75]:
   track=v43.track_for_v4(r)
   archived[r["recording_id"]]={"schema":v4.SCHEMA,"track":track,
      "providers":{"piped":{"candidates":[]}},
      "first_v3":{"selected_url":""},"v4":{"state":"no_eligible_candidate",
      "eligible":0,"selected_url":"","score":0},"attempts":[]}
 return archived

class V43Tests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.records=fake_source()
  cls.prior=prior_for(cls.records)
 def test_1000_unique_quota_and_prior_preservation(self):
  sample=v43.build_manifest(self.records,self.prior)
  self.assertEqual(len(sample),1000)
  self.assertEqual(len({x["recording_id"] for x in sample}),1000)
  self.assertEqual({k:sum(x["country"]==k for x in sample) for k in v43.REGIONS},v43.QUOTAS)
  self.assertEqual(sum(x["source"]=="v4_cache" for x in sample),300)
  self.assertEqual(sample,v43.build_manifest(self.records,self.prior))
 def test_invalid_prior_and_immutable_schema(self):
  with self.assertRaises(ValueError):
   v43.build_manifest(self.records,{})
  with self.assertRaises(ValueError):
   v43.build_manifest(self.records,self.prior|{"unexpected":{
     **next(iter(self.prior.values())),
     "track":{"country":"kr","id":"not_exist","artist":"x","title":"x"}}})
 def test_master_protection_no_automatic_write(self):
  with tempfile.TemporaryDirectory() as d:
   file=Path(d)/"MASTER.csv"
   with file.open("w",encoding="utf-8-sig",newline="") as f:
    w=csv.DictWriter(f,fieldnames=["아티스트","곡명","YouTube 오디오 URL","링크검증"])
    w.writeheader()
    w.writerow({"아티스트":"Artist 0","곡명":"Song 0",
       "YouTube 오디오 URL":URL,"링크검증":"검증완료"})
   protect=v43.master_protection(file)
   self.assertEqual(protect[("artist0","song0")],(URL,"검증완료"))
   self.assertIn("검증완료",file.read_text(encoding="utf-8-sig"))
 def test_sqlite_app_export_all_41416_unprocessed_and_protected(self):
  sample=v43.build_manifest(self.records,self.prior)
  prior=next(iter(self.prior.values()))
  original={**prior,"v43_schema":v43.SCHEMA,"v43_source":"v4_cache",
    "v43_attempts":[],"v43_result_status":"cached"}
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/"app.sqlite3"
   with patch.object(v43,"catalog_records",return_value=self.records):
    v43.export_database(Path(tmp)/"input.sqlite3",path,sample,
                        {original["track"]["id"]:original},
                        {("artist0","song0"):(URL,"검증완료")})
   db=sqlite3.connect(path)
   self.assertEqual(db.execute("SELECT COUNT(*) FROM recording").fetchone()[0],41416)
   self.assertEqual(db.execute("SELECT COUNT(*) FROM link_state").fetchone()[0],41416)
   self.assertEqual(db.execute("SELECT count(*) FROM link_state WHERE status='existing_verified_protected'").fetchone()[0],1)
   self.assertEqual(db.execute("SELECT count(*) FROM link_state WHERE automatic_applied!=0 OR verified!=0").fetchone()[0],0)
   db.close()
 def test_checkpoint_resume_does_not_duplicate_api_requests(self):
  records=self.records
  prior=self.prior
  calls=[]
  def fake_fetch(base,query,filter_name,timeout):
   calls.append(query)
   return {"status":200,"ms":21,"error":"","candidates":[]}
  with tempfile.TemporaryDirectory() as tmp:
   directory=Path(tmp)
   db=directory/"original.sqlite3";db.write_text("fixture")
   cache=directory/"cache"
   with patch.object(v43,"catalog_records",return_value=records),patch.object(
        v43,"import_prior",return_value=prior):
    first=v43.run(db,cache,directory/"output",limit=302,max_queries=1,
                  delay=0,bases=("https://example.invalid",),
                  fetcher=fake_fetch,database_export=False)
    self.assertEqual(first["processed"],302)
    self.assertEqual(first["fresh_searched"],2)
    self.assertEqual(first["reused_prior_300"],300)
    self.assertEqual(len(calls),2)
    second=v43.run(db,cache,directory/"output",limit=302,max_queries=1,
                   delay=0,bases=("https://example.invalid",),
                   fetcher=fake_fetch,database_export=False)
   self.assertEqual(second["processed"],302)
   self.assertEqual(len(calls),2)
   rows=list(csv.DictReader((directory/"output"/"audit_1000.csv").open(encoding="utf-8-sig")))
   self.assertEqual(len(rows),302)
   self.assertTrue(all(x["verified"]=="NO" and x["safe_to_auto_import"]=="NO" for x in rows))
 def test_provider_unavailable_not_falsely_marked_verified(self):
  d=v43.report(v43.build_manifest(self.records,self.prior),{
   "x":{"v43_source":"live","v43_attempts":[{"status":503,"latency_ms":10}],
        "v43_result_status":"provider_unavailable","v4":{"eligible":0,"state":"no_eligible_candidate"}}})
  self.assertEqual(d["provider_unavailable"],1)
  self.assertEqual(d["new_http_ok"],0)

if __name__=="__main__":unittest.main()
