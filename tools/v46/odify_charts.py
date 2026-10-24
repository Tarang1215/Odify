"""Resumable, separately scoped chart collection. Never edits the user's MASTER."""
from __future__ import annotations
import argparse, csv, hashlib, json, os, re, sqlite3, subprocess, sys, time, unicodedata
from pathlib import Path
from datetime import datetime, timezone
from contextlib import closing
from urllib.request import Request, urlopen
from concurrent.futures import ThreadPoolExecutor,as_completed
from threading import Event

REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'windows-collector'))
import music_catalog as audio
from run_master import candidate_matches, access_blocked, single_instance

PLAYLISTS={'kr':'pl.d3d10c32fbc540b38e266367dc8cb00c',
           'jp':'pl.043a2c9876114d95a4659988497567be','us':'pl.606afcbb70264d2eb2b51d8dbcfa6a12'}
def norm(s): return ''.join(c for c in unicodedata.normalize('NFKC',s).casefold() if c.isalnum())
def key(row): return norm(row['artist'])+'|'+norm(row['title'])
def fetch(url,limit=10_000_000):
    with urlopen(Request(url,headers={'User-Agent':'Mozilla/5.0 (Odify personal music library)'}),timeout=35) as r:
        data=r.read(limit+1)
    if len(data)>limit: raise ValueError('Response exceeds bounded size')
    return data
