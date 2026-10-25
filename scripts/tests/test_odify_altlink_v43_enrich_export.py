import sqlite3
from contextlib import closing
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_altlink_v43_enrich_export as enr

class ExportTests(unittest.TestCase):
 def test_real_album_membership_and_identity_without_writes(self):
  with tempfile.TemporaryDirectory() as d:
   d=Path(d);cat=d/"catalog.sqlite3";links=d/"links.sqlite3";out=d/"enriched.sqlite3"
   c=sqlite3.connect(cat)
   c.executescript("""CREATE TABLE recording(id TEXT PRIMARY KEY);
    CREATE TABLE album(id TEXT PRIMARY KEY,artist_id TEXT,title TEXT);
    CREATE TABLE album_track(album_id TEXT,recording_id TEXT,disc INTEGER,position INTEGER);
    CREATE TABLE external_identity(provider TEXT,ext_id TEXT,recording_id TEXT);
    INSERT INTO recording VALUES('r1'); INSERT INTO recording VALUES('r2');
    INSERT INTO album VALUES('a1','artist1','Album');
    INSERT INTO album_track VALUES('a1','r1',1,1);
    INSERT INTO external_identity VALUES('apple','123','r1');
    """);c.commit();c.close()
   c=sqlite3.connect(links)
   c.executescript("""PRAGMA foreign_keys=ON;
    CREATE TABLE recording(id TEXT PRIMARY KEY);
    CREATE TABLE album(id TEXT PRIMARY KEY,artist_id TEXT,title TEXT);
    CREATE TABLE link_state(recording_id TEXT,status TEXT,verified INTEGER,automatic_applied INTEGER);
    CREATE TABLE source_candidate(recording_id TEXT,video_id TEXT);
    INSERT INTO recording VALUES('r1'); INSERT INTO recording VALUES('r2');
    INSERT INTO link_state VALUES('r1','manual_review',0,0);
    INSERT INTO link_state VALUES('r2','unprocessed',0,0);
    INSERT INTO source_candidate VALUES('r1','abcdefghijk');
    """);c.commit();c.close()
   summary=enr.audit(cat,links,out,expected=2)
   self.assertEqual(summary["real_album_records"],1)
   self.assertEqual(summary["real_album_track_memberships"],1)
   self.assertEqual(summary["external_recording_identity_rows"],1)
   self.assertEqual(summary["candidate_rows"],1)
   z=sqlite3.connect(out)
   self.assertEqual(z.execute("SELECT COUNT(*) FROM album_track").fetchone()[0],1)
   self.assertEqual(z.execute("SELECT external_id FROM external_recording_identity").fetchone()[0],"123")
   self.assertEqual(z.execute("SELECT COUNT(*) FROM link_state WHERE verified!=0").fetchone()[0],0)
   z.close()
   with closing(sqlite3.connect(links)) as check:
    self.assertEqual(check.execute("SELECT COUNT(*) FROM album").fetchone()[0],0)
   with self.assertRaises(FileExistsError):enr.audit(cat,links,out,expected=2)
 def test_export_rejects_unsafe_prior_selection(self):
  with tempfile.TemporaryDirectory() as d:
   d=Path(d)
   a=sqlite3.connect(d/"a.sqlite3")
   a.executescript("CREATE TABLE recording(id);INSERT INTO recording VALUES('1');")
   a.commit();a.close()
   b=sqlite3.connect(d/"b.sqlite3")
   b.executescript("""CREATE TABLE recording(id);INSERT INTO recording VALUES('1');
    CREATE TABLE link_state(verified INTEGER,automatic_applied INTEGER);
    INSERT INTO link_state VALUES(1,1);""");b.commit();b.close()
   with self.assertRaises(ValueError):
    enr.audit(d/"a.sqlite3",d/"b.sqlite3",d/"c.sqlite3",expected=1)

if __name__=="__main__":unittest.main()
