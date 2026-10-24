"""Resumable, separately scoped chart collection. Never edits the user's MASTER."""
from __future__ import annotations
import argparse, csv, hashlib, json, os, re, sqlite3, subprocess, sys, time, unicodedata
from pathlib import Path
from datetime import datetime, timezone
from contextlib import closing
from urllib.request import Request, urlopen

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
def resolve(row,root):
    pairs=variants(row,root)
    candidates=[]
    for artist,title in pairs[:2]:
        listing=json.loads(execute([sys.executable,'-m','yt_dlp','--flat-playlist','--dump-single-json',
            '--socket-timeout','20','--retries','1',f'ytsearch5:{artist} {title} official audio'],120))
        candidates.extend(listing.get('entries') or [])
    seen=set()
    for candidate in candidates:
        video=candidate.get('id','')
        if video in seen or not re.fullmatch(r'[A-Za-z0-9_-]{11}',video):continue
        seen.add(video);url='https://www.youtube.com/watch?v='+video
        try:
            info=json.loads(execute([sys.executable,'-m','yt_dlp','--no-playlist','--skip-download',
                '--dump-single-json','--socket-timeout','20','--retries','1',url],120))
        except Exception as error:
            # An individual recording may require age authorization. Hold that
            # recording without cookies/authentication, but do not classify it
            # as a site-wide challenge or rate limit for the other chart songs.
            if 'sign in to confirm your age' in str(error).casefold():
                raise ValueError('Age-restricted recording held; no authentication attempted') from error
            if access_blocked(str(error)):raise
            continue
        target=row.get('duration_ms',0)/1000
        if target and abs((info.get('duration') or 0)-target)>5:continue
        for artist,title in pairs:
            expected={'Track ID':'CHART'+row['id'],'곡명':title,'아티스트':artist}
            if candidate_matches(expected,info):
                audio.inspect_audio_video(url,title,artist=artist)
                return url,artist,title,{k:info.get(k) for k in ('id','track','title','artist','channel','duration')}
    raise ValueError('No unambiguous original studio recording; held for review')
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
def collect(args):
    root=args.root.resolve();root.mkdir(parents=True,exist_ok=True)
    with single_instance(root):
        status=root/'chart_status.json'
        rows=json.loads(status.read_text(encoding='utf-8'))['tracks'] if status.exists() else prepare(root,args.snapshots)
        if args.plan:
            print(json.dumps(snapshot(root,rows,False,'Preview; no collection or upload'),ensure_ascii=False)[:400]);return
        known=existing_catalog(args.catalog)
        db=audio.connection(root/'charts.sqlite3')
        rc=[args.rclone,'--config',str(args.config),'--drive-root-folder-id',args.folder_id]
        # One exact existing library root; all new files live under a dated child.
        remote='mydrive:Charts_'+root.name+'/Audio/'
        snapshot(root,rows,True)
        failures=0
        try:
            for row in rows:
                if row['status'] in ('UPLOADED_VERIFIED','REUSED_EXISTING') or row['status']=='HELD_REVIEW' and not args.retry_held:continue
                try:
                    candidates=variants(row,root)
                    existing=next((known.get(norm(a)+'|'+norm(t)) for a,t in candidates if known.get(norm(a)+'|'+norm(t))),None)
                    if existing:
                        failures=0
                        row.update(status='REUSED_EXISTING',file_id=existing,error='');snapshot(root,rows,True);continue
                    row.update(status='LINK_REVIEW',error='');snapshot(root,rows,True)
                    url,artist,title,evidence=resolve(row,root)
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
                        audio.download_one(db,(identity,artist,title,row['album'],'',url),root,True,True,audio_format='mp3')
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
                print(row['id'],row['status'],flush=True)
            snapshot(root,rows,False,'Finished; held recordings remain separate from verified transfers')
        finally:db.close()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True)
    p.add_argument('--snapshots',type=Path);p.add_argument('--catalog',type=Path);p.add_argument('--plan',action='store_true')
    p.add_argument('--retry-held',action='store_true',help='Explicitly retry prior review holds')
    p.add_argument('--config',type=Path);p.add_argument('--folder-id');p.add_argument('--rclone',default='rclone')
    a=p.parse_args()
    if not a.plan and (not a.config or not re.fullmatch(r'[A-Za-z0-9_-]{10,150}',a.folder_id or '')):p.error('Exact Drive root and private config required')
    collect(a)
if __name__=='__main__':main()
