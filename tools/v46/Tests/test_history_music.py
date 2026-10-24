import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('history_music',Path(__file__).parents[1]/'history_music.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
rspec=importlib.util.spec_from_file_location('runtime_search',Path(__file__).parents[1]/'history_music_runtime_search.py')
r=importlib.util.module_from_spec(rspec);rspec.loader.exec_module(r)
bspec=importlib.util.spec_from_file_location('history_background',Path(__file__).parents[1]/'history_background.py')
bg=importlib.util.module_from_spec(bspec);bspec.loader.exec_module(bg)

def row(identity='one'):
    return dict(id=identity,title='Song',artist='Artist',country='kr',rank=1,status='QUEUED',duration_ms=180000,
        appearances=[dict(year_month='2024-01',country='kr',rank=1),dict(year_month='2024-02',country='jp',rank=7)])

class HistoryMusicTests(unittest.TestCase):
    def test_finalizer_recovers_pool_without_overwriting_earlier_monthly_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pool=root/'Audio/CHARTone.mp3';pool.parent.mkdir();pool.write_bytes(b'new verified')
            existing=root/'2024/01/kr/Audio/HISTone.mp3';existing.parent.mkdir(parents=True);existing.write_bytes(b'old preserved')
            item={**row(),'status':'DOWNLOADED_VERIFIED','sha256':m.digest(pool)}
            m.save(root,[item]);rows,errors=bg.finalize(root)
            self.assertEqual([],errors);self.assertEqual(b'old preserved',existing.read_bytes())
            self.assertEqual(b'new verified',(root/rows[0]['local_path']).read_bytes())
            self.assertTrue(rows[0]['recovered_without_overwrite'])
    def test_background_stops_source_requests_but_age_gate_is_track_specific(self):
        self.assertTrue(bg.source_blocked([{'error':'HTTP Error 429: Too Many Requests'}]))
        self.assertTrue(bg.source_blocked([{'error':"Sign in to confirm you're not a bot"}]))
        self.assertFalse(bg.source_blocked([{'error':'Sign in to confirm your age'}]))

    def test_shared_apple_recording_reuses_verified_history_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path=root/'one.mp3';path.write_bytes(b'original')
            owned={**row('owned'),'status':'DOWNLOADED_VERIFIED','local_path':'one.mp3',
                   'sha256':m.digest(path),'_apple_song_ids':['123']}
            other={**row('same'),'_apple_song_ids':['123']};m.reuse_history(root,[owned,other])
            self.assertEqual('REUSED_HISTORY',other['status']);self.assertEqual('one.mp3',other['local_path'])

    def test_runtime_consensus_requires_distinct_matching_artist_title_candidates(self):
        song=row();one=dict(id='abcdefghijk',title='Artist - Song (Official Audio)',duration=180,channel='Artist')
        two=dict(id='lmnopqrstuv',title='Artist - Song (Lyrics)',duration=181,channel='Fan')
        self.assertIsNone(r.consensus(song,[one]));self.assertIsNone(r.consensus(song,[one,one]))
        self.assertEqual(181000,r.consensus(song,[one,two])['duration_ms'])
        self.assertIsNone(r.consensus(song,[one,{**two,'title':'Artist - Song (Live)'}]))
        self.assertIsNone(r.consensus(song,[one,{**two,'duration':190}]))

    def test_months_reference_one_verified_file_and_preserve_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'history';root.mkdir();original=Path(tmp)/'original.mp3';original.write_bytes(b'original audio')
            song=row();m.place(root,song,original);song['status']='REUSED_LOCAL';m.save(root,[song])
            self.assertEqual(b'original audio',original.read_bytes())
            self.assertEqual(1,len(list(root.rglob('*.mp3'))))
            self.assertIn('2024\\01\\kr\\Audio',song['local_path'])
            manifest=(root/'2024/02/jp/download_status.csv').read_text('utf-8-sig')
            self.assertIn('REUSED_LOCAL',manifest);self.assertIn(song['local_path'],manifest)
            self.assertIn('HISTone.mp3',(root/'2024/02/jp/available.m3u8').read_text('utf-8'))

    def test_existing_destination_is_protected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'source.mp3';source.write_bytes(b'new')
            target=root/'2024/01/kr/Audio/HISTone.mp3';target.parent.mkdir(parents=True);target.write_bytes(b'existing')
            with self.assertRaises(ValueError):m.place(root,row(),source)
            self.assertEqual(b'existing',target.read_bytes())

    def test_batch_worker_cannot_replace_the_full_queue_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);rows=[row('active'),{**row('finished'),'status':'REUSED_LOCAL'}]
            observed=[]
            def collect(args,folder,batch,selected):
                m.w.snapshot(folder,batch,True,'during transfer')
                observed.append(len(json.loads((root/'chart_status.json').read_text('utf-8'))['tracks']))
                batch[0]['status']='HELD_REVIEW'
            with patch.object(m.w,'quick_links',return_value={}),patch.object(m.w,'collect_quick',side_effect=collect):
                m.run_download(root,rows,1)
            self.assertEqual([2],observed)
            self.assertEqual(2,len(json.loads((root/'chart_status.json').read_text('utf-8'))['tracks']))

if __name__=='__main__':unittest.main()
