"""One-shot local supervisor for the authorized history collection (not a schedule)."""
from __future__ import annotations
import argparse,collections,ctypes,importlib.util,json,subprocess,sys,time,traceback
from datetime import datetime,timezone
from pathlib import Path

spec=importlib.util.spec_from_file_location('history_music',Path(__file__).with_name('history_music.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def read_state(root):return json.loads((root/'chart_status.json').read_text('utf-8'))

def update(root,phase,**details):
    value=dict(phase=phase,updated_at=datetime.now(timezone.utc).isoformat(),**details)
    try:value['counts']=read_state(root)['counts']
    except (OSError,ValueError):pass
    m.w.write_cache(root/'background_status.json',value);print(json.dumps(value,ensure_ascii=False),flush=True)

def run_child(args,root,phase,command):
    update(root,phase,command=Path(command[1]).name)
    log=root/(phase+'.log')
    with log.open('a',encoding='utf-8') as stream:
        child=subprocess.Popen(command,cwd=m.ROOT,stdout=stream,stderr=subprocess.STDOUT)
        while child.poll() is None:
            update(root,phase,child_pid=child.pid);time.sleep(30)
    if child.returncode:raise RuntimeError(phase+' failed, see '+str(log))

def wait_existing(root,pid,quiet=False):
    if not pid:return
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[ctypes.c_uint32,ctypes.c_int,ctypes.c_uint32]
    kernel.OpenProcess.restype=ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_uint32]
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    handle=kernel.OpenProcess(0x00100000,0,pid)
    if not handle:return
    try:
        while kernel.WaitForSingleObject(handle,30000)==0x102:
            if not quiet:update(root,'existing_download',child_pid=pid)
    finally:kernel.CloseHandle(handle)

def source_blocked(rows):
    return any(m.w.access_blocked(r.get('error','')) and not m.w.age_restricted(r.get('error','')) for r in rows)

def finalize(root):
    rows=read_state(root)['tracks'];verified={};errors=[]
    for row in rows:
        if row['status']=='QUEUED' and not row.get('duration_ms'):
            row.update(status='NEED_METADATA',error='Reference duration/identity could not be verified from available catalogs/searches')
        if row['status']=='DOWNLOADED_VERIFIED' and not row.get('local_path'):
            pool=root/'Audio'/('CHART'+row['id']+'.mp3')
            if pool.is_file() and m.digest(pool)==row.get('sha256'):
                try:m.place(root,row,pool)
                except ValueError:
                    # An earlier receipt can describe different bytes. Keep
                    # that file and recover the verified pool under a new name.
                    first=row['appearances'][0];year,month=first['year_month'].split('-')
                    target=root/year/month/first['country']/'Audio'/('HIST'+row['id']+'-'+row['sha256'][:12]+'.mp3')
                    if target.exists() and m.digest(target)!=row['sha256']:raise
                    if not target.exists():
                        import shutil
                        shutil.copy2(pool,target)
                    if m.digest(target)!=row['sha256']:raise ValueError('Recovery checksum mismatch')
                    row.update(local_path=str(target.relative_to(root)),recovered_without_overwrite=True)
        if not row.get('local_path'):continue
        path=root/row['local_path']
        if path not in verified:verified[path]=m.digest(path) if path.is_file() else None
        if verified[path]!=row.get('sha256'):
            row.update(status='HELD_CHANGED_FILE',error='Local file missing or hash changed; existing file protected');errors.append(row['id'])
    receipt=root/'drive_upload_status.json'
    if receipt.exists():
        cloud=json.loads(receipt.read_text('utf-8')).get('files',{})
        for row in rows:
            item=cloud.get(row['id'])
            if item and item.get('sha256')==row.get('sha256') and item['status'] in ('VERIFIED_EXISTING','UPLOADED_VERIFIED'):
                row.update(file_id=item['id'],upload_status=item['status'])
    m.save(root,rows,'Verified final monthly receipts; unresolved items remain explicit')
    return rows,errors

def run(args):
    # Keep this process awake while it supervises transfers; do not modify the
    # user's power plan. Windows releases the request when this process exits.
    ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
    try:
        if not args.wait_quiet:update(args.root,'existing_download' if args.wait_pid else 'starting',child_pid=args.wait_pid)
        wait_existing(args.root,args.wait_pid,args.wait_quiet)
        rows=read_state(args.root)['tracks'];blocked=source_blocked(rows)
        if not blocked and not args.finalize_only:
            base=[sys.executable,'-u',str(Path(__file__).with_name('history_music.py'))]
            run_child(args,args.root,'refresh_metadata',base+['refresh','--root',str(args.root)])
            # One bounded retry for prior "no equal-length" holds after metadata
            # and version guards have been strengthened. Never retry age gates.
            with m.w.single_instance(args.root):
                rows=read_state(args.root)['tracks']
                for row in rows:
                    if row['status']=='HELD_REVIEW' and 'No equal-length' in row.get('error','') and not row.get('history_retry_done'):
                        row.update(status='QUEUED',error='',history_retry_done=True)
                m.save(args.root,rows,'One bounded retry for unresolved links; age gates remain skipped')
            run_child(args,args.root,'remaining_download',base+['download','--root',str(args.root),'--workers','8'])
        with m.w.single_instance(args.root):finalize(args.root)
        if args.config and args.folder_id:
            run_child(args,args.root,'drive_upload',[sys.executable,'-u',str(Path(__file__).with_name('publish_history_music.py')),
                '--root',str(args.root),'--config',str(args.config),'--folder-id',args.folder_id,'--rclone',args.rclone])
        with m.w.single_instance(args.root):rows,errors=finalize(args.root)
        blocked=source_blocked(rows)
        unresolved=sum(r['status'] not in ('DOWNLOADED_VERIFIED','REUSED_LOCAL','REUSED_HISTORY') for r in rows)
        update(args.root,'source_paused' if blocked else 'finished_with_gaps' if unresolved or errors else 'finished',
            unresolved=unresolved,changed_files=errors,verified_unique_paths=len({r.get('local_path') for r in rows if r.get('local_path')}))
    except Exception:
        update(args.root,'failed',error=traceback.format_exc()[-3000:]);raise
    finally:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(r'D:\Odify\History'))
    p.add_argument('--wait-pid',type=int,default=0);p.add_argument('--config',type=Path);p.add_argument('--folder-id')
    p.add_argument('--wait-quiet',action='store_true');p.add_argument('--finalize-only',action='store_true')
    p.add_argument('--rclone',default='rclone');run(p.parse_args())
if __name__=='__main__':main()
