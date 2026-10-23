/**
 * Odify MASTER AutoLink (Google Apps Script).
 * Extensions > Apps Script in the existing native MASTER Google Sheet.
 * Searches metadata only. No audio downloads or official-channel requirement.
 * Existing URLs are immutable; automatic additions receive status '재검토'.
 */
const ODIFY = Object.freeze({
  sheetName:'MASTER',scanName:'ODIFY_LINK_SCAN',auditName:'ODIFY_LINK_AUDIT',
  maxPerRun:55,maxDaily:95,perQuery:50,tolerance:2,minSources:3,retryDays:21,
  progressName:'ODIFY_PROGRESS',candidatesName:'ODIFY_LINK_CANDIDATES',
  cacheDays:21,refreshEvery:4
});

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Odify 자동 링크')
    .addItem('YouTube API 키 입력','odifySetYoutubeKey')
    .addItem('매일 자동실행 설정','odifyInstallAutoLink')
    .addItem('진행률/남은 곡 새로고침','odifyRefreshProgress')
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
  [4,15].forEach(hour=>{
    ScriptApp.newTrigger('odifyAutoLink').timeBased().everyDays(1)
      .atHour(hour).inTimezone('Asia/Seoul').create();
  });
  odifyRefreshProgress();
  SpreadsheetApp.getUi().alert('매일 2회 검색을 설정했습니다. ODIFY_PROGRESS 탭에서 진행률을 볼 수 있습니다.');
}

