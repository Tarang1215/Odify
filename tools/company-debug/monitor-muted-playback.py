"""Monitor real background playback without instrumentation pinning the process.
Does not start playback or change volume. Process-death simulation is opt-in.
"""
import argparse,json,os,re,shutil,subprocess,time
from pathlib import Path

PKG='com.odify.music.nativepreview.v11.debug'

def sessions(raw):
    lines=raw.splitlines();result=[]
    for i,line in enumerate(lines):
        if line.strip()!='package='+PKG:continue
        indent=len(line)-len(line.lstrip());start=i
        while start>0 and i-start<10:
            previous=lines[start-1]
            if previous.strip() and len(previous)-len(previous.lstrip())<indent:break
            start-=1
        header=lines[start-1].strip() if start else ''
        end=i+1
        while end<len(lines):
            row=lines[end]
            if row.strip() and len(row)-len(row.lstrip())<indent:break
            end+=1
        block='\n'.join(lines[start:end]);state=re.search(r'state=([A-Z_]+)\((\d+)\)',block)
        title=re.search(r'metadata:.*description=(.*)',block)
        result.append(dict(header=header,state=int(state.group(2)) if state else 0,
            description=title.group(1)[:200] if title else ''))
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=int,default=180)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--simulate-process-death',action='store_true',help='Explicitly terminate ONLY the background Odify YouTube host once; test sticky recovery')
    parser.add_argument('--self-test',action='store_true')
    args=parser.parse_args()
    if args.self_test:
        sample=f'  Sessions Stack:\n    Odify {PKG}/Odify/7\n      ownerPid=123\n      package={PKG}\n      active=true\n      state=PlaybackState {{state=PLAYING(3), position=1000}}\n      metadata: size=12, description=Song, Artist, Album\n    Other other.package/Player/9\n      package=other.package\n      metadata: private other song\n'
        rows=sessions(sample)
        assert len(rows)==1 and rows[0]['state']==3 and 'Other' not in str(rows)
        print('Session parser self-test passed; no device accessed.');return
    if not 15<=args.seconds<=1800:parser.error('--seconds must be 15..1800')
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    adb=str(Path(sdk)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')) if sdk else shutil.which('adb')
    if not adb:parser.error('Set ANDROID_HOME or put adb on PATH')
    def call(*items):return subprocess.check_output([adb,*items],text=True,encoding='utf-8',errors='replace',timeout=15)
    devices=[s for s in call('devices').splitlines() if s.endswith('\tdevice')]
    if len(devices)!=1:raise RuntimeError('Exactly one authorized device is required')
    base=args.output or Path(__file__).resolve().parents[2]/'.local/company-debug/background'/time.strftime('%Y%m%d-%H%M%S')
    base.mkdir(parents=True,exist_ok=True)
    began=time.monotonic();next_report=0;missing_at=None;terminated=False;recovered=False;initial_pid=''
    try:
        with (base/'samples.jsonl').open('w',encoding='utf-8') as log:
            while time.monotonic()-began<args.seconds:
                volume=re.search(r'volume is (\d+)',call('shell','cmd','media_session','volume','--stream','3','--get'))
                if not volume or int(volume.group(1))!=0:raise RuntimeError('Muted test stopped: music volume is not zero; volume was not changed')
                process=subprocess.run([adb,'shell','pidof',PKG],capture_output=True,text=True,
                    encoding='utf-8',errors='replace',timeout=15)
                pid=process.stdout.strip() if process.returncode==0 else ''
                rows=sessions(call('shell','dumpsys','media_session'))
                playing=any(row['state'] in (3,6,8) for row in rows)
                elapsed=round(time.monotonic()-began,1)
                if not initial_pid:
                    if not pid or not playing:raise RuntimeError('Start Odify playback at volume zero, then return to the launcher before monitoring')
                    initial_pid=pid
                row=dict(seconds=elapsed,pid=pid,sessions=rows,volume=0)
                log.write(json.dumps(row,ensure_ascii=False)+'\n');log.flush()
                if elapsed>=next_report:print(json.dumps(row,ensure_ascii=True),flush=True);next_report=elapsed+10
                if args.simulate_process_death and not terminated and elapsed>=10:
                    if not any('/Odify/' in r['header'] and r['state'] in (3,6,8) for r in rows):
                        raise RuntimeError('Process-death simulation requires the YouTube playback session, not native-only audio')
                    focus=next((s for s in call('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in s),'')
                    if PKG in focus:raise RuntimeError('Refusing to terminate a foreground app; return to the launcher first')
                    if not pid.isdecimal():raise RuntimeError('Unexpected app PID')
                    identity=call('shell','run-as',PKG,'cat',f'/proc/{pid}/cmdline').split('\0')[0]
                    if identity!=PKG:raise RuntimeError('PID identity changed; no termination performed')
                    call('shell','run-as',PKG,'kill','-9',pid);terminated=True;missing_at=time.monotonic()
                elif playing:
                    if terminated and pid!=initial_pid:recovered=True
                    missing_at=None
                elif missing_at is None:missing_at=time.monotonic()
                elif time.monotonic()-missing_at>90:
                    raise RuntimeError('Playback did not recover within 90 seconds; check interruption/exit evidence')
                time.sleep(1)
        if missing_at is not None:raise RuntimeError('Observation ended during a playback interruption; recovery is not verified')
        if args.simulate_process_death and not recovered:raise RuntimeError('New playing host not observed; increase duration to allow recovery')
        print('Muted background observation completed. Playback was not stopped by this read-only monitor.',flush=True)
    finally:
        # Store only this package's process exit evidence. Never dump other
        # apps' notification bodies or alter personal launcher/settings state.
        try:(base/'app-exits.txt').write_text(call('shell','dumpsys','activity','exit-info',PKG),encoding='utf-8')
        except Exception:pass

if __name__=='__main__':main()
