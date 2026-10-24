#!/usr/bin/env python3
"""Export bounded monthly metadata, completeness receipts and a consistent DB copy."""
from __future__ import annotations
import argparse,csv,hashlib,importlib.util,json,sqlite3
from pathlib import Path

spec=importlib.util.spec_from_file_location('albums',Path(__file__).with_name('odify_recent_albums.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a);h=a.h
VIEWS='''
CREATE VIEW IF NOT EXISTS history_all_album_links AS
 SELECT 'apple' provider,l.recording_id,l.album_id,au.apple_album_id source_album_id,
        l.apple_song_id source_song_id,l.market,l.method,au.status
 FROM history_album_link l JOIN history_apple_track t ON t.track_id=l.apple_song_id AND t.market=l.market
 JOIN history_album_audit au ON au.album_id=l.album_id AND au.market=l.market
 AND au.apple_album_id=CAST(json_extract(t.payload,'$.collectionId') AS TEXT)
 UNION ALL
 SELECT l.provider,l.recording_id,l.album_id,l.source_album_id,l.source_song_id,
        CASE WHEN l.provider='billboard-japan-goods' THEN 'jp' ELSE 'kr' END,l.method,au.status
 FROM history_supplement_link l JOIN history_supplement_album_audit au
 ON au.provider=l.provider AND au.source_album_id=l.source_album_id;
CREATE VIEW IF NOT EXISTS history_recording_album_coverage AS
 SELECT r.recording_id,r.title,r.artist,
 CASE WHEN EXISTS(SELECT 1 FROM history_all_album_links l WHERE l.recording_id=r.recording_id
                  AND status IN ('complete','complete_audio','published_tracklist')) THEN 'complete'
      WHEN EXISTS(SELECT 1 FROM history_all_album_links l WHERE l.recording_id=r.recording_id) THEN 'partial'
      ELSE 'unresolved' END album_status
 FROM (SELECT DISTINCT recording_id,title,artist FROM recent_monthly_chart) r;
'''

def csv_file(path,rows,fields=None):
    rows=list(rows);path.parent.mkdir(parents=True,exist_ok=True)
    if fields is None:fields=list(rows[0].keys()) if rows else []
    with path.open('w',encoding='utf-8-sig',newline='') as stream:
        w=csv.DictWriter(stream,fieldnames=fields);w.writeheader();w.writerows(rows)

def prepare(con):
    # Both collectors are optional on a new DB; initialize their extension tables.
    spec=importlib.util.spec_from_file_location('bugs',Path(__file__).with_name('odify_history_bugs.py'))
    b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
    con.executescript(a.SCHEMA+b.SCHEMA+VIEWS)
    # API track IDs are kept alongside the normalized album membership. This
    # preserves distinct regional editions even where the legacy album table merges them.
    receipts={(r['market'],r['apple_album_id']):r['album_id'] for r in con.execute('SELECT * FROM history_album_audit')}
    identities=dict(con.execute("SELECT ext_id,recording_id FROM external_identity WHERE provider='apple'"))
    with con:
        for row in con.execute('SELECT market,payload FROM history_apple_track'):
            song=json.loads(row['payload']);identity=str(song['trackId']);album=str(song['collectionId'])
            albumid=receipts.get((row['market'],album));rid=identities.get(identity)
            if not albumid or not rid or not song.get('trackNumber'):continue
            con.execute('INSERT OR IGNORE INTO history_source_album_track VALUES(?,?,?,?,?,?,?)',
                ('apple:'+row['market'],album,identity,albumid,rid,int(song.get('discNumber') or 1),int(song['trackNumber'])))

def export(db,destination,baseline=None):
    snapshot=destination/'odify_catalog.sqlite3'
    if snapshot.exists():raise FileExistsError('Use a new export folder; existing snapshot is protected')
    con=h.connect(db);prepare(con);destination.mkdir(parents=True,exist_ok=True)
    checks={'integrity':con.execute('PRAGMA integrity_check').fetchone()[0],
        'foreign_key_errors':[list(x) for x in con.execute('PRAGMA foreign_key_check')],
        'bad_monthly_lists':[dict(x) for x in con.execute('''SELECT country,year_month,count(*) rows,
           count(distinct rank) ranks,min(rank) first,max(rank) last FROM recent_monthly_chart
           GROUP BY 1,2 HAVING rows!=100 OR ranks!=100 OR first!=1 OR last!=100''')]}
    if baseline:
        con.execute('ATTACH DATABASE ? AS baseline',(str(baseline.resolve()),))
        checks['preserved_baseline']={}
        for table,columns in [('recording','id'),('album_track','album_id,recording_id,disc,position'),
                              ('chart_entry','edition_id,rank,recording_id,source_song_id')]:
            missing=con.execute(f'SELECT count(*) FROM (SELECT {columns} FROM baseline.{table} EXCEPT SELECT {columns} FROM main.{table})').fetchone()[0]
            checks['preserved_baseline'][table]=missing==0
        con.execute('DETACH DATABASE baseline')
    if checks['integrity']!='ok' or checks['foreign_key_errors'] or checks['bad_monthly_lists'] or not all(checks.get('preserved_baseline',{}).values()):
        raise ValueError('Validation failed: '+json.dumps(checks))
    coverage=[dict(r) for r in con.execute('SELECT * FROM history_month ORDER BY year_month,country')]
    for month in coverage:
        year,number=month['year_month'].split('-');folder=destination/year/number/month['country']
        rows=[dict(r) for r in con.execute('''SELECT m.*,co.album_status FROM recent_monthly_chart m
          JOIN history_recording_album_coverage co USING(recording_id) WHERE country=? AND year_month=? ORDER BY rank''',
          (month['country'],month['year_month']))]
        csv_file(folder/'chart.csv',rows)
        (folder/'chart.json').write_text(json.dumps({'edition':month,'tracks':rows},ensure_ascii=False,indent=2),encoding='utf-8')
    csv_file(destination/'monthly_coverage.csv',coverage)
    csv_file(destination/'album_coverage.csv',[dict(r) for r in con.execute('SELECT * FROM history_recording_album_coverage ORDER BY artist,title')])
    csv_file(destination/'album_sources.csv',[dict(r) for r in con.execute('SELECT * FROM history_all_album_links ORDER BY recording_id,provider,source_album_id')])
    csv_file(destination/'album_tracks.csv',[dict(r) for r in con.execute('''SELECT s.*,al.title album,ar.name album_artist,
          r.title,a.name artist FROM history_source_album_track s JOIN album al ON al.id=s.album_id
          JOIN artist ar ON ar.id=al.artist_id JOIN recording r ON r.id=s.recording_id JOIN artist a ON a.id=r.artist_id
          ORDER BY s.provider,s.source_album_id,s.disc,s.position''')])
    report={**h.summary(con),'chart_rows':con.execute('SELECT count(*) FROM recent_monthly_chart').fetchone()[0],
        'unique_chart_recordings':con.execute('SELECT count(*) FROM history_recording_album_coverage').fetchone()[0],
        'album_coverage':[dict(r) for r in con.execute('SELECT album_status,count(*) recordings FROM history_recording_album_coverage GROUP BY 1')],
        'album_receipts':[dict(r) for r in con.execute('''SELECT 'apple' provider,status,count(*) albums FROM history_album_audit GROUP BY status
          UNION ALL SELECT provider,status,count(*) FROM history_supplement_album_audit GROUP BY provider,status''')],
        'checks':checks}
    with sqlite3.connect(snapshot) as copied:con.backup(copied)
    with snapshot.open('rb') as stream:report['snapshot_sha256']=hashlib.file_digest(stream,'sha256').hexdigest()
    (destination/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2));con.close();return report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--db',type=Path,default=Path('.local/odify_catalog.sqlite3'))
    p.add_argument('--destination',type=Path,required=True);p.add_argument('--baseline',type=Path)
    args=p.parse_args();export(args.db,args.destination,args.baseline)
if __name__=='__main__':main()
