import importlib.util,json,sqlite3,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from contextlib import closing

spec=importlib.util.spec_from_file_location('album_music',Path(__file__).parents[1]/'album_music.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class AlbumMusicTests(unittest.TestCase):
    def test_catalog_dates_and_dedup_preserve_existing_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            db=Path(tmp)/'catalog.db';root=Path(tmp)/'albums';c=m.m.a.h.connect(db);c.executescript(m.m.a.SCHEMA)
            rid=m.m.a.c.recording(c,'Song','Artist')
            edition='test-edition'
            # A minimal explicit chart table avoids building unrelated feeds.
            c.execute('DROP VIEW recent_monthly_chart');c.execute('CREATE TABLE recent_monthly_chart(recording_id TEXT)')
            c.execute('INSERT INTO recent_monthly_chart VALUES(?)',(rid,))
            for name,day in [('First','2024-01-10'),('Second','2025-02-10'),('Old','2023-12-31'),('Future','2026-12-01')]:
                aid=m.m.a.c.album(c,name,'Artist',day)
                c.execute('INSERT INTO album_track VALUES(?,?,1,1)',(aid,rid))
                c.execute('INSERT INTO history_album_link VALUES(?,?,?,?,?)',(rid,aid,'123','us','fixture'))
                c.execute('INSERT INTO history_source_album_track VALUES(?,?,?,?,?,?,?)',('apple:us',aid,'123',aid,rid,1,1))
            payload=dict(trackId=123,artistId=10,artistName='Artist',trackName='Song',collectionId=42,collectionName='First',trackTimeMillis=180000)
            c.execute('INSERT INTO history_apple_track VALUES(?,?,?,?)',('us','123','10',json.dumps(payload)))
            c.commit();before=list(c.execute('SELECT * FROM history_album_link'));c.close()
            rows=m.prepare(db,root,'2026-10-25')
            self.assertEqual(1,len(rows));self.assertEqual(2,len(rows[0]['appearances']))
            self.assertEqual(180000,rows[0]['duration_ms'])
            with closing(sqlite3.connect(db)) as c:
                self.assertEqual([tuple(r) for r in before],list(c.execute('SELECT * FROM history_album_link')))
                self.assertEqual(1,c.execute('SELECT count(*) FROM album_audio_evidence').fetchone()[0])
            self.assertEqual(2,json.loads((root/'album_scope.json').read_text())['albums'])

    def test_reuses_verified_history_bytes_without_altering_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'albums';root.mkdir();history=Path(tmp)/'history';history.mkdir()
            audio=history/'song.mp3';audio.write_bytes(b'original audio')
            source=dict(id='one',status='DOWNLOADED_VERIFIED',local_path='song.mp3',sha256=m.m.digest(audio),duration_ms=180000)
            (history/'chart_status.json').write_text(json.dumps(dict(tracks=[source])))
            row=dict(id='one',status='QUEUED',duration_ms=180000,appearances=[dict(year_month='2024-01',country='Album',rank=1)])
            m.reuse_history([row],root,history)
            self.assertEqual('REUSED_HISTORY',row['status']);self.assertEqual(b'original audio',audio.read_bytes())
            self.assertEqual(source['sha256'],m.m.digest(root/row['local_path']))

    def test_changed_history_bytes_are_never_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'albums';root.mkdir();history=Path(tmp)/'history';history.mkdir()
            (history/'song.mp3').write_bytes(b'changed')
            (history/'chart_status.json').write_text(json.dumps(dict(tracks=[dict(id='one',status='DOWNLOADED_VERIFIED',local_path='song.mp3',sha256='bad')])))
            row=dict(id='one',status='QUEUED')
            m.reuse_history([row],root,history)
            self.assertEqual('QUEUED',row['status']);self.assertNotIn('local_path',row)

    def test_audio_gate_is_global_but_age_gate_is_track_specific(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'chart_status.json'
            p.write_text(json.dumps(dict(tracks=[dict(error='Sign in to confirm your age')])))
            self.assertFalse(m.source_paused(root))
            p.write_text(json.dumps(dict(tracks=[dict(error='[SSL: UNEXPECTED_EOF_WHILE_READING] Sign in to confirm you are not a bot')])))
            self.assertTrue(m.source_paused(root))
            p.write_text(json.dumps(dict(tracks=[dict(error="Sign in to confirm you're not a bot")])))
            self.assertTrue(m.source_paused(root))
            p.write_text(json.dumps(dict(tracks=[dict(error='[SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred in violation of protocol')])))
            self.assertFalse(m.source_paused(root))

    def test_cached_link_work_never_calls_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);row=dict(id='one',artist='Artist',title='Song',duration_ms=180000,status='QUEUED')
            with patch.object(m.m.w,'execute',side_effect=AssertionError('No network permitted')),patch.object(m.m.w,'fetch',side_effect=AssertionError('No network permitted')):
                m.cached_links([row],root,root)
            self.assertEqual('QUEUED',row['status'])

    def test_proven_recovery_acknowledges_only_prior_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);error=dict(id='old',error="Sign in to confirm you're not a bot")
            (root/'chart_status.json').write_text(json.dumps(dict(tracks=[error])))
            fingerprint=m.m.hashlib.sha256((error['id']+'|'+error['error']).encode()).hexdigest()
            (root/'source_access.json').write_text(json.dumps(dict(acknowledged_errors=[fingerprint])))
            self.assertFalse(m.source_paused(root))
            (root/'chart_status.json').write_text(json.dumps(dict(tracks=[error,dict(id='new',error=error['error'])])))
            self.assertTrue(m.source_paused(root))

    def test_runtime_cache_never_rewrites_verified_audio(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'runtime_metadata.json').write_text(json.dumps(dict(one=dict(duration_ms=180000),two=dict(duration_ms=200000))))
            rows=[dict(id='one',duration_ms=0,status='NEED_METADATA'),dict(id='two',duration_ms=180000,status='DOWNLOADED_VERIFIED')]
            m.apply_album_runtimes(root,rows)
            self.assertEqual('QUEUED',rows[0]['status']);self.assertEqual(180000,rows[0]['duration_ms'])
            self.assertEqual('DOWNLOADED_VERIFIED',rows[1]['status']);self.assertEqual(180000,rows[1]['duration_ms'])

if __name__=='__main__':unittest.main()
