"""Monthly chart audio queue: reuse originals, download missing, keep month receipts."""
from __future__ import annotations
import argparse,collections,csv,hashlib,importlib.util,json,os,re,shutil,sqlite3,time
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[2]
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
a=load('history_albums',ROOT/'scripts/odify_recent_albums.py')
b=load('history_bugs',ROOT/'scripts/odify_history_bugs.py')
w=load('history_chart_worker',Path(__file__).with_name('odify_charts.py'))

def digest(path,kind='sha256'):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,kind).hexdigest()

def save(root,rows,detail=''):
    w.snapshot(root,rows,False,detail)
    by_month=collections.defaultdict(list)
    for row in rows:
        for appearance in row['appearances']:
            by_month[(appearance['year_month'],appearance['country'])].append({
                'rank':appearance['rank'],'recording_id':row['id'],'artist':row['artist'],'title':row['title'],
                'status':row['status'],'local_path':row.get('local_path',''),'drive_file_id':row.get('file_id',''),
                'sha256':row.get('sha256',''),'url':row.get('video_url',''),'reason':row.get('error','')})
    for (month,country),items in by_month.items():
        year,number=month.split('-');folder=root/year/number/country;folder.mkdir(parents=True,exist_ok=True)
        with (folder/'download_status.csv').open('w',encoding='utf-8-sig',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=list(items[0]));writer.writeheader();writer.writerows(sorted(items,key=lambda x:x['rank']))
        playlist=['#EXTM3U']
        for item in sorted(items,key=lambda x:x['rank']):
            if item['local_path']:
                playlist+=['#EXTINF:-1,'+item['artist']+' - '+item['title'],os.path.relpath(root/item['local_path'],folder)]
        (folder/'available.m3u8').write_text('\n'.join(playlist)+'\n',encoding='utf-8')

def rows_for(db,root):
    old=json.loads((root/'chart_status.json').read_text('utf-8'))['tracks'] if (root/'chart_status.json').exists() else []
    by_id={r['id']:r for r in old};con=a.h.connect(db);index,artists=a.library(con)
    appearances_by_id=collections.defaultdict(list)
    apple_by_id=collections.defaultdict(set)
    for recording,identity in con.execute('SELECT recording_id,apple_song_id FROM history_album_link'):
        if identity:apple_by_id[recording].add(identity)
    for item in con.execute('SELECT year_month,country,rank,recording_id id FROM recent_monthly_chart ORDER BY year_month,country,rank'):
        appearances_by_id[item['id']].append(dict(item))
    rows=[]
    for target in a.targets(con):
        identity=target['id'];existing=by_id.get(identity)
        song=(None if existing and existing.get('duration_ms') and existing.get('aliases')
              else a.match_recording(target,index,artists))
        appearances=appearances_by_id[identity]
        row=by_id.get(identity) or dict(id=identity,title=target['title'],artist=target['artist'],album='',duration_ms=0,
            artwork='',country=appearances[0]['country'],rank=appearances[0]['rank'],status='QUEUED',file_id='',error='')
        row['appearances']=appearances
        row['_apple_song_ids']=sorted(apple_by_id[identity])
        aliases=[{'artistName':artist,'trackName':title,'trackId':identity}
                 for artist,title in row.get('aliases',[(row['artist'],row['title'])])]
        if song:
            row['duration_ms']=row.get('duration_ms') or song.get('trackTimeMillis',0)
            row['album']=row.get('album') or song.get('collectionName','')
            row['artwork']=row.get('artwork') or song.get('artworkUrl100','').replace('100x100bb','600x600bb')
            aliases.append({**song,'trackId':identity})
        if not existing or song:
            cache=root/'Metadata'/('lookup_'+identity+'.json');cache.parent.mkdir(parents=True,exist_ok=True)
            w.write_cache(cache,aliases)
        row['aliases']=[(x['artistName'],x['trackName']) for x in aliases]
        if row['status']=='NEED_METADATA' and row.get('duration_ms'):row.update(status='QUEUED',error='')
        rows.append(row)
    rows.sort(key=lambda r:(r['appearances'][0]['year_month'],r['rank'],r['country'],r['id']))
    con.close();print('QUEUE PREPARED',len(rows),flush=True);return rows

