import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
from types import SimpleNamespace

spec=importlib.util.spec_from_file_location('odify_charts',Path(__file__).parents[1]/'odify_charts.py')
charts=importlib.util.module_from_spec(spec);spec.loader.exec_module(charts)
publisher_spec=importlib.util.spec_from_file_location('publish_chart_index',Path(__file__).parents[1]/'publish_chart_index.py')
publisher=importlib.util.module_from_spec(publisher_spec);publisher_spec.loader.exec_module(publisher)

class ChartTests(unittest.TestCase):
    def test_parallel_quick_download_embeds_cover_without_querying_lyrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'Audio').mkdir()
            row={'id':'123','artist':'Artist','title':'Song Name','album':'Album','country':'kr','rank':1,
                 'duration_ms':180000,'status':'LINK_SELECTED','video_url':'https://youtu.be/abcdefghijk',
                 'appearances':[{'id':'123','country':'kr','rank':1}]}
            info={'id':'abcdefghijk','title':'Artist - Song Name (Lyrics)','duration':180,
                  '_odifySelectionPolicy':'duration-title-prefer-official-then-lyrics-v1'}
            def download(db,track,folder,*args,**kwargs):
                self.assertFalse(args[0], 'Network enrichment must be deferred')
                kwargs['metadata_validator'](track[-1],track[2],info)
                (folder/'Audio'/'CHART123.mp3').write_bytes(b'new downloaded audio')
            args=SimpleNamespace(download_only=True,config=None,folder_id=None,rclone='unavailable')
            with patch.object(charts.audio,'download_one',side_effect=download), \
                 patch.object(charts,'quick_cover',return_value=(b'jpeg cover','chart')), \
                 patch.object(charts.audio,'tag_audio') as tag, \
                 patch('mutagen.File',return_value=SimpleNamespace(info=SimpleNamespace(length=180))), \
                 patch.object(charts.audio,'try_lyrics',side_effect=AssertionError('Lyrics deferred')):
                charts.collect_quick(args,root,[row],{'123':{'metadata':info}})
                self.assertEqual(b'jpeg cover',tag.call_args.args[5])
                self.assertIsNone(tag.call_args.args[6])
            self.assertEqual('DOWNLOADED_VERIFIED',row['status'])
            self.assertEqual(64,len(row['sha256']))
    def test_quick_chart_policy_accepts_lyrics_without_verified_channel_and_rejects_live(self):
        row={'title':'Song Name','artist':'Artist','duration_ms':180000}
        info={'id':'abcdefghijk','title':'Artist - Song Name (Lyrics)','duration':181,'channel':'Lyrics fan'}
        self.assertTrue(charts.quick_candidate_matches(row,info))
        self.assertTrue(charts.quick_candidate_matches({**row,'title':'Song Name (Mixed)'},info))
        for change in ({'duration':190},{'title':'Artist - Song Name (Live concert)'},
                       {'title':'Artist - Song Name (Instrumental)'},{'title':'Different Song'},
                       {'title':'Artist - Song Name remix'},{'title':'Artist - Song Name【ライブ映像】'}):
            self.assertFalse(charts.quick_candidate_matches(row,{**info,**change}))
        self.assertLess(charts.quick_rank(row,{**info,'title':'Artist - Song Name Official Audio'}),
                        charts.quick_rank(row,info))
        with self.assertRaises(ValueError):
            charts.validate_quick_metadata(row,'https://youtube.com/watch?v=XXXXXXXXXXX','Song Name',info)
    def test_official_visuals_require_owner_identity_exact_title_and_tighter_runtime(self):
        expected={'Track ID':'CHART123','아티스트':'Artist','곡명':'Song'}
        info={'id':'abcdefghijk','title':'Artist - Song (Lyric Video)','duration':181,
              'channel':'Artist','channel_is_verified':True}
        self.assertTrue(charts.official_visual_matches(expected,info,180))
        for changed in ({'channel':'Lyrics Channel'},{'channel_is_verified':False}, {'duration':184},
                        {'title':'Artist - Song (Live)'},{'title':'Artist - Song (English ver.)'}):
            self.assertFalse(charts.official_visual_matches(expected,{**info,**changed},180))
    def test_download_only_never_requires_drive_config_or_runs_rclone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            row={'id':'123','artist':'Artist','title':'Song','album':'Album','country':'kr','rank':1,
                 'duration_ms':180000,'status':'QUEUED','appearances':[{'id':'123','country':'kr','rank':1}]}
            (root/'chart_status.json').write_text(json.dumps({'tracks':[row]}),encoding='utf-8')
            (root/'Metadata').mkdir()
            def download(db,track,folder,*args,**kwargs):
                (folder/'Audio').mkdir(exist_ok=True)
                (folder/'Audio'/'CHART123.mp3').write_bytes(b'validated audio for transport test')
            args=SimpleNamespace(root=root,catalog=None,plan=False,links_only=False,reuse_only=False,
                download_only=True,retry_held=True,config=None,folder_id=None,rclone='not-installed',snapshots=None)
            with patch.object(charts,'variants',return_value=[('Artist','Song')]), \
                 patch.object(charts,'resolve',return_value=('https://youtu.be/abcdefghijk','Artist','Song',{})), \
                 patch.object(charts.audio,'download_one',side_effect=download), \
                 patch.object(charts,'execute',side_effect=AssertionError('No Drive requests')):
                charts.collect(args)
            result=json.loads((root/'chart_status.json').read_text(encoding='utf-8'))
            self.assertEqual('DOWNLOADED_VERIFIED',result['tracks'][0]['status'])
            self.assertEqual(64,len(result['tracks'][0]['sha256']))
    def test_reuse_only_is_network_free_and_preserves_holds_and_versions(self):
        rows=[{'id':str(i),'artist':'Artist','title':title,'status':status,'file_id':''}
            for i,title,status in [(1,'Song','QUEUED'),(2,'Song (English ver.)','QUEUED'),(3,'Song','HELD_REVIEW')]]
        with tempfile.TemporaryDirectory() as tmp,patch.object(charts,'fetch',side_effect=AssertionError('Network prohibited')):
            self.assertEqual(1,charts.reuse_cached_owned(rows,{'artist|song':'OwnedDriveID123'},Path(tmp)))
        self.assertEqual(['REUSED_EXISTING','QUEUED','HELD_REVIEW'],[r['status'] for r in rows])
    def test_age_gated_recording_is_held_without_treating_it_as_site_wide_block(self):
        with patch.object(charts,'variants',return_value=[('Artist','Song')]), \
             patch.object(charts,'search_candidates',return_value=[{'id':'abcdefghijk'}]), \
             patch.object(charts,'inspected_video',side_effect=RuntimeError('Sign in to confirm your age')):
            with self.assertRaisesRegex(ValueError,'Age-restricted recording held') as held:
                charts.resolve({'id':'123'},Path('.'))
        self.assertFalse(charts.access_blocked(str(held.exception)))
    def test_site_wide_challenge_still_stops_collection(self):
        with patch.object(charts,'variants',return_value=[('Artist','Song')]), \
             patch.object(charts,'search_candidates',return_value=[{'id':'abcdefghijk'}]), \
             patch.object(charts,'inspected_video',side_effect=RuntimeError('Sign in to confirm you are not a bot')):
            with self.assertRaises(RuntimeError) as blocked:
                charts.resolve({'id':'123'},Path('.'))
        self.assertTrue(charts.access_blocked(str(blocked.exception)))
    def test_official_audio_wrappers_keep_artist_and_version_checks(self):
        expected={'Track ID':'CHART123','아티스트':'Artist','곡명':'Song'}
        info={'id':'abcdefghijk','title':'Artist - Song (Official Audio)','duration':180,
              'channel':'Artist','artist':'Artist','uploader_verified':True}
        self.assertTrue(charts.chart_candidate_matches(expected,info))
        self.assertTrue(charts.chart_candidate_matches(expected,{**info,'title':'Artist - "Song" Official Audio',
            'uploader_verified':None,'channel_is_verified':True}))
        for changed in ({'artist':'Other','channel':'Other'}, {'title':'Artist - Song (English ver.) (Official Audio)'},
                        {'title':'Artist - Song (Live) (Official Audio)'}, {'uploader_verified':False}):
            self.assertFalse(charts.chart_candidate_matches(expected,{**info,**changed}))
    def test_resolver_reuses_metadata_without_a_second_inspection(self):
        info={'id':'abcdefghijk','title':'Song','track':'Song','artist':'Artist','channel':'Artist - Topic','duration':180}
        with patch.object(charts,'variants',return_value=[('Artist','Song'),('Artist','Alias')]), \
             patch.object(charts,'search_candidates',return_value=[{'id':'abcdefghijk','duration':180}]) as search, \
             patch.object(charts,'inspected_video',return_value=info) as inspect, \
             patch.object(charts.audio,'inspect_audio_video',side_effect=AssertionError('Duplicate inspection')):
            result=charts.resolve({'id':'123','duration_ms':180000},Path('.'))
        self.assertEqual(info,result[3]);self.assertEqual(1,search.call_count);self.assertEqual(1,inspect.call_count)
    def test_fast_link_plan_never_approves_or_downloads_candidates(self):
        rows=[{'id':'123','artist':'Artist','title':'Song','status':'QUEUED','file_id':'','video_url':''}]
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(charts,'search_candidates',return_value=[{'id':'abcdefghijk','title':'Song'}]), \
             patch.object(charts,'inspected_video',side_effect=AssertionError('No full extraction')):
            result=charts.prepare_link_plan(Path(tmp),rows)
            self.assertEqual('CANDIDATE_ONLY',result['123']['link_status'])
            self.assertEqual('',result['123']['verified_url'])
        self.assertEqual('QUEUED',rows[0]['status'])
    def test_only_ready_owned_ids_enter_the_append_only_chart_index(self):
        def row(status,identity):
            return {'status':status,'file_id':identity,'artist':'Artist','title':'Song',
                    'appearances':[{'id':'123'},{'id':'456'}]}
        status={'tracks':[row('HELD_REVIEW','A'*20),row('UPLOADED_VERIFIED','B'*20),row('REUSED_EXISTING','bad/id')]}
        self.assertEqual({'123','456'},set(publisher.mappings(status)))
        self.assertTrue(all(x['driveId']=='B'*20 for x in publisher.mappings(status).values()))
    def page(self,n=100,duplicate=False):
        items=[{'rankingText':str(i+1),'title':f'Song {i}','artistName':'Artist',
                'duration':180000,'contentDescriptor':{'kind':'song','identifiers':{'storeAdamID':str(i if not duplicate else 1)}},
                'artwork':{'dictionary':{'url':'https://is1-ssl.mzstatic.com/{w}x{h}.{f}'}},'tertiaryLinks':[]}
               for i in range(n)]
        return '<script type="application/json" id="serialized-server-data">'+json.dumps({'data':[{'items':items}]})+'</script>'
    def test_requires_one_hundred_real_distinct_ranked_entries(self):
        self.assertEqual(100,len(charts.playlist_rows(self.page(),'kr')))
        for raw in (self.page(99),self.page(100,True)):
            with self.assertRaises(ValueError):charts.playlist_rows(raw,'kr')
    def test_preserves_artist_rank_and_duration_without_fabricating_genre(self):
        row=charts.playlist_rows(self.page(),'jp')[0]
        self.assertEqual(('Artist',1,180000,[]),(row['artist'],row['rank'],row['duration_ms'],row['genres']))
        self.assertIn('600x600.jpg',row['artwork'])
    def test_unicode_and_punctuation_share_identity_but_versions_do_not(self):
        self.assertEqual(charts.key({'artist':'NewJeans','title':'Ｈｙｐｅ Ｂｏｙ'}),charts.key({'artist':'newjeans','title':'Hype Boy'}))
        self.assertNotEqual(charts.key({'artist':'A','title':'Song'}),charts.key({'artist':'A','title':'Song (English ver.)'}))
if __name__=='__main__':unittest.main()
