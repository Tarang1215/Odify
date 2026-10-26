import os,sys,time,subprocess,shutil,re,json,xml.etree.ElementTree as ET
from pathlib import Path
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb=str(Path(sdk)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')) if sdk else shutil.which('adb')
if not adb: raise RuntimeError('Set ANDROID_HOME or put adb on PATH')
pkg='com.odify.music.nativepreview.v11.debug'
base=Path(os.environ.get('ODIFY_BENCHMARK_OUTPUT',str(Path.home()/'odify-scroll-measurements')))/sys.argv[1];base.mkdir(parents=True,exist_ok=True)
def call(*a):return subprocess.check_output([adb,*a],text=True,encoding='utf-8',errors='replace')
def tree():
 call('shell','uiautomator','dump','/data/local/tmp/odify-scroll.xml')
 return ET.fromstring(call('exec-out','cat','/data/local/tmp/odify-scroll.xml'))
def bounds(label):
 n=next((n for n in tree().iter('node') if n.get('text')==label or n.get('content-desc')==label),None)
 if n is None:raise RuntimeError('Missing non-playback control: '+label)
 return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def tap(label):
 if label not in ['홈','탐색','BIG Naughty','리센느','컬렉션 닫기','전체 화면으로 펼치기']:raise ValueError(label)
 x1,y1,x2,y2=bounds(label);call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.55)
def swipe(x1,y1,x2,y2,ms=360):call('shell','input','swipe',str(x1),str(y1),str(x2),str(y2),str(ms))
def phase(name,action):
 call('shell','dumpsys','gfxinfo',pkg,'reset');action();time.sleep(.4)
 raw=call('shell','dumpsys','gfxinfo',pkg,'framestats');(base/(name+'.txt')).write_text(raw,encoding='utf-8')
 patterns={'frames':r'Total frames rendered: (\d+)','jankPercent':r'Janky frames: \d+ \(([\d.]+)%\)','p50Ms':r'50th percentile: (\d+)ms','p95Ms':r'95th percentile: (\d+)ms','p99Ms':r'99th percentile: (\d+)ms','gpuP95Ms':r'95th gpu percentile: (\d+)ms'}
 result={k:float(m.group(1)) for k,p in patterns.items() if (m:=re.search(p,raw))}
 timings=[]
 for line in raw.splitlines():
  if line.startswith('Flags,'):header=line.split(',');continue
  if line.startswith('0,') and 'header' in locals():
   row=dict(zip(header,line.split(',')))
   try:
    t=(int(row['FrameCompleted'])-int(row['IntendedVsync']))/1e6
    if 0<t<10000:timings.append(t)
   except (KeyError,ValueError):pass
 if timings:result.update(maxFrameMs=round(max(timings),1),over100ms=sum(t>100 for t in timings))
 report[name]=result;(base/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(name,json.dumps(result),flush=True)
def top():
 for _ in range(3):swipe(720,950,720,2650,250)
 time.sleep(.3)
call('shell','cmd','media_session','volume','--stream','3','--set','0')
call('shell','settings','put','global','stay_on_while_plugged_in','2')
tap('홈');top();report={}
phase('home-reversals',lambda:[(swipe(720,2570,720,900),swipe(720,900,720,2570)) for _ in range(3)])
top()
swipe(720,2400,720,1800,250);time.sleep(.3)
x1,y1,x2,y2=bounds('리센느');y=y1-140
phase('artist-horizontal',lambda:[(swipe(1200,y,220,y),swipe(220,y,1200,y)) for _ in range(3)])
tap('리센느');tap('전체 화면으로 펼치기')
phase('artist-reversals',lambda:[(swipe(750,2600,750,1200),swipe(750,1200,750,2600)) for _ in range(3)])
tap('컬렉션 닫기')
top()
phase('home-navigation',lambda:[(tap('탐색'),tap('홈')) for _ in range(3)])
tap('탐색')
phase('country-swipe',lambda:[(swipe(1250,2100,200,2100),swipe(200,2100,1250,2100)) for _ in range(3)])
tap('홈')
(base/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