def source_index(external):
    suffixes={'.mp3','.m4a','.flac','.opus','.ogg','.wav','.aac'}
    files=[p for p in external.joinpath('Music').rglob('*') if p.is_file() and p.suffix.lower() in suffixes
           and p.stem!='ODF000201']  # Previously held English-version mismatch.
    files+=list(external.joinpath('Charts','20261024','Audio').glob('*.mp3'))
    names=collections.defaultdict(list);index=collections.defaultdict(list)
    for path in files:
        names[path.name].append(path)
    previous=external/'Charts/20261024/chart_status.json'
    if previous.exists():
        for row in json.loads(previous.read_text('utf-8'))['tracks']:
            path=external/'Charts/20261024/Audio'/('CHART'+row['id']+'.mp3')
            if path.exists() and row['status'] in ('DOWNLOADED_VERIFIED','UPLOADED_VERIFIED'):
                index[w.norm(row['artist'])+'|'+w.norm(row['title'])].append((path,row.get('duration_ms',0)/1000))
    phone=ROOT/'.local/v46/device_catalog_after_update.sqlite3'
    if phone.exists():
        with sqlite3.connect(phone) as db:
            for name,artist,title in db.execute('SELECT name,artist,title FROM tracks'):
                candidates=names.get(name,[])
                if len(candidates)!=1:continue
                index[w.norm(artist)+'|'+w.norm(title)].append((candidates[0],None))
    print('OWNED INDEX',len(index),'titles',flush=True)
    return index

def place(root,row,source):
    first=row['appearances'][0];year,month=first['year_month'].split('-')
    destination=root/year/month/first['country']/'Audio'/('HIST'+row['id']+Path(source).suffix)
    destination.parent.mkdir(parents=True,exist_ok=True);expected=digest(source)
    if destination.exists():
        if digest(destination)!=expected:raise ValueError('Existing destination differs; protected: '+str(destination))
    else:
        try:os.link(source,destination)
        except OSError:shutil.copy2(source,destination)
        if digest(destination)!=expected:raise ValueError('Local copy verification failed')
    row.update(local_path=str(destination.relative_to(root)),sha256=expected)

def reuse(root,rows,external):
    from mutagen import File
    index=source_index(external);count=0
    durations={}
    for row in rows:
        if row.get('local_path') and (root/row['local_path']).exists():continue
        if row['status'] not in ('QUEUED','NEED_METADATA'):continue
        for artist,title in row.get('aliases',[(row['artist'],row['title'])]):
            candidates=index.get(w.norm(artist)+'|'+w.norm(title),[])
            found=None
            for path,duration in candidates:
                if not duration:
                    try:
                        if path not in durations:durations[path]=File(path).info.length
                        duration=durations[path]
                    except Exception:continue
                if not row.get('duration_ms') or abs(duration-row['duration_ms']/1000)<=5:
                    found=path,duration;break
            if not found:continue
            path,duration=found;place(root,row,path)
            row.update(status='REUSED_LOCAL',duration_ms=row.get('duration_ms') or int(duration*1000),error='');count+=1;break
    print('REUSED LOCAL',count,flush=True);save(root,rows,'Existing originals reused; no original tags changed')

