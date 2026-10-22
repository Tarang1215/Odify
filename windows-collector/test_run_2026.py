import contextlib
import csv
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import sys
from tempfile import TemporaryDirectory
from unittest import TestCase, main
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('run_2026', ROOT / 'run_2026.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class FakeWorker:
    def connection(self, p):
        p.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(p)
        conn.execute('CREATE TABLE IF NOT EXISTS tracks (id TEXT PRIMARY KEY, chart_year TEXT, status TEXT, file_path TEXT, cover_status TEXT, lyric_status TEXT, last_error TEXT)')
        return conn
    def ingest(self, db, csv_path, default_year):
        for row in runner.parse_snapshot(csv_path):
            db.execute('INSERT OR IGNORE INTO tracks VALUES (?,?,?,?,?,?,?)', (row['Track ID'],default_year,'queued',None,'pending','pending',None))
        db.commit()
    def run_worker(self, args):
        rows=runner.parse_snapshot(runner.DATA)
        n=0
        for row in rows:
            id=row['Track ID']
            state=args.root.joinpath('catalog.sqlite3')
            db=self.connection(state)
            try:
                st=db.execute('SELECT status FROM tracks WHERE id=?',(id,)).fetchone()[0]
                if st=='downloaded' or row['링크검증']!='검증완료':
                    continue
                if n>=args.limit: break
                path=args.root/'Audio'/f'{id}.mp3'
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(b'MP3-MOCK-FOR-TESTING-ONLY')
                db.execute('UPDATE tracks SET status=?,file_path=?,cover_status=? WHERE id=?',('downloaded',f'Audio/{id}.mp3','youtube_thumbnail',id))
                db.commit()
                n+=1
            finally:
                db.close()
    def export_status(self,db,path):
        path.write_text('track,status\n'+'\n'.join(f'{id},{st}' for id,st in db.execute('SELECT id,status FROM tracks')))

class BatchTests(TestCase):
    def test_csv_exact_ids_and_year(self):
        rows=runner.parse_snapshot(runner.DATA)
        self.assertEqual(len(rows),152)
        self.assertEqual(len({r['Track ID'] for r in rows}),152)
        self.assertEqual(sum(r['링크검증']=='검증완료' for r in rows),151)
    def test_batch_skips_six_and_collects_145_without_duplicates(self):
        remote={f'ODF{j:06d}':f'ODF{j:06d}.mp3' for j in [1,2,3,4,5,11]}
        state={'uploaded':False,'upload_calls':0,'checks':0}
        def fake_remote(_):
            if state['uploaded']:
                return {**remote, **{f'ODF{j:06d}':f'ODF{j:06d}.mp3' for j in range(1,308) if f'ODF{j:06d}' in {r['Track ID'] for r in runner.parse_snapshot(runner.DATA)} and j!=55}}
            return remote
        def fake_run(args,timeout=600):
            if args[1]=='copy':
                state['uploaded']=True
                state['upload_calls']+=1
            if args[1]=='check': state['checks']+=1
            return 'ok'
        with TemporaryDirectory() as tmp:
            with patch.object(runner,'get_worker',return_value=FakeWorker()),\
                 patch.object(runner,'remote_files',side_effect=fake_remote),\
                 patch.object(runner,'probe_youtube_access',return_value=None),\
                 patch.object(runner,'run',side_effect=fake_run),\
                 patch.object(runner.shutil,'which',return_value='fake'),\
                 patch.object(runner.importlib.util,'find_spec',return_value=object()),\
                 patch.object(sys,'argv',['run_2026.py','--root',tmp,'--skip-cover-repair']):
                with contextlib.redirect_stdout(io.StringIO()):
                    rc=runner.main()
            self.assertEqual(rc,0)
            report=json.loads((Path(tmp)/'2026'/'odify_2026_report.json').read_text())
            self.assertEqual(report['total_tracks'],152)
            self.assertEqual(report['remote_audio_count'],151)
            self.assertEqual(state['upload_calls'],1)
            self.assertEqual(state['checks'],1)
            self.assertIn('ODF000055',report['pending_link_review'])
            new_files=list((Path(tmp)/'2026'/'Audio').glob('*.mp3'))
            self.assertEqual(len(new_files),145)
            self.assertTrue(all(x.stem not in remote for x in new_files))

if __name__=='__main__': main()
