import csv,sqlite3,tempfile,sys,unittest
from contextlib import closing
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_pc_probe_ingest_v46 as v46

class WindowsProbeIngestTests(unittest.TestCase):
    def test_metadata_only_not_approved_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            b=Path(d);catalog=b/"catalog.db";rowfile=b/"probes.csv";out=b/"observations.db"
            con=sqlite3.connect(catalog)
            con.executescript("""CREATE TABLE artist(id TEXT,name TEXT);
                CREATE TABLE recording(id TEXT,artist_id TEXT,title TEXT);
                CREATE TABLE source_candidate(recording_id TEXT,url TEXT);
                INSERT INTO artist VALUES('artist','AKMU (악뮤)');
                INSERT INTO recording VALUES('first','artist','후라이의 꿈');
                INSERT INTO source_candidate VALUES('first','https://www.youtube.com/watch?v=ZlDDiHK4tQM');""")
            con.executemany("INSERT INTO recording VALUES(?,?,?)",
                ((f"dummy{i}","artist",f"other title {i}") for i in range(41415)))
            con.commit();con.close()
            item={"probe_key":"kr|AKMU|ZlDDiHK4tQM","url":"https://www.youtube.com/watch?v=ZlDDiHK4tQM",
                "video_id":"ZlDDiHK4tQM","artist":"AKMU (악뮤)","track":"후라이의 꿈",
                "region":"kr","variant":"positive_baseline","extractor_status":"audio_formats_present",
                "audio_formats":"3","preferred_format":"140/m4a","extracted_title":"후라이의 꿈"}
            with rowfile.open("w",encoding="utf-8-sig",newline="") as f:
                w=csv.DictWriter(f,fieldnames=item.keys());w.writeheader();w.writerow(item)
            result=v46.import_observations(catalog,[rowfile],out)
            self.assertEqual(result["observations"],1)
            self.assertEqual(result["pc_audio_formats_present"],1)
            self.assertEqual(result["auto_approved"],0)
            dbase=sqlite3.connect(out)
            self.assertEqual(dbase.execute("SELECT approved_for_playback FROM pc_probe").fetchone()[0],0)
            self.assertEqual(dbase.execute("SELECT recording_id FROM pc_probe").fetchone()[0],"first")
            dbase.close()
            with closing(sqlite3.connect(catalog)) as check:
                self.assertEqual(check.execute("SELECT COUNT(*) FROM source_candidate").fetchone()[0],1)
            with self.assertRaises(ValueError):v46.import_observations(catalog,[rowfile],out)
    def test_url_validation(self):
        self.assertIsNone(v46.URL.fullmatch("https://www.youtube.com.evil.example/watch?v=ZlDDiHK4tQM"))

if __name__=="__main__":unittest.main()
