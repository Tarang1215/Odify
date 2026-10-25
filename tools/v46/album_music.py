"""Resumable, external-only audio queue for cataloged 2024-2026 albums.

Only existing catalog albums released by the cutoff are included. This is not
a claim to cover every album published worldwide. MASTER/source approvals are
never rewritten; selected audio links live in their own evidence table.
"""
from __future__ import annotations
import argparse,collections,importlib.util,json,re,sqlite3,subprocess,sys,time
from contextlib import closing
from datetime import date,datetime,timezone
from pathlib import Path

spec=importlib.util.spec_from_file_location('album_history_music',Path(__file__).with_name('history_music.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
READY={'DOWNLOADED_VERIFIED','REUSED_LOCAL','REUSED_HISTORY'}
# These IDs are catalog hashes, not Apple IDs. Use the aliases already loaded
# from album metadata instead of making two doomed iTunes lookups per song.
m.w.variants=lambda row,root:row.get('aliases') or [(row['artist'],row['title'])]

def safe_folder(artist,title,identity):
    text=re.sub(r'[<>:"/\\|?*\x00-\x1f]','_',artist+' - '+title).strip(' .')[:75].rstrip(' .')
    return (text or 'Album')+' ['+identity[:8]+']'

def prepare(db,root,cutoff):
    root.mkdir(parents=True,exist_ok=True)
    old={r['id']:r for r in json.loads((root/'chart_status.json').read_text('utf-8'))['tracks']} if (root/'chart_status.json').exists() else {}
    con=m.a.h.connect(db);index,artists=m.a.library(con)
    payloads={(r['market'],r['track_id']):json.loads(r['payload']) for r in con.execute('SELECT market,track_id,payload FROM history_apple_track')}
    sources=collections.defaultdict(list)
    for item in con.execute('SELECT * FROM history_source_album_track'):
        sources[item['recording_id']].append(dict(item))
    linked='SELECT recording_id,album_id FROM history_album_link'
    if con.execute("SELECT 1 FROM sqlite_master WHERE name='history_supplement_link'").fetchone():
        linked+=' UNION SELECT recording_id,album_id FROM history_supplement_link'
    albums=[dict(r) for r in con.execute('''SELECT DISTINCT a.*,ar.name artist FROM album a
        JOIN artist ar ON ar.id=a.artist_id JOIN ('''+linked+''') h ON h.album_id=a.id
        JOIN recent_monthly_chart chart ON chart.recording_id=h.recording_id
        WHERE a.release_date>='2024-01-01' AND a.release_date<=? ORDER BY a.release_date,a.id''',(cutoff,))]
    rows={};manifest=[]
    for album in albums:
        month=album['release_date'][:7]
        folder=safe_folder(album['artist'],album['title'],album['id'])
        tracks=[]
        for track in con.execute('''SELECT r.id,r.title,ar.name artist,at.disc,at.position FROM album_track at
            JOIN recording r ON r.id=at.recording_id JOIN artist ar ON ar.id=r.artist_id
            WHERE at.album_id=? ORDER BY at.disc,at.position,r.id''',(album['id'],)):
            track=dict(track);rid=track['id'];refs=sources[rid]
            market=next((r['provider'].split(':',1)[1] for r in refs if r['provider'].startswith('apple:')), 'jp' if any(r['provider']=='billboard-japan-goods' for r in refs) else 'kr' if any(r['provider']=='bugs' for r in refs) else 'us')
            exact=[payloads.get((r['provider'].split(':',1)[1],r['source_track_id'])) for r in refs if r['provider'].startswith('apple:')]
            song=next((s for s in exact if s and s.get('trackTimeMillis')),None)
            if song is None:song=m.a.match_recording({**track,'markets':market},index,artists)
            row=rows.get(rid)
            if row is None:
                row=dict(old.get(rid) or dict(id=rid,title=track['title'],artist=track['artist'],album=album['title'],
                    country=market,rank=track['position'],status='QUEUED',file_id='',error='',duration_ms=0,artwork=''))
                row.update(appearances=[],album_ids=[],aliases=[(track['artist'],track['title'])],_apple_song_ids=sorted({r['source_track_id'] for r in refs if r['provider'].startswith('apple:')}))
                if song:
                    row['duration_ms']=row.get('duration_ms') or int(song.get('trackTimeMillis') or 0)
                    row['artwork']=row.get('artwork') or song.get('artworkUrl100','')
                    pair=(song.get('artistName',track['artist']),song.get('trackName',track['title']))
                    if pair not in row['aliases']:row['aliases'].append(pair)
                if row['status'] in ('QUEUED','NEED_METADATA'):
                    row.update(status='QUEUED' if row.get('duration_ms') else 'NEED_METADATA',error='' if row.get('duration_ms') else 'Reference album-track runtime not yet available')
                rows[rid]=row
            row['album_ids'].append(album['id'])
            row['appearances'].append(dict(year_month=month,country=folder,rank=int(track['disc'])*10000+int(track['position'])))
            tracks.append({**track,'sources':refs})
        year,number=month.split('-');destination=root/year/number/folder;destination.mkdir(parents=True,exist_ok=True)
        data=dict(album=album,tracks=tracks,scope='Cataloged chart-associated album released 2024 through cutoff',cutoff=cutoff)
        m.w.write_cache(destination/'album.json',data);manifest.append(dict(id=album['id'],title=album['title'],artist=album['artist'],release_date=album['release_date'],tracks=len(tracks),path=str(destination.relative_to(root))))
    con.close()
    rows=list(rows.values());m.apply_runtime_cache(rows)
    m.save(root,rows,'Album audio prepared; external only, no Drive upload')
    m.w.write_cache(root/'album_scope.json',dict(cutoff=cutoff,albums=len(albums),unique_recordings=len(rows),release_start='2024-01-01',drive_upload=False,items=manifest))
    persist(db,rows)
    print('ALBUM QUEUE',len(albums),'albums',len(rows),'recordings',dict(collections.Counter(r['status'] for r in rows)),flush=True)
    return rows

def persist(db,rows):
    with closing(sqlite3.connect(db,timeout=60)) as con,con:
        con.execute('''CREATE TABLE IF NOT EXISTS album_audio_evidence(
            recording_id TEXT PRIMARY KEY REFERENCES recording(id),status TEXT NOT NULL,
            selected_url TEXT,duration_ms INTEGER,local_path TEXT,sha256 TEXT,policy TEXT,error TEXT,updated_at TEXT)''')
        now=datetime.now(timezone.utc).isoformat()
        con.executemany('INSERT OR REPLACE INTO album_audio_evidence VALUES(?,?,?,?,?,?,?,?,?)',[
            (r['id'],r['status'],r.get('video_url',''),r.get('duration_ms',0),r.get('local_path',''),r.get('sha256',''),r.get('selection_policy',''),r.get('error',''),now) for r in rows])

def reuse_history(rows,root,history):
    status=history/'chart_status.json'
    if not status.exists():return
    owned=json.loads(status.read_text('utf-8'))['tracks'];by_id={r['id']:r for r in owned if r['status'] in READY and r.get('local_path')}
    by_apple={identity:r for r in by_id.values() for identity in r.get('_apple_song_ids',[])}
    for row in rows:
        if row.get('local_path') or row['status'] not in ('QUEUED','NEED_METADATA'):continue
        source=by_id.get(row['id']) or next((by_apple[x] for x in row.get('_apple_song_ids',[]) if x in by_apple),None)
        if not source:continue
        path=history/source['local_path']
        if not path.is_file() or m.digest(path)!=source.get('sha256'):continue
        if row.get('duration_ms') and source.get('duration_ms') and abs(row['duration_ms']-source['duration_ms'])>5000:continue
        m.place(root,row,path);row.update(status='REUSED_HISTORY',duration_ms=row.get('duration_ms') or source.get('duration_ms',0),error='',reused_from=str(path))

def enrich_missing(db,root,rows):
    """Catalog metadata only; never asks the gated audio source for a stream."""
    con=m.a.h.connect(db)
    api=m.a.Apple(m.ROOT/'.local/history2024/album-runtime-cache')
    pending=[r for r in rows if not r.get('duration_ms') and r['status']=='NEED_METADATA']
    for i,row in enumerate(pending,1):
        try:
            market='jp' if row['country']=='jp' else 'us'
            raw=api.get('search',dict(term=row['artist']+' '+row['title'],entity='song',limit=10,country=market))
            songs=[{**s,'_market':market} for s in raw.get('results',[]) if s.get('kind')=='song' and s.get('trackTimeMillis') and s.get('artistId') and s.get('collectionId')]
            index=collections.defaultdict(list)
            for song in songs:
                for key in m.a.title_keys(song['trackName']):index[key].append(song)
            selected=m.a.match_recording({**row,'markets':market},index,{})
            if selected:
                m.a.store(con,raw,market)
                row.update(duration_ms=int(selected['trackTimeMillis']),status='QUEUED',error='',
                    artwork=row.get('artwork') or selected.get('artworkUrl100',''),
                    duration_source=selected.get('trackViewUrl','itunes lookup'))
            print('ALBUM RUNTIME',i,'/',len(pending),row['id'],row['status'],flush=True)
        except Exception as error:print('ALBUM METADATA DEFER',row['id'],str(error)[-150:],flush=True)
        if i%25==0 or i==len(pending):
            m.save(root,rows,'Album runtime metadata enrichment; audio access may be paused');persist(db,rows)
    con.close()

def source_paused(history):
    path=history/'chart_status.json'
    if not path.exists():return False
    return any(m.w.access_blocked(r.get('error','')) and not m.w.age_restricted(r.get('error',''))
               for r in json.loads(path.read_text('utf-8'))['tracks'])

def cached_links(rows,root,history):
    """Resolve existing evidence without issuing new requests after a gate."""
    original=m.w.search_candidates
    original_inspected=m.w.inspected_video
    def cached(artist,title,unused,preference='official audio'):
        suffix='' if preference=='official audio' else '|'+preference
        key=m.hashlib.sha256((artist+'|'+title+suffix).encode()).hexdigest()[:24]
        for folder in (root,history):
            file=folder/'Metadata'/('search_'+key+'.json')
            if file.exists():return json.loads(file.read_text('utf-8'))
        return []
    m.w.search_candidates=cached
    def inspected(video,unused):
        for folder in (root,history):
            file=folder/'Metadata'/('video_v2_'+video+'.json')
            if file.exists():return json.loads(file.read_text('utf-8'))
        return {}
    m.w.inspected_video=inspected
    count=0
    try:
        for row in rows:
            if row['status'] not in ('QUEUED','LINK_SELECTED') or not row.get('duration_ms'):continue
            info=m.w.select_quick_candidate(row,root)
            if info:
                row.update(status='LINK_SELECTED',video_url='https://www.youtube.com/watch?v='+info['id'],
                    selection_policy='cached-duration-title-prefer-official-then-lyrics',error='')
                count+=1
    finally:
        m.w.search_candidates=original;m.w.inspected_video=original_inspected
    print('CACHED ALBUM LINKS',count,flush=True)

def run(args):
    args.root.mkdir(parents=True,exist_ok=True)
    if args.expand_db:
        # Bounded retry of cataloged album pages; no new unrelated artist crawl.
        result=subprocess.run([sys.executable,'-u','scripts/odify_recent_albums.py','--db',str(args.db),'--catalog-only','--retry-incomplete'],cwd=m.ROOT)
        if result.returncode:print('Metadata retry deferred; using preserved catalog',result.returncode,flush=True)
    with m.w.single_instance(args.root):rows=prepare(args.db,args.root,args.cutoff)
    if args.prepare_only:return
    if args.wait_pid:
        spec=importlib.util.spec_from_file_location('album_wait',Path(__file__).with_name('history_background.py'))
        bg=importlib.util.module_from_spec(spec);spec.loader.exec_module(bg)
        print('WAIT MONTHLY DOWNLOAD',args.wait_pid,flush=True);bg.wait_existing(args.root,args.wait_pid,True)
    with m.w.single_instance(args.root):
        rows=json.loads((args.root/'chart_status.json').read_text('utf-8'))['tracks']
        reuse_history(rows,args.root,args.history);m.reuse(args.root,rows,args.external);persist(args.db,rows)
        if args.enrich_runtimes:enrich_missing(args.db,args.root,rows)
        if source_paused(args.history):
            cached_links(rows,args.root,args.history)
            m.save(args.root,rows,'Audio source requested verification; metadata/cached links saved, external only')
            persist(args.db,rows)
            print('AUDIO PAUSED: monthly source requested verification; no new audio requests or Drive upload',flush=True)
            return
        count=0
        while any(r['status'] in ('QUEUED','LINK_SELECTED','DOWNLOADING') and r.get('duration_ms') for r in rows):
            if args.limit and count>=args.limit:break
            free=__import__('shutil').disk_usage(args.root).free
            if free<2*1024**3:
                print('PAUSED: external disk headroom below 2 GiB',flush=True);break
            attempted=min(20,args.limit-count) if args.limit else 20
            m.run_download(args.root,rows,attempted,args.workers)
            persist(args.db,rows)
            count+=attempted
            if any(m.w.access_blocked(r.get('error','')) and not m.w.age_restricted(r.get('error','')) for r in rows):
                print('SOURCE PAUSED: no VPN rotation, cookie export or challenge bypass',flush=True);break
        m.save(args.root,rows,'Album audio queue saved; external only');persist(args.db,rows)

def main():
    for stream in (sys.stdout,sys.stderr):
        if hasattr(stream,'reconfigure'):stream.reconfigure(encoding='utf-8',errors='backslashreplace')
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--db',type=Path,default=m.ROOT/'.local/odify_catalog.sqlite3')
    p.add_argument('--root',type=Path,default=Path(r'D:\Odify\Albums'))
    p.add_argument('--history',type=Path,default=Path(r'D:\Odify\History'))
    p.add_argument('--external',type=Path,default=Path(r'D:\Odify'))
    p.add_argument('--cutoff',default='2026-10-25');p.add_argument('--wait-pid',type=int,default=0)
    p.add_argument('--workers',type=int,choices=range(1,5),default=4);p.add_argument('--limit',type=int,default=0)
    p.add_argument('--prepare-only',action='store_true');p.add_argument('--expand-db',action='store_true')
    p.add_argument('--enrich-runtimes',action='store_true')
    args=p.parse_args();date.fromisoformat(args.cutoff)
    import os
    kernel=None
    if os.name=='nt':
        import ctypes
        kernel=ctypes.windll.kernel32;kernel.SetThreadExecutionState(0x80000001)
    try:run(args)
    finally:
        if kernel:kernel.SetThreadExecutionState(0x80000000)
if __name__=='__main__':main()
