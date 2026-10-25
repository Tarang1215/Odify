"""Upload verified monthly audio within free-space budget; never sync/delete originals."""
from __future__ import annotations
import argparse,collections,hashlib,importlib.util,json,subprocess,time
from pathlib import Path

spec=importlib.util.spec_from_file_location('history_music',Path(__file__).with_name('history_music.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def command(args,timeout=900):
    result=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if result.returncode:raise RuntimeError((result.stderr or result.stdout)[-1500:])
    return result.stdout

def run(args):
    rc=[args.rclone,'--config',str(args.config),'--drive-root-folder-id',args.folder_id]
    quota=json.loads(command(rc+['about','mydrive:','--json']))
    inventory=json.loads(command(rc+['lsjson','mydrive:','--recursive','--files-only','--hash'],300))
    remote_by_md5=collections.defaultdict(list)
    for item in inventory:
        value=item.get('Hashes',{}).get('md5')
        if value:remote_by_md5[(item['Size'],value)].append(item)
    receipt_path=args.root/'drive_upload_status.json'
    receipts=json.loads(receipt_path.read_text('utf-8')) if receipt_path.exists() else {'files':{}}
    rows=json.loads((args.root/'chart_status.json').read_text('utf-8'))['tracks']
    groups=collections.defaultdict(list);reused=0
    for row in rows:
        if row['status'] not in ('DOWNLOADED_VERIFIED','REUSED_LOCAL','REUSED_HISTORY') or not row.get('local_path'):continue
        path=args.root/row['local_path']
        if not path.is_file():continue
        if m.digest(path)!=row.get('sha256'):raise ValueError('Local hash changed; upload refused: '+row['id'])
        md5=m.digest(path,'md5');matching=remote_by_md5.get((path.stat().st_size,md5))
        if matching:
            item=matching[0];receipts['files'][row['id']]={'status':'VERIFIED_EXISTING','id':item['ID'],'md5':md5,
                'bytes':item['Size'],'remote_path':item['Path'],'sha256':row['sha256']};reused+=1;continue
        first=row['appearances'][0];groups[(first['year_month'],first['country'])].append((row,path,md5))
    remaining=max(0,int(quota.get('free',0))-args.reserve_bytes);chosen=[];waiting=[]
    for key,entries in sorted(groups.items()):
        size=sum(path.stat().st_size for row,path,md5 in entries)
        if size>remaining:
            waiting.extend(row['id'] for row,path,md5 in entries);continue
        chosen.extend(entries);remaining-=size
    stamp=time.strftime('%Y%m%dT%H%M%S');plan=m.ROOT/'.local/history2024'/('drive_upload_'+stamp+'.txt')
    plan.write_text(''.join(path.relative_to(args.root).as_posix()+'\n' for row,path,md5 in chosen),encoding='utf-8')
    receipts.update(free_before=quota.get('free'),reserve_bytes=args.reserve_bytes,
        queued_upload=len(chosen),waiting_for_space=waiting,reused_existing=reused,running=bool(chosen))
    m.w.write_cache(receipt_path,receipts)
    print('DRIVE PLAN',len(chosen),'new;',reused,'existing;',len(waiting),'space-limited',flush=True)
    if chosen:
        command(rc+['copy',str(args.root),'mydrive:History','--files-from-raw',str(plan),
            '--immutable','--transfers','4','--checkers','4','--retries','2'],7200)
        after=json.loads(command(rc+['lsjson','mydrive:History','--recursive','--files-only','--hash'],300))
        by_path={item['Path']:item for item in after}
        for row,path,md5 in chosen:
            relative=path.relative_to(args.root).as_posix();item=by_path.get(relative)
            if not item or item['Size']!=path.stat().st_size or item.get('Hashes',{}).get('md5')!=md5:
                raise ValueError('Remote checksum verification failed: '+relative)
            receipts['files'][row['id']]={'status':'UPLOADED_VERIFIED','id':item['ID'],'md5':md5,
                'bytes':item['Size'],'remote_path':'History/'+relative,'sha256':row['sha256']}
        print('DRIVE UPLOAD VERIFIED',len(chosen),flush=True)
    receipts.update(running=False,free_after=json.loads(command(rc+['about','mydrive:','--json'])).get('free'))
    m.w.write_cache(receipt_path,receipts)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(r'D:\Odify\History'))
    p.add_argument('--config',type=Path,required=True);p.add_argument('--folder-id',required=True)
    p.add_argument('--rclone',default='rclone');p.add_argument('--reserve-bytes',type=int,default=1024**3)
    run(p.parse_args())
if __name__=='__main__':main()
