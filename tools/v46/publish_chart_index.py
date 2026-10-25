"""Append-only chart-to-owned-Drive identity snapshots; no audio or MASTER writes."""
import argparse,hashlib,json,re,subprocess,time,uuid
from pathlib import Path
from datetime import datetime,timezone

def mappings(status):
    result={}
    for row in status['tracks']:
        if row['status'] not in ('UPLOADED_VERIFIED','REUSED_EXISTING'):continue
        identity=row.get('file_id','')
        if not re.fullmatch(r'[A-Za-z0-9_-]{10,150}',identity):continue
        for occurrence in row['appearances']:
            result[occurrence['id']]={'driveId':identity,'title':row['title'],'artist':row['artist']}
    return result
def publish(args):
    status=json.loads((args.root/'chart_status.json').read_text(encoding='utf-8'))
    data={'schemaVersion':1,'mappings':mappings(status)}
    raw=json.dumps(data,ensure_ascii=False,sort_keys=True).encode('utf-8')
    digest=hashlib.sha256(raw).hexdigest()
    checkpoint=args.root/'index_published.json'
    previous=json.loads(checkpoint.read_text(encoding='utf-8')) if checkpoint.exists() else {}
    if digest!=previous.get('sha256') and data['mappings']:
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')
        name=f'odify_chart_index_{stamp}_{uuid.uuid4().hex[:8]}.json'
        local=args.root/'Metadata'/name;local.write_bytes(raw)
        destination=f'mydrive:Charts_{args.root.name}/{name}'
        command=[args.rclone,'--config',str(args.config),'--drive-root-folder-id',args.folder_id]
        subprocess.run(command+['copyto',str(local),destination,'--immutable'],check=True,capture_output=True,timeout=120)
        observed=json.loads(subprocess.run(command+['lsjson',destination,'--stat','--hash'],check=True,capture_output=True,text=True,timeout=120).stdout)
        if observed['Size']!=len(raw) or observed['Hashes']['md5']!=hashlib.md5(raw).hexdigest():raise RuntimeError('Index verification failed')
        checkpoint.write_text(json.dumps({'sha256':digest,'drive_id':observed['ID'],'entries':len(data['mappings']),'at':stamp}),encoding='utf-8')
        print('Verified chart mappings:',len(data['mappings']),flush=True)
    return status.get('running',False)
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True)
    p.add_argument('--config',type=Path,required=True);p.add_argument('--folder-id',required=True)
    p.add_argument('--rclone',default='rclone');p.add_argument('--watch',action='store_true');args=p.parse_args()
    while True:
        try:running=publish(args)
        except Exception as e:
            print(type(e).__name__+': '+str(e)[-500:],flush=True)
            if not args.watch:raise
            running=True
        if not args.watch or not running:break
        time.sleep(60)
if __name__=='__main__':main()
