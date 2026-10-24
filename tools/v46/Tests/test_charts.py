import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('odify_charts',Path(__file__).parents[1]/'odify_charts.py')
charts=importlib.util.module_from_spec(spec);spec.loader.exec_module(charts)
publisher_spec=importlib.util.spec_from_file_location('publish_chart_index',Path(__file__).parents[1]/'publish_chart_index.py')
publisher=importlib.util.module_from_spec(publisher_spec);publisher_spec.loader.exec_module(publisher)

class ChartTests(unittest.TestCase):
    def test_age_gated_recording_is_held_without_treating_it_as_site_wide_block(self):
        listing=json.dumps({'entries':[{'id':'abcdefghijk'}]})
        with patch.object(charts,'variants',return_value=[('Artist','Song')]), \
             patch.object(charts,'execute',side_effect=[listing,RuntimeError('Sign in to confirm your age')]):
            with self.assertRaisesRegex(ValueError,'Age-restricted recording held') as held:
                charts.resolve({'id':'123'},Path('.'))
        self.assertFalse(charts.access_blocked(str(held.exception)))
    def test_site_wide_challenge_still_stops_collection(self):
        listing=json.dumps({'entries':[{'id':'abcdefghijk'}]})
        with patch.object(charts,'variants',return_value=[('Artist','Song')]), \
             patch.object(charts,'execute',side_effect=[listing,RuntimeError('Sign in to confirm you are not a bot')]):
            with self.assertRaises(RuntimeError) as blocked:
                charts.resolve({'id':'123'},Path('.'))
        self.assertTrue(charts.access_blocked(str(blocked.exception)))
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
