/* Compiled HBC98. Only direct, verified Metro owners are changed; all native data calls are cache-only. */
(function (global) {
    'use strict';
    if (global.LurkTwitchChat !== undefined || global[1998063107] !== undefined) return;
    let owners, original, config = {}, installed = false;
    const received = new Map(), delayed = new Map(), snapshots = new WeakMap(), rowIndexes = new WeakMap();
    const LIMIT = 1000, catalogs = new Map(), stripes = new Map();
    let nextOwner = 0, columnWidth = 0;
    const providerSubscribers = new Map();
    let providerTimer, providerToken;
    function stopProviders() {
        if (providerTimer !== undefined) global.clearTimeout(providerTimer);
        providerTimer=undefined;providerToken=undefined;
        const subscribers=Array.from(providerSubscribers.values());providerSubscribers.clear();
        for (const entry of subscribers) {
            entry.owner.active=false;data({op:'release',id:entry.owner.id});entry.refresh(value=>value+1);
        }
    }
    function pollProviders() {
        providerTimer=undefined;
        if (!providerSubscribers.size) return;
        const revision=data({op:'revision'});
        if (revision.active !== true || typeof revision.token !== 'string') {stopProviders();return;}
        if (revision.token !== providerToken) {
            providerToken=revision.token;
            for (const entry of providerSubscribers.values()) entry.refresh(value=>value+1);
        }
        if (providerSubscribers.size) providerTimer=global.setTimeout(pollProviders,2000);
    }
    function subscribeProvider(owner,refresh) {
        providerSubscribers.set(owner.id,{owner,refresh});
        if (providerTimer === undefined) providerTimer=global.setTimeout(pollProviders,2000);
        return ()=>{
            providerSubscribers.delete(owner.id);
            if (!providerSubscribers.size) {
                if (providerTimer !== undefined) global.clearTimeout(providerTimer);
                providerTimer=undefined;providerToken=undefined;
            }
        };
    }

    function bounded(map, key, value) {
        map.delete(key); map.set(key, value);
        if (map.size > LIMIT) map.delete(map.keys().next().value);
    }
    function data(value) {
        try { return JSON.parse(owners.native.NativeModules.TwitchRNFeedSettings.getLurkChatData(JSON.stringify(value))); }
        catch (_) { return {}; }
    }
    function settings() { const c = data({op:'config'}); return c.version === 1 ? c : {}; }
    function normal(line, masks) {
        return !!line && ['chat','firstchatter','channelpointsreward'].indexOf(line.type) >= 0 &&
            typeof line.id === 'string' && line.id.length > 0 && typeof line.user === 'string' &&
            typeof line.body === 'string' && line.body.length <= 10000 && !line.hidden && !line.isUnpublished &&
            !line.unpublished && !line.lowTrust && !line.isLowTrust && !line.automod && !line.isAutoMod &&
            !line.blocked && !(line.maskedRanges && line.maskedRanges.length) && !(masks && masks.length);
    }
    function proof(line) { return line.user + '\u0000' + line.body; }
    function key(channel,line) { return channel + '\u0000' + line.id; }
    function verdict(line) {
        if (!config.keywords || !line || typeof line.body !== 'string' || typeof line.user !== 'string') return 0;
        const v = data({op:'verdict',user:line.user,body:line.body,id:line.id}).verdict;
        return v === 1 || v === 2 ? v : 0;
    }
    function delayedPredicate(line,event) {
        config = settings();
        if (!config.deleted || !normal(line) || !event || ['delete','timeout','ban'].indexOf(event.kind) < 0) return undefined;
        const matches = event.kind === 'delete' ? line.id === event.targetMsgId : line.user.toLowerCase() === event.userLogin;
        if (!matches) return false;
        bounded(delayed,line,proof(line));
        line.deleted = true;
        return false; // Keep the original timer and release path; never publish early.
    }
    delayedPredicate[1998063109] = 1998063110;
    global[1998063107] = delayedPredicate;
    function clock(ts) {
        const date = new Date(ts);
        return (date.getHours() % 12 || 12) + ':' + ('0' + date.getMinutes()).slice(-2);
    }
    function scale() { return config.appearance && [85,115,130].indexOf(config.scale) >= 0 ? config.scale / 100 : 1; }
    function textTree(tree, factor) {
        const R = owners.react;
        if (factor === 1 || !R.isValidElement(tree)) return tree;
        const changes = {};
        if (tree.type === owners.native.Text) {
            const style = owners.native.StyleSheet.flatten(tree.props.style) || {};
            const sizing = {};
            for (const name of ['fontSize','lineHeight']) if (typeof style[name] === 'number') sizing[name] = style[name] * factor;
            if (Object.keys(sizing).length) changes.style = [tree.props.style,sizing];
        }
        const children = tree.props.children;
        if (Array.isArray(children)) changes.children = children.map(child=>textTree(child,factor));
        else if (R.isValidElement(children)) changes.children = textTree(children,factor);
        return Object.keys(changes).length ? R.cloneElement(tree,changes) : tree;
    }
    function originalLine(props) {
        const message = props.message;
        if (!message || message.type !== undefined) return message;
        // ChatLineComponent projects the row payload and omits its type. Recover only a matching
        // original store line; extension/callout rows without a store do not gain retention privileges.
        const state = props.chatStore && props.chatStore.getState();
        const lines = state && state.lines;
        if (!Array.isArray(lines)) return undefined;
        let index = rowIndexes.get(lines);
        if (!index) {
            index = new Map();
            for (const entry of lines.slice(-LIMIT)) if (entry && entry.line) index.set(entry.line.id,entry.line);
            rowIndexes.set(lines,index);
        }
        const line = index.get(message.id);
        return line && line.body === message.body && line.user === message.user ? line : undefined;
    }

    function providers(props, origin) {
        const R = owners.react, ref = R.useRef(null), refresh = R.useState(0)[1];
        if (!ref.current) ref.current = {id:String(++nextOwner),active:false};
        const owner = ref.current, line = props.message;
        const channel = String(line.sourceRoomID || props.channelID || '');
        const enabled = !!config.providers && normal(origin,props.maskedRanges) && !line.deleted;
        const user = line.user, userId = String(line.userId || '');
        R.useEffect(()=>{
            if (!enabled) return;
            owner.active = data({op:'acquire',id:owner.id,channel,user,userId}).active === true;
            const unsubscribe=owner.active ? subscribeProvider(owner,refresh) : ()=>{};
            if (owner.active) refresh(value=>value+1);
            return ()=>{unsubscribe();owner.active=false;data({op:'release',id:owner.id});};
        },[enabled,channel,user,userId,config.revision]);
        if (!enabled || !owner.active) return {};
        let cached = catalogs.get(channel);
        const snapshot = data({op:'catalog',id:owner.id,generation:cached ? cached.generation : -1});
        if (snapshot.emotes && typeof snapshot.emotes === 'object') {
            cached = {generation:snapshot.generation,emotes:snapshot.emotes};
            catalogs.delete(channel);catalogs.set(channel,cached);
            if (catalogs.size > 16) catalogs.delete(catalogs.keys().next().value);
        }
        const changes = {}, message = {}, native = Array.isArray(line.emotes) ? line.emotes : [];
        if (cached) {
            const chars = Array.from(line.body), added = [];
            for (let start=0; start<chars.length;) {
                if (/\s/.test(chars[start])) {start++;continue;}
                let end=start+1;while(end<chars.length && !/\s/.test(chars[end]))end++;
                const id=cached.emotes[chars.slice(start,end).join('')];
                if (typeof id==='string' && /^lurk[7bf][as]_/.test(id) &&
                    !native.some(span=>span.start<end && span.end>=start)) added.push({id,start,end:end-1});
                start=end;
            }
            if(added.length) message.emotes=native.concat(added).sort((a,b)=>a.start-b.start);
        }
        if (Array.isArray(snapshot.badges) && snapshot.badges.length) {
            const badges=Object.assign({},line.badges), registry=Object.assign({},props.badgeRegistry);
            snapshot.badges.slice(0,33).forEach((badge,index)=>{
                if (!badge || typeof badge.title!=='string' || typeof badge.url!=='string' ||
                    !/^(https:\/\/|data:image\/png;base64,)/.test(badge.url)) return;
                const set='lurk-provider-'+index, version='1', key=owners.badges.badgeKey(set,version);
                if (Object.prototype.hasOwnProperty.call(badges,set) || Object.prototype.hasOwnProperty.call(registry,key)) return;
                badges[set]=version;registry[key]={setID:set,version,title:badge.title,
                    image1x:badge.url,image2x:badge.url,image4x:badge.url};
            });
            message.badges=badges;changes.badgeRegistry=registry;
        }
        if(Object.keys(message).length) changes.message=Object.assign({},line,message);
        return changes;
    }

    function stripe(tree,channel,line,highlighted) {
        if (!config.appearance || !config.alternate || !line || !owners.react.isValidElement(tree)) return tree;
        let room=stripes.get(channel);
        if(!room) {
            room={next:false,rows:new Map()};stripes.set(channel,room);
            if(stripes.size>16)stripes.delete(stripes.keys().next().value);
        }
        if(!room.rows.has(line.id)) {bounded(room.rows,line.id,room.next);room.next=!room.next;}
        if(!room.rows.get(line.id) || highlighted) return tree;
        function style(stock) {
            const flat=owners.native.StyleSheet.flatten(stock)||{};
            return flat.backgroundColor && flat.backgroundColor!=='transparent' ? stock :
                [stock,{backgroundColor:'rgba(128,128,128,0.0784313725490196)'}];
        }
        const stock=tree.props.style;
        return owners.react.cloneElement(tree,{style:typeof stock==='function' ? state=>style(stock(state)) : style(stock)});
    }

    function trackWidth(expanded,collapsed,wide,enabled) {
        if(columnWidth && !expanded) {
            if(collapsed==='33.3333%')collapsed=columnWidth+'%';
            else if(collapsed==='66.6667%')collapsed=(100-columnWidth)+'%';
        }
        return original.column(expanded,collapsed,wide,enabled);
    }
    function stage(props) {
        config=settings();
        const active=config.appearance && props.mode==='landscape-column-chat' &&
            !props.pipFloatSettled && !props.pipCompactFrame && !props.isTheatreDocked;
        const old=columnWidth;
        columnWidth=active && [20,25,35,40].indexOf(config.width)>=0 ? config.width : 0;
        let result;
        try {result=original.stage(props);} finally {columnWidth=old;}
        if(!active || [25,50,75].indexOf(config.opacity)<0) return result;
        const root=result.beforePlayerRegion, R=owners.react;
        if(!R.isValidElement(root) || !Array.isArray(root.props.children) || root.props.children.length!==2) return result;
        const chat=root.props.children[1];
        if(!R.isValidElement(chat))return result;
        const style=owners.native.StyleSheet.flatten(chat.props.style)||{}, color=style.backgroundColor;
        let rgb;
        if(typeof color==='string' && /^#[0-9a-f]{6}$/i.test(color))rgb=parseInt(color.slice(1),16);
        else if(typeof owners.native.processColor==='function')rgb=owners.native.processColor(color);
        if(typeof rgb!=='number')return result;
        const backgroundColor='rgba('+((rgb>>>16)&255)+','+((rgb>>>8)&255)+','+(rgb&255)+','+(config.opacity/100)+')';
        const children=root.props.children.slice();
        children[1]=R.cloneElement(chat,{style:[chat.props.style,{backgroundColor}]});
        return Object.assign({},result,{beforePlayerRegion:R.cloneElement(root,{children})});
    }

    function row(props) {
        config = settings();
        const R = owners.react, metrics = R.useContext(owners.metrics.ChatTextMetricsContext);
        const fontScale = R.useContext(owners.metrics.ChatFontScaleContext);
        const line = props.message, origin = originalLine(props), channel = String(props.channelID || '');
        let next = props;
        const changes = providers(props,origin);
        if (config.timestamps) { changes.showTimestamp = true; changes.hideTimestamp = false; }
        if (normal(origin,props.maskedRanges) && !line.hidden && !line.isUnpublished) {
            if (!line.deleted) bounded(received,key(channel,line),proof(line));
            else if (config.deleted && !line.modActionType &&
                (received.get(key(channel,line)) === proof(line) || delayed.get(origin) === proof(line))) {
                changes.message = Object.assign({},line,{modActionType:'delete'});
                // The ordinary-viewer placeholder had no content actions. Retaining its body must
                // not turn URLs, emotes or reply/thread callbacks back on.
                changes.openURL = changes.onPressEmote = changes.onReply = changes.onOpenThread = undefined;
            }
        }
        const v = verdict(line);
        if (v === 1) changes.highlighted = true;
        if (Object.keys(changes).length) next = Object.assign({},props,changes);
        // Always call the same original function, including hidden rows, preserving its hook sequence.
        let tree = original.row.type ? original.row.type(next) : original.row(next);
        if (v === 2) return null;
        if(normal(origin,props.maskedRanges)) tree=stripe(tree,channel,line,v===1 || props.highlighted);
        const factor = scale();
        tree = textTree(tree,factor);
        if (factor !== 1) {
            const scaled = metrics && Object.assign({},metrics);
            if (scaled) for (const name of ['xHeight','ascender','descender','capHeight','attachmentHeight'])
                if (typeof scaled[name] === 'number') scaled[name] *= factor;
            tree = R.createElement(owners.metrics.ChatTextMetricsContext.Provider,{value:scaled},tree);
            tree = R.createElement(owners.metrics.ChatFontScaleContext.Provider,{value:fontScale * factor},tree);
        }
        return tree;
    }
    function body(props) {
        config = settings();
        const next = config.appearance && config.plain && props.action ? Object.assign({},props,{action:false}) : props;
        return textTree(original.body(next),scale());
    }
    function timestamp(props) {
        config = settings();
        let tree = original.timestamp(props);
        if (config.timestamps && config.clock === 1 && owners.react.isValidElement(tree))
            tree = owners.react.cloneElement(tree,{children:[clock(props.tmiSentTs),'\u00a0']});
        return textTree(tree,scale());
    }
    function format(ts,intl) {
        config = settings();
        return config.timestamps && config.clock === 1 ? clock(ts) : original.format(ts,intl);
    }
    function selected(store,select) {
        config = settings();
        const c = config.keywords;
        const wrapped = owners.react.useMemo(()=>snapshot=>{
            if (!c || !Array.isArray(snapshot.lines)) return select(snapshot);
            let cached = snapshots.get(snapshot);
            // Config revision changes invalidate verdicts without altering the underlying store.
            if (!cached || cached.revision !== config.revision) {
                const lines = snapshot.lines.filter(entry=>verdict(entry.line) !== 2);
                cached = {revision:config.revision,value:lines.length === snapshot.lines.length ? snapshot : Object.assign({},snapshot,{lines})};
                snapshots.set(snapshot,cached);
            }
            return select(cached.value);
        },[select,c,config.revision]);
        return original.selector(store,wrapped);
    }
    function useStore(channel,options) {
        const store = original.store(channel,options);
        owners.react.useEffect(()=>{
            const id='store'+(++nextOwner), room=String(channel || '');
            const state=store && store.getState(), handlers=state && state._tmiClient && state._tmiClient.lineHandlers;
            // The original store is keyed by channel login; provider row leases separately use numeric IDs.
            if (!(handlers instanceof Set) || !/^[a-z0-9_]{1,25}$/i.test(room)) return;
            let active=data({op:'watch',id,channel:room}).active===true;
            if (!active) return;
            const incoming=line=>{
                const current=settings();
                if (!active || !current.keywords || !current.vibrate || !normal(line) || line.deleted || line.isHistorical) return;
                data({op:'live',id,channel:room,messageId:line.id,user:line.user,body:line.body});
            };
            handlers.add(incoming);
            return ()=>{active=false;handlers.delete(incoming);data({op:'unwatch',id});};
        },[store,channel]);
        return store;
    }
    function restore() {
        if (!installed) return;
        stopProviders();
        owners.row.ChatMessageRow=original.row;owners.body.MessageBody=original.body;
        owners.time.TimestampPart=original.timestamp;owners.time.formatTimestamp=original.format;
        owners.store.useChatSelector=original.selector;owners.store.useChatStore=original.store;
        owners.stage.useClassicStagePresentation=original.stage;owners.column.useColumnTrackWidth=original.column;
        installed=false;received.clear();delayed.clear();catalogs.clear();stripes.clear();
    }
    const api = {configure(raw) {
        const contract = JSON.parse(raw);
        if (!owners) {
            const found = {};
            for (const key of ['react','native','row','body','time','store','metrics','badges','stage','column']) found[key] = global.__r(contract[key]);
            for (const pair of [['row','ChatMessageRow'],['body','MessageBody'],['time','TimestampPart'],['time','formatTimestamp'],['store','useChatStore'],['store','useChatSelector'],['stage','useClassicStagePresentation'],['column','useColumnTrackWidth']]) {
                const descriptor = Object.getOwnPropertyDescriptor(found[pair[0]],pair[1]);
                if (!descriptor || !descriptor.writable || !descriptor.value) throw Error('Twitch chat owner contract: '+pair[1]);
            }
            if (typeof found.native.NativeModules.TwitchRNFeedSettings.getLurkChatData !== 'function') throw Error('Twitch chat native contract');
            if (typeof found.badges.badgeKey !== 'function') throw Error('Twitch chat badge contract');
            owners=found;
            original={row:owners.row.ChatMessageRow,body:owners.body.MessageBody,timestamp:owners.time.TimestampPart,
                format:owners.time.formatTimestamp,store:owners.store.useChatStore,selector:owners.store.useChatSelector,
                stage:owners.stage.useClassicStagePresentation,column:owners.column.useColumnTrackWidth};
        }
        config=settings();
        if (!config.timestamps && !config.deleted && !config.keywords && !config.appearance && !config.providers) {restore();return;}
        if (installed) return;
        owners.row.ChatMessageRow=owners.react.memo(row);owners.body.MessageBody=body;
        owners.time.TimestampPart=timestamp;owners.time.formatTimestamp=format;
        owners.store.useChatSelector=selected;owners.store.useChatStore=useStore;
        owners.stage.useClassicStagePresentation=stage;owners.column.useColumnTrackWidth=trackWidth;
        installed=true;
        global.console.log('Lurk Twitch: chat consumers installed');
    }};
    global.LurkTwitchChat=api;
    global.RN$registerCallableModule('LurkTwitchChat',()=>api);
    global.console.log('Lurk Twitch: compiled chat bootstrap registered');
})(globalThis);
