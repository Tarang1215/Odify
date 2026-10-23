/**
 * Odify MASTER AutoLink (Google Apps Script).
 * Extensions > Apps Script in the existing native MASTER Google Sheet.
 * Searches metadata only. No audio downloads or official-channel requirement.
 * Existing URLs are immutable; automatic additions receive status '재검토'.
 */
const ODIFY = Object.freeze({
  sheetName:'MASTER',scanName:'ODIFY_LINK_SCAN',auditName:'ODIFY_LINK_AUDIT',
  maxPerRun:45,maxDaily:90,perQuery:15,tolerance:2,minSources:3,retryDays:21
});

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Odify 자동 링크')
    .addItem('YouTube API 키 입력','odifySetYoutubeKey')
    .addItem('매일 자동실행 설정','odifyInstallAutoLink')
    .addItem('지금 링크 검색','odifyAutoLink')
    .addItem('자동실행 해제','odifyRemoveAutoLink')
    .addToUi();
}

function odifySetYoutubeKey() {
  const ui=SpreadsheetApp.getUi();
  const r=ui.prompt('YouTube Data API v3 키','시트 셀이 아닌 비공개 Script Properties에 저장됩니다.',ui.ButtonSet.OK_CANCEL);
  if (r.getSelectedButton()!==ui.Button.OK)return;
  const key=r.getResponseText().trim();
  if (key.length<15)throw Error('잘못된 API 키');
  PropertiesService.getScriptProperties().setProperty('ODIFY_YOUTUBE_KEY',key);
  ui.alert('API 키가 저장되었습니다.');
}

function odifyInstallAutoLink() {
  const ss=SpreadsheetApp.getActiveSpreadsheet();
  if (!ss||!ss.getSheetByName('MASTER'))throw Error('MASTER 시트에서 실행하세요.');
  PropertiesService.getScriptProperties().setProperty('ODIFY_SHEET_ID',ss.getId());
  odifyRemoveAutoLink();
  ScriptApp.newTrigger('odifyAutoLink').timeBased().everyDays(1).atHour(4)
    .inTimezone('Asia/Seoul').create();
  SpreadsheetApp.getUi().alert('매일 자동 링크 검색을 설정했습니다.');
}

function odifyRemoveAutoLink() {
  ScriptApp.getProjectTriggers().forEach(x=>{
    if (x.getHandlerFunction()==='odifyAutoLink')ScriptApp.deleteTrigger(x);
  });
}

function odifyNormalize(s) {
  return String(s||'').normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
}

function odifyFirstArtist(s) {
  const original=String(s||'').split(/\s+(?:feat\.?|ft\.?|featuring)\s+|\s+[&＆+]\s+|\s+(?:and|with)\s+|\s*,\s*/i)[0];
  const names=[original,original.replace(/\([^)]*\)/g,'')].concat(
    (original.match(/\(([^)]+)\)/g)||[]).map(x=>x.slice(1,-1)));
  return [...new Set(names.map(odifyNormalize).filter(x=>x.length>=2))];
}

function odifyVersionMismatch(song,video) {
  const source=(song.title+' '+song.edition).toLowerCase();
  const actual=video.title.toLowerCase();
  const tests=[
    /\b(?:english|eng|en)\s*(?:ver\.?|version)\b|영어\s*버전/i,
    /\b(?:japanese|jpn|jp)\s*(?:ver\.?|version)\b|일본어\s*버전/i,
    /\b(?:korean|kor)\s*(?:ver\.?|version)\b|한국어\s*버전/i,
    /\b(?:remix|rework)\b|리믹스/i,
    /\b(?:sped[ -]?up|slowed|nightcore|432\s*hz)\b/i,
    /\b(?:cover|karaoke|instrumental)\b|커버|カバー/i,
    /\b(?:live|concert|fancam|stage|dance practice|performance)\b|라이브/i,
    /\b(?:demo|acoustic|piano)\b/i,
    /\b(?:19\d{2}|20\d{2})\s*remaster(?:ed)?\b|\bremaster\b/i
  ];
  if(tests.some(re=>re.test(actual)&&!re.test(source)))return true;
  const expect=source.match(/\b(?:19|20)\d{2}\s*remaster/);
  const found=actual.match(/\b(?:19|20)\d{2}\s*remaster/);
  if(expect&&found&&odifyNormalize(expect[0])!==odifyNormalize(found[0]))return true;
  const partners=song.artist.split(/\s+[&＆+]\s+|\s*,\s*|\s+(?:and|with|feat\.?|ft\.?)\s+/i);
  const evidence=odifyNormalize(video.title+' '+video.description.slice(0,320));
  return partners.slice(1).some(x=>odifyNormalize(x).length>=4&&!evidence.includes(odifyNormalize(x)));
}

