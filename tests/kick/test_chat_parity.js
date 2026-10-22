'use strict';
// The page side of tests/kick/test_chat_parity.py: the times Kick's chat page shows with Chat timestamps and its
// clock, and Twitch's React Native chat's 12-hour clock, for the same moments.
// Usage: node test_chat_parity.js <kick chat-page.js> <twitch runtime chat.js> <moments.json> <out.json>
// moments.json lists [hour, minute] pairs. out.json gets {kick: {<locale>: {"0": [...], "1": [...]}}, twitchRn: {...}}.
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const [pageScript, twitchChat, momentsFile, outFile] = process.argv.slice(2);
const moments = JSON.parse(fs.readFileSync(momentsFile, 'utf8')).map(([h, m]) => new Date(2026, 0, 15, h, m));

// Kick's page writes each line's time with toLocaleTimeString(locale, {hour: '2-digit', minute: '2-digit'}) in its
// config's locale (tests/kick/chat-page-contract.json pins that call on the live page). Lurk's page script then
// rewrites it; this runs the real script over a page holding one line per moment.
function kickPage(locale, clock) {
    class Node {
        constructor(className, text) { this.className = className; this.textContent = text; this.dataset = {}; this.nodeType = 1; }
        matches(selector) { return selector === '.' + this.className; }
        querySelectorAll() { return []; }
    }
    const stamps = moments.map((date) => new Node('timestamp', date.toLocaleTimeString(locale, {hour: '2-digit', minute: '2-digit'})));
    const app = {querySelectorAll: (selector) => selector === '.timestamp' ? stamps : []};
    const sink = {appendChild() {}};
    const context = {
        location: {host: 'chat.mobile.kick.com',
            href: 'https://chat.mobile.kick.com/?config=' + encodeURIComponent(JSON.stringify({channelId: 1, locale}))},
        document: {readyState: 'complete', getElementById: (id) => id === 'app' ? app : null,
            createElement: () => ({}), head: sink, documentElement: sink, addEventListener() {}},
        MutationObserver: class { observe() {} disconnect() {} },
        addEventListener() {}, URL, Date, JSON, Math, Map, Number, Array, Object, String, setTimeout, clearTimeout,
    };
    context.window = context;
    const apply = vm.runInNewContext(fs.readFileSync(pageScript, 'utf8'), context);
    const report = apply({timestamps: true, clock});
    assert.match(report, clock === 1 ? /12-hour clock/ : /24-hour clock/, 'the page script reports its clock');
    return stamps.map((stamp) => stamp.textContent);
}

// Twitch's React Native chat formats a 12-hour time itself (chat.js clock()); its 24-hour time is Twitch's own
// formatTimestamp, which Lurk leaves alone. The module doubles are the ones tests/twitch/test_chat.js uses.
function twitchRn(clock) {
    const element = (type, props) => ({type, props});
    const React = {createElement: element, cloneElement: (e, p) => ({...e, props: {...e.props, ...p}}), isValidElement: (e) => !!(e && e.props),
        useState: (v) => [v, () => {}], useRef: () => ({current: 0}), useMemo: (f) => f(), useEffect: () => {}, useLayoutEffect: () => {},
        useContext: (c) => c.value, createContext: (value) => ({value}), memo: (f) => ({type: f})};
    const config = {version: 1, timestamps: true, clock};
    const stock = (ts) => 'twitch-' + ts;
    const modules = {
        react: React,
        native: {NativeModules: {TwitchRNFeedSettings: {getLurkChatData: (raw) =>
            JSON.stringify(JSON.parse(raw).op === 'config' ? config : {})}}},
        row: {ChatMessageRow: () => null}, body: {MessageBody: () => null},
        time: {formatTimestamp: stock, TimestampPart: () => null},
        store: {useChatStore: () => null, useChatSelector: () => null}, metrics: {}, badges: {badgeKey: () => ''},
        stage: {useClassicStagePresentation: () => ({})}, column: {useColumnTrackWidth: () => 0},
        overlayHost: {OneChatOverlayHost: () => null}, theatreStore: {useTheatreChatStore: () => null},
        classicColumn: {ClassicChatColumn: () => null}, chatArea: {ChatArea: () => null},
    };
    const global = {console: {log() {}}, Date, Map, Set, WeakMap, WeakSet, setTimeout, clearTimeout,
        __r: (id) => modules[id], RN$registerCallableModule: () => {}};
    global.globalThis = global;
    vm.runInNewContext(fs.readFileSync(twitchChat, 'utf8'), global);
    global.LurkTwitchChat.configure(JSON.stringify(Object.fromEntries(Object.keys(modules).map((k) => [k, k]))));
    return moments.map((date) => modules.time.formatTimestamp(date.valueOf(), {}));
}

const kick = {};
for (const locale of ['en', 'en-GB', 'de', 'fr', 'ja', 'ar']) kick[locale] = {0: kickPage(locale, 0), 1: kickPage(locale, 1)};
const rn = {0: twitchRn(0), 1: twitchRn(1)};
assert.deepEqual(rn[0], moments.map((date) => 'twitch-' + date.valueOf()), "Twitch's 24-hour clock is its own formatter");
fs.writeFileSync(outFile, JSON.stringify({kick, twitchRn: {1: rn[1]}}));
console.log('page side: Kick chat page times for ' + Object.keys(kick).length + ' locales, Twitch React Native 12-hour times');
