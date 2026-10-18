// Deterministic production-page lifecycle boundary; not a live-provider/runtime UI test.
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const source=fs.readFileSync('patches/src/main/resources/kick/chat/chat-page.js','utf8');
const pageFunction=source.slice(source.indexOf('    function sevenTv('),source.lastIndexOf('\n    return applied'));
const flush=async()=>{for(let i=0;i<8;i++)await new Promise(setImmediate);};
const G='111111111111111111111111', C='222222222222222222222222', N='333333333333333333333333';
const emote=name=>({id:'444444444444444444444444',name});
async function harness(){
 const timers=new Map(), sockets=[], events=[], requests=[], handlers={};let timerId=0;
 let globalSet={id:G,emotes:[emote('shared'),emote('global')]};
 let connection={id:N,emote_set:{id:C,emotes:[emote('shared'),emote('channel')]}};
 const responses=new Map();
 const context={console,AbortController,Response,TextDecoder,Uint8Array,queueMicrotask,
  options:{generation:'page',channelId:'668'},whenReady(){},
  window:{lurkChat:{},ReactNativeWebView:{postMessage:s=>events.push(JSON.parse(s))},addEventListener:(name,fn)=>handlers[name]=fn},
  document:{hidden:false,addEventListener:(name,fn)=>handlers[name]=fn},
  Math:Object.assign(Object.create(Math),{random:()=>0.5}),
  setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),
  fetch:async url=>{requests.push(url);let data=url.endsWith('/global')?globalSet:connection;
   if(responses.has(url))data=responses.get(url);
   if(data instanceof Error)throw data;
   return new Response(JSON.stringify(data));},
  WebSocket:class {constructor(){this.sent=[];sockets.push(this);queueMicrotask(()=>this.onopen&&this.onopen());}
   send(s){this.sent.push(JSON.parse(s));}close(){this.closed=true;if(this.onclose)this.onclose();}}
 };
 vm.runInNewContext(pageFunction+"\nsevenTv('channel','42');",context);await flush();
 const tick=async ms=>{const pair=[...timers].find(([,t])=>t.ms===ms);assert.ok(pair,'scheduled timer '+ms);timers.delete(pair[0]);pair[1].fn();await flush();};
 return {context,timers,sockets,events,requests,responses,handlers,tick,
  connection:value=>connection=value,global:value=>globalSet=value,
  names:()=>events.at(-1).emotes.map(e=>e.name),
  counts:()=>JSON.parse(JSON.stringify(context.window.lurkChat.sevenTvCount)),
  active:()=>sockets.filter(s=>!s.closed),
  dispatch:(seq,id=C)=>sockets.at(-1).onmessage({data:JSON.stringify({op:0,s:seq,d:{type:'emote_set.update',body:{id}}})})};
}
(async()=>{
 const h=await harness();assert.deepEqual(h.counts(),[2,2]);
 assert.deepEqual(h.names(),['shared','channel','global'],'channel aliases precede globals');
 // Failure and malformed success retain authoritative data; null/omitted no-set remove it.
 h.connection(new Error('offline'));await h.tick(300000);await h.tick(500);await h.tick(1000);
 assert.deepEqual(h.counts(),[2,2]);
 h.connection({emote_set:{id:C,emotes:'invalid'}});await h.tick(300000);assert.deepEqual(h.counts(),[2,2]);
 h.connection({emote_set:null});await h.tick(300000);
 assert.deepEqual(h.counts(),[0,2],'authoritative null removes channel only');
 assert.deepEqual(h.names(),['shared','global']);
 assert.equal(h.active().length,1);assert.deepEqual(h.active()[0].sent.map(m=>m.d.condition.object_id),[G],'removed set unsubscribed');
 h.connection({id:N,emote_set:{id:C,emotes:[emote('channel')]}});await h.tick(300000);
 assert.deepEqual(h.counts(),[1,2]);
 h.connection({id:N});await h.tick(300000);assert.deepEqual(h.counts(),[0,2],'validated omitted no-set removes channel');
 h.global({id:G});await h.tick(300000);assert.deepEqual(h.counts(),[0,0],'omitted empty vector clears global');
 assert.deepEqual(h.names(),[]);

 const e=await harness();
 e.responses.set('https://7tv.io/v3/emote-sets/'+C,{id:C});e.dispatch(1);await e.tick(250);
 assert.deepEqual(e.counts(),[0,2],'last channel emote removal clears cached vector');
 e.responses.set('https://7tv.io/v3/emote-sets/'+G,{id:G});
 e.dispatch(3);await e.tick(250);await e.tick(250);
 assert.deepEqual(e.counts(),[0,0],'sequence gap refreshes both active sets');
 assert.ok(e.requests.includes('https://7tv.io/v3/emote-sets/'+G));
 e.connection({id:N,emote_set:{id:N,emotes:[emote('new')]}});await e.tick(300000);
 assert.deepEqual(e.active()[0].sent.map(m=>m.d.condition.object_id),[N,G],'changed set IDs resubscribe without old ID');
 const old=e.active()[0];await e.tick(61000);assert.ok(old.closed,'heartbeat expiry closes stale socket');
 await e.tick(1000);assert.equal(e.active().length,1,'first retry reconnects');
 // No HELLO means no successful recovery. A burst has eight bounded reconnects.
 for(let i=1;i<8;i++){e.active()[0].close();await e.tick(Math.min(60000,1000*2**Math.min(i,6)));}
 e.active()[0].close();assert.equal(e.active().length,0);
 assert.equal([...e.timers.values()].some(t=>[1000,2000,4000,8000,16000,32000,60000].includes(t.ms)),false,'retry burst exhausted');
 e.handlers.pagehide();assert.equal(e.events.at(-1).type,'lurk7tvDispose','pagehide ends native authority');
 assert.equal(e.timers.size,0,'teardown clears pending work');
 console.log('PASS: authoritative empty/removal, set resubscription, sequence gap, heartbeat, retry exhaustion and disposal');
})().catch(error=>{console.error(error);process.exitCode=1;});
