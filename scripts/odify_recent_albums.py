#!/usr/bin/env python3
"""Expand albums for the bounded monthly history; never touches playback links."""
from __future__ import annotations
import argparse,collections,importlib.util,json,re,time,sqlite3,unicodedata
from pathlib import Path
from urllib.parse import urlencode
from urllib.error import HTTPError,URLError

spec=importlib.util.spec_from_file_location('recent',Path(__file__).with_name('odify_recent_history.py'))
h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h);c=h.catalog
SCHEMA='''
CREATE TABLE IF NOT EXISTS history_apple_track(
 market TEXT,track_id TEXT,artist_id TEXT,payload TEXT,PRIMARY KEY(market,track_id));
CREATE TABLE IF NOT EXISTS history_artist_search(
 artist_key TEXT,market TEXT,status TEXT,PRIMARY KEY(artist_key,market));
CREATE TABLE IF NOT EXISTS history_artist_lookup(
 artist_id TEXT,market TEXT,PRIMARY KEY(artist_id,market));
CREATE TABLE IF NOT EXISTS history_artist_hint(
 artist_key TEXT,artist_id TEXT,source_url TEXT,PRIMARY KEY(artist_key,artist_id));
CREATE TABLE IF NOT EXISTS history_recording_enrichment(
 recording_id TEXT REFERENCES recording(id),market TEXT,status TEXT,reason TEXT,
 PRIMARY KEY(recording_id,market));
CREATE TABLE IF NOT EXISTS history_album_component(
 album_id TEXT REFERENCES album(id),market TEXT,component_id TEXT,position INTEGER,
 kind TEXT,title TEXT,source_url TEXT,PRIMARY KEY(album_id,market,component_id));
CREATE TABLE IF NOT EXISTS history_source_album_track(
 provider TEXT,source_album_id TEXT,source_track_id TEXT,album_id TEXT REFERENCES album(id),
 recording_id TEXT REFERENCES recording(id),disc INTEGER,position INTEGER,
 PRIMARY KEY(provider,source_album_id,disc,position));
'''

def main_artist(name):
    return re.split(r'\s+(?:featuring|feat\.?|ft\.?|with)\s+',name,maxsplit=1,flags=re.I)[0].strip()

def artist_keys(name):
    name=main_artist(name);keys={c.norm(name)}
    plain=re.sub(r'\([^()]+\)','',name).strip()
    if plain:keys.add(c.norm(plain))
    for value in re.findall(r'\(([^()]+)\)',name):
        if value and not re.search(r'feat|official|topic',value,re.I):keys.add(c.norm(value))
    keys|={''.join(ch for ch in unicodedata.normalize('NFKD',key) if not unicodedata.combining(ch))
           for key in list(keys) if key.isascii() or all(ord(ch)<0x250 for ch in key)}
    return keys-{''}

def title_key(name):
    name=name.replace('(ハート)','♥')
    value=re.split(r'\s*[\[(](?:feat\.?|with|ft\.?|prod\.?|narr\.)\s',name,maxsplit=1,flags=re.I)[0]
    return c.norm(value) or 'symbol:'+value.strip()

def title_keys(name):
    keys={title_key(name)}
    for part in re.findall(r'\(([^()]+)\)',name):
        if re.search(r'[A-Za-z]',part) and not re.search(r'feat|prod|remix|live|inst|ver\.|ost',part,re.I):
            keys.add(title_key(part))
    return keys

def items(raw):
    if isinstance(raw,dict):
        if all(raw.get(k) is not None for k in ['trackId','artistId','collectionId','trackName','artistName']):yield raw
        for value in raw.values():yield from items(value)
    elif isinstance(raw,list):
        for value in raw:yield from items(value)

def store(con,raw,market=None):
    count=0
    if isinstance(raw,dict):
        for value in raw.get('results',[]):
            if value.get('wrapperType')=='artist' and value.get('artistId'):
                region=re.search(r'music.apple.com/([a-z]{2})/',value.get('artistLinkUrl',''))
                place=market or (region[1] if region else 'us')
                con.execute('INSERT OR IGNORE INTO history_artist_lookup VALUES(?,?)',(str(value['artistId']),place))
    for song in items(raw):
        if song.get('kind') not in ('song',None):continue
        country=market or {'USA':'us','JPN':'jp','KOR':'kr'}.get(song.get('country'),'us')
        # Metadata only: do not persist preview audio or lyrical descriptions.
        safe={k:v for k,v in song.items() if k not in ('previewUrl','description','longDescription')}
        con.execute('INSERT OR REPLACE INTO history_apple_track VALUES(?,?,?,?)',
            (country,str(song['trackId']),str(song['artistId']),json.dumps(safe,ensure_ascii=False)))
        count+=1
    con.commit();return count

