#!/usr/bin/env python3
"""2024-present monthly chart metadata and album-track enrichment, resumable."""
from __future__ import annotations
import argparse,calendar,hashlib,html,importlib.util,json,re,sqlite3,time
from datetime import date,datetime,timedelta,timezone
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
from urllib.parse import urlencode,parse_qs,urlsplit
from urllib.request import Request,urlopen

spec=importlib.util.spec_from_file_location('catalog',Path(__file__).with_name('odify_catalog_pipeline.py'))
catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)
SCHEMA='''
CREATE TABLE IF NOT EXISTS history_month(
 country TEXT,year_month TEXT,edition_id TEXT REFERENCES chart_edition(id),
 status TEXT,source_type TEXT,source_editions INTEGER,latest_source_date TEXT,cutoff TEXT,
 PRIMARY KEY(country,year_month));
CREATE TABLE IF NOT EXISTS history_source_detail(
 recording_id TEXT REFERENCES recording(id),provider TEXT,source_id TEXT,
 album_title TEXT,artwork_url TEXT,release_url TEXT,
 PRIMARY KEY(recording_id,provider,source_id));
CREATE TABLE IF NOT EXISTS history_monthly_metric(
 edition_id TEXT REFERENCES chart_edition(id),recording_id TEXT REFERENCES recording(id),
 points INTEGER,appearances INTEGER,best_rank INTEGER,PRIMARY KEY(edition_id,recording_id));
CREATE TABLE IF NOT EXISTS history_fetch_issue(
 provider TEXT,requested_date TEXT,url TEXT,error TEXT,PRIMARY KEY(provider,requested_date));
CREATE TABLE IF NOT EXISTS history_album_link(
 recording_id TEXT REFERENCES recording(id),album_id TEXT REFERENCES album(id),
 apple_song_id TEXT,market TEXT,method TEXT,PRIMARY KEY(recording_id,album_id,market));
CREATE TABLE IF NOT EXISTS history_album_audit(
 apple_album_id TEXT,market TEXT,album_id TEXT REFERENCES album(id),
 expected_tracks INTEGER,observed_tracks INTEGER,status TEXT,url TEXT,
 PRIMARY KEY(apple_album_id,market));
CREATE VIEW IF NOT EXISTS recent_monthly_chart AS
 SELECT h.country,h.year_month,h.status,h.source_type,e.provider,e.source_url,
        c.rank,r.id recording_id,r.title,a.name artist
 FROM history_month h JOIN chart_edition e ON e.id=h.edition_id
 JOIN chart_entry c ON c.edition_id=e.id JOIN recording r ON r.id=c.recording_id
 JOIN artist a ON a.id=r.artist_id;
'''

def connect(path):
    con=catalog.connect(path);con.execute('PRAGMA foreign_keys=ON');con.execute('PRAGMA busy_timeout=30000')
    con.executescript(SCHEMA);return con

def request(cache,url,data=None,max_age=None):
    key=hashlib.sha256(url.encode()+(data or b'')).hexdigest()
    path=cache/(key+'.json' if '/api/' in url or 'itunes.apple' in url or url.endswith('.json') else key+'.html')
    if path.exists() and (max_age is None or time.time()-path.stat().st_mtime<max_age):return path.read_bytes()
    headers={'User-Agent':'Mozilla/5.0 (Odify personal metadata catalog)',
             'Referer':'https://circlechart.kr/page_chart/onoff.circle'}
    if data:headers['Content-Type']='application/x-www-form-urlencoded'
    with urlopen(Request(url,data=data,headers=headers),timeout=30) as response:
        body=response.read(16_000_001)
    if len(body)>16_000_000:raise ValueError('Metadata response too large')
    cache.mkdir(parents=True,exist_ok=True);temp=path.with_suffix(path.suffix+'.tmp')
    temp.write_bytes(body);temp.replace(path);return body

def clean(value):return html.unescape(re.sub('<[^>]+>','',value)).strip()

