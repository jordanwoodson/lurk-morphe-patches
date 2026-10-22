// Loaded as a separate, compiled HBC99 bundle after Kick's main Metro bundle.
(function (global) {
    'use strict';
    if (global.LurkKickChat) return;
    let store, session, host, commands, files, installed = false, enabled = false, generation, channelId;
    let current = [], names = new Map(), images = new Map(), merging = false;
    let inputOwner, inputInstance, retiredGeneration;
    const seen = new Set();
    const owned = (entry) => entry && typeof entry.id === 'string' && entry.id.indexOf('lurk7tv_') === 0;
    function retire() {
        if (generation) retiredGeneration = generation;
        enabled = false; generation = undefined; current = [];
        inputOwner = inputInstance = undefined;
    }
    function ownsInput(state) {
        if (!enabled || !generation) return false;
        const ref = state.inputRef && state.inputRef.current;
        if (state.inputRef !== inputOwner || (inputInstance && ref !== inputInstance)) {
            retire(); return false;
        }
        // React may attach the first input after the page association. The ref
        // container must still be the one that association authorized.
        if (!inputInstance && ref) inputInstance = ref;
        return true;
    }
    function merge() {
        if (!installed || merging) return;
        merging = true;
        try {
            const state = store.getState();
            ownsInput(state);
            const native = (state.emotes || []).filter((e) => !owned(e));
            const nativeNames = new Set(native.map((e) => e.name));
            const additions = enabled ? current.filter((e) => !nativeNames.has(e.name)) : [];
            names = new Map(); images = new Map();
            const entries = additions.map((e) => {
                names.set(e.name, e); images.set('lurk7tv_' + e.id, e); seen.add(e.name);
                return {id:'lurk7tv_' + e.id, name:e.name, locked:false};
            });
            if (seen.size > 4000) { seen.clear(); additions.forEach((e) => seen.add(e.name)); }
            const sections = (state.emotesSections || []).filter((section) => section.id !== 'lurk7tv');
            // Stock picker tabs need an icon or avatar to expose their section.
            if (entries.length) sections.push({id:'lurk7tv',name:'7TV',icon:'global',emotes:entries});
            store.setState({emotesSections:sections,emotes:native.concat(entries),
                allowedEmotes:(state.allowedEmotes || []).filter((e) => !owned(e) && nativeNames.has(e.name)).concat(entries)});
        } finally { merging = false; }
    }
    function insert(name, autocomplete, receiver, args, original) {
        if (enabled && !ownsInput(store.getState())) merge();
        const entry = names.get(name);
        if (!entry) {
            if (seen.has(name) && !store.getState().emotes.some((e) => !owned(e) && e.name === name)) return;
            return original.apply(receiver, args);
        }
        const state = store.getState(), ref = state.inputRef && state.inputRef.current;
        if (!enabled || !generation || !ref || session.getState().status !== 'authenticated') return;
        let props;
        try {
            const handle = host.getInternalInstanceHandleFromPublicInstance(ref.getNativeRef());
            props = handle.stateNode.canonical.currentProps;
        } catch (_) { return; }
        if (!props || props.editable !== true || !Number.isSafeInteger(props.mostRecentEventCount) ||
                props.mostRecentEventCount < 0 ||
                typeof state.setInputContent !== 'function' || typeof state.setSelection !== 'function') return;
        const text = state.inputContent;
        if (typeof text !== 'string' || !state.selection) return;
        // JS/RN offsets are UTF-16. Never cut a surrogate pair if selection arrives between its halves.
        function position(value) {
            let p = Math.max(0, Math.min(text.length, Number.isFinite(value) ? Math.trunc(value) : text.length));
            if (p > 0 && p < text.length && /[\uD800-\uDBFF]/.test(text[p-1]) && /[\uDC00-\uDFFF]/.test(text[p])) p--;
            return p;
        }
        let start = position(state.selection.start), end = Math.max(start, position(state.selection.end));
        if (autocomplete) {
            const token = /(?:^|\s):[^\s:]*$/.exec(text.slice(0,start));
            if (!token) return;
            start = text.lastIndexOf(':',start-1);
            const remainder = /^[^\s:]*:?/.exec(text.slice(end))[0];
            end += remainder.length;
        }
        const prefix = text.slice(0,start), suffix = text.slice(end);
        const word = (prefix && !/\s$/.test(prefix) ? ' ' : '') + name;
        const trailing = suffix && /^\s/.test(suffix) ? '' : ' ';
        const result = prefix + word + trailing + suffix;
        if (Number.isFinite(props.maxLength) && result.length > props.maxLength) return;
        let cursor = prefix.length + word.length + trailing.length;
        if (!trailing && suffix[0] === ' ') cursor++;
        // Match the stock setter/selection/focus path; never invoke send or mutate reply/commands directly.
        // RN's span replacement preserves distance from the native end. Set text and cursor
        // together first, so its subsequent same-length span commit preserves this selection.
        // No delayed correction can overwrite a later manual selection or keyboard dismissal.
        commands.setTextAndSelection(ref.getNativeRef(), props.mostRecentEventCount, result, cursor, cursor);
        state.setInputContent(result);
        state.setSelection({start:cursor,end:cursor});
        if (typeof ref.focus === 'function') ref.focus();
    }
    const api = {
        configure(value) {
            const config = JSON.parse(value);
            if (installed) return;
            store = global.__r(config.store).chatRoomStore;
            const cache = global.__r(config.images).ImageCacheV2;
            session = global.__r(config.session).useSessionStore;
            host = global.__r(config.host);
            const input = global.__r(config.input);
            commands = input && input.Commands;
            files = config.files;
            const state = store && store.getState();
            const descriptor = cache && Object.getOwnPropertyDescriptor(cache,'getImage');
            if (!state || typeof store.subscribe !== 'function' || typeof store.setState !== 'function' ||
                !descriptor || !descriptor.writable || typeof cache.getImage !== 'function' ||
                !session || typeof session.getState !== 'function' ||
                typeof host.getInternalInstanceHandleFromPublicInstance !== 'function' ||
                !commands || typeof commands.setTextAndSelection !== 'function' ||
                !input.__INTERNAL_VIEW_CONFIG || input.__INTERNAL_VIEW_CONFIG.uiViewClassName !== 'AndroidTextInput' ||
                typeof state.insertEmote !== 'function' || typeof state.autofillEmote !== 'function' ||
                typeof state.setEmotes !== 'function' || files !== 'https://files.kick.com/') throw new Error('Lurk Kick contract mismatch');
            const originalImage = cache.getImage;
            cache.getImage = function(url) {
                const prefix = [files + 'emotes/', 'https://files.cdn.kick.com/emotes/']
                    .find((value) => typeof url === 'string' && url.indexOf(value) === 0);
                if (prefix) {
                    const match = /^(lurk7tv_[A-Za-z0-9]+)\/fullsize(?:\?width=\d+,format=(?:auto|webp|png))?$/.exec(url.slice(prefix.length));
                    if (match && images.has(match[1])) {
                        const still = /format=png$/.test(url);
                        const entry = images.get(match[1]); url = entry.url;
                        if (still && entry.animated === true) url = url.replace(/\/2x\.webp$/, '/2x_static.webp');
                    }
                }
                return originalImage.call(this,url);
            };
            const wrap = (original, auto) => function(name) { return insert(name,auto,this,arguments,original); };
            const originalSet = state.setEmotes;
            installed = true;
            store.setState({insertEmote:wrap(state.insertEmote,false),autofillEmote:wrap(state.autofillEmote,true),
                setEmotes:function() {
                    merging = true;
                    try { return originalSet.apply(this,arguments); }
                    finally { merging = false; merge(); }
                }});
            store.subscribe((next, previous) => {
                if (enabled && !ownsInput(next)) { merge(); return; }
                if (!merging && (next.emotes !== previous.emotes || next.emotesSections !== previous.emotesSections || next.allowedEmotes !== previous.allowedEmotes)) merge();
            });
            if (typeof state.updateRecents === 'function') {
                const originalRecents = state.updateRecents;
                store.setState({updateRecents:function(name) {
                    if (names.has(name) || (typeof name === 'string' && name.indexOf('lurk7tv_') === 0)) return;
                    return originalRecents.apply(this,arguments);
                }});
            }
            console.log('Lurk Kick: composer/image contracts installed');
        },
        channel(value) {
            const next = JSON.parse(value);
            if (next.enabled !== true) { retire(); merge(); return; }
            if (!next.generation || next.generation === retiredGeneration) return;
            if (enabled && generation === next.generation && channelId === next.channelId) return;
            enabled = true;
            generation = next.generation; channelId = next.channelId;
            inputOwner = store.getState().inputRef;
            inputInstance = inputOwner && inputOwner.current;
            current = []; merge();
        },
        catalog(value) {
            if (!installed || !enabled) return;
            if (!ownsInput(store.getState())) { merge(); return; }
            const data = JSON.parse(value);
            if (data.generation !== generation || data.channelId !== channelId || !Array.isArray(data.emotes) || data.emotes.length > 2000) return;
            current = data.emotes.filter((e) => e && typeof e.name === 'string' && /^[^\s:<>&"'\\]{1,100}$/.test(e.name) &&
                typeof e.id === 'string' && /^(?:[a-f0-9]{24}|[0-9A-HJKMNP-TV-Z]{26})$/i.test(e.id) &&
                e.url === 'https://cdn.7tv.app/emote/' + e.id + (/\/2x_static\.webp$/.test(e.url) ? '/2x_static.webp' : '/2x.webp'));
            merge();
        },
    };
    global.LurkKickChat = api;
    global.RN$registerCallableModule('LurkKickChat', () => api);
    console.log('Lurk Kick: compiled bootstrap registered');
})(globalThis);
