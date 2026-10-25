import importlib.util,json,sqlite3,tempfile,unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
h=load('odify_recent_history');a=load('odify_recent_albums');b=load('odify_history_bugs');j=load('odify_history_japan_albums')

class RecentHistoryTests(unittest.TestCase):
    def test_circle_period_and_complete_rank_validation(self):
        payload={'ResultStatus':'OK','FormToMap':{'hitYear':'2024','targetTime':'01'},'List':{
            str(i):{'SERVICE_RANKING':str(i),'SONG_NAME':f'Song {i}','ARTIST_NAME':'Artist','ALBUM_NAME':'Album'} for i in range(1,101)}}
        self.assertEqual(100,len(h.circle_entries(payload,'2024-01')))
        with self.assertRaises(ValueError):h.circle_entries(payload,'2024-02')
        payload['List']['100']['SERVICE_RANKING']='1'
        with self.assertRaises(ValueError):h.circle_entries(payload,'2024-01')

    def test_japan_actual_publication_date_and_future_rejection(self):
        raw='<p class="date">[ 2026/10/21 公開]</p>'+''.join(
            f'<tr><td headers="rank"><span>{i}</span></td><p class="musuc_title">Song &amp; {i}</p><p class="artist_name"><a>Artist</a></p></tr>' for i in range(1,101))
        published,rows=h.japan_entries(raw,date(2026,10,25))
        self.assertEqual('2026-10-21',published);self.assertEqual('Song & 1',rows[0]['title'])
        with self.assertRaises(ValueError):h.japan_entries(raw,date(2026,10,20))

    def test_weighted_monthly_aggregate_preserves_sources_and_marks_current_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            con=h.connect(Path(tmp)/'catalog.db')
            try:
                for day,rank in [('2026-10-07',1),('2026-10-14',100)]:
                    h.catalog.snapshot(con,'official','jp',day,[{'rank':rank,'title':'A','artist':'Artist'},
                        {'rank':2,'title':'B','artist':'Artist'}])
                con.commit();before=con.execute("select count(*) from chart_entry").fetchone()[0]
                h.derive(con,'jp','official','2026-10',date(2026,10,25));con.commit()
                self.assertEqual('B',con.execute('select title from recent_monthly_chart where rank=1').fetchone()[0])
                self.assertEqual('month_to_date',con.execute('select status from history_month').fetchone()[0])
                self.assertEqual(4,con.execute("select count(*) from chart_entry ce join chart_edition e on e.id=ce.edition_id where e.provider='official'").fetchone()[0])
                self.assertEqual(198,con.execute('select max(points) from history_monthly_metric').fetchone()[0])
                h.derive(con,'jp','official','2026-10',date(2026,10,25));con.commit()
                self.assertEqual(before+2,con.execute('select count(*) from chart_entry').fetchone()[0])
            finally:con.close()

    def test_symbol_only_song_title_is_not_an_empty_recording(self):
        with tempfile.TemporaryDirectory() as tmp:
            con=h.connect(Path(tmp)/'catalog.db')
            try:
                first=h.catalog.recording(con,'^_^','ちゃんみな')
                self.assertEqual(first,h.catalog.recording(con,'^_^','ちゃんみな'))
                self.assertNotEqual(first,h.catalog.recording(con,'!!!','ちゃんみな'))
                with self.assertRaises(ValueError):h.catalog.recording(con,' ','Artist')
            finally:con.close()