def playlist_rows(raw,country):
    match=re.search(r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>',raw,re.S)
    if not match: raise ValueError('Public playlist metadata missing')
    data=json.loads(match.group(1)); found=[]
    def visit(node):
        if isinstance(node,dict):
            descriptor=node.get('contentDescriptor') or {}
            if descriptor.get('kind')=='song' and node.get('rankingText') and node.get('artistName'):
                found.append(node);return
            for value in node.values(): visit(value)
        elif isinstance(node,list):
            for value in node:visit(value)
    visit(data)
    result=[]
    for item in sorted(found,key=lambda x:int(x['rankingText'])):
        identity=item['contentDescriptor']['identifiers']['storeAdamID']
        artwork=(item.get('artwork') or {}).get('dictionary',{}).get('url','')
        result.append({'id':identity,'title':item['title'],'artist':item['artistName'],
            'album':next((x['title'] for x in item.get('tertiaryLinks',[])),''),
            'duration_ms':item.get('duration',0),'artwork':artwork.replace('{w}','600').replace('{h}','600').replace('{f}','jpg'),
            'rank':int(item['rankingText']),'country':country,'genres':[]})
    if len(result)!=100 or len({x['id'] for x in result})!=100:raise ValueError('Chart must contain exactly 100 distinct ranked entries')
    return result
def prepare(root,snapshots):
    metadata=root/'Metadata';metadata.mkdir(parents=True,exist_ok=True)
    charts={}
    for country,identity in PLAYLISTS.items():
        cached=snapshots/f'{country}_playlist.html' if snapshots else None
        raw=cached.read_bytes() if cached and cached.exists() else fetch(f'https://music.apple.com/{country}/playlist/top-100/{identity}')
        (metadata/f'{country}_playlist.html').write_bytes(raw)
        charts[country]=playlist_rows(raw.decode('utf-8'),country)
        (metadata/f'{country}_top100.json').write_text(json.dumps(charts[country],ensure_ascii=False,indent=2),encoding='utf-8')
    groups={}
    for rank in range(100):
        for country in PLAYLISTS:
            row=charts[country][rank]
            # Apple identity is authoritative across storefronts; same full
            # artist/title also reuses the recording without removing chart ranks.
            same=next((x for x in groups.values() if x['id']==row['id'] or key(x)==key(row)),None)
            if same: same['appearances'].append({'country':country,'rank':row['rank'],'id':row['id']})
            else: groups[row['id']]={**row,'appearances':[{'country':country,'rank':row['rank'],'id':row['id']}],
                'status':'QUEUED','file_id':'','error':''}
    return list(groups.values())
def execute(args,timeout=180):
    result=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if result.returncode:raise RuntimeError((result.stderr or result.stdout)[-1800:])
    return result.stdout
def variants(row,root):
    cache=root/'Metadata'/f"lookup_{row['id']}.json"
    if cache.exists():data=json.loads(cache.read_text(encoding='utf-8'))
    else:
        data=[]
        for country in dict.fromkeys([row['country'],'us']):
            try:data.extend(json.loads(fetch(f"https://itunes.apple.com/lookup?id={row['id']}&entity=song&country={country}"))['results'])
            except Exception:pass
        cache.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
    pairs=[(row['artist'],row['title'])]+[(x.get('artistName',''),x.get('trackName','')) for x in data if str(x.get('trackId'))==str(row['id'])]
    return list(dict.fromkeys((a,t) for a,t in pairs if a and t))
def write_cache(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+f'.{os.getpid()}.{time.time_ns()}.tmp')
    temporary.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8');temporary.replace(path)
def fresh_cache(path):
    if path.exists() and time.time()-path.stat().st_mtime<86400:
        return json.loads(path.read_text(encoding='utf-8'))
    return None
def age_restricted(message):
    return any(x in message.casefold() for x in ('confirm your age','age-restricted recording'))
def search_candidates(artist,title,root):
    digest=hashlib.sha256((artist+'|'+title).encode()).hexdigest()[:24]
    cache=root/'Metadata'/f'search_{digest}.json'
    known=fresh_cache(cache)
    if known is not None:return known
    listing=json.loads(execute([sys.executable,'-m','yt_dlp','--flat-playlist','--dump-single-json',
        '--socket-timeout','15','--retries','0',f'ytsearch5:{artist} {title} official audio'],60))
    candidates=[{k:x.get(k) for k in ('id','title','duration','channel','uploader','channel_id')}
        for x in listing.get('entries') or [] if re.fullmatch(r'[A-Za-z0-9_-]{11}',x.get('id') or '')]
    write_cache(cache,candidates);return candidates
def inspected_video(video,root):
    cache=root/'Metadata'/f'video_v2_{video}.json';known=fresh_cache(cache)
    if known is not None:return known
    info=json.loads(execute([sys.executable,'-m','yt_dlp','--no-playlist','--skip-download',
        '--dump-single-json','--socket-timeout','15','--retries','0','https://www.youtube.com/watch?v='+video],60))
    known={k:info.get(k) for k in ('id','track','title','artist','creator','channel','uploader','duration',
        'uploader_verified','channel_is_verified','description','album','is_live','live_status','release_date','upload_date')}
    write_cache(cache,known);return known
def chart_candidate_matches(expected,info):
    if candidate_matches(expected,info):return True
    # Explicit official-audio wrappers may be removed for the full-title
    # equality check; artist, version, studio and duration checks still apply.
    cleaned=audio.clean_audio_title(str(info.get('title') or ''),expected['아티스트'])
    if norm(cleaned)!=norm(audio.recording_title(expected['곡명'])):return False
    return candidate_matches(expected,{**info,'track':cleaned})
def official_visual_matches(expected,info,target):
    if not target or not (info.get('channel_is_verified') or info.get('uploader_verified')):return False
    channel=re.sub(r'\s+-\s+Topic$','',str(info.get('channel') or ''),flags=re.I)
    if norm(channel)!=norm(expected['아티스트']):return False
    if abs((info.get('duration') or 0)-target)>2:return False
    if norm(audio.clean_audio_title(str(info.get('title') or ''),expected['아티스트']))!=norm(audio.recording_title(expected['곡명'])):return False
    try:audio.validate_audio_metadata('https://www.youtube.com/watch?v='+info['id'],expected['곡명'],info,True,artist=expected['아티스트'])
    except (ValueError,KeyError):return False
    return True
def resolve(row,root,official_visuals=False):
    pairs=variants(row,root);seen=set();target=row.get('duration_ms',0)/1000
    for query_artist,query_title in pairs[:2]:
        for candidate in search_candidates(query_artist,query_title,root):
            video=candidate.get('id','')
            if video in seen or not re.fullmatch(r'[A-Za-z0-9_-]{11}',video):continue
            seen.add(video)
            duration=candidate.get('duration')
            if target and isinstance(duration,(int,float)) and abs(duration-target)>5:continue
            url='https://www.youtube.com/watch?v='+video
            try:info=inspected_video(video,root)
            except Exception as error:
                if 'sign in to confirm your age' in str(error).casefold():
                    raise ValueError('Age-restricted recording held; no authentication attempted') from error
                if access_blocked(str(error)):raise
                continue
            if target and abs((info.get('duration') or 0)-target)>5:continue
            for artist,title in pairs:
                expected={'Track ID':'CHART'+row['id'],'곡명':title,'아티스트':artist}
                if chart_candidate_matches(expected,info):
                    audio.validate_audio_metadata(url,title,info,artist=artist)
                    return url,artist,title,info
                if official_visuals and official_visual_matches(expected,info,target):
                    return url,artist,title,{**info,'_odifyOfficialVisual':True}
    raise ValueError('No unambiguous original studio recording; held for review')

def prepare_link_plan(root,rows):
    """Fast public search metadata only; candidate links are never approved."""
    stop=Event();results={};destination=root/'Links';destination.mkdir(exist_ok=True)
    def one(row):
        result={'id':row['id'],'artist':row['artist'],'title':row['title'],'status':row['status'],
            'verified_url':row.get('video_url','') if row['status']=='UPLOADED_VERIFIED' else '',
            'drive_id':row.get('file_id',''),'candidates':[]}
        if row['status'] in ('UPLOADED_VERIFIED','REUSED_EXISTING') or age_restricted(row.get('error','')):return result
        if stop.is_set():result['link_status']='PAUSED';return result
        try:
            candidates=search_candidates(row['artist'],row['title'],root)
            result['candidates']=[{'url':'https://www.youtube.com/watch?v='+c['id'],'title':c.get('title'),
                'channel':c.get('channel') or c.get('uploader'),'duration':c.get('duration')} for c in candidates]
            result['link_status']='CANDIDATE_ONLY' if candidates else 'NOT_FOUND'
        except Exception as error:
            result.update(link_status='ERROR',error=str(error)[-500:])
            if access_blocked(str(error)):stop.set()
        return result
    def save():
        ordered=[results[r['id']] for r in rows if r['id'] in results]
        write_cache(destination/'chart_links.json',{'schemaVersion':1,'candidateLinksAreUnverified':True,
            'processed':len(ordered),'total':len(rows),'paused':stop.is_set(),'tracks':ordered})
        temp=destination/'chart_links.csv.tmp'
        with temp.open('w',encoding='utf-8-sig',newline='') as h:
            writer=csv.DictWriter(h,fieldnames=['id','artist','title','status','link_status','verified_url','drive_id']+
                [f'candidate_{i}' for i in range(1,6)],extrasaction='ignore');writer.writeheader()
            for item in ordered:
                writer.writerow({**item,**{f'candidate_{i+1}':x['url'] for i,x in enumerate(item['candidates'])}})
        temp.replace(destination/'chart_links.csv')
    with ThreadPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(one,row) for row in rows]):
            result=future.result();results[result['id']]=result;save()
            print('Link plan:',len(results),'/',len(rows),result.get('link_status',result['status']),flush=True)
    return results