class Apple:
    def __init__(self,cache):self.cache=cache;self.last=0;self.calls=0
    def get(self,path,params):
        import hashlib
        url='https://itunes.apple.com/'+path+'?'+urlencode(params)
        cached=self.cache/(hashlib.sha256(url.encode()).hexdigest()+'.json')
        if cached.exists():return json.loads(cached.read_text(encoding='utf-8'))
        for attempt in range(3):
            time.sleep(max(0,3.2-(time.monotonic()-self.last)))
            self.last=time.monotonic();self.calls+=1
            try:return json.loads(h.request(self.cache,url))
            except (URLError,TimeoutError) as error:
                if isinstance(error,HTTPError) and error.code<500:raise
                if attempt==2:raise
                print('TRANSIENT API RETRY',attempt+1,type(error).__name__,flush=True)

def targets(con):
    return [dict(row) for row in con.execute('''SELECT r.id,r.title,a.name artist,
       GROUP_CONCAT(DISTINCT hm.country) markets,COUNT(*) popularity FROM history_month hm
       JOIN chart_entry ce ON ce.edition_id=hm.edition_id JOIN recording r ON r.id=ce.recording_id
       JOIN artist a ON a.id=r.artist_id GROUP BY r.id ORDER BY popularity DESC,r.id''')]

def library(con):
    index=collections.defaultdict(list);artists=collections.defaultdict(set)
    for row in con.execute('SELECT market,artist_id,payload FROM history_apple_track'):
        song=json.loads(row['payload']);song['_market']=row['market']
        for key in title_keys(song['trackName']):index[key].append(song)
        for key in artist_keys(song['artistName']):artists[key].add((row['artist_id'],row['market']))
    for key,identity in con.execute('SELECT artist_key,artist_id FROM history_artist_hint'):
        for market in ('kr','jp','us'):artists[key].add((identity,market))
    return index,artists

def artist_hints(con,cache,desired,artists):
    # IDs supplement discovery only; recording title/version/artist are still checked.
    names=sorted({main_artist(t['artist']) for t in desired if not any(k in artists for k in artist_keys(t['artist']))})
    for start in range(0,len(names),5):
        group=names[start:start+5]
        values=' '.join(json.dumps(name,ensure_ascii=False)+'@'+lang for name in group for lang in ('en','ko','ja'))
        query='SELECT DISTINCT ?label ?id WHERE { VALUES ?label { '+values+' } ?entity (rdfs:label|skos:altLabel) ?label; wdt:P2850 ?id. }'
        url='https://query.wikidata.org/sparql?'+urlencode(dict(query=query,format='json'))
        try:
            data=json.loads(h.request(cache,url));found=collections.defaultdict(set)
            for entry in data.get('results',{}).get('bindings',[]):
                identity=entry['id']['value'];name=entry['label']['value']
                if identity.isdigit():found[name].add(identity)
            with con:
                for name,ids in found.items():
                    if len(ids)==1:
                        for key in artist_keys(name):con.execute('INSERT OR IGNORE INTO history_artist_hint VALUES(?,?,?)',(key,next(iter(ids)),url))
        except Exception as error:
            print('ARTIST HINT DEFER',str(error)[:100],flush=True);break
    print('ARTIST ID HINTS',con.execute('SELECT count(*) FROM history_artist_hint').fetchone()[0],flush=True)

def match_recording(target,index,artists):
    names=artist_keys(target['artist']);known=set()
    for key in names:known.update(identity for identity,_ in artists.get(key,()))
    matches=[]
    for song in [s for key in title_keys(target['title']) for s in index.get(key,[])]:
        if c.version_label(target['title'])!=c.version_label(song['trackName']):continue
        if not (names & artist_keys(song['artistName']) or str(song['artistId']) in known):continue
        matches.append(song)
    if not matches:return None
    def score(song):
        album=song.get('collectionName','')
        compilation=bool(re.search(r'karaoke|tribute|greatest hits|compilation|^now |workout|playlist',album,re.I))
        return compilation, bool(re.search('deluxe|expanded|anniversary',album,re.I)),
    # A full album is preferred to an isolated single when both describe the same recording.
    return min(matches,key=lambda x:(*score(x),int(x.get('trackCount',0))<=2,
        x.get('_market') not in target['markets'].split(','),x.get('releaseDate','9999'),str(x['collectionId'])))

