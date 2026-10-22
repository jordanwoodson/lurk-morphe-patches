// Show deleted messages, on Kick's chat page (chat.mobile.kick.com). The page draws removed messages struck
// through and labelled, with ban, timeout and unban lines, when its config says userCanModerate. The app passes
// that config as a `config` parameter of the page's address, and Kick's server receives the address, so Lurk
// leaves it as Kick builds it. The page reads its config from react-native-webview's
// `ReactNativeWebView.injectedObjectJson()` when that exists and from the address otherwise; this script gives
// the page the address's own config with userCanModerate set to true through that function. KickChat registers
// it as a document-start script, so it runs before the page's own scripts, and only on the chat page's origin.
(function () {
    'use strict';
    if (location.protocol !== 'https:' || location.host !== 'chat.mobile.kick.com') return;
    const params = new URL(location.href).searchParams;
    // A single KICKs gift preview is the same page with preview=true; it shows no chat.
    if (params.get('preview') !== null) return;
    const raw = params.get('config');
    if (!raw) return;
    let config;
    try {
        config = JSON.parse(raw);
    } catch (_) {
        return;
    }
    if (!config || typeof config !== 'object' || Array.isArray(config)) return;
    const bridge = window.ReactNativeWebView;
    // An app-provided config wins: never replace one.
    if (!bridge || typeof bridge.injectedObjectJson !== 'undefined') return;
    config.userCanModerate = true;
    const json = JSON.stringify(config);
    try {
        bridge.injectedObjectJson = function () {
            return json;
        };
    } catch (_) {
        return;
    }
    window.lurkModeratorView = typeof bridge.injectedObjectJson === 'function';
})();