def circle_entries(payload,period):
    if payload.get('ResultStatus')!='OK':raise ValueError('Circle response not OK')
    params=payload.get('FormToMap') or {}
    if str(params.get('hitYear'))!=period[:4] or str(params.get('targetTime')).zfill(2)!=period[-2:]:
        raise ValueError('Circle period differs from request')
    items=payload.get('List') or {};items=list(items.values()) if isinstance(items,dict) else items
    rows=[{'rank':int(x['SERVICE_RANKING']),'title':clean(x['SONG_NAME']),
           'artist':clean(x['ARTIST_NAME']),'album':clean(x.get('ALBUM_NAME','')),
           'source_id':str(x.get('SEQ_MOM','')),'artwork':x.get('ALBUMIMG','')}
          for x in items if 1<=int(x.get('SERVICE_RANKING') or 0)<=100]
    if len(rows)!=100 or {r['rank'] for r in rows}!=set(range(1,101)):
        raise ValueError('Circle Top 100 incomplete or duplicated')
    return rows

def japan_entries(raw,cutoff):
    match=re.search(r'class="date">\[\s*(\d{4})/(\d{2})/(\d{2})',raw)
    if not match:raise ValueError('Japan publication date missing')
    published=date(*map(int,match.groups()))
    if published>cutoff:raise ValueError('Future chart rejected')
    rows=[]
    for block in re.findall(r'<tr\b[^>]*>.*?</tr>',raw,re.S|re.I):
        rank=re.search(r'<td[^>]*headers="rank"[^>]*>\s*<span>(\d+)</span>',block)
        title=re.search(r'<p[^>]*class="musuc_title"[^>]*>(.*?)</p>',block,re.S)
        artist=re.search(r'<p[^>]*class="artist_name"[^>]*>(.*?)</p>',block,re.S)
        if not (rank and title and artist):continue
        goods=re.search(r'href="(/goods/detail/\d+)"',block)
        album=re.search(r'alt="[^"]*「([^」]+)」',block)
        rows.append({'rank':int(rank[1]),'title':clean(title[1]),'artist':clean(artist[1]),
            'album':clean(album[1]) if album else '',
            'release_url':'https://www.billboard-japan.com'+goods[1] if goods else ''})
    if len(rows)!=100 or {x['rank'] for x in rows}!=set(range(1,101)):
        raise ValueError('Japan Top 100 incomplete or duplicated')
    return published.isoformat(),rows

def details(con,edition,rows,provider):
    ids=dict(con.execute('SELECT rank,recording_id FROM chart_entry WHERE edition_id=?',(edition,)))
    for item in rows:
        con.execute('INSERT OR REPLACE INTO history_source_detail VALUES(?,?,?,?,?,?)',
            (ids[item['rank']],provider,item.get('source_id') or item.get('release_url',''),item.get('album',''),
             item.get('artwork',''),item.get('release_url','')))