function odifyRemoveAutoLink() {
  ScriptApp.getProjectTriggers().forEach(x=>{
    if (['odifyAutoLink','odifyRunAutoLink'].includes(x.getHandlerFunction()))
      ScriptApp.deleteTrigger(x);
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

/**
 * Monitoring is visible in the sheet even while the trigger is running.
 * The % value is the share of this run's eligible backlog attempted, not
 * a fake claim that the 1980–2026 catalog has been filled.
 */
function odifyShowProgress(ss, state) {
  const sheet=odifyLogSheet(ss,ODIFY.progressName,['항목','현재 값']);
  const rows=[
    ['수집 상태',state.phase||'대기'],
    ['갱신 시각',new Date()],
    ['전체 MASTER 곡 수',state.total||0],
    ['현재 YouTube URL 연결곡',state.connected||0],
    ['연결되지 않은 곡',state.missing||0],
    ['이번 실행 검색 대상',state.target||0],
    ['이번 실행 처리 완료',state.done||0],
    ['이번 실행 진행률 (%)',state.target?Math.round(1000*state.done/state.target)/10:100],
    ['이번 실행 신규 링크',state.linked||0],
    ['이번 실행 재검토/보류',state.review||0],
    ['이번 실행 캐시 재사용',state.cached||0],
    ['이번 실행 API 검색 호출',state.apiCalls||0],
    ['오늘 누적 API 검색 호출',state.todayUsed||0],
    ['오늘 남은 기본 검색 호출',Math.max(0,ODIFY.maxDaily-(state.todayUsed||0))],
    ['현재 처리 곡',state.current||''],
    ['최종 실행 메시지',state.message||''],
  ];
  sheet.getRange(2,1,rows.length,2).setValues(rows);
  sheet.getRange(8,2).setNumberFormat('0');
  sheet.getRange(9,2).setNumberFormat('0.0');
  SpreadsheetApp.flush();
}
function odifyRefreshProgress() {
  const props=PropertiesService.getScriptProperties();
  const ss=SpreadsheetApp.getActiveSpreadsheet() ||
    SpreadsheetApp.openById(props.getProperty('ODIFY_SHEET_ID') ||
      props.getProperty('ODIFY_SPREADSHEET_ID'));
  const sh=ss.getSheetByName(ODIFY.sheetName);
  if(!sh)throw Error('MASTER 탭이 없습니다.');
  const rows=sh.getDataRange().getDisplayValues();
  const headers=rows[0]||[];
  const linkCol=headers.indexOf('YouTube 오디오 URL');
  if(linkCol<0)throw Error('YouTube 오디오 URL 열이 없습니다.');
  const data=rows.slice(1).filter(r=>/^ODF\\d{6}$/.test(r[0]));
  const connected=data.filter(r=>r[linkCol]).length;
  const usage=JSON.parse(props.getProperty('ODIFY_QUERY_USAGE')||
    props.getProperty('ODIFY_DAILY_CALLS')||'{}');
  const date=Utilities.formatDate(new Date(),'America/Los_Angeles','yyyy-MM-dd');
  odifyShowProgress(ss,{
    phase:'대기',total:data.length,connected,missing:data.length-connected,
    todayUsed:usage.date===date?Number(usage.count||0):0,
    message:'지금 링크 검색 또는 예약 실행을 기다립니다.'
  });
}
function odifyCandidateIndex(sheet) {
  const values=sheet.getDataRange().getValues();
  const index=new Map();
  for(const row of values.slice(1)) {
    const [code,query,when,id,title,channel,seconds,views,description]=row;
    if(!code||!id)continue;
    const prev=index.get(code);
    if(!prev||prev.query!==query||Number(prev.when)!==Number(when)) {
      index.set(code,{query,when,items:[]});
    }
    index.get(code).items.push({
      id:String(id),title:String(title||''),channel:String(channel||''),
      seconds:Number(seconds)||0,views:Number(views)||0,
      description:String(description||''),
      url:'https://www.youtube.com/watch?v='+id
    });
  }
  return index;
}
function odifyCandidateRows(code,query,when,found) {
  return found.map(v=>[
    code,query,when,v.id,v.title,v.channel,v.seconds,v.views,
    (v.description||'').slice(0,300)
  ]);
}

function odifyAutoLink() {
  const lock=LockService.getScriptLock();
  if(!lock.tryLock(2000))return {skipped:'Already running'};
  let ss,progress={phase:'준비 중',message:'시트 조회 중'};
  try {
    const props=PropertiesService.getScriptProperties();
    const key=props.getProperty('ODIFY_YOUTUBE_KEY')||
      props.getProperty('ODIFY_YOUTUBE_API_KEY');
    const ssId=props.getProperty('ODIFY_SHEET_ID')||
      props.getProperty('ODIFY_SPREADSHEET_ID');
    if(!key||!ssId)throw Error('YouTube API 키와 MASTER 시트 연결을 먼저 설정하세요.');
    ss=SpreadsheetApp.openById(ssId);
    const sh=ss.getSheetByName(ODIFY.sheetName);
    const rows=sh.getDataRange().getDisplayValues();
    const col=name=>rows[0].indexOf(name);
    for(const name of ['Track ID','아티스트','곡명','YouTube 오디오 URL','링크검증']){
      if(col(name)<0)throw Error('열 누락: '+name);
    }
    const scan=odifyLogSheet(ss,ODIFY.scanName,
      ['Track ID','검색일','결과','재검토일','검색 쿼리']);
    const audit=odifyLogSheet(ss,ODIFY.auditName,
      ['일시','Track ID','아티스트','곡명','결과','제안 URL','점수','판단 근거']);
    const candidateSheet=odifyLogSheet(ss,ODIFY.candidatesName,
      ['Track ID','검색 쿼리','검색 시각','영상 ID','제목','채널',
       '재생초','조회수','설명 일부']);
    const previous=new Map(scan.getDataRange().getValues().slice(1).map(
      (r,i)=>[String(r[0]),{sheetRow:i+2,retry:r[3],query:r[4]}]));
    const cachedIndex=odifyCandidateIndex(candidateSheet);
    const date=Utilities.formatDate(new Date(),'America/Los_Angeles','yyyy-MM-dd');
    const usage=JSON.parse(props.getProperty('ODIFY_QUERY_USAGE')||
      props.getProperty('ODIFY_DAILY_CALLS')||'{}');
    const used=usage.date===date?Number(usage.count||0):0;
    const available=Math.min(ODIFY.maxPerRun,Math.max(0,ODIFY.maxDaily-used));
    const index=[];
    let connected=0;
    for(let i=1;i<rows.length;i++){
      if(!/^ODF\\d{6}$/.test(rows[i][0]))continue;
      if(rows[i][col('YouTube 오디오 URL')]) {connected++;continue}
      if(!rows[i][col('아티스트')]||!rows[i][col('곡명')])continue;
      const last=previous.get(rows[i][0]);
      const song={artist:rows[i][col('아티스트')],title:rows[i][col('곡명')],
         edition:col('음원 버전')>=0?rows[i][col('음원 버전')]:'',code:rows[i][0]};
      const query=odifyNormalize(song.artist+'|'+song.title+'|'+song.edition);
      if(last&&last.query===query&&last.retry&&
        new Date(last.retry).getTime()>Date.now())continue;
      index.push({i,song,query});
    }
    progress={
      phase:'검색 중',total:rows.length-1,connected,
      missing:rows.length-1-connected,target:index.length,
      done:0,linked:0,review:0,cached:0,apiCalls:0,todayUsed:used,
      current:'',message:'기존 링크 보존 · 최대 '+available+'회 검색 호출'
    };
    odifyShowProgress(ss,progress);
    let newAudit=[],newScan=[],newCandidates=[];
    let examined=0,stopReason='';
    const start=Date.now();
    for(const task of index) {
      const {i,song,query}=task;
      if(Date.now()-start>4.4*60*1000){
        stopReason='Apps Script 실행 시간 보호: 다음 실행에서 계속';
        break;
      }
      const hit=cachedIndex.get(song.code);
      const cacheGood=hit&&hit.query===query&&
        (Date.now()-new Date(hit.when).getTime())<ODIFY.cacheDays*86400000;
      let found;
      if(cacheGood){
        found=hit.items;
        progress.cached++;
      } else {
        if(progress.apiCalls>=available){
          stopReason='오늘의 검색 API 예산 또는 이번 실행 횟수 소진';
          break;
        }
        // Reserve the search allowance BEFORE making the API call: quota
        // may still be consumed even if the request ends in an error.
        progress.apiCalls++;
        progress.todayUsed=used+progress.apiCalls;
        props.setProperty('ODIFY_QUERY_USAGE',JSON.stringify({
          date,count:progress.todayUsed
        }));
        try{
          found=odifyDiscover(song,key);
          const now=new Date();
          const candidates=odifyCandidateRows(song.code,query,now,found);
          if(candidates.length)newCandidates.push(...candidates);
          cachedIndex.set(song.code,{query,when:now,items:found});
        }catch(error){
          stopReason='API 호출 중단: '+String(error).slice(0,150);
          newAudit.push([new Date(),song.code,song.artist,song.title,
            'API 오류','',0,stopReason]);
          break;
        }
      }
      const choice=odifyConsensus(song,found);
      const target=sh.getRange(i+1,col('YouTube 오디오 URL')+1);
      if(choice.status==='자동입력'&&!target.getDisplayValue()){
        target.setValue(choice.selected.url);
        sh.getRange(i+1,col('링크검증')+1).setValue('재검토');
        progress.linked++;
        progress.connected++;
        progress.missing--;
      }else progress.review++;
      const retry=new Date(Date.now()+ODIFY.retryDays*86400000);
      const row=[song.code,new Date(),choice.status,retry,query];
      const last=previous.get(song.code);
      if(last)scan.getRange(last.sheetRow,1,1,5).setValues([row]);
      else newScan.push(row);
      newAudit.push([new Date(),song.code,song.artist,song.title,
        choice.status,choice.selected?choice.selected.url:'',
        choice.confidence||0,choice.reason||'']);
      progress.done++;
      progress.current=song.artist+' — '+song.title;
      examined++;
      if(examined%ODIFY.refreshEvery===0)odifyShowProgress(ss,progress);
    }
    if(newScan.length)scan.getRange(scan.getLastRow()+1,1,newScan.length,5)
      .setValues(newScan);
    if(newCandidates.length)candidateSheet.getRange(
      candidateSheet.getLastRow()+1,1,newCandidates.length,9)
      .setValues(newCandidates);
    if(newAudit.length)audit.getRange(audit.getLastRow()+1,1,newAudit.length,8)
      .setValues(newAudit);
    progress.phase=stopReason?'일부 처리 / 다음 실행 대기':'이번 실행 완료';
    progress.message=stopReason||'기존 URL은 덮어쓰지 않았으며 신규 링크는 재검토 상태입니다.';
    progress.current='';
    odifyShowProgress(ss,progress);
    return {searched:progress.apiCalls,cached:progress.cached,
      linked:progress.linked,remaining:Math.max(0,ODIFY.maxDaily-progress.todayUsed),
      attempted:progress.done,total:progress.target};
  }catch(error){
    if(ss)try{
      progress.phase='오류';
      progress.message=String(error);
      odifyShowProgress(ss,progress);
    }catch(ignored){}
    throw error;
  }finally{lock.releaseLock();}
}
