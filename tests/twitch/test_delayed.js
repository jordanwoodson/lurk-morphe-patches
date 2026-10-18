'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const sandbox = {};
vm.runInNewContext(source, sandbox);
const make = sandbox[1998063108];
const message = {type:'chat',id:'a',user:'Alice',body:'received'};
for (const kind of ['delete','timeout','ban']) {
    const event = {kind,targetMsgId:'a',userLogin:'alice'};
    const predicate = make(event);
    const checkStock = () => {
        assert.equal(predicate(message),true,kind+' matching original predicate');
        assert.equal(predicate({...message,id:'b',user:'Bob'}),false,kind+' unrelated original predicate');
        assert.equal(predicate({...message,type:'automod'}),false,kind+' protected original type');
    };
    checkStock();
    for (const hook of [null,{},()=>undefined,()=>({}),()=>{throw new Error('bootstrap');}]) {
        if (typeof hook === 'function') hook[1998063109]=1998063110;
        sandbox[1998063107]=hook; checkStock();
    }
    Object.defineProperty(sandbox,'1998063107',{configurable:true,get(){throw new Error('getter');}});
    checkStock(); delete sandbox[1998063107];
    sandbox[1998063107]=()=>false;
    checkStock(); // A colliding global callable without our marker has no authority.
    sandbox[1998063107][1998063109]=1998063110;
    assert.equal(predicate(message),false,'valid retention result');
    delete sandbox[1998063107];
}
console.log('PASS delayed predicate missing/throwing getter/function/invalid fallback and valid delegation');
