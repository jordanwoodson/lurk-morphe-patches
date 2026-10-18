// Boundary fixture only. The APK/native-Hermes stage verifies these contracts in the target app.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
let state, session = {status:'authenticated'}, api, props = {editable:true, maxLength:500,mostRecentEventCount:42};
let nativeText="😀 hello tail", nativeCursor=8, deferFabric=false;
const fabric=[]; const hostRef={};
// Pinned ReactTextInputManager.updateExtraData preserves distance from the old native end.
function commitSpans(text){const distance=nativeText.length-nativeCursor;nativeText=text;nativeCursor=Math.max(0,text.length-distance);}
const calls = [], listeners = [], timers = [];
const ref = {getNativeRef:()=>hostRef,setSelection:(a,b)=>{nativeCursor=Math.min(nativeText.length,a);calls.push(['cursor',a,b]);},focus:()=>calls.push(['focus'])};
const native = {id:'1',name:'Native',locked:false};
const store = {getState:()=>state,setState:(patch)=>{const old=state;state={...state,...patch};listeners.forEach(fn=>fn(state,old));},subscribe:(fn)=>{listeners.push(fn);return()=>{};}};
state = {inputContent:'😀 hello tail', selection:{start:8,end:8}, inputRef:{current:ref}, reply:{id:7},
    emotes:[native],allowedEmotes:[native],emotesSections:[{id:'Global',name:'Global',emotes:[native]}],
    setInputContent:text=>{store.setState({inputContent:text});if(deferFabric)fabric.push(text);else commitSpans(text);},setSelection:selection=>store.setState({selection}),
    insertEmote:name=>calls.push(['native',name]),autofillEmote:name=>calls.push(['native-auto',name]),
    setEmotes:()=>store.setState({emotes:[native],allowedEmotes:[native],emotesSections:[{id:'Global',emotes:[native]}]})};
class ImageCacheV2 { static getImage(url) { calls.push(['image',url,this]); return Promise.resolve(url); } }
const modules = {10:{chatRoomStore:store},20:{ImageCacheV2},30:{useSessionStore:{getState:()=>session}},
    40:{getInternalInstanceHandleFromPublicInstance:()=>({stateNode:{canonical:{currentProps:props}}})},
    50:{__INTERNAL_VIEW_CONFIG:{uiViewClassName:'AndroidTextInput'},Commands:{setTextAndSelection:(ref,count,text,start,end)=>{assert.equal(ref,hostRef);assert.equal(count,42);assert.equal(start,end);nativeText=text;nativeCursor=start;calls.push(['atomic',text,start]);}}}};