def derive(con,country,provider,ym,cutoff):
    rows=con.execute('''SELECT r.id,r.title,a.name artist,r.version,SUM(101-c.rank) points,
        COUNT(*) appearances,MIN(c.rank) best FROM chart_entry c
        JOIN chart_edition e ON e.id=c.edition_id JOIN recording r ON r.id=c.recording_id
        JOIN artist a ON a.id=r.artist_id WHERE e.country=? AND e.provider=? AND e.category='songs'
        AND substr(e.as_of,1,7)=? AND e.as_of<=? GROUP BY r.id
        ORDER BY points DESC,appearances DESC,best ASC,r.title,r.id LIMIT 100''',
        (country,provider,ym,cutoff.isoformat())).fetchall()
    count,latest=con.execute('''SELECT count(*),max(as_of) FROM chart_edition
        WHERE country=? AND provider=? AND category='songs' AND substr(as_of,1,7)=? AND as_of<=?''',
        (country,provider,ym,cutoff.isoformat())).fetchone()
    if not rows:return
    current=ym==cutoff.strftime('%Y-%m');year,month=map(int,ym.split('-'))
    asof=cutoff.isoformat() if current else f'{ym}-{calendar.monthrange(year,month)[1]:02}'
    items=[{'rank':i+1,'title':r['title'],'artist':r['artist'],'version':r['version']} for i,r in enumerate(rows)]
    edition=catalog.snapshot(con,provider+'-odify-monthly',country,asof,items,category='monthly-derived',
        url='https://www.billboard-japan.com/charts/detail?a=hot100' if country=='jp' else
            'https://circlechart.kr/page_chart/onoff.circle' if country=='kr' else 'https://www.billboard.com/charts/hot-100/',
        rights='Odify aggregate: sum(101-rank), month of provider chart date (US issue date, JP publication date, KR week end); not official monthly')
    con.execute('DELETE FROM history_monthly_metric WHERE edition_id=?',(edition,))
    for r in rows:
        con.execute('INSERT OR REPLACE INTO history_monthly_metric VALUES(?,?,?,?,?)',
            (edition,r['id'],r['points'],r['appearances'],r['best']))
    con.execute('INSERT OR REPLACE INTO history_month VALUES(?,?,?,?,?,?,?,?)',
        (country,ym,edition,'month_to_date' if current else 'complete','weekly-derived',count,latest,cutoff.isoformat()))

