const assert=require("node:assert/strict");
const fs=require("node:fs");
const vm=require("node:vm");
const path=require("node:path");
const src=fs.readFileSync(path.resolve(__dirname,"../odify_autolink_sheets.gs"),"utf8");
const context=vm.createContext({});
vm.runInContext(src,context);

function video(i,seconds,title,channel) {
  return {id:"abcdefghi"+String(i).padStart(2,"0"),
    title,seconds,channel:channel||"u"+i,
    description:"",views:100,url:"https://www.youtube.com/watch?v=abcdefghi"+String(i).padStart(2,"0")};
}
const track={artist:"LE SSERAFIM",title:"EASY",edition:"한국어 원곡"};
let candidates=[
  video(0,165,"LE SSERAFIM - EASY","lyricsA"),
  video(1,164,"LE SSERAFIM - EASY","lyricsB"),
  video(2,166,"LE SSERAFIM - EASY","lyricsC"),
  video(3,165,"LE SSERAFIM EASY (English ver.)","singer")
];
assert.equal(context.odifyVersionMismatch(track,candidates[3]),true);
assert.equal(context.odifyConsensus(track,candidates).status,"자동입력");
assert.equal(context.odifyConsensus(track,candidates).selected.channel!=="singer",true);
const ambiguous=[video(0,165,"LE SSERAFIM EASY","u0"),
video(1,165,"LE SSERAFIM EASY","u1"),
video(2,165,"LE SSERAFIM EASY","u2"),
video(3,225,"LE SSERAFIM EASY","u3"),
video(4,225,"LE SSERAFIM EASY","u4")];
assert.equal(context.odifyConsensus(track,ambiguous).status,"검토필요");
const sameChannel=[0,1,2,3].map(i=>video(i,165,"LE SSERAFIM EASY","same"));
assert.equal(context.odifyConsensus(track,sameChannel).status,"검토필요");
const remaster={artist:"Fleetwood Mac",title:"Dreams - 2001 Remaster",edition:""};
assert.equal(context.odifyVersionMismatch(remaster,
 video(9,258,"Fleetwood Mac Dreams (2004 Remaster)","Fleetwood Mac")),true);
assert.equal(context.odifyParseDuration("PT3M15S"),195);

assert.equal(context.ODIFY===undefined,true); // const ODIFY is scoped inside VM
const records=[];
const fakeCandidateSheet={getDataRange(){return {getValues(){
 return [
  ["Track ID","검색 쿼리","검색 시각","영상 ID","제목","채널","재생초","조회수","설명 일부"],
  ["ODF000201","easy","2026-10-23","abcdefghijk","LE SSERAFIM EASY","Uploader",165,12000,""]
 ];
}}}};
const cidx=context.odifyCandidateIndex(fakeCandidateSheet);
assert.equal(cidx.get("ODF000201").items.length,1);
assert.equal(cidx.get("ODF000201").items[0].seconds,165);
assert.equal(context.odifyCandidateRows("ODF000201","easy",new Date(),candidates).length,4);
const writes=[];
context.SpreadsheetApp={flush(){}};
context.odifyLogSheet=(ss,name,header)=>({
 getRange(row,col,numRows,numCols){return {
  setValues(v){writes.push({name,row,col,values:v});return this;},
  setNumberFormat(){return this;}
 }}
});
context.odifyShowProgress({},{
 phase:"검색 중",total:508,connected:328,missing:180,target:50,
 done:25,linked:13,review:12,cached:8,apiCalls:17,todayUsed:65,current:"test"});
const table=writes[0].values;
assert.equal(table.find(x=>x[0]==="이번 실행 진행률 (%)")[1],50);
assert.equal(table.find(x=>x[0]==="이번 실행 캐시 재사용")[1],8);
assert.equal(table.find(x=>x[0]==="오늘 누적 API 검색 호출")[1],65);
assert.equal(table.find(x=>x[0]==="현재 처리 곡")[1],"test");

process.stdout.write("Odify Google Sheets AutoLink smoke tests passed.\n");
