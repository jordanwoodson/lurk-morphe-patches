'use strict';

// Controlled hook/context and render/commit boundaries for the RN consumer fixtures.
// This is the subset used by these tests, not a replacement for target React checks.
function createReactHarness({getRoots,beforePass=()=>{},onNativeElement=()=>{}}) {
 const fibers=new Map(),contexts=new Map(),pending=[];
 let current,hook,dirty=false,seen;
 const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>v===b[i]);
 const React={
  Fragment:Symbol('Fragment'),
  createElement:(type,props,...children)=>({type,props:{...props,...(children.length?{children:children.length===1?children[0]:children}:{})}}),
  cloneElement:(e,p)=>({...e,props:{...e.props,...p}}),
  isValidElement:e=>!!(e&&e.type),memo:type=>({type}),
  createContext:value=>{const c={value};c.Provider={context:c};return c;},
  useContext:c=>contexts.has(c)?contexts.get(c):c.value,
  useRef:value=>{const i=hook++;return current.hooks[i]||(current.hooks[i]={current:value});},
  useState:value=>{
   const f=current,i=hook++;
   if(!f.hooks[i])f.hooks[i]={value};
   return[f.hooks[i].value,next=>{
    const old=f.hooks[i].value,result=typeof next==='function'?next(old):next;
    if(result!==old){f.hooks[i].value=result;dirty=true;}
   }];
  },
  useMemo:(f,deps)=>{
   const i=hook++;
   if(!same(current.hooks[i]?.deps,deps))current.hooks[i]={deps,value:f()};
   return current.hooks[i].value;
  },
  useEffect:(f,deps)=>{
   const owner=current,i=hook++,old=owner.hooks[i];
   if(!same(old?.deps,deps)){
    owner.hooks[i]={deps,cleanup:old?.cleanup};
    pending.push(()=>{owner.hooks[i].cleanup?.();owner.hooks[i].cleanup=f();});
   }
  }
 };
 // Preserve the fixtures' explicit commit queue and parent-before-child effect order.
 React.useLayoutEffect=React.useEffect;
 function render(e,path='root') {
  if(Array.isArray(e)){e.forEach((v,i)=>render(v,path+'/'+i));return;}
  if(!e||typeof e!=='object')return;
  const {type,props}=e;
  if(type.context){
   const c=type.context,old=contexts.get(c),had=contexts.has(c);
   contexts.set(c,props.value);render(props.children,path+'/context');
   if(had)contexts.set(c,old);else contexts.delete(c);
   return;
  }
  const f=typeof type==='function'?type:type.type;
  if(!f){onNativeElement(e);render(props.children,path+'/native');return;}
  path+='/'+(props.key||'');let fiber=fibers.get(path);
  if(!fiber||fiber.type!==f){fiber={type:f,hooks:[]};fibers.set(path,fiber);}
  seen.add(path);current=fiber;hook=0;render(f(props),path+'/child');
 }
 function pass(commit=true) {
  seen=new Set();beforePass();render(getRoots());
  if(commit){
   for(const [p,f]of fibers)if(!seen.has(p)){
    for(const h of f.hooks)h?.cleanup?.();
    fibers.delete(p);
   }
   while(pending.length)pending.shift()();
  }
 }
 function settle() {
  for(let n=0;n<12;n++){dirty=false;pass();if(!dirty)return;}
  throw Error('effect loop');
 }
 return {React,fibers,pass,settle};
}

module.exports={createReactHarness};
