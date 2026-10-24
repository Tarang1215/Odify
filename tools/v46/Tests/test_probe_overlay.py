import sqlite3, tempfile,unittest,csv
from pathlib import Path
import sys
from contextlib import closing
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import odify_probe_overlay as overlay

class TestProbeOverlay(unittest.TestCase):
    def test_no_overwrite_and_review_flags(self):
        with tempfile.TemporaryDirectory() as tmp:
            b=Path(tmp);db=b/'source.db';result=b/'result.csv';out=b/'overlay.db'
            con=sqlite3.connect(db)
            con.executescript('''CREATE TABLE artist(id TEXT,name TEXT);
            CREATE TABLE recording(id TEXT,artist_id TEXT,title TEXT);
            CREATE TABLE source_candidate(recording_id TEXT,url TEXT);
            INSERT INTO artist VALUES('a','AKMU (악뮤)');
            INSERT INTO recording VALUES('r1','a','후라이의 꿈');
            INSERT INTO source_candidate VALUES('r1','https://www.youtube.com/watch?v=ZlDDiHK4tQM');''')
            for i in range(41415):con.execute('INSERT INTO recording VALUES(?,?,?)',(f'rx{i}','a','unique'+str(i)))
            con.commit();con.close()
            row=dict(probe_key='kr|AKMU (악뮤)|후라이의 꿈|ZlDDiHK4tQM|positive_baseline|v44',
              url='https://www.youtube.com/watch?v=ZlDDiHK4tQM',video_id='ZlDDiHK4tQM',
              artist='AKMU (악뮤)',track='후라이의 꿈',region='kr',variant='positive_baseline',
              extractor_status='audio_formats_present',audio_formats='3',preferred_format='140/m4a',
              extracted_title='후라이의 꿈',extracted_channel='AKMU',manual_same_recording='PENDING')
            with result.open('w',encoding='utf-8-sig',newline='') as f:
                w=csv.DictWriter(f,fieldnames=row.keys());w.writeheader();w.writerow(row)
            report=overlay.report(db,[result],out)
            self.assertEqual(report['imported'],1)
            self.assertEqual(report['linked_to_unique_catalog_recording'],1)
            self.assertEqual(report['approved_for_playback'],0)
            with closing(sqlite3.connect(out)) as check:
                self.assertEqual(check.execute('select approved_for_playback from probe').fetchone()[0],0)
            with closing(sqlite3.connect(db)) as check:
                self.assertEqual(check.execute('select count(*) from source_candidate').fetchone()[0],1)
            with self.assertRaises(FileExistsError):overlay.report(db,[result],out)

if __name__=='__main__':unittest.main()