class AlbumHistoryTests(unittest.TestCase):
    def test_latin_artist_diacritic_and_japan_heart_markup_alias(self):
        self.assertTrue(a.artist_keys('Beyonce') & a.artist_keys('Beyoncé'))
        self.assertEqual(a.title_key('総意(ハート)So Free'),a.title_key('総意♡So Free'))

    def test_bilingual_title_alias_and_non_audio_album_component(self):
        self.assertIn('everydayeverymoment',a.title_keys('모든 날, 모든 순간 (Every day, Every Moment)'))
        song={'trackId':1,'kind':'song','discNumber':1,'trackNumber':1}
        video={'trackId':2,'kind':'music-video','discNumber':2,'trackNumber':1}
        self.assertEqual('complete_audio',a.album_status(2,[song],[song,video]))
        self.assertEqual('partial',a.album_status(3,[song],[song,video]))
        self.assertEqual('partial',a.album_status(2,[song,{**song,'trackId':3}],[song,video]))

    def test_matching_preserves_artist_and_version_identity(self):
        target={'title':'Song','artist':'Artist','markets':'us','id':'one'}
        song={'trackName':'Song (feat. Guest)','artistName':'Artist','artistId':1,'collectionId':3,'_market':'us','collectionName':'Album','trackCount':10}
        self.assertEqual(song,a.match_recording(target,{'song':[song]},{}))
        self.assertIsNone(a.match_recording({**target,'artist':'Other'},{'song':[song]},{}))
        self.assertIsNone(a.match_recording({**target,'title':'Song (Live)'},{'songlive':[song]},{}))

    def test_public_album_ignores_related_album_tracks(self):
        def track(album,identity,position):
            return {'title':f'Song {identity}','artistName':'Artist','trackNumber':position,'discNumber':1,
                'contentDescriptor':{'kind':'song','identifiers':{'storeAdamID':str(identity)},
                    'url':f'https://music.apple.com/us/album/album/{album}?i={identity}'}}
        payload=[track(123,1,1),track(123,2,2),track(456,3,1)]
        raw=('<script id="serialized-server-data">'+json.dumps(payload)+'</script>').encode()
        with patch.object(h,'request',return_value=raw):pass
        with patch.object(a.h,'request',return_value=raw):
            found=a.public_album(Path('.'),'us','123',{'artistName':'Artist','artistId':4})
        self.assertEqual({'1','2'},{str(x['trackId']) for x in found})

    def test_incomplete_album_is_not_claimed_as_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            con=h.connect(Path(tmp)/'catalog.db');con.executescript(a.SCHEMA)
            try:
                rid=h.catalog.recording(con,'Song','Artist');con.commit()
                target={'id':rid};song={'trackId':1,'artistId':4,'collectionId':123,'artistName':'Artist',
                    'trackName':'Song','collectionName':'Album','trackCount':3,'trackNumber':1,'_market':'us'}
                api=type('API',(),{'calls':0,'get':lambda *args:{'results':[
                    {'wrapperType':'collection','collectionId':123,'collectionName':'Album','artistName':'Artist','trackCount':3},
                    {**song,'kind':'song','wrapperType':'track'}]}})()
                with patch.object(a,'public_album',return_value=[]):a.expand(con,api,Path(tmp),[(target,song)])
                self.assertEqual(('partial',3,1),tuple(con.execute('select status,expected_tracks,observed_tracks from history_album_audit').fetchone()))
                self.assertEqual(1,con.execute('select count(*) from album_track').fetchone()[0])
            finally:con.close()

class BugsHistoryTests(unittest.TestCase):
    def test_unavailable_track_title_is_retained_as_metadata(self):
        raw='''<tr albumId="123" trackId="456"><input name="_isStream" disabled="disabled">
          <input name="check" disc_id="1" title="Song (CD Only)">
          <p class="trackIndex"><em>1</em></p><p class="title"><span>Song (CD Only)</span></p>
          <p class="artist"><a title="Artist">Artist</a></p></tr>'''
        song=b.parse_tracks(raw,'123')[0]
        self.assertEqual('Song (CD Only)',song['title']);self.assertFalse(song['available'])

    def test_local_artist_alias_is_required_and_instrumental_rejected(self):
        target={'title':'사랑인가 봐','artist':'멜로망스(Melomance)'}
        song={'title':'사랑인가 봐','artist':'멜로망스(MeloMance)','artist_names':[]}
        self.assertEqual(song,b.choose(target,[song]))
        self.assertIsNone(b.choose(target,[{**song,'artist':'Other'}]))
        self.assertIsNone(b.choose(target,[{**song,'title':'사랑인가 봐 (Instrumental)'}]))

    def test_album_identity_expected_count_and_positions_are_verified(self):
        header={'@type':'MusicAlbum','name':'Album','url':['https://music.bugs.co.kr/album/123']}
        def row(identity,position):
            return f'''<tr albumId="123" trackId="{identity}"><input disc_id="1">
            <p class="trackIndex"><em>{position}</em></p>
            <p class="title"><a title="Song {identity}">Song</a></p>
            <p class="artist"><a title="Artist">Artist</a></p></tr>'''
        raw='<script type="application/ld+json">'+json.dumps(header)+'</script><h1>수록곡</h1><p>(2)</p>'+row(1,1)+row(2,2)
        self.assertEqual('complete',b.parse_album(raw,'123')[3])
        self.assertEqual('partial',b.parse_album(raw.replace('<em>2</em>','<em>1</em>'),'123')[3])
        with self.assertRaises(ValueError):b.parse_album(raw,'456')

class JapanReleaseTests(unittest.TestCase):
    def test_only_main_release_tracks_and_numbering_are_used(self):
        raw='''<hgroup><h3>Album</h3><h4><a>Artist</a></h4></hgroup>
          <div id="tracklist"><table><th>Disc01</th><tr><td>01.Song</td></tr>
          <tr><td>02.Other</td></tr></table></div><!-- / #tracklist -->
          <div><table><th>Disc01</th><tr><td>01.Related album</td></tr></table></div>'''
        data=j.parse(raw);self.assertEqual(2,len(data['tracks']));self.assertEqual('published_tracklist',data['status'])
        self.assertEqual('partial',j.parse(raw.replace('02.Other','03.Other'))['status'])

if __name__=='__main__':unittest.main()
