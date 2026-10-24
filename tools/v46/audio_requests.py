"""Process Odify audio reports and explicit source overrides, preserving Drive IDs.

Report-only is the default. --process-links performs user-pasted replacements;
--resolve-reports additionally searches candidates for previously reported tracks.
No MASTER edits, shared credentials, DRM handling, or access-gate workarounds.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,re,sqlite3,subprocess,sys,time
from pathlib import Path
from urllib.parse import urlparse,parse_qs,urlencode
from urllib.request import Request,urlopen

MAX_BYTES=128*1024*1024
ID=re.compile(r'[A-Za-z0-9_-]{10,150}\Z')
UUID=re.compile(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\Z')
INPUT_FIELDS=('schema','request_id','kind','song_id','drive_file_id','expected_md5','title','artist','album','duration_ms','youtube_url','reason','created_at')

class SourceAccessPaused(RuntimeError):pass

def source_call(call):
    try:return call()
    except Exception as error:
        text=str(error).lower()
        if any(x in text for x in ('429','too many requests','not a bot','sign in','403')):raise SourceAccessPaused() from error
        raise

def youtube_url(value):
    u=urlparse(value.strip())
    if u.scheme!='https' or u.username or u.password or u.port is not None:raise ValueError('Expected https YouTube video URL')
    if u.hostname=='youtu.be':video=u.path.lstrip('/')
    elif u.hostname in ('youtube.com','www.youtube.com','music.youtube.com','m.youtube.com'):
        values=parse_qs(u.query).get('v',[])
        video=values[0] if u.path=='/watch' and len(values)==1 else u.path.rsplit('/',1)[-1] if u.path.startswith(('/shorts/','/embed/')) else ''
    else:video=''
    if not re.fullmatch(r'[A-Za-z0-9_-]{11}',video):raise ValueError('Expected one YouTube video, not playlist')
    return 'https://www.youtube.com/watch?v='+video

def validate(body):
    if body.get('schema')!=1 or not UUID.fullmatch(body.get('request_id','')):raise ValueError('Invalid request schema/ID')
    if body.get('kind') not in ('report','paste_link'):raise ValueError('Invalid request kind')
    target=body.get('drive_file_id','')
    if target and (not ID.fullmatch(target) or body.get('song_id')!='gdrive:'+target):raise ValueError('Target ID mismatch')
    if body['kind']=='paste_link':
        if not target:raise ValueError('Drive target required')
        youtube_url(body.get('youtube_url',''))
    md5=body.get('expected_md5','')
    if md5 and not re.fullmatch('[a-fA-F0-9]{32}',md5):raise ValueError('Invalid expected checksum')
    for k in ('title','artist','album','reason'):
        if not isinstance(body.get(k,''),str) or len(body.get(k,''))>2000:raise ValueError('Invalid request text')
    return body

def digest(path,kind='md5'):
    with Path(path).open('rb') as f:return hashlib.file_digest(f,kind).hexdigest()

def connect(path):
    db=sqlite3.connect(path,timeout=30);db.row_factory=sqlite3.Row
    db.executescript('''PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS audio_request(
      id TEXT PRIMARY KEY,drive_id TEXT NOT NULL,kind TEXT NOT NULL,input TEXT NOT NULL,
      status TEXT NOT NULL,detail TEXT NOT NULL DEFAULT '',old_md5 TEXT NOT NULL DEFAULT '',
      new_md5 TEXT NOT NULL DEFAULT '',new_path TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '');
    CREATE TABLE IF NOT EXISTS audio_source_override(
      drive_id TEXT PRIMARY KEY,request_id TEXT NOT NULL,source_url TEXT NOT NULL,
      old_md5 TEXT NOT NULL,new_md5 TEXT NOT NULL,local_path TEXT NOT NULL,updated_at TEXT NOT NULL);
    ''');return db

class Drive:
    def __init__(self,rclone,config):self.rc=[rclone,'--config',str(config),'--drive-root-folder-id','root'];self.token=''
    def renew(self):
        # rclone refreshes its own account. Never emit its token/config output.
        subprocess.run(self.rc+['lsf','mydrive:','--dirs-only','--max-depth','1'],capture_output=True,check=True,timeout=90)
        result=subprocess.run(self.rc+['config','dump'],capture_output=True,check=True,timeout=30)
        cfg=json.loads(result.stdout)['mydrive'];tok=cfg['token']
        self.token=(json.loads(tok) if isinstance(tok,str) else tok)['access_token']
    def call(self,method,path,data=None,mime='application/json',upload=False):
        if not self.token:self.renew()
        base='https://www.googleapis.com/'+('upload/' if upload else '')+'drive/v3/'
        req=Request(base+path,data=data,method=method,headers={'Authorization':'Bearer '+self.token,'Content-Type':mime})
        with urlopen(req,timeout=120) as resp:
            raw=resp.read(MAX_BYTES+1)
            if len(raw)>MAX_BYTES:raise ValueError('Drive response exceeds size limit')
            return raw
    def json(self,method,path,body=None,upload=False):
        return json.loads(self.call(method,path,json.dumps(body).encode() if body is not None else None,upload=upload))
    def listing(self,q):
        items=[];page=''
        while True:
            result=self.json('GET','files?'+urlencode(dict(q=q,fields='nextPageToken,files(id,name,mimeType)',pageSize=1000,pageToken=page)))
            items+=result['files'];page=result.get('nextPageToken','')
            if not page:return items
    def requests(self):
        roots=self.listing("name='Odify_Audio_Requests' and mimeType='application/vnd.google-apps.folder' and trashed=false")
        for root in roots:
            for item in self.listing("'"+root['id']+"' in parents and mimeType='application/json' and trashed=false"):
                if re.fullmatch(r'request-[0-9a-f-]{36}\.json',item['name']):
                    data=self.call('GET','files/'+item['id']+'?alt=media')
                    if len(data)>32768:continue
                    body=json.loads(data)
                    if item['name']=='request-'+body.get('request_id','')+'.json':yield item['id'],body
    def metadata(self,target):
        return self.json('GET','files/'+target+'?'+urlencode({'fields':'id,name,mimeType,md5Checksum,size,trashed,headRevisionId,capabilities/canEdit'}))
    def download(self,target,path):path.write_bytes(self.call('GET','files/'+target+'?alt=media'))
    def pin(self,target,revision):
        if revision:self.json('PATCH','files/'+target+'/revisions/'+revision,{'keepForever':True})
    def replace(self,target,path,mime):
        self.call('PATCH','files/'+target+'?uploadType=media',path.read_bytes(),mime,upload=True)
    def publish(self,remote_id,body):
        self.call('PATCH','files/'+remote_id+'?uploadType=media',json.dumps(body,ensure_ascii=False).encode(),upload=True)

def blocked(root):
    # Source restriction is persistent until the operator resolves it. Do not
    # move the same restricted requests to another client/network/provider.
    if (root/'SOURCE_PAUSED').exists():return True
    status=root.parent/'History'/'chart_status.json'
    if status.exists():
        for row in json.loads(status.read_text('utf-8')).get('tracks',[]):
            error=row.get('error','').lower()
            if any(x in error for x in ('429','too many requests','not a bot','sign in to confirm you')) and 'age' not in error:return True
    return False

def source_download(body,folder,old_path,resolve=False):
    from mutagen import File as AudioFile
    from yt_dlp import YoutubeDL
    spec=importlib.util.spec_from_file_location('request_charts',Path(__file__).with_name('odify_charts.py'))
    charts=importlib.util.module_from_spec(spec);spec.loader.exec_module(charts)
    old=AudioFile(old_path);duration=old.info.length if old else 0
    row=dict(id=body['request_id'],title=body['title'],artist=body['artist'],album=body.get('album',''),
             duration_ms=round(duration*1000),country='kr',artwork='')
    if resolve:
        for name in ('Metadata','Links','Artwork'): (folder/name).mkdir(exist_ok=True)
        match=source_call(lambda:charts.select_quick_candidate(row,folder))
        if not match:raise ValueError('No matching non-live equal-length recording found')
        url='https://www.youtube.com/watch?v='+match['id']
    else:url=youtube_url(body['youtube_url'])
    # Keep the existing container/mime so all Drive consumers remain compatible.
    ext=old_path.suffix.lower().lstrip('.');fmt={'m4a':'m4a','mp3':'mp3','flac':'flac','wav':'wav','ogg':'vorbis','opus':'opus'}.get(ext)
    if not fmt:raise ValueError('Unsupported target container; existing audio preserved')
    with YoutubeDL(dict(quiet=True,no_warnings=True,noplaylist=True,retries=0,extractor_retries=0,
        socket_timeout=25,format='bestaudio/best',outtmpl=str(folder/'replacement.%(ext)s'),
        match_filter=lambda info,**kw:'Live stream not supported' if info.get('is_live') else None,
        postprocessors=[{'key':'FFmpegExtractAudio','preferredcodec':fmt,'preferredquality':'0'}])) as ydl:
        source_call(lambda:ydl.extract_info(url,download=True))
    output=folder/('replacement.'+ext)
    audio=AudioFile(output)
    if not audio or not (1<audio.info.length<1800) or output.stat().st_size>MAX_BYTES:raise ValueError('Downloaded audio validation failed')
    # The pasted link is an explicit selection, not an automatic title match.
    # Copy existing embedded art when possible; do not fetch lyrics here.
    cover=None
    if old is not None:
        if hasattr(old,'pictures') and old.pictures:cover=old.pictures[0].data
        elif old.tags:
            if hasattr(old.tags,'getall'):
                pics=old.tags.getall('APIC');cover=pics[0].data if pics else None
            elif old.tags.get('covr'):cover=bytes(old.tags['covr'][0])
    if ext in ('mp3','m4a'):
        charts.audio.tag_audio(output,body['artist'],body['title'],body.get('album',''),'',cover,None)
    elif ext in ('flac','ogg','opus'):
        audio['title']=body['title'];audio['artist']=body['artist'];audio['album']=body.get('album','');audio.save()
    return output,url

def process(db,drive,body,root,process_links=False,resolve_reports=False,downloader=source_download):
    validate(body);identity=body['request_id'];target=body.get('drive_file_id','')
    raw=json.dumps({k:body.get(k) for k in INPUT_FIELDS},sort_keys=True,ensure_ascii=False)
    prior=db.execute('SELECT * FROM audio_request WHERE id=?',(identity,)).fetchone()
    if prior and prior['input']!=raw:raise ValueError('Existing request input changed')
    db.execute('INSERT OR IGNORE INTO audio_request(id,drive_id,kind,input,status) VALUES(?,?,?,?,?)',
        (identity,target,body['kind'],raw,'REPORTED' if body['kind']=='report' else 'QUEUED'));db.commit()
    def finish(status,detail):
        db.execute('UPDATE audio_request SET status=?,detail=? WHERE id=?',(status,detail,identity));db.commit()
        return {**body,'status':status,'detail':detail,'replacement_md5':db.execute('SELECT new_md5 FROM audio_request WHERE id=?',(identity,)).fetchone()[0]}
    prior=db.execute('SELECT * FROM audio_request WHERE id=?',(identity,)).fetchone()
    if prior['status']=='COMPLETED':return finish('COMPLETED','Verified same Drive file ID; previous version backed up')
    if body['kind']=='report' and not resolve_reports:return finish('REPORTED','Audio error recorded; no file modified')
    if body['kind']=='paste_link' and not process_links:return finish('QUEUED','Waiting for PC audio processor')
    if not target:return finish('REPORTED','Local-only recording: no Drive target inferred')
    try:
        meta=drive.metadata(target)
        if meta.get('id')!=target or meta.get('trashed') or not meta.get('mimeType','').startswith('audio/') or int(meta.get('size',0))>MAX_BYTES:
            return finish('CONFLICT','Target is unavailable or not a supported audio file')
        expected=prior['old_md5'] or body.get('expected_md5') or meta['md5Checksum']
        # Recover a crash after upload but before the completion receipt.
        if prior['new_md5'] and meta['md5Checksum']==prior['new_md5']:
            return complete(db,identity,target,prior['source_url'],expected,prior['new_md5'],prior['new_path'],finish)
        if meta['md5Checksum'].lower()!=expected.lower():return finish('CONFLICT','Drive audio changed since request; no overwrite')
        if blocked(root):return finish('SOURCE_PAUSED','Source access requires operator attention; no extraction attempted')
        folder=root/identity;folder.mkdir(parents=True,exist_ok=True)
        ext=Path(meta['name']).suffix.lower()
        backup=folder/('previous'+ext)
        if not backup.exists():
            temp=backup.with_suffix(ext+'.partial');drive.download(target,temp)
            if digest(temp)!=expected:raise ValueError('Backup checksum mismatch')
            temp.replace(backup)
        if digest(backup)!=expected:raise ValueError('Existing backup changed')
        db.execute('UPDATE audio_request SET old_md5=?,status=? WHERE id=?',(expected,'PROCESSING',identity));db.commit()
        if prior['new_path'] and Path(prior['new_path']).is_file() and digest(Path(prior['new_path']))==prior['new_md5']:
            output,url=Path(prior['new_path']),prior['source_url']
        else:output,url=downloader(body,folder,backup,resolve=body['kind']=='report')
        new_md5=digest(output)
        db.execute('UPDATE audio_request SET new_md5=?,new_path=?,source_url=? WHERE id=?',(new_md5,str(output),url,identity));db.commit()
        fresh=drive.metadata(target)
        if fresh['md5Checksum']!=expected:return finish('CONFLICT','Target changed during download; new file kept separately')
        drive.pin(target,fresh.get('headRevisionId',''))
        drive.replace(target,output,meta['mimeType'])
        after=drive.metadata(target)
        if after['id']!=target or after.get('md5Checksum')!=new_md5 or int(after.get('size',0))!=output.stat().st_size:
            raise ValueError('Upload not verified; backup retained for recovery')
        return complete(db,identity,target,url,expected,new_md5,str(output),finish)
    except Exception as error:
        if isinstance(error,SourceAccessPaused):
            (root/'SOURCE_PAUSED').write_text('Source authentication/rate gate encountered. Resolve before processing again.\n','utf-8')
            return finish('SOURCE_PAUSED','Source access paused; original audio and backup retained')
        return finish('FAILED',str(error)[-500:])

def complete(db,identity,target,url,old_md5,new_md5,path,finish):
    db.execute('INSERT OR REPLACE INTO audio_source_override VALUES(?,?,?,?,?,?,?)',
        (target,identity,url,old_md5,new_md5,path,time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())))
    db.commit();return finish('COMPLETED','Verified same Drive file ID; previous version backed up')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(r'D:\Odify\AudioRequests'))
    p.add_argument('--config',type=Path,required=True);p.add_argument('--rclone',default='rclone')
    p.add_argument('--process-links',action='store_true');p.add_argument('--resolve-reports',action='store_true')
    p.add_argument('--watch-seconds',type=int,default=0)
    args=p.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    # Reuse the existing exclusive Windows collector lock, once for this worker.
    spec=importlib.util.spec_from_file_location('requests_worker',Path(__file__).with_name('odify_charts.py'))
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    with mod.single_instance(args.root),connect(args.root/'audio_requests.sqlite3') as db:
        drive=Drive(args.rclone,args.config);deadline=time.monotonic()+max(0,args.watch_seconds)
        while True:
            try:
                for remote_id,body in drive.requests():
                    try:
                        result=process(db,drive,body,args.root,args.process_links,args.resolve_reports)
                        if any(result.get(k)!=body.get(k) for k in ('status','detail','replacement_md5')):drive.publish(remote_id,result)
                        print(body.get('request_id'),result['status'],flush=True)
                    except ValueError as e:print('Invalid request ignored:',str(e)[:150],flush=True)
            except Exception:print('Drive connection unavailable; requests remain durable for next run',flush=True)
            if time.monotonic()>=deadline:break
            time.sleep(min(30,max(0,deadline-time.monotonic())))

if __name__=='__main__':main()