def public_album(cache,market,identity,expected):
    url=f'https://music.apple.com/{market}/album/{identity}'
    raw=h.request(cache,url).decode('utf-8')
    found=re.search(r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>',raw,re.S)
    if not found:return []
    result={}
    def visit(node):
        if isinstance(node,dict):
            descriptor=node.get('contentDescriptor') or {};uri=descriptor.get('url','')
            kind=descriptor.get('kind')
            belongs=bool(re.search('/'+re.escape(str(identity))+r'(?:\?|$)',uri)) or f' - {identity} - ' in str(node.get('id',''))
            if (kind in ('song','music-video') and node.get('title') and node.get('trackNumber') and belongs):
                songid=descriptor.get('identifiers',{}).get('storeAdamID')
                if songid:result[str(songid)]={'trackId':songid,'collectionId':identity,'trackName':node['title'],
                    'artistName':node.get('artistName') or expected.get('artistName',''),
                    'artistId':expected.get('artistId') if artist_keys(node.get('artistName','')) & artist_keys(expected.get('artistName','')) else None,
                    'collectionName':expected.get('collectionName',''),
                    'trackNumber':node['trackNumber'],'discNumber':node.get('discNumber',1),
                    'trackTimeMillis':node.get('duration'), 'kind':kind,'wrapperType':'track'}
            for value in node.values():visit(value)
        elif isinstance(node,list):
            for value in node:visit(value)
    visit(json.loads(found[1]));return list(result.values())

def album_status(expected,tracks,components):
    positions={(int(x.get('discNumber') or 1),int(x.get('trackNumber') or 0)) for x in tracks}
    valid=bool(tracks) and all(d>0 and p>0 for d,p in positions) and len(positions)==len(tracks)
    if expected>0 and valid and len(tracks)==expected:return 'complete'
    extras=[x for x in components if x.get('kind')=='music-video' and x.get('trackId')]
    observed=len({str(x['trackId']) for x in components if x.get('trackId')})
    if expected>0 and valid and observed==expected and extras:return 'complete_audio'
    return 'partial' if tracks else 'metadata_only'

def expand(con,api,cache,matched):
    albums={}
    for target,song in matched:
        market=song['_market'];identity=str(song['collectionId']);albums.setdefault((market,identity),song)
    waiting=[]
    for pair,song in albums.items():
        old=con.execute('SELECT status FROM history_album_audit WHERE apple_album_id=? AND market=?',(pair[1],pair[0])).fetchone()
        if not old or getattr(api,'retry_incomplete',False) and old['status'] in ('partial','metadata_only'):waiting.append((pair,song))
    by_market=collections.defaultdict(list)
    for pair,song in waiting:by_market[pair[0]].append((pair[1],song))
    finished=0
    for market,entries in by_market.items():
        for start in range(0,len(entries),50):
            group=entries[start:start+50]
            data=api.get('lookup',dict(id=','.join(x[0] for x in group),entity='song',limit=200,country=market))
            store(con,data,market)
            for identity,seed in group:
                header=next((x for x in data.get('results',[]) if x.get('wrapperType')=='collection' and str(x.get('collectionId'))==identity),seed)
                components=[x for x in data.get('results',[]) if x.get('wrapperType')=='track' and str(x.get('collectionId'))==identity]
                tracks=[x for x in components if x.get('kind')=='song']
                expected=int(header.get('trackCount') or seed.get('trackCount') or 0)
                positions={(x.get('discNumber',1),x.get('trackNumber')) for x in tracks}
                if not tracks or expected and len(positions)!=expected:
                    try:
                        alternative=public_album(cache,market,identity,header)
                        if len(alternative)>len(components):components=alternative
                        tracks=[x for x in components if x.get('kind')=='song']
                    except Exception as e:print('ALBUM PAGE DEFER',identity,str(e)[:100],flush=True)
                tracks=[x for x in tracks if x.get('trackName') and x.get('artistName') and int(x.get('trackNumber') or 0)>0]
                positions={(int(x.get('discNumber') or 1),int(x['trackNumber'])) for x in tracks}
                extras=[x for x in components if x.get('kind')!='song' and x.get('trackId')]
                status=album_status(expected,tracks,components)
                with con:
                    prior=con.execute("SELECT album_id,status FROM external_album WHERE provider='apple' AND ext_id=?",(identity,)).fetchone()
                    albumid=prior[0] if prior and prior[0] else c.album(con,header.get('collectionName') or seed['collectionName'],
                        header.get('artistName') or seed['artistName'],(header.get('releaseDate') or '')[:10])
                    for track in tracks:
                        rid=c.recording(con,track['trackName'],track['artistName'],source='apple',source_id=track['trackId'],
                            album=track.get('collectionName',''),release_date=(track.get('releaseDate') or '')[:10])
                        con.execute('INSERT OR IGNORE INTO album_track VALUES(?,?,?,?)',
                            (albumid,rid,int(track.get('discNumber') or 1),int(track['trackNumber'])))
                        con.execute('INSERT OR REPLACE INTO history_source_album_track VALUES(?,?,?,?,?,?,?)',
                            ('apple:'+market,identity,str(track['trackId']),albumid,rid,
                             int(track.get('discNumber') or 1),int(track['trackNumber'])))
                        if track.get('primaryGenreName'):con.execute('INSERT OR IGNORE INTO recording_genre VALUES(?,?,?)',(rid,track['primaryGenreName'],'apple'))
                    for component in extras:
                        con.execute('INSERT OR REPLACE INTO history_album_component VALUES(?,?,?,?,?,?,?)',
                            (albumid,market,str(component['trackId']),component.get('trackNumber'),component.get('kind'),
                             component.get('trackName'),component.get('trackViewUrl','')))
                    global_status='done' if status in ('complete','complete_audio') or prior and prior[1]=='done' else status
                    con.execute('INSERT OR REPLACE INTO external_album VALUES(?,?,?,?,CURRENT_TIMESTAMP)',('apple',identity,albumid,global_status))
                    con.execute('INSERT OR REPLACE INTO history_album_audit VALUES(?,?,?,?,?,?,?)',
                        (identity,market,albumid,expected,len(positions),status,f'https://music.apple.com/{market}/album/{identity}'))
                finished+=1
            print('ALBUMS',finished,'/',len(waiting),'API',api.calls,flush=True)
    with con:
        for target,song in matched:
            row=con.execute('SELECT album_id,status FROM history_album_audit WHERE apple_album_id=? AND market=?',
                (str(song['collectionId']),song['_market'])).fetchone()
            if row and row['album_id']:
                con.execute('INSERT OR REPLACE INTO history_album_link VALUES(?,?,?,?,?)',
                    (target['id'],row['album_id'],str(song['trackId']),song['_market'],'title/version and artist identity'))
                con.execute('INSERT OR REPLACE INTO history_recording_enrichment VALUES(?,?,?,?)',
                    (target['id'],song['_market'],row['status'],''))

def run(args):
    con=h.connect(args.db);con.executescript(SCHEMA);api=Apple(args.cache);args.cache.mkdir(parents=True,exist_ok=True)
    api.retry_incomplete=args.retry_incomplete
    for seed in [Path('.local/catalog-enrichment.json'),*args.seeds.glob('lookup_*.json')]:
        if seed.exists():
            try:store(con,json.loads(seed.read_text(encoding='utf-8')))
            except (ValueError,KeyError):pass
    for saved in args.cache.glob('*.json'):
        try:store(con,json.loads(saved.read_text(encoding='utf-8')))
        except (ValueError,KeyError):pass
    desired=targets(con);index,artists=library(con)
    if not (args.catalog_only or args.search_only):artist_hints(con,args.cache,desired,artists)
    index,artists=library(con)
    identities=collections.defaultdict(set)
    for target in ([] if args.catalog_only or args.search_only else desired):
        for key in artist_keys(target['artist']):
            for identity,market in artists.get(key,()):
                if market not in target['markets'].split(','):continue
                if not con.execute('SELECT 1 FROM history_artist_lookup WHERE artist_id=? AND market=?',(identity,market)).fetchone():identities[market].add(identity)
    for market,ids in identities.items():
        ids=sorted(ids)
        for start in range(0,len(ids),5):
            group=ids[start:start+5]
            raw=api.get('lookup',dict(id=','.join(group),entity='song',limit=200,country=market))
            store(con,raw,market)
            con.executemany('INSERT OR IGNORE INTO history_artist_lookup VALUES(?,?)',[(identity,market) for identity in group]);con.commit()
        print('ARTIST BATCH',market,len(ids),'API',api.calls,flush=True)
    index,artists=library(con)
    if not args.catalog_only:
        # A popular artist's 200-song catalog can omit a charting deep cut.
        # Search the remaining individual titles, keeping the same identity checks.
        remaining=[]
        for target in desired:
            if match_recording(target,index,artists):continue
            if con.execute("SELECT 1 FROM sqlite_master WHERE name='history_supplement_link'").fetchone() and con.execute(
                'SELECT 1 FROM history_supplement_link WHERE recording_id=?',(target['id'],)).fetchone():continue
            remaining.append(target)
        for i,target in enumerate(remaining,1):
            market='jp' if 'jp' in target['markets'].split(',') else 'us'
            raw=api.get('search',dict(term=target['title']+' '+main_artist(target['artist']),entity='song',limit=25,country=market))
            store(con,raw,market)
            if i%10==0 or i==len(remaining):
                index,artists=library(con)
                found=[(t,match_recording(t,index,artists)) for t in desired];found=[(t,s) for t,s in found if s]
                expand(con,api,args.cache,found)
                print('SONG SEARCH',i,'/',len(remaining),'MATCHED',len(found),flush=True)
        index,artists=library(con)
    matched=[(target,match_recording(target,index,artists)) for target in desired]
    matched=[(target,song) for target,song in matched if song]
    print('SEEDED MATCHES',len(matched),'/',len(desired),flush=True)
    expand(con,api,args.cache,matched)
    unresolved=[t for t in desired if t['id'] not in {x[0]['id'] for x in matched}]
    groups=collections.defaultdict(list)
    for target in ([] if args.catalog_only or args.search_only else unresolved):
        if con.execute("SELECT 1 FROM sqlite_master WHERE name='history_supplement_link'").fetchone() and con.execute(
            'SELECT 1 FROM history_supplement_link WHERE recording_id=?',(target['id'],)).fetchone():continue
        # The KR iTunes song endpoint is sparse; US searches also index Hangul queries.
        market='jp' if 'jp' in target['markets'].split(',') else 'us'
        groups[(main_artist(target['artist']),market)].append(target)
    for i,((artist,market),group) in enumerate(groups.items(),1):
        key=c.norm(artist)
        old=con.execute('SELECT 1 FROM history_artist_search WHERE artist_key=? AND market=?',(key,market)).fetchone()
        linked={r[0] for r in con.execute('SELECT recording_id FROM history_album_link')}
        if all(t['id'] in linked for t in group):continue
        if not old:
            data=api.get('search',dict(term=artist,entity='song',attribute='artistTerm',limit=200,country=market))
            store(con,data,market)
            con.execute('INSERT OR REPLACE INTO history_artist_search VALUES(?,?,?)',(key,market,'queried'));con.commit()
        if i%10==0 or i==len(groups):
            index,artists=library(con)
            found=[(t,match_recording(t,index,artists)) for t in desired];found=[(t,s) for t,s in found if s]
            expand(con,api,args.cache,found)
            print('ARTIST SEARCH',i,'/',len(groups),'MATCHED',len(found),'/',len(desired),'API',api.calls,flush=True)
    index,artists=library(con)
    with con:
        for target in desired:
            if not match_recording(target,index,artists):
                con.execute('INSERT OR REPLACE INTO history_recording_enrichment VALUES(?,?,?,?)',
                    (target['id'],target['markets'].split(',')[0],'not_found','No exact title/version/artist catalog match'))
    report={'target_recordings':len(desired),'linked_recordings':con.execute('SELECT count(distinct recording_id) FROM history_album_link').fetchone()[0],
        'albums':[dict(r) for r in con.execute('SELECT status,count(*) count FROM history_album_audit GROUP BY status')],
        'album_track_rows':con.execute('SELECT count(*) FROM album_track').fetchone()[0],'api_requests':api.calls}
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True);con.close()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--db',type=Path,default=Path('.local/odify_catalog.sqlite3'))
    p.add_argument('--cache',type=Path,default=Path('.local/history2024/apple-cache'))
    p.add_argument('--seeds',type=Path,default=Path(r'D:\Odify\Charts\20261024\Metadata'))
    p.add_argument('--report',type=Path,default=Path('.local/history2024/album_report.json'))
    p.add_argument('--retry-incomplete',action='store_true',help='Recheck incomplete album receipts, including non-audio components')
    p.add_argument('--catalog-only',action='store_true',help='Expand cached exact matches without artist discovery')
    p.add_argument('--search-only',action='store_true',help='Skip bulk artist-ID discovery; search unresolved albums only')
    args=p.parse_args();run(args)
if __name__=='__main__':main()
