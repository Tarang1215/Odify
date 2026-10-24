#!/usr/bin/env python3
import json
from contextlib import closing
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_link_multi_backend as m

class MultiBackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.db=Path(self.tmp.name)/"catalog.db"
        conn=sqlite3.connect(self.db)
        conn.executescript("""
           CREATE TABLE chart_edition(id TEXT PRIMARY KEY,country TEXT,provider TEXT,as_of TEXT,category TEXT);
           CREATE TABLE artist(id TEXT PRIMARY KEY,name TEXT);
           CREATE TABLE recording(id TEXT PRIMARY KEY,artist_id TEXT,title TEXT);
           CREATE TABLE chart_entry(edition_id TEXT,recording_id TEXT,rank INTEGER);
        """)
        for region in m.COUNTRIES:
            prov="listenbrainz-sitewide" if region=="gl" else "apple-rss"
            conn.execute("INSERT INTO chart_edition VALUES(?,?,?,'2026-10-23','songs')",
                         (region,region,prov))
            for i in range(1,26):
                key=f"{region}{i}"
                conn.execute("INSERT INTO artist VALUES(?,?)",(key,"Test Artist"))
                conn.execute("INSERT INTO recording VALUES(?,?,?)",(key,key,"Test Song"))
                conn.execute("INSERT INTO chart_entry VALUES(?,?,?)",(region,key,i))
        conn.commit();conn.close()
        self.output=Path(self.tmp.name)/"pilot"
        self.state=Path(self.tmp.name)/"state.db"

    def tearDown(self):
        self.tmp.cleanup()

    def args(self,n=1,retry=False):
        return SimpleNamespace(db=self.db,state=self.state,output_dir=self.output,
          limit_per_country=n,max_songs_this_run=0,timeout=1,delay=0,retry_errors=retry,
          providers="piped,invidious",
          piped_instances="https://piped.test",invidious_instances="https://inv.test")

    def test_parser_and_version_guard(self):
        items=m.parse_candidates("piped",{"items":[
            {"url":"/watch?v=ABCDEFGHIJK","title":"Test Artist Test Song Official Audio",
             "uploaderName":"Test Artist - Topic","duration":190,"views":30},
            {"url":"/watch?v=ABCDEFGHIJK","title":"dup","uploaderName":"Channel","duration":190},
            {"url":"/watch?v=invalid","title":"bad","duration":190}
        ]})
        self.assertEqual(len(items),1)
        self.assertEqual(items[0]["video_id"],"ABCDEFGHIJK")
        self.assertEqual(len(m.parse_candidates("invidious",[
           {"type":"video","videoId":"ABCDEFGHIJK","title":"Test Artist Test Song",
            "author":"Artist - Topic","lengthSeconds":191,"viewCount":31},
           {"type":"channel","author":"Ignore"}
        ])),1)
        self.assertNotEqual(m.parse_candidates("invidious",{}),[1])
        self.assertEqual(m.parse_video_id("https://www.youtube.com/watch?v=ABCDEFGHIJK"),"ABCDEFGHIJK")
        self.assertEqual(m.parse_video_id("http://evil/watch?v=ABCDEFGH123"),"ABCDEFGH123") # parser returns only IDs; never accesses host

    def test_100_stratified_and_read_only_retry(self):
        sample=m.sample_catalog(self.db,25)
        self.assertEqual(len(sample),100)
        self.assertEqual({x["country"] for x in sample},set(m.COUNTRIES))
        self.assertEqual(len({x["recording_id"] for x in sample}),100)
        calls=[]
        def request(url,timeout):
            calls.append(url)
            data=({"items":[
              {"url":"/watch?v=ABCDEFGHIJK","title":"Test Artist Test Song (Official Audio)",
               "uploaderName":"Test Artist - Topic","duration":187,"views":100}
            ]} if "/search?" in url and "piped" in url
            else [{"type":"video","videoId":"ABCDEFGHIJK",
                   "title":"Test Artist Test Song (Official Audio)",
                   "author":"Test Artist - Topic","lengthSeconds":187,"viewCount":100}])
            return data,200,35
        summary=m.run(self.args(),request=request)
        self.assertEqual(summary["total"],4)
        self.assertEqual(summary["attempts"],8)
        self.assertEqual(summary["completed"],4)
        self.assertEqual(len(calls),8)
        self.assertEqual(summary["matching_quality"]["manually_verified_accuracy_percent"],None)
        self.assertEqual(summary["matching_quality"]["eligible_candidate_coverage_percent"],100)
        self.assertFalse(summary["master_modified"])
        # Re-run: persisted results and no repeated API lookups
        second=m.run(self.args(),request=request)
        self.assertEqual(len(calls),8)
        self.assertEqual(second["attempts"],8)
        self.assertEqual(sum(1 for _ in csv_rows(self.output/"pilot_link_proposals.csv")),4)
        with closing(sqlite3.connect(self.state)) as con:
            self.assertEqual(con.execute("SELECT count(*) FROM candidate").fetchone()[0],8)

    def test_offline_provider_failures_and_retry_errors(self):
        def fail(url,timeout):return None,503,17,"HTTP 503"
        s=m.run(self.args(),request=fail)
        self.assertEqual(s["attempts"],8)
        self.assertEqual(s["successful_requests"],0)
        self.assertEqual(s["failed_requests"],8)
        self.assertEqual(s["status_counts"],{"provider_unavailable":4})
        calls=[]
        def succeed(url,timeout):
            calls.append(url)
            if "/search?" in url:
                return {"items":[]},200,5
            return [],200,5
        m.run(self.args(retry=True),request=succeed)
        self.assertEqual(len(calls),8)
        self.assertEqual(m.run(self.args(),request=fail)["failed_requests"],0)

    def test_unsafe_instances_rejected(self):
        for bad in ["http://invidious.example","https://user:pass@evil.test",
                    "https://evil.test:8080","https://evil.test/endpoint"]:
            with self.assertRaises(ValueError):m.safe_origin(bad)

def csv_rows(path):
    import csv
    with path.open(encoding="utf-8-sig",newline="") as fp:
        return list(csv.DictReader(fp))

if __name__=="__main__":
    unittest.main()
