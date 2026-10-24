import csv
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v43_master_overlay as m

class OverlayTests(unittest.TestCase):
 def test_verified_master_kept_conflicts_skipped(self):
  with tempfile.TemporaryDirectory() as d:
   d=Path(d);src=d/"app.db";csvfile=d/"MASTER.csv";out=d/"new.db"
   c=sqlite3.connect(src)
   c.executescript("""CREATE TABLE artist(id TEXT PRIMARY KEY,name TEXT);
      CREATE TABLE recording(id TEXT PRIMARY KEY,artist_id TEXT,title TEXT);
      CREATE TABLE link_state(recording_id TEXT PRIMARY KEY,status TEXT,
        existing_master_url TEXT,existing_master_status TEXT,reason TEXT,
        verified INTEGER,automatic_applied INTEGER);
      INSERT INTO artist VALUES('a','BTS');
      INSERT INTO artist VALUES('b','IU');
      INSERT INTO recording VALUES('1','a','Dynamite');
      INSERT INTO recording VALUES('2','b','Blueming');
      INSERT INTO recording VALUES('3','b','Blueming');
      INSERT INTO link_state VALUES('1','manual_review','','','',0,0);
      INSERT INTO link_state VALUES('2','manual_review','','','',0,0);
      INSERT INTO link_state VALUES('3','manual_review','','','',0,0);""")
   c.commit();c.close()
   with csvfile.open("w",encoding="utf-8-sig",newline="") as f:
    w=csv.DictWriter(f,fieldnames=("아티스트","곡명","YouTube 오디오 URL","링크검증"))
    w.writeheader()
    w.writerow({"아티스트":"BTS","곡명":"Dynamite",
      "YouTube 오디오 URL":"https://youtu.be/abcdefghijk","링크검증":"검증완료"})
    w.writerow({"아티스트":"IU","곡명":"Blueming",
      "YouTube 오디오 URL":"https://youtu.be/ABCDEFGHIJK","링크검증":"검증완료"})
   result=m.apply(src,csvfile,out,expected=3)
   self.assertEqual(result["protected_verified_links"],1)
   self.assertEqual(result["ambiguous_catalog_identity"],1)
   s=sqlite3.connect(src)
   self.assertEqual(s.execute("select status from link_state where recording_id='1'").fetchone()[0],"manual_review")
   s.close()
   x=sqlite3.connect(out)
   self.assertEqual(x.execute("select status,existing_master_url from link_state where recording_id='1'").fetchone(),
     ("existing_verified_protected","https://www.youtube.com/watch?v=abcdefghijk"))
   self.assertEqual(x.execute("select status from link_state where recording_id='2'").fetchone()[0],"manual_review")
   self.assertEqual(x.execute("select count(*) from link_state where verified!=0 or automatic_applied!=0").fetchone()[0],0)
   x.close()
   self.assertTrue((d/"new_master_identity_conflicts.csv").exists())
   with self.assertRaises(FileExistsError):m.apply(src,csvfile,out,expected=3)
 def test_disallows_untrusted_host(self):
  self.assertEqual(m.canon("https://youtube.com.evil.example/watch?v=abcdefghijk"),"")
  self.assertEqual(m.canon("http://youtube.com/watch?v=abcdefghijk"),"")
  self.assertEqual(m.canon("https://youtu.be/abcdefghijk"),"https://www.youtube.com/watch?v=abcdefghijk")

if __name__=="__main__":unittest.main()