def snapshot(root,rows,running,detail=''):
    counts={}
    for row in rows:counts[row['status']]=counts.get(row['status'],0)+1
    result={'updated_at':datetime.now(timezone.utc).isoformat(),'running':running,'detail':detail,
        'chart_entries':sum(len(r['appearances']) for r in rows),'unique_recordings':len(rows),'counts':counts,'tracks':rows}
    temp=root/'chart_status.tmp';temp.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');temp.replace(root/'chart_status.json')
    with (root/'chart_status.csv').open('w',encoding='utf-8-sig',newline='') as h:
        fields=['id','country','rank','artist','title','status','video_url','file_id','error']
        writer=csv.DictWriter(h,fieldnames=fields,extrasaction='ignore');writer.writeheader();writer.writerows(rows)
    return result
def existing_catalog(path):
    if not path:return {}
    with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True)) as db:
        return {norm(a)+'|'+norm(t):identity for identity,a,t in db.execute('SELECT id,artist,title FROM tracks') if a and t}
def reuse_cached_owned(rows,known,root):
    """Progress after a source block without any extraction/network requests."""
    reused=0
    for row in rows:
        if row['status']!='QUEUED':continue
        pairs=[(row['artist'],row['title'])]
        cache=root/'Metadata'/f"lookup_{row['id']}.json"
        if cache.exists():
            pairs.extend((x.get('artistName',''),x.get('trackName',''))
                for x in json.loads(cache.read_text(encoding='utf-8'))
                if str(x.get('trackId'))==str(row['id']))
        identity=next((known.get(norm(a)+'|'+norm(t)) for a,t in pairs if a and t and known.get(norm(a)+'|'+norm(t))),None)
        if identity:
            row.update(status='REUSED_EXISTING',file_id=identity,error='');reused+=1
    return reused
