#!/usr/bin/env python3
"""Supplement chart albums with public, localized Bugs metadata. No audio/lyrics."""
from __future__ import annotations
import argparse,collections,html,importlib.util,json,re,time
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
from urllib.parse import urlencode

spec=importlib.util.spec_from_file_location('recent_albums',Path(__file__).with_name('odify_recent_albums.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a);h=a.h;c=a.c
SCHEMA='''
CREATE TABLE IF NOT EXISTS history_supplement_album_audit(
 provider TEXT,source_album_id TEXT,album_id TEXT REFERENCES album(id),
 expected_tracks INTEGER,observed_tracks INTEGER,status TEXT,url TEXT,
 PRIMARY KEY(provider,source_album_id));
CREATE TABLE IF NOT EXISTS history_supplement_link(
 provider TEXT,recording_id TEXT REFERENCES recording(id),album_id TEXT REFERENCES album(id),
 source_album_id TEXT,source_song_id TEXT,method TEXT,
 PRIMARY KEY(provider,recording_id,album_id));
CREATE TABLE IF NOT EXISTS history_supplement_fetch(
 provider TEXT,recording_id TEXT REFERENCES recording(id),status TEXT,url TEXT,error TEXT,
 PRIMARY KEY(provider,recording_id));
CREATE TABLE IF NOT EXISTS history_source_album_track(
 provider TEXT,source_album_id TEXT,source_track_id TEXT,album_id TEXT REFERENCES album(id),
 recording_id TEXT REFERENCES recording(id),disc INTEGER,position INTEGER,
 PRIMARY KEY(provider,source_album_id,disc,position));
CREATE TABLE IF NOT EXISTS history_album_artwork(
 provider TEXT,source_album_id TEXT,url TEXT,PRIMARY KEY(provider,source_album_id));
CREATE TABLE IF NOT EXISTS history_track_availability(
 provider TEXT,source_track_id TEXT,available INTEGER,reason TEXT,PRIMARY KEY(provider,source_track_id));
'''

def attrs(raw):return {k.lower():html.unescape(v) for k,v in re.findall(r'([\w_]+)="([^"]*)"',raw)}

def parse_tracks(raw,album=None):
    result=[]
    for tag,body in re.findall(r'<tr\b([^>]*)>(.*?)</tr>',raw,re.S|re.I):
        meta=attrs(tag)
        if not meta.get('trackid') or not meta.get('albumid'):continue
        if album and meta['albumid']!=str(album):continue
        title=re.search(r'<p\b[^>]*class="title"[^>]*>(.*?)</p>',body,re.S)
        artist=re.search(r'<p\b[^>]*class="artist"[^>]*>(.*?)</p>',body,re.S)
        if not title or not artist:continue
        titles=re.findall(r'title="([^"]+)"',title[1]);names=re.findall(r'title="([^"]+)"',artist[1])
        if not titles:
            fallback=re.search(r'<input[^>]*name="check"[^>]*title="([^"]+)"',body)
            if not fallback:continue
            titles=[fallback[1]]
        # Multi-artist lists expose the full credit in artist_disp_nm.
        credit=re.search(r'artist_disp_nm="([^"]+)"',body)
        name=html.unescape(credit[1]) if credit else h.clean(artist[1])
        if not name:continue
        index=re.search(r'class="trackIndex"[^>]*>\s*<em>(\d+)</em>',body,re.S)
        disc=re.search(r'disc_id="(\d+)"',body)
        albumname=re.search(r'class="album"\s+title="([^"]+)"',body)
        result.append(dict(id=meta['trackid'],album_id=meta['albumid'],title=html.unescape(titles[0]),
            artist=name,artist_names=[html.unescape(x) for x in names],
            album_title=html.unescape(albumname[1]) if albumname else '',
            disc=int(disc[1]) if disc else 1,position=int(index[1]) if index else None,
            available=not bool(re.search(r'name="_isStream"[^>]*disabled=',body))))
    return result

def title_keys(title):
    keys={a.title_key(title)}
    # Optional bilingual spelling is an alias, never an arbitrary fuzzy match.
    parts=re.findall(r'\(([^()]+)\)',title)
    for part in parts:
        if re.search(r'[A-Za-z]',part) and not re.search(r'feat|prod|remix|live|inst|ver\.|ost',part,re.I):keys.add(c.norm(part))
    if parts:keys.add(a.title_key(re.sub(r'\s*\([^()]+\)\s*',' ',title).strip()))
    return keys-{''}

def choose(target,rows):
    found=[]
    for row in rows:
        if not title_keys(target['title']) & title_keys(row['title']):continue
        if c.version_label(target['title'])!=c.version_label(row['title']):continue
        names=set().union(*(a.artist_keys(x) for x in [row['artist'],*row['artist_names']]))
        if not a.artist_keys(target['artist']) & names:continue
        found.append(row)
    return found[0] if found else None

def parse_album(raw,identity):
    header=None
    for text in re.findall(r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>',raw,re.S):
        try:value=json.loads(text)
        except ValueError:continue
        if isinstance(value,dict) and value.get('@type')=='MusicAlbum':header=value;break
    if not header or not any('/album/'+str(identity) in str(x) for x in [header.get('sameAs'),header.get('url')]):
        raise ValueError('Album identity not confirmed')
    count=re.search(r'<h1>수록곡</h1>\s*<p[^>]*>\((\d+)\)</p>',raw)
    if not count:raise ValueError('Published album track count missing')
    tracks=parse_tracks(raw,identity);expected=int(count[1])
    positions={(x['disc'],x['position']) for x in tracks}
    if not tracks or any(x['position'] is None for x in tracks):raise ValueError('Missing track positions')
    discs=collections.defaultdict(list)
    for disc,position in positions:discs[disc].append(position)
    complete=(len(tracks)==len(positions)==expected and
              all(sorted(ps)==list(range(1,len(ps)+1)) for ps in discs.values()))
    return header,tracks,expected,'complete' if complete else 'partial'

def fetch(cache,url):
    for attempt in range(3):
        try:return h.request(cache,url).decode('utf-8')
        except Exception as error:
            if getattr(error,'code',None) in (401,403,429) or attempt==2:raise
            time.sleep(1+attempt)

def run(args):
    con=h.connect(args.db);con.executescript(SCHEMA);con.execute('PRAGMA busy_timeout=30000')
    desired=a.targets(con);index,artists=a.library(con)
    # Korean titles need the local catalog; other markets supplement only unresolved songs.
    targets=[t for t in desired if ('kr' in t['markets'].split(',') or not a.match_recording(t,index,artists)) and
        not con.execute("SELECT 1 FROM history_supplement_fetch WHERE provider='bugs' AND recording_id=? AND status IN ('matched','not_found')",(t['id'],)).fetchone()]
    def search(target):
        artist=re.sub(r'\([^()]+\)','',a.main_artist(target['artist'])).strip()
        url='https://music.bugs.co.kr/search/track?'+urlencode(dict(q=target['title']+' '+artist))
        try:
            raw=fetch(args.cache,url);return target,url,choose(target,parse_tracks(raw)),None
        except Exception as error:return target,url,None,str(error)
        finally:time.sleep(0.6)
    matches=[]
    with ThreadPoolExecutor(max_workers=2) as pool:
        for i,future in enumerate(as_completed([pool.submit(search,t) for t in targets]),1):
            target,url,song,error=future.result()
            with con:con.execute('INSERT OR REPLACE INTO history_supplement_fetch VALUES(?,?,?,?,?)',
                ('bugs',target['id'],'error' if error else 'matched' if song else 'not_found',url,error or ''))
            if song:matches.append((target,song))
            if i%25==0 or i==len(targets):print('BUGS SEARCH',i,'/',len(targets),'MATCHED',len(matches),flush=True)
    # Resume successful searches whose album pass was interrupted, using cached pages.
    known={t['id'] for t,s in matches}
    for target in desired:
        if target['id'] in known:continue
        row=con.execute("""SELECT url FROM history_supplement_fetch f WHERE provider='bugs' AND recording_id=? AND status='matched'
          AND (NOT EXISTS (SELECT 1 FROM history_supplement_link l WHERE l.provider=f.provider AND l.recording_id=f.recording_id)
          OR (? AND EXISTS (SELECT 1 FROM history_supplement_link l JOIN history_supplement_album_audit au
            ON au.provider=l.provider AND au.source_album_id=l.source_album_id
            WHERE l.provider=f.provider AND l.recording_id=f.recording_id AND au.status!='complete')))""",(target['id'],args.retry_incomplete)).fetchone()
        if row:
            song=choose(target,parse_tracks(fetch(args.cache,row['url'])))
            if song:matches.append((target,song))
    albums={s['album_id'] for t,s in matches}
    pending=[identity for identity in sorted(albums) if not con.execute("SELECT 1 FROM history_supplement_album_audit WHERE provider='bugs' AND source_album_id=? AND status='complete'",(identity,)).fetchone()]
    def get_album(identity):
        url='https://music.bugs.co.kr/album/'+identity
        try:return identity,url,parse_album(fetch(args.cache,url),identity),None
        except Exception as error:return identity,url,None,str(error)
        finally:time.sleep(0.6)
    with ThreadPoolExecutor(max_workers=2) as pool:
        for i,future in enumerate(as_completed([pool.submit(get_album,x) for x in pending]),1):
            identity,url,data,error=future.result()
            if error:print('BUGS ALBUM DEFER',identity,error,flush=True);continue
            header,tracks,expected,status=data
            with con:
                prior=con.execute("SELECT album_id FROM external_album WHERE provider='bugs' AND ext_id=?",(identity,)).fetchone()
                albumid=prior[0] if prior else c.album(con,header['name'],header['byArtist']['name'],header.get('datePublished'))
                for track in tracks:
                    con.execute('INSERT OR REPLACE INTO history_track_availability VALUES(?,?,?,?)',
                        ('bugs',track['id'],int(track['available']),'' if track['available'] else 'Not streamable at metadata provider; metadata retained'))
                    target=next((t for t,s in matches if s['id']==track['id']),None)
                    if target:con.execute('INSERT OR IGNORE INTO external_identity VALUES(?,?,?)',('bugs',track['id'],target['id']))
                    rid=c.recording(con,track['title'],track['artist'],source='bugs',source_id=track['id'])
                    con.execute('INSERT OR IGNORE INTO album_track VALUES(?,?,?,?)',(albumid,rid,track['disc'],track['position']))
                    con.execute('INSERT OR REPLACE INTO history_source_album_track VALUES(?,?,?,?,?,?,?)',
                        ('bugs',identity,track['id'],albumid,rid,track['disc'],track['position']))
                con.execute('INSERT OR REPLACE INTO external_album VALUES(?,?,?,?,CURRENT_TIMESTAMP)',('bugs',identity,albumid,'done' if status=='complete' else status))
                con.execute('INSERT OR REPLACE INTO history_supplement_album_audit VALUES(?,?,?,?,?,?,?)',('bugs',identity,albumid,expected,len(tracks),status,url))
                if header.get('image'):con.execute('INSERT OR REPLACE INTO history_album_artwork VALUES(?,?,?)',('bugs',identity,header['image']))
            if i%25==0 or i==len(pending):print('BUGS ALBUMS',i,'/',len(pending),flush=True)
    with con:
        for target,song in matches:
            row=con.execute("SELECT album_id FROM history_supplement_album_audit WHERE provider='bugs' AND source_album_id=?",(song['album_id'],)).fetchone()
            if row:con.execute('INSERT OR REPLACE INTO history_supplement_link VALUES(?,?,?,?,?,?)',
                ('bugs',target['id'],row['album_id'],song['album_id'],song['id'],'localized title/version and artist spelling'))
    print('BUGS COMPLETE',con.execute('SELECT count(distinct recording_id) FROM history_supplement_link').fetchone()[0],flush=True);con.close()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--db',type=Path,default=Path('.local/odify_catalog.sqlite3'))
    p.add_argument('--cache',type=Path,default=Path('.local/history2024/bugs-cache'))
    p.add_argument('--retry-incomplete',action='store_true');run(p.parse_args())
if __name__=='__main__':main()
