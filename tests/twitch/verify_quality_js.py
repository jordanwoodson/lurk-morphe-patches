#!/usr/bin/env python3
"""Execute Twitch's actual decompiled RN initial-quality effect, with boundary doubles.

Usage: python3 tests/twitch/verify_quality_js.py /path/to/hermes/decompiled.js
No bundle is modified. The effect consumes the preference returned by the native storage fallback.
"""
from pathlib import Path
import re
import subprocess
import sys

source = Path(sys.argv[1]).read_text()
start = source.index('Original name: usePreferredVideoQuality,')
start = source.index('r7 = function() { // Original name: t1', start) + len('r7 = ')
end = source.index('};\n                r0 = 3;', start) + 1
effect = source[start:end]
# Hermes decompiler names each function's register file r0...; retain that lexical isolation.
declarations = 'var ' + ','.join(f'r{i}' for i in range(32)) + ';'
effect = re.sub(r'function\([^)]*\) \{', lambda m: m[0] + declarations, effect)
runner = r'''
const assert = require('node:assert/strict');
function run(preference, names, repeat=false) {
    const calls=[], writes=[];
    const _closure1_slot7={current:false};
    const _closure1_slot0={names};
    const _closure1_slot1={current:{setQuality:q=>calls.push(q)}};
    const _closure1_slot3=preference;
    const _closure1_slot4=q=>writes.push(q);
    const effect=EFFECT;
    effect();
    if (repeat) effect();
    return {calls,writes};
}
// Real names: the source rendition carries a suffix. Stock absent/null preference never chooses a
// named rendition; Lurk's Source choice leaves the row absent, so it behaves the same.
const names=['1080p60 (source)','720p60','480p'];
assert.deepEqual(run(null,names),{calls:[],writes:[]});
assert.deepEqual(run('720p60',names,true),{calls:['720p60'],writes:[]});
// Twitch accepts p30 and p as the same frame-rate rendition.
assert.deepEqual(run('720p',['1080p60 (source)','720p30']),{calls:['720p30'],writes:[]});
// A missing rendition, including a name that is only the source's without its suffix, resets the
// stored preference to Auto, once per player instance.
assert.deepEqual(run('1080p60',names,true),{calls:[],writes:[null]});
assert.deepEqual(run('720p60',[]),{calls:[],writes:[]});
console.log('PASS: original RN initial-quality effect: stock negative and Source, explicit rendition, p30 normalization, source suffix and unavailable Auto fallback, no repeat reset');
'''.replace('EFFECT', effect)
subprocess.run(['node', '-'], input=runner, text=True, check=True)
