'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const refs=new Map(),leases=new Map(),effects=[],calls=[];let ref,rowProps,stale;
const R={useRef:()=>ref,useState:()=>[0,()=>{}],useEffect:f=>effects.push(f),useContext:c=>c.value,memo:f=>({type:f}),isValidElement:()=>false};
R.createContext=value=>({value,Provider:"Provider"});R.createElement=()=>null;R.useMemo=f=>f();R.useLayoutEffect=R.useEffect;
const catalogs={42:{Old:'lurk7s_old'},43:{New:'lurk7s_new'}};
function data(q){
 calls.push(q);
 if(q.op==='config')return{version:1,providers:true,revision:1};
 if(q.op==='acquire'){leases.set(q.id,q);return{active:true};}
 if(q.op==='release'){leases.delete(q.id);return{};}
 if(q.op==='catalog'){const lease=leases.get(q.id);return lease?{id:q.id,channel:lease.channel,user:lease.user,userId:lease.userId,...(stale?{[stale]:'retired'}:{}),generation:7,...(q.generation===7?{}:{emotes:catalogs[lease.channel]}),badges:[{title:lease.user+lease.userId,url:'https://example.test/badge.png'}]}:{};}
 return{};
}
const modules={react:R,native:{NativeModules:{TwitchRNFeedSettings:{getLurkChatData:raw=>JSON.stringify(data(JSON.parse(raw)))}}},row:{ChatMessageRow:{type:p=>{rowProps=p;return null;}}},body:{MessageBody:()=>{}},time:{TimestampPart:()=>{},formatTimestamp:()=>{}},store:{useChatStore:()=>{},useChatSelector:()=>{}},metrics:{ChatTextMetricsContext:{},ChatFontScaleContext:{}},badges:{badgeKey:(set,version)=>set+'/'+version},stage:{useClassicStagePresentation:()=>{}},column:{useColumnTrackWidth:()=>{}}};
modules.overlayHost={OneChatOverlayHost:()=>null};modules.theatreStore={useTheatreChatStore:()=>null};
modules.classicColumn={ClassicChatColumn:()=>null};modules.chatArea={ChatArea:()=>null};
const global={console,__r:id=>modules[id],RN$registerCallableModule:()=>{},setTimeout:()=>1,clearTimeout:()=>{}};global.globalThis=global;
vm.runInNewContext(fs.readFileSync(process.argv[2],'utf8'),global);
global.LurkTwitchChat.configure(JSON.stringify(Object.fromEntries(Object.keys(modules).map(k=>[k,k]))));
const message={id:'shared-message',type:'chat',body:'Old New',user:'viewer',userId:'1',emotes:[]};
function render(channel,user='viewer',userId='1',row='a',sourceRoomID){
 if(!refs.has(row))refs.set(row,{current:null});ref=refs.get(row);effects.length=0;
 modules.row.ChatMessageRow.type({message:{...message,user,userId,sourceRoomID},channelID:channel});
}
const ids=()=>Array.from(rowProps.message.emotes||[],e=>e.id);
render('42');let cleanup=effects[0]();render('42');assert.deepEqual(ids(),['lurk7s_old']);
function transition(channel,user,userId,expected,sourceRoomID){
 const before=calls.filter(q=>q.op==='catalog').length;
 render(channel,user,userId,'a',sourceRoomID);const effect=effects[0];
 assert.deepEqual(ids(),[],'uncommitted identity cannot use old provider metadata');
 assert.equal(calls.filter(q=>q.op==='catalog').length,before,'no stale lease read before cleanup/acquire');
 const retired=cleanup;cleanup();cleanup=effect();render(channel,user,userId,'a',sourceRoomID);
 assert.deepEqual(ids(),[expected]);assert.equal(rowProps.badgeRegistry['lurk-provider-0/1'].title,user+userId);
 retired();render(channel,user,userId,'a',sourceRoomID);assert.deepEqual(ids(),[expected],'retired cleanup cannot invalidate replacement');
}
transition('43','viewer','1','lurk7s_new');
transition('43','other','1','lurk7s_new');
transition('43','other','2','lurk7s_new');
transition('43','other','2','lurk7s_old','42');
render('43','viewer','1','b');const cleanupB=effects[0]();render('43','viewer','1','b');assert.deepEqual(ids(),['lurk7s_new'],'shared destination cache stays correct');
for(const field of ['id','channel','user','userId']){stale=field;render('43','viewer','1','b');assert.deepEqual(ids(),[],'mismatched '+field+' response cannot reuse cached metadata');}stale=undefined;
leases.clear();render('43','viewer','1','b');assert.deepEqual(ids(),[],'retired native lease cannot reuse cached metadata');cleanupB();cleanup();
console.log('PASS committed channel/user/userId/source-room identities, stale response, retired cleanup and shared catalog');
