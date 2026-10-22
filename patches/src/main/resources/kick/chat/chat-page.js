// Lurk's changes to Kick's chat page (chat.mobile.kick.com), which Kick shows in a WebView on the stream screen
// and the channel's Chat tab. KickChat evaluates `(<this function>)(options)` when the page starts and again when
// it finishes loading, with the switches that are on:
//   ignoreClears  keep the messages when a moderator clears the chat
//   hide          CSS selectors of chat lines to hide (subs and gifts, hosts, reward redemptions)
//   sevenTv       show 7TV emotes. sevenTvChannel is the channel's slug and sevenTvUser its owner's Kick user ID,
//                 each null when the app hasn't seen them; without either only global emotes show.
// The page is served by Kick and can change at any time, so every step only touches what it finds and does
// nothing otherwise.
(function (options) {
    'use strict';
    // At page start the WebView can still be showing the previous document.
    if (location.host !== 'chat.mobile.kick.com') return 'not the chat page';
    if (window.lurkChat) return 'already applied';
    window.lurkChat = options;
    const applied = [];

    function whenReady(run) {
        const go = () => {
            const app = document.getElementById('app');
            if (app) run(app);
        };
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go, {once: true});
        else go();
    }

    if (options.hide && options.hide.length) {
        whenReady(() => {
            const style = document.createElement('style');
            style.textContent = options.hide.join(',') + '{display:none!important}';
            (document.head || document.documentElement).appendChild(style);
        });
        applied.push('hide ' + options.hide.join(' '));
    }

    // The page clears chat by emptying the message list (`app.innerHTML = ""`); nothing else empties it outside
    // the KICKs gift preview, which KickChat leaves alone.
    if (options.ignoreClears) {
        whenReady((app) => {
            const html = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
            if (!html || !html.set) return;
            Object.defineProperty(app, 'innerHTML', {
                configurable: true,
                get() {
                    return html.get.call(this);
                },
                set(value) {
                    if (value !== '') html.set.call(this, value);
                },
            });
        });
        applied.push('ignore clears');
    }

    if (options.sevenTv) {
        sevenTv(options.sevenTvChannel, options.sevenTvUser);
        applied.push('7tv ' + (options.sevenTvUser || options.sevenTvChannel || 'global only'));
    }

    // 7TV: a word of a message that is the name of one of the channel's 7TV emotes, or of a global one, becomes
    // that emote's image. Messages already on the page are rewritten once the sets load, later ones as they come.
    function sevenTv(slug, user) {
        const API = 'https://7tv.io/v3/';
        const idOK = (id) => typeof id === 'string' && /^(?:[a-f0-9]{24}|[0-9A-HJKMNP-TV-Z]{26})$/i.test(id);
        const sets = {channel: null, global: null};
        const timers = new Set(), controllers = new Set(), flights = new Map();
        let stopped = false, observer, socket, reconnect = 0, heartbeat, lastSequence;
        let byName = new Map(), app;
        const originals = new WeakMap(), failedImages = new Set();
        const later = (fn, ms) => {
            const timer = setTimeout(() => { timers.delete(timer); if (!stopped) fn(); }, ms);
            timers.add(timer); return timer;
        };
        const cancel = (timer) => { clearTimeout(timer); timers.delete(timer); };
        const get = (url) => {
            if (flights.has(url)) return flights.get(url);
            const task = (async () => {
                for (let attempt = 0; attempt < 3 && !stopped && !document.hidden; attempt++) {
                    const controller = new AbortController(); controllers.add(controller);
                    let deadline, retry = 500 * Math.pow(2, attempt);
                    try {
                        const response = await Promise.race([
                            fetch(url, {credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal}),
                            new Promise((_, reject) => { deadline = later(() => {
                                controller.abort(); reject(new Error('deadline'));
                            }, 10000); }),
                        ]);
                        if (response.status === 404) return null;
                        if (!response.ok) {
                            if (response.status !== 429 && response.status < 500) return null;
                            const after = Number(response.headers.get('Retry-After'));
                            if (Number.isFinite(after) && after > 0) retry = Math.min(60000, after * 1000);
                            throw new Error('transient');
                        }
                        // Keep the deadline through body consumption, including trickling responses.
                        const body = await Promise.race([
                            (async () => {
                                const reader = response.body.getReader();
                                const chunks = []; let length = 0;
                                while (true) {
                                    const part = await reader.read(); if (part.done) break;
                                    length += part.value.length;
                                    if (length > 2097152) { await reader.cancel(); throw new Error('body too large'); }
                                    chunks.push(part.value);
                                }
                                const data = new Uint8Array(length); let offset = 0;
                                for (const part of chunks) { data.set(part, offset); offset += part.length; }
                                return JSON.parse(new TextDecoder().decode(data));
                            })(),
                            new Promise((_, reject) => controller.signal.addEventListener('abort', () => reject(new Error('deadline')), {once:true})),
                        ]);
                        return body;
                    } catch (_) {
                        if (stopped) return null;
                    } finally {
                        cancel(deadline); controllers.delete(controller);
                    }
                    if (attempt < 2) await new Promise((resolve) => later(resolve, retry));
                }
                return null;
            })().finally(() => flights.delete(url));
            flights.set(url, task); return task;
        };
        function catalog(set) {
            const result = [];
            for (const e of (set && Array.isArray(set.emotes) ? set.emotes : []).slice(0, 2000)) {
                if (!e || !idOK(e.id) || typeof e.name !== 'string' || !/^[^\s:<>&"'\\]{1,100}$/u.test(e.name)) continue;
                result.push({id: e.id, name: e.name, flags: ((e.flags & 1) || (e.data && (e.data.flags & 256))) ? 1 : 0,
                    url: 'https://cdn.7tv.app/emote/' + e.id + (options.nonAnimatedEmotes && e.data && e.data.animated === true ? '/2x_static.webp' : '/2x.webp'),
                    animated: !!(e.data && e.data.animated === true), width: 64, height: 64});
            }
            return result;
        }
        function publish() {
            if (stopped) return;
            byName = new Map(); failedImages.clear();
            for (const key of ['channel', 'global']) for (const e of catalog(sets[key])) if (!byName.has(e.name)) byName.set(e.name, e);
            window.lurkChat.sevenTvCount = ['channel', 'global'].map((key) => sets[key] && Array.isArray(sets[key].emotes) ? sets[key].emotes.length : 0);
            if (app) scan(app, true);
            if (options.generation && window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify({
                type: 'lurk7tvCatalog', generation: options.generation, channelId: options.channelId || null,
                slug: slug || null, setIds: currentIds(), emotes: Array.from(byName.values()).slice(0, 2000),
            }));
        }
        function currentIds() { return Array.from(new Set(['channel', 'global'].map((k) => sets[k] && sets[k].id).filter(idOK))); }
        function normalizedSet(set) {
            if (!set || typeof set !== 'object' || Array.isArray(set)) return null;
            if (Array.isArray(set.emotes)) return set;
            // The provider omits an empty emote vector. Require a valid set identity
            // before treating an omitted vector as authoritative rather than malformed.
            if (!Object.prototype.hasOwnProperty.call(set, 'emotes') && idOK(set.id)) return {...set, emotes:[]};
            return null;
        }
        async function loadGlobal() {
            const set = normalizedSet(await get(API + 'emote-sets/global'));
            if (!stopped && set) { sets.global = set; publish(); connect(); }
        }
        async function loadChannel() {
            const owner = user || (slug && await get('https://kick.com/api/v2/channels/' + encodeURIComponent(slug)) || {}).user_id;
            if (!owner || stopped) return;
            const result = await get(API + 'users/kick/' + encodeURIComponent(owner));
            if (stopped || !result || typeof result !== 'object' || Array.isArray(result)) return;
            const set = normalizedSet(result.emote_set);
            const noSet = result.emote_set === null ||
                (!Object.prototype.hasOwnProperty.call(result, 'emote_set') && idOK(result.id));
            if (set || noSet) {
                sets.channel = set; publish(); connect();
            }
        }
        const refreshes = new Map();
        function refetch(id) {
            if (!currentIds().includes(id) || refreshes.has(id)) return;
            refreshes.set(id, later(async () => {
                refreshes.delete(id);
                const set = normalizedSet(await get(API + 'emote-sets/' + id));
                if (stopped || !set || set.id !== id) return;
                for (const key of ['channel', 'global']) if (sets[key] && sets[key].id === id) sets[key] = set;
                publish();
            }, 250));
        }
        let subscribed = '';
        function connect() {
            if (stopped || document.hidden) return;
            const ids = currentIds(), signature = ids.join(',');
            if (socket && subscribed === signature) return;
            if (socket) { socket.onclose = null; socket.close(); }
            socket = null; cancel(heartbeat); lastSequence = null;
            subscribed = signature;
            if (!ids.length) return;
            const ws = socket = new WebSocket('wss://events.7tv.io/v3');
            let interval = 30000;
            const watchdog = () => { cancel(heartbeat); heartbeat = later(() => ws.close(), interval * 2 + 1000); };
            ws.onopen = () => {
                if (socket !== ws || stopped) return;
                for (const id of ids) ws.send(JSON.stringify({op: 35, d: {type: 'emote_set.update', condition: {object_id: id}}}));
                watchdog();
            };
            ws.onmessage = (event) => {
                if (socket !== ws || stopped || typeof event.data !== 'string' || event.data.length > 262144) return;
                try {
                    const message = JSON.parse(event.data);
                    if (message.op === 1) { interval = Math.max(1000, Math.min(60000, Number(message.d.heartbeat_interval) || 30000)); reconnect = 0; watchdog(); }
                    if (message.op === 2) watchdog();
                    if (message.op === 4 || message.op === 6) { ws.close(); return; }
                    if (message.op !== 0) return;
                    if (Number.isSafeInteger(message.s)) {
                        if (lastSequence != null && message.s !== lastSequence + 1) ids.forEach(refetch);
                        lastSequence = message.s;
                    }
                    if (message.d && message.d.type === 'emote_set.update' && message.d.body) refetch(message.d.body.id);
                } catch (_) { /* Ignore malformed dispatches. */ }
            };
            ws.onclose = () => {
                if (socket !== ws || stopped) return;
                socket = null; lastSequence = null; cancel(heartbeat);
                if (document.hidden || reconnect >= 8) return;
                later(() => { currentIds().forEach(refetch); connect(); }, Math.min(60000, 1000 * Math.pow(2, Math.min(reconnect++, 6))) * (0.8 + Math.random() * 0.4));
            };
        }
        function revalidate() {
            if (!document.hidden) { loadGlobal(); loadChannel(); }
            later(revalidate, 300000);
        }
        function restore(content) {
            for (const wrapper of content.querySelectorAll('[data-lurk-layer]')) wrapper.replaceWith(...wrapper.childNodes);
            for (const generated of content.querySelectorAll('[data-lurk-emote]')) {
                const text = originals.get(generated);
                if (text != null) generated.replaceWith(document.createTextNode(text));
            }
            content.normalize();
        }
        function rewrite(content, refresh) {
            if (refresh) restore(content);
            for (const node of Array.from(content.childNodes)) {
                if (node.nodeType !== Node.TEXT_NODE) continue;
                const words = node.data.split(/(\s+)/);
                if (!words.some((word) => byName.has(word))) continue;
                const parts = document.createDocumentFragment(); let text = '';
                for (const word of words) {
                    const emote = byName.get(word);
                    if (!emote || failedImages.has(emote.url)) { text += word; continue; }
                    if (text) parts.appendChild(document.createTextNode(text)); text = '';
                    const image = document.createElement('img');
                    image.src = emote.url; image.alt = word; image.title = word;
                    image.decoding = 'async'; image.loading = 'lazy'; image.referrerPolicy = 'no-referrer';
                    image.classList.add('emote'); image.dataset.lurkEmote = String(emote.flags);
                    originals.set(image, word);
                    image.onerror = () => {
                        if (!image.isConnected || stopped) return;
                        failedImages.add(emote.url); scan(content, true);
                    };
                    parts.appendChild(image);
                }
                if (text) parts.appendChild(document.createTextNode(text));
                node.replaceWith(parts);
            }
            let base = null, whitespace = [];
            for (const node of Array.from(content.childNodes)) {
                if (node.nodeType === Node.TEXT_NODE && /^[ \t]*$/.test(node.data)) { whitespace.push(node); continue; }
                if (node.nodeType === Node.ELEMENT_NODE && node.matches('img.emote')) {
                    if (node.dataset.lurkEmote === '1' && base) {
                        let wrapper = base;
                        if (!base.hasAttribute('data-lurk-layer')) {
                            wrapper = document.createElement('span'); wrapper.dataset.lurkLayer = '1';
                            wrapper.style.cssText = 'display:inline-block;position:relative;vertical-align:middle';
                            base.replaceWith(wrapper); wrapper.appendChild(base); base = wrapper;
                        }
                        // Keep whitespace as owned nodes so refresh restores the original message exactly.
                        for (const space of whitespace) { const saved = document.createElement('span'); saved.dataset.lurkEmote = 'space'; originals.set(saved, space.data); space.replaceWith(saved); wrapper.appendChild(saved); }
                        node.style.cssText = 'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%)';
                        wrapper.appendChild(node);
                    } else base = node;
                } else if (node.nodeType === Node.ELEMENT_NODE && node.hasAttribute('data-lurk-layer')) base = node;
                else base = null;
                whitespace = [];
            }
        }
        function scan(root, refresh) {
            if (root.matches && root.matches('.message-content')) rewrite(root, refresh);
            if (root.querySelectorAll) root.querySelectorAll('.message-content').forEach((node) => rewrite(node, refresh));
            if (observer) observer.takeRecords();
        }
        whenReady((root) => {
            if (stopped) return; app = root;
            observer = new MutationObserver((records) => {
                const changed = new Set();
                for (const record of records) {
                    const content = record.target.nodeType === 1 ? record.target.closest('.message-content') : record.target.parentElement && record.target.parentElement.closest('.message-content');
                    if (content) changed.add(content);
                    for (const node of record.addedNodes) if (node.nodeType === 1) changed.add(node);
                }
                changed.forEach((node) => scan(node, false));
            });
            observer.observe(app, {childList:true, subtree:true, characterData:true}); scan(app, false);
        });
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) { controllers.forEach((c) => c.abort()); if (socket) socket.close(); }
            else { loadGlobal(); loadChannel(); currentIds().forEach(refetch); connect(); }
        });
        window.addEventListener('pagehide', () => {
            if (options.generation && window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify({
                type:'lurk7tvDispose', generation:options.generation, channelId:options.channelId || null, slug:slug || null,
            }));
            stopped = true; controllers.forEach((c) => c.abort()); timers.forEach(clearTimeout); timers.clear();
            if (socket) socket.close(); if (observer) observer.disconnect();
        }, {once:true});
        revalidate();
    }

    return applied.join(', ') || 'nothing';
})
