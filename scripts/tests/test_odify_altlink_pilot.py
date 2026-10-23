import csv
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import sys

BASE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BASE))
spec=importlib.util.spec_from_file_location("pilot",BASE/"odify_altlink_pilot.py")
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class AltLinkTests(unittest.TestCase):
    def test_piped_invidious_schema(self):
        piped=m.adapt("piped",{"items":[
            {"type":"stream","url":"/watch?v=abcdefghijk","title":"Singer - Track",
             "uploaderName":"Singer - Topic","duration":180,"views":321},
            {"type":"channel","url":"/channel/test","title":"Ignore"}]})
        inv=m.adapt("invidious",[
            {"type":"video","videoId":"abcdefghijk","title":"Singer - Track",
             "author":"Singer - Topic","lengthSeconds":180,"viewCount":321},
            {"type":"playlist","playlistId":"abc"}])
        self.assertEqual(len(piped),1)
        self.assertEqual(len(inv),1)
        self.assertEqual(piped["abcdefghijk"]["seconds"],180)
        self.assertEqual(inv["abcdefghijk"]["channel"],"Singer - Topic")
        self.assertEqual(piped["abcdefghijk"]["url"],inv["abcdefghijk"]["url"])

    def test_bad_versions_cannot_be_marked_good(self):
        track={"country":"kr","id":"a","title":"EASY","artist":"LE SSERAFIM",
               "source_date":"2024-01-01","source_rank":1,
               "source_provider":"test","reference_url":""}
        def respond(provider,track,bases,timeout,interval=0):
            return {"status":"ok","latency_ms":30,"attempts":[{"base":"mock","ms":30}],
              "candidates":[{"url":"https://www.youtube.com/watch?v=abcdefghijk",
              "title":"LE SSERAFIM EASY English Version","channel":"Uploader",
              "seconds":165,"description":"","views":1000}]}
        with patch.object(m,"request_provider",side_effect=respond):
            r=m.process_one(track,("https://mock.example",),("https://mock.example",),3)
        self.assertEqual(r["eligible_candidates"],0)
        self.assertEqual(r["decision"]["status"],"후보부족")

    def test_resume_skips_completed_and_does_not_write_master(self):
        with tempfile.TemporaryDirectory() as td:
            t=Path(td)
            db=t/"catalog.sqlite3"
            con=sqlite3.connect(db)
            con.executescript("""
             CREATE TABLE artist(id TEXT PRIMARY KEY,name TEXT);
             CREATE TABLE recording(id TEXT PRIMARY KEY,title TEXT,artist_id TEXT);
             CREATE TABLE chart_edition(id TEXT PRIMARY KEY,country TEXT,as_of TEXT,
                                        category TEXT,provider TEXT);
             CREATE TABLE chart_entry(edition_id TEXT,recording_id TEXT,rank INTEGER);
            """)
            for c in m.REGIONS:
                con.execute("INSERT INTO chart_edition VALUES(?,?,?,?,?)",
                            ("e"+c,c,"2026-10-23","songs","test"))
                for n in range(25):
                    artist="Singer "+c+str(n)
                    title="Song "+c+str(n)
                    aid=c+str(n);rid="r"+c+str(n)
                    con.execute("INSERT INTO artist VALUES(?,?)",(aid,artist))
                    con.execute("INSERT INTO recording VALUES(?,?,?)",(rid,title,aid))
                    con.execute("INSERT INTO chart_entry VALUES(?,?,?)",("e"+c,rid,n+1))
            con.commit();con.close()
            master=t/"master.csv"
            master.write_text("Track ID,YouTube 오디오 URL,링크검증\nODF000001,https://www.youtube.com/watch?v=12345678901,검증완료\n")
            original=master.read_bytes()
            calls=[]
            def request(provider,track,bases,timeout,interval=0):
                calls.append(provider)
                return {"status":"ok","latency_ms":20,"attempts":[{"ms":20,"status":"ok"}],
                        "candidates":[]}
            with patch.object(m,"request_provider",side_effect=request):
                first=m.run(db,t/"pilot",workers=2,limit=5)
                self.assertEqual(first["processed"],5)
                self.assertEqual(len(calls),10)
                second=m.run(db,t/"pilot",workers=2,limit=5)
                self.assertEqual(second["processed"],5)
                self.assertEqual(len(calls),10)
            self.assertEqual(original,master.read_bytes())
            self.assertEqual(len(json.loads((t/"pilot"/"sample_100.json").read_text())),100)
            self.assertEqual(json.loads((t/"pilot"/"summary.json").read_text())["percent"],5)
            proposals=(t/"pilot"/"review_only_proposals.csv").read_text(encoding="utf-8-sig")
            self.assertNotIn("검증완료",proposals)

    def test_same_video_is_one_candidate_even_two_sources(self):
        track={"country":"us","id":"r1","artist":"LE SSERAFIM",
               "title":"EASY","source_date":"2024-01-01","source_rank":1,
               "reference_url":"https://www.youtube.com/watch?v=abcdefghijk"}
        def provider(name,track,bases,timeout,interval=0):
            return {"status":"ok","latency_ms":44,"attempts":[],
                    "candidates":[{"url":"https://www.youtube.com/watch?v=abcdefghijk",
                     "title":"LE SSERAFIM EASY","channel":"Artist - Topic",
                     "seconds":165,"description":"","views":500}]}
        with patch.object(m,"request_provider",side_effect=provider):
            r=m.process_one(track,("https://a.test",),("https://b.test",),2)
        self.assertEqual(r["unique_candidates"],1)
        self.assertEqual(r["decision"]["status"],"검토필요")
        self.assertTrue(r["reference_exact"])

if __name__=="__main__":unittest.main()