def collect(args):
    root=args.root.resolve();root.mkdir(parents=True,exist_ok=True)
    with single_instance(root):
        status=root/'chart_status.json'
        rows=json.loads(status.read_text(encoding='utf-8'))['tracks'] if status.exists() else prepare(root,args.snapshots)
        if args.plan:
            print(json.dumps(snapshot(root,rows,False,'Preview; no collection or upload'),ensure_ascii=False)[:400]);return
        if args.links_only:
            prepare_link_plan(root,rows);return
        known=existing_catalog(args.catalog)
        if args.reuse_only:
            reused=reuse_cached_owned(rows,known,root)
            snapshot(root,rows,False,'Source downloads paused; network-free owned-file reuse pass complete')
            print('Additional owned files reused:',reused);return
        db=audio.connection(root/'charts.sqlite3')
        rc=[args.rclone,'--config',str(args.config),'--drive-root-folder-id',args.folder_id]
        # One exact existing library root; all new files live under a dated child.
        remote='mydrive:Charts_'+root.name+'/Audio/'
        snapshot(root,rows,True)
        failures=0
        try:
            for row in rows:
                if args.download_only and row['status']=='DOWNLOADED_VERIFIED':continue
                if row['status'] in ('UPLOADED_VERIFIED','REUSED_EXISTING') or row['status']=='HELD_REVIEW' and (
                    not args.retry_held or age_restricted(row.get('error',''))):continue
                try:
                    candidates=variants(row,root)
                    existing=next((known.get(norm(a)+'|'+norm(t)) for a,t in candidates if known.get(norm(a)+'|'+norm(t))),None)
                    if existing:
                        failures=0
                        row.update(status='REUSED_EXISTING',file_id=existing,error='');snapshot(root,rows,True);continue
                    row.update(status='LINK_REVIEW',error='');snapshot(root,rows,True)
                    url,artist,title,evidence=resolve(row,root,getattr(args,'official_visuals',False))
                    identity='CHART'+row['id'];row['video_url']=url
                    (root/'Metadata'/f'{identity}_identity.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
                    db.execute('INSERT OR IGNORE INTO tracks(id,country,artist,title,album,genre,video_url,verified) VALUES(?,?,?,?,?,?,?,?)',
                        (identity,row['country'],artist,title,row['album'],'',url,'chart identity checked'));db.commit()
                    path=root/'Audio'/f'{identity}.mp3'
                    if path.exists():
                        record=db.execute('SELECT status,sha256 FROM tracks WHERE id=?',(identity,)).fetchone()
                        if not record or record[0]!='downloaded' or hashlib.sha256(path.read_bytes()).hexdigest()!=record[1]:
                            raise ValueError('Existing incomplete or changed file protected; manual review required')
                    else:
                        row['status']='DOWNLOADING';snapshot(root,rows,True)
                        audio.download_one(db,(identity,artist,title,row['album'],'',url),root,True,True,audio_format='mp3',
                            inspected_metadata=evidence,accept_lyric_or_mv=bool(evidence.get('_odifyOfficialVisual')))
                    if args.download_only:
                        row.update(status='DOWNLOADED_VERIFIED',sha256=hashlib.sha256(path.read_bytes()).hexdigest(),error='')
                        snapshot(root,rows,True)
                        print(row['id'],row['status'],flush=True)
                        continue
                    # Canonical inspected metadata stays in the audio. Display
                    # aliases and original chart ranks stay in the sidecar.
                    row['status']='UPLOADING';snapshot(root,rows,True)
                    quota=json.loads(execute(rc+['about','mydrive:','--json']))
                    if quota.get('free',0)<path.stat().st_size+512*1024*1024:raise RuntimeError('Insufficient Drive headroom; transfer paused')
                    destination=remote+path.name
                    execute(rc+['copyto',str(path),destination,'--immutable','--retries','2'],900)
                    observed=json.loads(execute(rc+['lsjson',destination,'--stat','--hash']))
                    digest=hashlib.md5(path.read_bytes()).hexdigest()
                    if observed['Size']!=path.stat().st_size or observed.get('Hashes',{}).get('md5')!=digest:raise RuntimeError('Remote checksum mismatch')
                    row.update(status='UPLOADED_VERIFIED',file_id=observed['ID'],sha256=hashlib.sha256(path.read_bytes()).hexdigest(),error='')
                    failures=0
                except Exception as error:
                    # An identity hold is expected review work, not evidence of
                    # transport failure. Continue other chart rows without retrying it.
                    failures=0 if isinstance(error,ValueError) else failures+1
                    row.update(status='HELD_REVIEW',error=str(error)[-1000:])
                    if access_blocked(str(error)) or failures>=5 or 'headroom' in str(error):
                        snapshot(root,rows,False,'Paused: source access, storage or five consecutive failures. Resume is safe.');return
                snapshot(root,rows,True)
                print(row['id'],row['status'],'|',row['artist'],'-',row['title'],flush=True)
                if row['status']=='HELD_REVIEW':print('  Reason:',row.get('error','')[:220],flush=True)
            snapshot(root,rows,False,'Finished; held recordings remain separate from verified transfers')
        finally:db.close()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True)
    p.add_argument('--snapshots',type=Path);p.add_argument('--catalog',type=Path);p.add_argument('--plan',action='store_true')
    p.add_argument('--retry-held',action='store_true',help='Explicitly retry prior review holds')
    p.add_argument('--reuse-only',action='store_true',help='Only reuse cached owned files; make zero network requests')
    p.add_argument('--links-only',action='store_true',help='Cache public search candidates; do not inspect/download/approve audio')
    p.add_argument('--download-only',action='store_true',help='Download verified audio locally; no Drive credentials or uploads')
    p.add_argument('--official-visuals',action='store_true',help='Accept artist-verified official visual audio only within 2s of the recording runtime')
    p.add_argument('--config',type=Path);p.add_argument('--folder-id');p.add_argument('--rclone',default='rclone')
    a=p.parse_args()
    if not (a.plan or a.links_only or a.download_only) and (not a.config or not re.fullmatch(r'[A-Za-z0-9_-]{10,150}',a.folder_id or '')):p.error('Exact Drive root and private config required')
    collect(a)
if __name__=='__main__':main()
