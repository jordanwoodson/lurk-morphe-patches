'use strict';
// Controlled React commit/effect boundaries, original host/store/ChatLine projection shape.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const fibers=new Map(),contexts=new Map(),leases=new Map(),calls=[],rows=new Map(),pending=[];
let current,hook,dirty=false,seen,config={version:1,providers:true,deleted:true};
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>v===b[i]);
const React={
 createElement:(type,props,...children)=>({type,props:{...props,...(children.length?{children:children.length===1?children[0]:children}:{})}}),
 isValidElement:e=>!!(e&&e.type),memo:type=>({type}),
 createContext:value=>{const c={value};c.Provider={context:c};return c;},
 useContext:c=>contexts.has(c)?contexts.get(c):c.value,
 useRef:value=>{const i=hook++;return current.hooks[i]||(current.hooks[i]={current:value});},
 useState:value=>{const f=current,i=hook++;if(!f.hooks[i])f.hooks[i]={value};return[f.hooks[i].value,next=>{const old=f.hooks[i].value,result=typeof next==='function'?next(old):next;if(result!==old){f.hooks[i].value=result;dirty=true;}}];},
 useMemo:(f,deps)=>{const i=hook++;if(!same(current.hooks[i]?.deps,deps))current.hooks[i]={deps,value:f()};return current.hooks[i].value;},
 useEffect:(f,deps)=>{const owner=current,i=hook++,old=owner.hooks[i];if(!same(old?.deps,deps)){owner.hooks[i]={deps,cleanup:old?.cleanup};pending.push(()=>{owner.hooks[i].cleanup?.();owner.hooks[i].cleanup=f();});}}
};
function render(e,path='root'){
 if(Array.isArray(e)){e.forEach((v,i)=>render(v,path+'/'+i));return;}
 if(!e||typeof e!=='object')return;
 const {type,props}=e;
 if(type.context){const c=type.context,old=contexts.get(c),had=contexts.has(c);contexts.set(c,props.value);render(props.children,path+'/context');if(had)contexts.set(c,old);else contexts.delete(c);return;}
 const f=typeof type==='function'?type:type.type;
 if(!f)return;
 path+='/'+(props.key||'');let fiber=fibers.get(path);
 if(!fiber||fiber.type!==f){fiber={type:f,hooks:[]};fibers.set(path,fiber);}
 seen.add(path);current=fiber;hook=0;render(f(props),path+'/child');
}
let roots=[];
function pass(commit=true){seen=new Set();rows.clear();render(roots);if(commit){for(const [p,f]of fibers)if(!seen.has(p)){for(const h of f.hooks)h?.cleanup?.();fibers.delete(p);}while(pending.length)pending.shift()();}}
function settle(){for(let n=0;n<12;n++){dirty=false;pass();if(!dirty)return;}throw Error('effect loop');}
const raw=(id,extra={})=>({id,type:'chat',user:'viewer',userId:'1',body:'Kappa',...extra});
const store=line=>({getState:()=>({lines:[{line}]})});
const hostInstances=new Map();
const a=raw('a'),b=raw('b');let stores={alpha:store(a),beta:store(b)};
const action=()=>{};
const modules={react:React,native:{NativeModules:{TwitchRNFeedSettings:{getLurkChatData:raw=>{
 const q=JSON.parse(raw);calls.push(q);if(q.op==='acquire')leases.set(q.id,q);if(q.op==='release')leases.delete(q.id);
 const lease=leases.get(q.id);return JSON.stringify(q.op==='config'?config:q.op==='acquire'?{active:true}:q.op==='catalog'&&lease?{...lease,generation:1,emotes:{Kappa:'lurk7s_'+lease.channel},badges:[{title:'provider',url:'https://example.test/badge.png'},{title:'he/him',url:'data:image/png;base64,AA=='}]}:{});
 }}}},row:{ChatMessageRow:{type:p=>{rows.set(p.message.id,p);return null;}}},body:{MessageBody:()=>null},time:{TimestampPart:()=>null,formatTimestamp:()=>''},
 store:{useChatStore:()=>{},useChatSelector:()=>{}},metrics:{ChatTextMetricsContext:{},ChatFontScaleContext:{}},badges:{badgeKey:(s,v)=>s+'/'+v},stage:{useClassicStagePresentation:()=>{}},column:{useColumnTrackWidth:()=>{}},
 theatreStore:{useTheatreChatStore:p=>stores[p.channelLogin]},
 overlayHost:{OneChatOverlayHost:p=>{const identity=React.useRef({});hostInstances.set(p.channelLogin,identity.current);modules.theatreStore.useTheatreChatStore({channelLogin:p.channelLogin});return React.createElement(ChatLine,{line:p.line,...p.rowContext});}}
};
function ChatLine(p){const {type,...message}=p.line;return React.createElement(modules.row.ChatMessageRow,{message,openURL:action,onPressEmote:action,onReply:action,onOpenThread:action,...p.rowContext,...('channelID'in p?{channelID:p.channelID}:{}),...('chatStore'in p?{chatStore:p.chatStore}:{})});}
modules.classicColumn={ClassicChatColumn:()=>null};modules.chatArea={ChatArea:()=>null};
React.useLayoutEffect=React.useEffect;
const originalHost=modules.overlayHost.OneChatOverlayHost,originalStore=modules.theatreStore.useTheatreChatStore;
const global={console,__r:id=>modules[id],RN$registerCallableModule:()=>{},setTimeout:()=>1,clearTimeout:()=>{}};global.globalThis=global;
vm.runInNewContext(fs.readFileSync(process.argv[2],'utf8'),global);
const contract=JSON.stringify(Object.fromEntries(Object.keys(modules).map(k=>[k,k])));global.LurkTwitchChat.configure(contract);
function host(login,channel,line,rowContext){return React.createElement(modules.overlayHost.OneChatOverlayHost,{key:login,channelLogin:login,channelId:channel,line,rowContext});}
roots=[host('alpha','42',a),host('beta','43',b)];settle();
assert.equal(rows.get('a').message.emotes?.[0]?.id,'lurk7s_42','original floating missing props must acquire after committed store publication');
assert.equal(rows.get('b').message.emotes?.[0]?.id,'lurk7s_43','sibling host stays in own destination');
assert.equal(rows.get('a').badgeRegistry['lurk-provider-1/1'].title,'he/him','cached pronoun and provider badge reach overlay');
assert.equal(rows.get('a').chatStore,stores.alpha);assert.equal(rows.get('a').channelID,'42');
const hostIdentity=hostInstances.get('alpha');
const retiredPublishers=Array.from(fibers.values()).filter(f=>f.type===originalHost).flatMap(f=>f.hooks.map(h=>h?.cleanup).filter(Boolean));
const deleted={...a,deleted:true};stores.alpha=store(deleted);roots=[host('alpha','42',deleted)];settle();
retiredPublishers.forEach(f=>f());settle();assert.equal(rows.get('a').chatStore,stores.alpha,'retired publisher cleanup cannot clear replacement');
assert.equal(rows.get('a').message.modActionType,'delete','received canonical overlay line retains deleted body');
for(const k of ['openURL','onPressEmote','onReply','onOpenThread'])assert.equal(rows.get('a')[k],undefined);
for(const extra of [{hidden:true},{isUnpublished:true},{lowTrust:true},{automod:true},{maskedRanges:[1]}]){
 const line=raw('protected',extra);stores.alpha=store(line);roots=[host('alpha','42',line)];settle();assert.equal(rows.get('protected').message.emotes,undefined);assert.equal(leases.size,0);
 stores.alpha=store({...line,deleted:true});roots=[host('alpha','42',{...line,deleted:true})];settle();assert.equal(rows.get('protected').message.modActionType,undefined);
}
const shared=raw('shared',{sourceRoomID:'99'});stores.alpha=store(shared);roots=[host('alpha','42',shared)];settle();assert.equal(rows.get('shared').message.emotes[0].id,'lurk7s_99');
roots=[host('alpha','44',shared)];pass(false);assert.equal(rows.get('shared').message.emotes,undefined,'destination change has no precommit authority');settle();assert.equal(rows.get('shared').channelID,'44');assert.equal(hostInstances.get('alpha'),hostIdentity,'destination change preserves original host state');
for(const mismatch of [{body:'different'},{user:'other'},{userId:'2'},{sourceRoomID:'98'},{deleted:true},{hidden:true},{isUnpublished:true}]){
 roots=[host('alpha','44',{...shared,...mismatch})];settle();assert.equal(rows.get('shared').message.emotes,undefined,'wrong canonical payload cannot acquire');assert.equal(rows.get('shared').message.modActionType,undefined);
}
for(const context of [{channelID:'wrong'},{chatStore:stores.beta}]){roots=[host('alpha','44',shared,context)];settle();assert.equal(rows.get('shared').message.emotes,undefined,'explicit wrong context stays untrusted');}
roots=[];settle();assert.equal(leases.size,0,'scope disposal releases rows');
config={version:1};global.LurkTwitchChat.configure(contract);assert.equal(modules.overlayHost.OneChatOverlayHost,originalHost);assert.equal(modules.theatreStore.useTheatreChatStore,originalStore);
console.log('PASS floating context commit, siblings, destination/source identity, cached providers, received/protected/actions and restoration');
