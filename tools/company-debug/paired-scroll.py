import os,sys,time,subprocess,shutil
from pathlib import Path
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb=str(Path(sdk)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')) if sdk else shutil.which('adb')
if not adb: raise RuntimeError('Set ANDROID_HOME or put adb on PATH')
pkg='com.odify.music.nativepreview.v11.debug'
name=sys.argv[1];base=Path(os.environ.get('ODIFY_BENCHMARK_OUTPUT',str(Path.home()/'odify-scroll-measurements')))/name;base.mkdir(parents=True,exist_ok=True)
def call(*args):return subprocess.run([adb,*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
awake=call('shell','settings','get','global','stay_on_while_plugged_in').stdout.strip()
if awake not in ['0','1','2','3','4','5','6','7']:raise RuntimeError('Cannot read original screen-awake setting; check device connection')
call('shell','am','force-stop',pkg)
call('shell','run-as',pkg,'rm','-f','cache/company-scroll-ready','cache/company-scroll-stop')
with (base/'paused-harness.txt').open('w',encoding='utf-8') as log:
 p=subprocess.Popen([adb,'shell','am','instrument','-w','-r','-e','class','com.odify.nativeapp.OdifyCompanyScrollHarness',pkg+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
 try:
  deadline=time.monotonic()+45
  while call('shell','run-as',pkg,'test','-f','cache/company-scroll-ready').returncode:
   if p.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Paused harness did not become ready; inspect log')
   time.sleep(.4)
  result=subprocess.run([sys.executable,str(Path(__file__).with_name('measure-scroll.py')),name],timeout=110,
      stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',
      creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  (base/'measurement-log.txt').write_text(result.stdout,encoding='utf-8')
  print(result.stdout,flush=True)
 finally:
  call('shell','run-as',pkg,'touch','cache/company-scroll-stop')
  try:p.wait(timeout=18)
  finally:call('shell','settings','put','global','stay_on_while_plugged_in',awake)
text=(base/'paused-harness.txt').read_text(encoding='utf-8')
if 'OK (1 test)' not in text:raise RuntimeError('Paused harness failed; no real service is allowed')
if result.returncode:raise SystemExit(result.returncode)
print('Paused-only scroll measurement passed; no playback service started.',flush=True)