def collect_charts(args):
    con=connect(args.db);cutoff=date.fromisoformat(args.through);cache=args.cache;cache.mkdir(parents=True,exist_ok=True)
    jobs=[]
    for year in range(2024,cutoff.year+1):
        for month in range(1,13):
            ym=f'{year}-{month:02}'
            if ym>=cutoff.strftime('%Y-%m'):continue
            url='https://circlechart.kr/page_chart/onoff.circle?'+urlencode(dict(nationGbn='T',serviceGbn='ALL',termGbn='month',hitYear=year,targetTime=f'{month:02}',yearTime=1))
            params=dict(nationGbn='T',serviceGbn='ALL',termGbn='month',hitYear=year,targetTime=f'{month:02}',yearTime=1,curUrl=url.removeprefix('https://'))
            jobs.append(('kr',ym,url,urlencode(params).encode()))
    # The official page supplies the available weeks; never request unpublished current weeks.
    weekly=request(cache,'https://circlechart.kr/page_chart/onoff.circle?termGbn=week&serviceGbn=ALL',max_age=3600).decode('utf-8')
    for token,start,end in set(re.findall(r'<option value="(\d{6})"[^>]*>(\d{4}\.\d{2}\.\d{2})~(\d{4}\.\d{2}\.\d{2})',weekly)):
        enddate=end.replace('.','-')
        if enddate[:7]!=cutoff.strftime('%Y-%m') or enddate>cutoff.isoformat():continue
        url='https://circlechart.kr/page_chart/onoff.circle?'+urlencode(dict(nationGbn='T',serviceGbn='ALL',termGbn='week',hitYear=token[:4],targetTime=token[-2:],yearTime=1))
        params=dict(nationGbn='T',serviceGbn='ALL',termGbn='week',hitYear=token[:4],targetTime=token[-2:],yearTime=1,curUrl=url.removeprefix('https://'))
        jobs.append(('kr-week',enddate,url,urlencode(params).encode()))
    day=date(2024,1,3)
    while day<=cutoff:
        query=day+timedelta(days=5)
        url='https://www.billboard-japan.com/charts/detail?'+urlencode(dict(a='hot100',year=query.year,month=f'{query.month:02}',day=f'{query.day:02}'))
        jobs.append(('jp',day.isoformat(),url,None));day+=timedelta(days=7)
    valid=json.loads(request(cache,catalog.BILLBOARD_ARCHIVE+'valid_dates.json',max_age=3600))
    for day in valid:
        if '2024-01-01'<=day<=cutoff.isoformat() and not con.execute('SELECT 1 FROM chart_edition WHERE provider=? AND country=? AND as_of=? AND category=?',(catalog.BILLBOARD_PROVIDER,'us',day,'songs')).fetchone():
            jobs.append(('us',day,catalog.BILLBOARD_ARCHIVE+'date/'+day+'.json',None))
    def one(job):
        region,period,url,data=job
        try:
            raw=request(cache,'https://circlechart.kr/data/api/chart/onoff' if data else url,data)
            if region.startswith('kr'):
                params=parse_qs(urlsplit(url).query)
                expected=params['hitYear'][0]+'-'+params['targetTime'][0]
                return job,period,circle_entries(json.loads(raw),expected),None
            if region=='jp':
                published,rows=japan_entries(raw.decode('utf-8'),cutoff);return job,published,rows,None
            payload=json.loads(raw)
            rows=[dict(rank=x['this_week'],artist=x['artist'],title=x['song']) for x in payload['data']]
            if len(rows)!=100:raise ValueError('US week incomplete')
            return job,period,rows,None
        except Exception as e:return job,period,None,str(e)
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i,future in enumerate(as_completed([pool.submit(one,job) for job in jobs]),1):
            (region,period,url,_),actual,rows,error=future.result()
            provider={'kr':'circle-digital-monthly','kr-week':'circle-digital-weekly','jp':'billboard-japan-official','us':catalog.BILLBOARD_PROVIDER}[region]
            with con:
                if error:
                    con.execute('INSERT OR REPLACE INTO history_fetch_issue VALUES(?,?,?,?)',(provider,period,url,error));print('DEFER',region,period,error,flush=True);continue
                chartdate=actual if region!='kr' else actual+f'-{calendar.monthrange(*map(int,actual.split("-")))[1]:02}'
                eid=catalog.snapshot(con,provider,'kr' if region=='kr-week' else region,chartdate,rows,
                    category='monthly-official-source' if region=='kr' else 'songs',url=url,rights='Published chart metadata; no audio or lyrics')
                details(con,eid,rows,provider)
                con.execute('DELETE FROM history_fetch_issue WHERE provider=? AND requested_date=?',(provider,period))
                if region=='kr':con.execute('INSERT OR REPLACE INTO history_month VALUES(?,?,?,?,?,?,?,?)',('kr',period,eid,'complete','official-monthly',1,chartdate,cutoff.isoformat()))
            if i%20==0 or i==len(jobs):print('CHARTS',i,'/',len(jobs),flush=True)
    for year in range(2024,cutoff.year+1):
        for month in range(1,13):
            ym=f'{year}-{month:02}'
            if ym>cutoff.strftime('%Y-%m'):continue
            for country,provider in [('jp','billboard-japan-official'),('us',catalog.BILLBOARD_PROVIDER),('kr','circle-digital-weekly')]:
                if country=='kr' and ym!=cutoff.strftime('%Y-%m'):continue
                with con:derive(con,country,provider,ym,cutoff)
    print(json.dumps(summary(con),ensure_ascii=False),flush=True);con.close()

def summary(con):
    return {'counts':{t:con.execute('SELECT count(*) FROM '+t).fetchone()[0] for t in ['recording','album','album_track','chart_entry']},
        'monthly_coverage':[dict(r) for r in con.execute('SELECT country,substr(year_month,1,4) year,count(*) months FROM history_month GROUP BY 1,2')],
        'fetch_issues':con.execute('SELECT count(*) FROM history_fetch_issue').fetchone()[0]}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['charts','summary'])
    p.add_argument('--db',type=Path,default=Path('.local/odify_catalog.sqlite3'));p.add_argument('--cache',type=Path,default=Path('.local/history2024/cache'))
    p.add_argument('--through',default=datetime.now(timezone(timedelta(hours=9))).date().isoformat());args=p.parse_args()
    if args.command=='charts':collect_charts(args)
    else:
        con=connect(args.db);print(json.dumps(summary(con),ensure_ascii=False,indent=2));con.close()
if __name__=='__main__':main()
