"""Find reference runtimes from agreeing public search results, without audio extraction."""
from __future__ import annotations
import argparse,collections,importlib.util,json,re,threading,unicodedata
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path

spec=importlib.util.spec_from_file_location('history_music',Path(__file__).with_name('history_music.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);w=m.w

def plain(value):
    value=value.replace('(ハート)','♥')
    return w.norm(''.join(c for c in unicodedata.normalize('NFKD',value) if not unicodedata.combining(c)))

def consensus(row,candidates):
    valid=[]
    names=[a for a,t in row.get('aliases',[(row['artist'],row['title'])])]
    names += [m.a.main_artist(name) for name in list(names)]
    names += [re.split(r',| & | × |:',name,maxsplit=1)[0].strip() for name in list(names)]
    artists={plain(name) for name in names if len(plain(name))>=2}
    for candidate in candidates:
        seconds=candidate.get('duration')
        if not isinstance(seconds,(int,float)) or not 30<=seconds<=1800:continue
        if not w.quick_candidate_matches({**row,'duration_ms':seconds*1000},candidate):continue
        credit=plain((candidate.get('channel') or candidate.get('uploader') or '')+' '+candidate.get('title',''))
        if not any(artist in credit for artist in artists):continue
        valid.append(candidate)
    groups=[]
    for seed in valid:
        peers=[x for x in valid if abs(x['duration']-seed['duration'])<=2]
        if len({x['id'] for x in peers})>=2:groups.append(peers)
    if not groups:return None
    group=max(groups,key=lambda xs:(len({x['id'] for x in xs}),-min(x['duration'] for x in xs)))
    seconds=sorted(x['duration'] for x in group)[len(group)//2]
    return dict(duration_ms=round(seconds*1000),source='public search runtime consensus; title/artist checked',
                evidence=[dict(id=x['id'],title=x['title'],duration=x['duration']) for x in group])

def run(args):
    rows=json.loads((args.root/'chart_status.json').read_text('utf-8'))['tracks'];m.apply_runtime_cache(rows)
    path=m.ROOT/'.local/history2024/song-runtimes.json'
    runtimes=json.loads(path.read_text('utf-8')) if path.exists() else {}
    pending=[r for r in rows if not r.get('duration_ms')];stop=threading.Event();found=0
    def one(row):
        if stop.is_set():return row,None,'Source requests paused'
        try:
            candidates=w.search_candidates(row['artist'],row['title'],args.cache)
            value=consensus(row,candidates)
            if not value and args.verify_singles:
                for candidate in candidates[:3]:
                    detail=w.inspected_video(candidate['id'],args.cache)
                    seconds=detail.get('duration')
                    if not isinstance(seconds,(int,float)) or not 30<=seconds<=1800:continue
                    trial={**row,'duration_ms':seconds*1000}
                    if not w.quick_candidate_matches(trial,detail):continue
                    for artist,title in row.get('aliases',[(row['artist'],row['title'])]):
                        expected={'Track ID':'HIST'+row['id'],'아티스트':artist,'곡명':title}
                        if not w.chart_candidate_matches(expected,detail):continue
                        try:w.audio.validate_audio_metadata('https://www.youtube.com/watch?v='+detail['id'],title,detail,artist=artist)
                        except ValueError:continue
                        value=dict(duration_ms=round(seconds*1000),source='video metadata artist/title/version verified',
                                   evidence=[dict(id=detail['id'],title=detail['title'],duration=seconds)])
                        break
                    if value:break
            return row,value,''
        except Exception as error:
            if w.age_restricted(str(error)):return row,{'unavailable':'Age verification required; skipped'},''
            if w.access_blocked(str(error)):stop.set()
            return row,None,str(error)[-300:]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i,future in enumerate(as_completed([pool.submit(one,r) for r in pending]),1):
            row,value,error=future.result()
            if value:runtimes[row['id']]=value;found+=1
            if i%25==0 or i==len(pending):
                w.write_cache(path,runtimes);print('SEARCH RUNTIMES',i,'/',len(pending),'FOUND',found,flush=True)
            if error and 'Source requests paused'!=error:print('RUNTIME SOURCE DEFER',row['id'],error,flush=True)
    print('RUNTIME SEARCH DONE',found,'paused',stop.is_set(),flush=True)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(r'D:\Odify\History'))
    p.add_argument('--cache',type=Path,default=m.ROOT/'.local/history2024/runtime-search-cache')
    p.add_argument('--verify-singles',action='store_true',help='Inspect remaining candidates and require studio artist/title/version metadata')
    args=p.parse_args();args.cache.mkdir(parents=True,exist_ok=True);run(args)
if __name__=='__main__':main()