function odifyMatch(song,video) {
  if(video.seconds<60||video.seconds>600||odifyVersionMismatch(song,video))return false;
  const title=odifyNormalize(video.title);
  const orig=odifyNormalize(song.title);
  const shortened=odifyNormalize(song.title.split(/[\[(]/)[0]);
  if(!title.includes(orig)&&!(shortened.length>=5&&title.includes(shortened)))return false;
  return odifyFirstArtist(song.artist).some(x=>
    (title+odifyNormalize(video.channel)).includes(x));
}

function odifyParseDuration(s) {
  const m=String(s||'').match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if(!m)return 0;
  return Number(m[1]||0)*86400+Number(m[2]||0)*3600+
    Number(m[3]||0)*60+Number(m[4]||0);
}

function odifyConsensus(song,videos) {
  const good=videos.filter(x=>odifyMatch(song,x));
  if(!good.length)return {status:'후보부족',reason:'일치 후보 없음'};
  const channels=xs=>new Set(xs.map(x=>odifyNormalize(x.channel)||x.id)).size;
  const modes=[...new Set(good.map(x=>x.seconds))].map(s=>{
    const members=good.filter(x=>Math.abs(x.seconds-s)<=ODIFY.tolerance);
    return {sec:s,items:members,count:channels(members)};
  }).sort((a,b)=>b.count-a.count||b.items.length-a.items.length);
  const top=modes[0],comp=Math.max(0,...modes.filter(x=>
    Math.abs(x.sec-top.sec)>2*ODIFY.tolerance).map(x=>x.count));
  const total=channels(good);
  const preference=x=>(
    (/official\s*audio|topic|audio|음원|音源/i.test(x.title)?6:0)+
    (/provided to youtube by/i.test(x.description)?4:0)+
    Math.log10(1+x.views));
  const selected=top.items.sort((a,b)=>preference(b)-preference(a))[0];
  const confidence=Math.min(99,Math.round(10*(50+7*Math.min(top.count,5)+
    12*top.count/total+Math.min(5,Math.log10(1+selected.views))))/10);
  const strong=top.count>=ODIFY.minSources&&top.count>=comp+2&&
    top.count/total>=.45&&confidence>=78;
  return {status:strong?'자동입력':'검토필요',selected,confidence,
    reason:top.count+'/'+total+'개 독립 채널, '+top.sec+'±'+
      ODIFY.tolerance+'초, 경쟁 '+comp+'개'};
}

function odifyYouTube(endpoint,params,key) {
  const url='https://www.googleapis.com/youtube/v3/'+endpoint+'?'+
    Object.keys(params).map(k=>encodeURIComponent(k)+'='+encodeURIComponent(params[k])).join('&')+
    '&key='+encodeURIComponent(key);
  const response=UrlFetchApp.fetch(url,{muteHttpExceptions:true});
  if(response.getResponseCode()!==200)throw Error('YouTube API '+
    response.getResponseCode()+' '+response.getContentText().slice(0,100));
  return JSON.parse(response.getContentText());
}

function odifyDiscover(song,key) {
  const res=odifyYouTube('search',{
    part:'snippet',q:song.artist+' '+song.title+' audio lyrics',type:'video',
    maxResults:ODIFY.perQuery,safeSearch:'moderate'
  },key);
  const ids=[...new Set((res.items||[]).map(x=>x.id&&x.id.videoId).filter(Boolean))];
  if(!ids.length)return [];
  const details=odifyYouTube('videos',{
    part:'snippet,contentDetails,statistics',id:ids.join(',')
  },key);
  return (details.items||[]).map(x=>({
    id:x.id,title:(x.snippet||{}).title||'',
    description:(x.snippet||{}).description||'',
    channel:(x.snippet||{}).channelTitle||'',
    seconds:odifyParseDuration((x.contentDetails||{}).duration),
    views:Number((x.statistics||{}).viewCount||0),
    url:'https://www.youtube.com/watch?v='+x.id
  }));
}

function odifyLogSheet(ss,name,columns) {
  let sh=ss.getSheetByName(name);
  if(!sh){
    sh=ss.insertSheet(name);
    sh.getRange(1,1,1,columns.length).setValues([columns]);
    sh.setFrozenRows(1);
  }
  return sh;
}

function odifyAutoLink() {
  const lock=LockService.getScriptLock();
  if(!lock.tryLock(30000))return;
  try {
    const props=PropertiesService.getScriptProperties();
    const key=props.getProperty('ODIFY_YOUTUBE_KEY');
    const ssId=props.getProperty('ODIFY_SHEET_ID');
    if(!key||!ssId)throw Error('API 키와 스프레드시트 연결을 먼저 설정하세요.');
    const ss=SpreadsheetApp.openById(ssId);
    const sh=ss.getSheetByName(ODIFY.sheetName);
    const rows=sh.getDataRange().getDisplayValues();
    const col=name=>rows[0].indexOf(name);
    for(const name of ['Track ID','아티스트','곡명','YouTube 오디오 URL','링크검증']){
      if(col(name)<0)throw Error('열 누락: '+name);
    }
    const scan=odifyLogSheet(ss,ODIFY.scanName,['Track ID','검색일','결과','재검토일']);
    const audit=odifyLogSheet(ss,ODIFY.auditName,
      ['일시','Track ID','아티스트','곡명','결과','제안 URL','점수','판단 근거']);
    const oldRows=scan.getDataRange().getValues();
    const previous=new Map(oldRows.slice(1).map((r,i)=>[String(r[0]),{
      sheetRow:i+2,retry:r[3]
    }]));
    const stamp=Utilities.formatDate(new Date(),'America/Los_Angeles','yyyy-MM-dd');
    const usage=JSON.parse(props.getProperty('ODIFY_QUERY_USAGE')||'{}');
    const used=usage.date===stamp?Number(usage.count||0):0;
    const available=Math.min(ODIFY.maxPerRun,Math.max(0,ODIFY.maxDaily-used));
    let searched=0,linked=0;
    const newScan=[],newAudit=[];
    for(let i=1;i<rows.length&&searched<available;i++){
      const row=rows[i],code=row[col('Track ID')];
      if(!/^ODF\d{6}$/.test(code)||row[col('YouTube 오디오 URL')])continue;
      const last=previous.get(code);
      if(last&&last.retry&&new Date(last.retry).getTime()>Date.now())continue;
      const song={artist:row[col('아티스트')],title:row[col('곡명')],
        edition:col('음원 버전')>=0?row[col('음원 버전')]:'',code};
      if(!song.artist||!song.title)continue;
      let choice;
      try {
        const found=odifyDiscover(song,key);
        searched++;
        props.setProperty('ODIFY_QUERY_USAGE',JSON.stringify({
          date:stamp,count:used+searched}));
        choice=odifyConsensus(song,found);
      }catch(e){
        newAudit.push([new Date(),code,song.artist,song.title,
          'API 오류','',0,String(e).slice(0,180)]);
        break;
      }
      const target=sh.getRange(i+1,col('YouTube 오디오 URL')+1);
      if(choice.status==='자동입력'&&!target.getDisplayValue()){
        target.setValue(choice.selected.url);
        sh.getRange(i+1,col('링크검증')+1).setValue('재검토');
        linked++;
      }
      const retry=new Date(Date.now()+ODIFY.retryDays*86400000);
      const updated=[code,new Date(),choice.status,retry];
      if(last)scan.getRange(last.sheetRow,1,1,4).setValues([updated]);
      else newScan.push(updated);
      newAudit.push([new Date(),code,song.artist,song.title,choice.status,
        choice.selected?choice.selected.url:'',choice.confidence||0,choice.reason]);
    }
    if(newScan.length)scan.getRange(scan.getLastRow()+1,1,newScan.length,4)
      .setValues(newScan);
    if(newAudit.length)audit.getRange(audit.getLastRow()+1,1,newAudit.length,8)
      .setValues(newAudit);
    return {searched,linked,remaining:ODIFY.maxDaily-used-searched};
  }finally {
    lock.releaseLock();
  }
}