const context={console,setTimeout:fn=>timers.push(fn),__r:id=>modules[id],RN$registerCallableModule:(name,factory)=>{assert.equal(name,'LurkKickChat');api=factory();}};
context.global=context;
const path='patches/src/main/runtime/kick/chat.js';
assert.ok(fs.existsSync(path),'compiled composer source must exist');
vm.runInNewContext(fs.readFileSync(path,'utf8'),context);
api.configure(JSON.stringify({store:10,images:20,session:30,host:40,input:50,files:'https://files.kick.com/',enabled:true}));
const entry={id:'333333333333333333333333',name:'EZ',url:'https://cdn.7tv.app/emote/333333333333333333333333/2x.webp',flags:0,animated:true};
api.channel(JSON.stringify({generation:'one',channelId:'668',enabled:true}));
api.catalog(JSON.stringify({generation:'one',channelId:'668',emotes:[entry,{...entry,name:'Native'}]}));
assert.equal(state.emotesSections.length,2); assert.equal(state.allowedEmotes.length,2,'native name wins');
state.insertEmote('EZ');
assert.equal(nativeCursor,12,'actual native caret survives Fabric span replacement');assert.equal(timers.length,0,'no delayed caret correction');
assert.equal(state.inputContent,'😀 hello EZ tail'); assert.equal(state.selection.start,12); assert.equal(state.reply.id,7);
state.insertEmote('Native'); assert.equal(calls.at(-1)[0],'native');
nativeText='😀 :E tail';nativeCursor=5;store.setState({inputContent:nativeText,selection:{start:5,end:5}});
state.autofillEmote('EZ'); assert.equal(state.inputContent,'😀 EZ tail'); assert.equal(state.selection.start,6);
assert.equal(nativeCursor,6,'autocomplete native cursor');
nativeText='before old after';nativeCursor=7;store.setState({inputContent:nativeText,selection:{start:7,end:10}});
state.insertEmote('EZ');assert.equal(state.inputContent,'before EZ after');assert.equal(nativeCursor,10,'selected range replaced with caret before suffix');
state.setEmotes(); assert.equal(state.allowedEmotes.length,2,'stock rebuild retains provider catalog');
ImageCacheV2.getImage('https://files.kick.com/emotes/lurk7tv_333333333333333333333333/fullsize?width=64,format=auto');
assert.equal(calls.at(-1)[1],entry.url); assert.equal(calls.at(-1)[2],ImageCacheV2);
ImageCacheV2.getImage('https://files.cdn.kick.com/emotes/lurk7tv_333333333333333333333333/fullsize?width=96,format=png');
assert.equal(calls.at(-1)[1],entry.url.replace('2x.webp','2x_static.webp'),'actual native picker CDN/static request');
api.catalog(JSON.stringify({generation:'one',channelId:'668',emotes:[{...entry,animated:false}]}));
ImageCacheV2.getImage('https://files.cdn.kick.com/emotes/lurk7tv_333333333333333333333333/fullsize?width=96,format=png');
assert.equal(calls.at(-1)[1],entry.url,'naturally static images use the normal CDN filename');
const untrusted='https://files.cdn.kick.com.example/emotes/lurk7tv_333333333333333333333333/fullsize';
ImageCacheV2.getImage(untrusted);assert.equal(calls.at(-1)[1],untrusted,'unrelated host unchanged');
// Same-length native spans can commit after a deliberate later move, including the old/end positions.
for(const move of [0,1,12,18]){
 nativeText='before after';nativeCursor=7;store.setState({inputContent:nativeText,selection:{start:7,end:7}});
 deferFabric=true;state.insertEmote('EZ');
 nativeCursor=Math.min(move,nativeText.length);const desired=nativeCursor;
 fabric.splice(0).forEach(commitSpans);assert.equal(nativeCursor,desired,'later manual movement wins');
 assert.equal(timers.length,0,'no callback can undo movement or keyboard dismissal');deferFabric=false;
}
// Stock reset replaces inputRef before clearing sections. Old document authority must end immediately.
const catalog = generation => JSON.stringify({generation,channelId:'668',emotes:[entry]});
const associate = generation => api.channel(JSON.stringify({generation,channelId:'668',enabled:true}));
const commandCount = () => calls.filter(c=>c[0]==='atomic').length;
store.setState({inputRef:{current:null}});
store.setState({emotesSections:[],emoteBeingTyped:''});
assert.equal(state.emotesSections.some(s=>s.id==='lurk7tv'),false,'reset must not resurrect old section');
const resetDraft=state.inputContent, resetReply=state.reply, resetCommands=commandCount();
store.setState({inputRef:{current:ref}});
api.catalog(catalog('one'));state.insertEmote('EZ');
assert.equal(state.inputContent,resetDraft);assert.equal(commandCount(),resetCommands,'fresh ref cannot use prior page catalog');
associate('one');api.catalog(catalog('one'));state.insertEmote('EZ');
assert.equal(commandCount(),resetCommands,'retired generation cannot be reactivated');
associate('fresh');api.catalog(catalog('fresh'));state.setEmotes();
assert.equal(state.allowedEmotes.length,2,'fresh page and ordinary same-channel rebuild restore catalog');
assert.equal(state.reply,resetReply);assert.equal(state.inputContent,resetDraft);
// Ref.current can change without a Zustand write. Insertion must revalidate the actual ref lifetime.
state.inputRef.current={...ref};state.insertEmote('EZ');
assert.equal(commandCount(),resetCommands,'silent remount cannot inherit old authority');
state.inputRef.current=ref;associate('remount');api.catalog(catalog('remount'));
api.channel(JSON.stringify({enabled:false}));api.catalog(catalog('remount'));state.insertEmote('EZ');
assert.equal(commandCount(),resetCommands,'disposed page rejects late catalog and insertion');
assert.equal(state.allowedEmotes.length,1,'disposal retains only native emotes');
// Initial mount may attach to the same ref container after a fresh page association.
store.setState({inputRef:{current:null}});associate('initial-mount');api.catalog(catalog('initial-mount'));
state.inputRef.current=ref;state.insertEmote('EZ');
assert.equal(commandCount(),resetCommands+1,'fresh association permits first mount of its own ref');
props.mostRecentEventCount=undefined;const invalid=state.inputContent;state.insertEmote('EZ');
assert.equal(state.inputContent,invalid,'missing native event counter fails closed');props.mostRecentEventCount=42;
const before=state.inputContent;
props.editable=false;state.insertEmote('EZ');assert.equal(state.inputContent,before,'rules/read-only guard');
props.editable=true;session.status='unauthenticated';state.insertEmote('EZ');assert.equal(state.inputContent,before,'signed-out guard');
session.status='authenticated';
api.channel(JSON.stringify({generation:'two',channelId:'999',enabled:true}));
assert.equal(state.allowedEmotes.length,1); assert.equal(state.inputContent,before,'channel change preserves draft');
api.catalog(JSON.stringify({generation:'one',channelId:'668',emotes:[entry]})); assert.equal(state.allowedEmotes.length,1,'stale document rejected');
api.channel(JSON.stringify({generation:'two',channelId:'999',enabled:false}));
api.catalog(JSON.stringify({generation:'two',channelId:'999',emotes:[entry]})); assert.equal(state.allowedEmotes.length,1,'off rejects catalog');
console.log('PASS: composer insertion, permission guards, image mapping, native precedence and stale/off catalog');