def duration_metadata(root,rows,db,cache_only=False):
    cache_path=ROOT/'.local/history2024/song-runtimes.json'
    runtimes=json.loads(cache_path.read_text('utf-8')) if cache_path.exists() else {}
    con=a.h.connect(db)
    work=[row for row in rows if not row.get('duration_ms')]
    def one(row):
        # Successful Bugs searches are cached even while album expansion continues.
        fetchrow=con.execute("SELECT url FROM history_supplement_fetch WHERE provider='bugs' AND recording_id=? AND status='matched'",(row['id'],)).fetchone()
        return fetchrow
    tasks=[]
    for row in work:
        found=one(row)
        if not found:continue
        cached=b.fetch(ROOT/'.local/history2024/bugs-cache',found['url'])
        song=b.choose(row,b.parse_tracks(cached))
        if song:tasks.append((row,song))
    con.close()
    def fetch_song(pair):
        row,song=pair;url='https://music.bugs.co.kr/track/'+song['id']
        try:
            raw=b.fetch(ROOT/'.local/history2024/bugs-cache',url)
            for block in re.findall(r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>',raw,re.S):
                data=json.loads(block)
                if data.get('@type')!='MusicRecording' or data.get('sameAs')!=url:continue
                length=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?',data.get('duration',''))
                if not length:continue
                seconds=sum(int(x or 0)*scale for x,scale in zip(length.groups(),(3600,60,1)))
                if seconds<=0:continue
                return row,seconds*1000,data.get('image',''),song.get('album_title','')
        except Exception as error:print('DURATION DEFER',row['id'],str(error)[:120],flush=True)
        finally:time.sleep(0.6)
        return row,0,'',''
    with ThreadPoolExecutor(max_workers=2) as pool:
        for i,future in enumerate(as_completed([pool.submit(fetch_song,p) for p in tasks]),1):
            row,milliseconds,cover,album=future.result()
            if milliseconds:
                runtimes[row['id']]=dict(duration_ms=milliseconds,artwork=cover,album=album)
                row.update(duration_ms=milliseconds,artwork=row.get('artwork') or cover,album=row.get('album') or album)
            if i%25==0:
                w.write_cache(cache_path,runtimes)
                if not cache_only:save(root,rows,'Fetching missing reference runtimes')
                print('DURATIONS',i,'/',len(tasks),flush=True)
    for row in rows:
        if not row.get('duration_ms') and row['status']=='QUEUED':row.update(status='NEED_METADATA',error='Reference recording duration unavailable')
        elif row.get('duration_ms') and row['status']=='NEED_METADATA':row.update(status='QUEUED',error='')
    w.write_cache(cache_path,runtimes)
    if not cache_only:save(root,rows,'Reference runtimes prepared')

def apply_runtime_cache(rows):
    path=ROOT/'.local/history2024/song-runtimes.json'
    if not path.exists():return
    known=json.loads(path.read_text('utf-8'))
    for row in rows:
        info=known.get(row['id'])
        if info:
            if info.get('unavailable') and row['status'] in ('QUEUED','LINK_SELECTED','NEED_METADATA'):
                row.update(status='HELD_REVIEW',error=info['unavailable']);continue
            for key in ('duration_ms','artwork','album'):
                if not row.get(key):row[key]=info.get(key)
            if row['status']=='NEED_METADATA' and row.get('duration_ms'):row.update(status='QUEUED',error='')

def reuse_history(root,rows):
    known={}
    for row in rows:
        if row.get('local_path') and row.get('sha256') and row['status'] in ('DOWNLOADED_VERIFIED','REUSED_LOCAL','REUSED_HISTORY'):
            for identity in row.get('_apple_song_ids',[]):known.setdefault(identity,row)
    for row in rows:
        if row['status'] not in ('QUEUED','LINK_SELECTED','NEED_METADATA'):continue
        source=next((known[x] for x in row.get('_apple_song_ids',[]) if x in known),None)
        if source and (root/source['local_path']).is_file():
            row.update(status='REUSED_HISTORY',local_path=source['local_path'],sha256=source['sha256'],error='')

def run_download(root,rows,limit,workers=8):
    for row in rows:
        if row['status']=='DOWNLOADING':row['status']='LINK_SELECTED'
    reuse_history(root,rows)
    pending=[r for r in rows if r['status'] in ('QUEUED','LINK_SELECTED') and r.get('duration_ms')][:limit or None]
    args=SimpleNamespace(download_only=True,rclone='rclone',config=None,folder_id=None,workers=workers)
    original=w.snapshot
    # The existing worker writes per-batch progress; persist the entire queue on
    # each write so a power loss cannot discard other months' completed receipts.
    w.snapshot=lambda folder,batch,running,detail='':original(root,rows,running,detail)
    try:
        for start in range(0,len(pending),20):
            apply_runtime_cache(rows);reuse_history(root,rows)
            batch=[r for r in pending[start:start+20] if r['status'] in ('QUEUED','LINK_SELECTED')]
            if not batch:continue
            selected=w.quick_links(root,batch)
            w.snapshot(root,rows,False,'Links selected; downloading current batch')
            if any(w.access_blocked(r.get('error','')) and not w.age_restricted(r.get('error','')) for r in batch):
                save(root,rows,'Source requested verification; stopped without authentication');return
            w.collect_quick(args,root,batch,selected)
            for row in batch:
                if row['status']=='DOWNLOADED_VERIFIED':place(root,row,root/'Audio'/('CHART'+row['id']+'.mp3'))
            w.snapshot(root,rows,False,'Completed batch; originals and MASTER preserved')
            if start%100==0:save(root,rows,'Monthly receipts refreshed')
            if any(w.access_blocked(r.get('error','')) and not w.age_restricted(r.get('error','')) for r in batch):return
    finally:
        save(root,rows,'Monthly receipts refreshed; queue remains resumable')
        w.snapshot=original

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['prepare','refresh','runtimes','cache-runtimes','download'])
    p.add_argument('--db',type=Path,default=ROOT/'.local/odify_catalog.sqlite3')
    p.add_argument('--root',type=Path,default=Path(r'D:\Odify\History'))
    p.add_argument('--external',type=Path,default=Path(r'D:\Odify'));p.add_argument('--limit',type=int,default=0)
    p.add_argument('--workers',type=int,choices=range(1,9),default=8)
    args=p.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    status=args.root/'chart_status.json'
    if args.command=='cache-runtimes':
        rows=json.loads(status.read_text('utf-8'))['tracks'];apply_runtime_cache(rows)
        duration_metadata(args.root,rows,args.db,cache_only=True);return
    with w.single_instance(args.root):
        rows=(json.loads(status.read_text('utf-8'))['tracks'] if status.exists() and args.command not in ('prepare','refresh')
              else rows_for(args.db,args.root))
        apply_runtime_cache(rows)
        if args.command=='prepare':reuse(args.root,rows,args.external)
        elif args.command=='refresh':save(args.root,rows,'New album metadata merged; audio receipts preserved')
        elif args.command=='runtimes':duration_metadata(args.root,rows,args.db)
        else:run_download(args.root,rows,args.limit,args.workers)
if __name__=='__main__':main()
