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
process.stdout.write("Odify Google Sheets AutoLink smoke tests passed.\n");
