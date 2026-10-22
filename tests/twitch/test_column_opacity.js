'use strict';
// Original column surfaces and state across controlled React render/commit boundaries.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const rendered=new Map(),instances=new Map(),areas=new Map();
const {createReactHarness}=require('./react_harness');
let roots=[],config={version:1,appearance:true,opacity:50,width:25};
const {React,settle}=createReactHarness({
 getRoots:()=>roots,
 onNativeElement:({props})=>{if(props.testID==='theatre-chat')rendered.set(props.id,props);}
});
const e=React.createElement,flat=s=>Object.assign({},...[s].flat(10).filter(Boolean));
const callback=()=>{},fallback=()=>null;let malformed=false;
const modules={react:React,native:{View:'View',Animated:{View:'Animated'},StyleSheet:{flatten:flat},NativeModules:{TwitchRNFeedSettings:{getLurkChatData:()=>JSON.stringify(config)}}},
 row:{ChatMessageRow:{type:()=>null}},body:{MessageBody:()=>null},time:{TimestampPart:()=>null,formatTimestamp:()=>''},store:{useChatStore:()=>null,useChatSelector:()=>null},
 metrics:{ChatTextMetricsContext:{},ChatFontScaleContext:{}},badges:{badgeKey:()=>''},column:{useColumnTrackWidth:()=>''},overlayHost:{OneChatOverlayHost:()=>null},theatreStore:{useTheatreChatStore:()=>null},
 chatArea:{ChatArea:p=>{areas.set(p.id,p);return null;}},
 classicColumn:{ClassicChatColumn:p=>{const instance=React.useRef({}),[draft,setDraft]=React.useState('unsent');instances.set(p.id,{instance:instance.current,draft,setDraft});
  return e('View',{testID:'theatre-chat',id:p.id,style:{flex:1,backgroundColor:'#181818'},children:e('Boundary',{name:'RightColumn',level:'error',renderFallback:fallback,resetKeys:p.id,children:e('Latency',{name:'TheaterChatArea',reportOnMount:true,children:e(malformed?'Other':modules.chatArea.ChatArea,{id:p.id,style:{flex:1,backgroundColor:'#181818'},composerBarFill:'#181818',onMessageSent:callback})})})});}},
 stage:{useClassicStagePresentation:p=>({rootStyle:{backgroundColor:'#181818'},beforePlayerRegion:e(React.Fragment,{children:[e('View',{pointerEvents:'none',accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants',children:[e('Animated',{style:{width:'75%',backgroundColor:'#000000'}}),e('Animated',{style:{width:'25%',backgroundColor:'#181818'}})]}),null]}),
  afterPlayerRegion:e(React.Fragment,{children:[e('Animated',{style:{flexDirection:'column',overflow:'hidden',width:'25%',backgroundColor:'#181818'},children:[e('View',{children:'stock header'}),e('View',{style:{flex:1,minHeight:0,overflow:'hidden'},children:p.chat})]}),null,null,null,null]})})}}
const original=modules.classicColumn.ClassicChatColumn;
const g={console,__r:id=>modules[id],RN$registerCallableModule:()=>{},setTimeout:()=>1,clearTimeout:()=>{}};g.globalThis=g;
vm.runInNewContext(fs.readFileSync(process.argv[2],'utf8'),g);
const contract=JSON.stringify(Object.fromEntries(Object.keys(modules).map(k=>[k,k])));g.LurkTwitchChat.configure(contract);
function Stage(p){return modules.stage.useClassicStagePresentation({...p,chat:e(modules.classicColumn.ClassicChatColumn,{id:p.id,layoutMode:'landscape-column-chat'})}).afterPlayerRegion;}
function draw(extra={}){roots=[e(Stage,{id:'active',mode:'landscape-column-chat',...extra}),e(Stage,{id:'sibling',mode:'portrait'})];settle();}
draw();
assert.equal(flat(rendered.get('active').style).backgroundColor,'transparent','actual nested column covering fill cleared');
assert.equal(flat(areas.get('active').style).backgroundColor,'transparent','actual ChatArea covering fill cleared');
assert.equal(areas.get('active').composerBarFill,'#181818');assert.equal(areas.get('active').onMessageSent,callback);
assert.equal(flat(rendered.get('sibling').style).backgroundColor,'#181818','sibling tree cannot borrow opacity authority');
const instance=instances.get('active').instance;instances.get('active').setDraft('local state');
for(const [opacity,extra]of [[75,{}],[0,{}],[50,{pipFloatSettled:true}],[50,{pipCompactFrame:true}],[50,{isTheatreDocked:true}],[50,{}]]){
 config={...config,opacity};g.LurkTwitchChat.invalidate();draw(extra);assert.equal(instances.get('active').instance,instance,'opacity toggles preserve child instance');assert.equal(instances.get('active').draft,'local state');
 const enabled=opacity!==0&&!Object.values(extra).some(Boolean);
 assert.equal(flat(rendered.get('active').style).backgroundColor,enabled?'transparent':'#181818');
 assert.equal(flat(areas.get('active').style).backgroundColor,enabled?'transparent':'#181818');
}
config={...config,appearance:false};g.LurkTwitchChat.invalidate();draw();assert.equal(instances.get('active').instance,instance);assert.equal(flat(rendered.get('active').style).backgroundColor,'#181818');
config={...config,appearance:true};g.LurkTwitchChat.invalidate();malformed=true;draw();assert.equal(flat(rendered.get('active').style).backgroundColor,'#181818','wrong owner stays stock');malformed=false;
config={version:1};g.LurkTwitchChat.invalidate();g.LurkTwitchChat.configure(contract);assert.equal(modules.classicColumn.ClassicChatColumn,original,'full off restores exact original export');
console.log('Column opacity: exact original surfaces, sibling isolation, neutral/off guards and child state PASS');
