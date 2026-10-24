#!/usr/bin/env python3
"""Collect the physical-release tracklists linked by the official Japan chart."""
from __future__ import annotations
import argparse,collections,importlib.util,re,time
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path

spec=importlib.util.spec_from_file_location('bugs',Path(__file__).with_name('odify_history_bugs.py'))
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b);a=b.a;h=b.h;c=b.c
PROVIDER='billboard-japan-goods'

def parse(raw):
    # Restrict parsing to the main release, excluding the related-products area.
    head=re.search(r'<hgroup>\s*<h3>(.*?)</h3>\s*<h4>(.*?)</h4>',raw,re.S)
    body=re.search(r'<div id="tracklist">(.*?)<!-- / #tracklist -->',raw,re.S)
    if not head or not body:raise ValueError('Main release header or tracklist missing')
    title,artist=map(h.clean,head.groups());tracks=[]
    for table in re.findall(r'<table>(.*?)</table>',body[1],re.S):
        disc=re.search(r'<th>Disc(\d+)</th>',table)
        if not disc:continue
        for cell in re.findall(r'<tr\b[^>]*>\s*<td>(.*?)</td>',table,re.S):
            match=re.match(r'(\d+)\.(.+)',h.clean(cell))
            if match:tracks.append(dict(disc=int(disc[1]),position=int(match[1]),title=match[2].strip()))
    if not title or not artist or not tracks:raise ValueError('Release metadata empty')
    discs=collections.defaultdict(list)
    for track in tracks:discs[track['disc']].append(track['position'])
    continuous=all(sorted(ps)==list(range(1,max(ps)+1)) for ps in discs.values())
    date=re.search(r'発売日：(\d{4})/(\d{2})/(\d{2})',raw)
    cover=re.search(r'<div id="cdInfo">\s*<p><img src="([^"]+)"',raw)
    return dict(title=title,artist=artist,tracks=tracks,
        release_date='-'.join(date.groups()) if date else '',
        cover='https://www.billboard-japan.com'+cover[1] if cover else '',
        expected=sum(max(ps) for ps in discs.values()),status='published_tracklist' if continuous else 'partial')

def run(args):
    con=h.connect(args.db);con.executescript(b.SCHEMA)
    releases=collections.defaultdict(list)
    for row in con.execute('''SELECT DISTINCT m.recording_id,m.title,m.artist,d.release_url,d.album_title
        FROM recent_monthly_chart m JOIN history_source_detail d USING(recording_id)
        WHERE m.country='jp' AND d.release_url LIKE 'https://www.billboard-japan.com/goods/detail/%'
        AND NOT EXISTS(SELECT 1 FROM history_supplement_link l WHERE l.recording_id=m.recording_id AND l.provider=?
          AND l.source_album_id=replace(d.release_url,'https://www.billboard-japan.com/goods/detail/',''))''',(PROVIDER,)):
        releases[row['release_url']].append(dict(row))
    def one(url):
        try:return url,parse(b.fetch(args.cache,url)),None
        except Exception as error:return url,None,str(error)
        finally:time.sleep(0.6)
    with ThreadPoolExecutor(max_workers=2) as pool:
        for i,future in enumerate(as_completed([pool.submit(one,url) for url in releases]),1):
            url,data,error=future.result();identity=url.rsplit('/',1)[-1]
            if error:print('JAPAN RELEASE DEFER',identity,error,flush=True);continue
            targets=releases[url]
            if not any(a.artist_keys(t['artist']) & a.artist_keys(data['artist']) and c.norm(t['album_title'])==c.norm(data['title']) for t in targets):
                print('JAPAN RELEASE IDENTITY DEFER',identity,flush=True);continue
            with con:
                prior=con.execute('SELECT album_id FROM external_album WHERE provider=? AND ext_id=?',(PROVIDER,identity)).fetchone()
                albumid=prior[0] if prior else c.album(con,data['title'],data['artist'],data['release_date'])
                for track in data['tracks']:
                    sourceid=identity+':'+str(track['disc'])+':'+str(track['position'])
                    target=next((t for t in targets if a.title_keys(t['title']) & a.title_keys(track['title'])),None)
                    if target:con.execute('INSERT OR IGNORE INTO external_identity VALUES(?,?,?)',(PROVIDER,sourceid,target['recording_id']))
                    rid=c.recording(con,track['title'],data['artist'],source=PROVIDER,source_id=sourceid,release_date=data['release_date'])
                    con.execute('INSERT OR IGNORE INTO album_track VALUES(?,?,?,?)',(albumid,rid,track['disc'],track['position']))
                    con.execute('INSERT OR REPLACE INTO history_source_album_track VALUES(?,?,?,?,?,?,?)',
                        (PROVIDER,identity,sourceid,albumid,rid,track['disc'],track['position']))
                con.execute('INSERT OR REPLACE INTO external_album VALUES(?,?,?,?,CURRENT_TIMESTAMP)',(PROVIDER,identity,albumid,data['status']))
                con.execute('INSERT OR REPLACE INTO history_supplement_album_audit VALUES(?,?,?,?,?,?,?)',
                    (PROVIDER,identity,albumid,data['expected'],len(data['tracks']),data['status'],url))
                for target in targets:
                    if not a.artist_keys(target['artist']) & a.artist_keys(data['artist']):continue
                    con.execute('INSERT OR REPLACE INTO history_supplement_link VALUES(?,?,?,?,?,?)',
                        (PROVIDER,target['recording_id'],albumid,identity,'','official chart release link + artist/album title'))
                if data['cover']:con.execute('INSERT OR REPLACE INTO history_album_artwork VALUES(?,?,?)',(PROVIDER,identity,data['cover']))
            if i%25==0 or i==len(releases):print('JAPAN RELEASES',i,'/',len(releases),flush=True)
    print('JAPAN LINKED',con.execute('SELECT count(distinct recording_id) FROM history_supplement_link WHERE provider=?',(PROVIDER,)).fetchone()[0],flush=True);con.close()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--db',type=Path,default=Path('.local/odify_catalog.sqlite3'))
    p.add_argument('--cache',type=Path,default=Path('.local/history2024/japan-album-cache'));run(p.parse_args())
if __name__=='__main__':main()
