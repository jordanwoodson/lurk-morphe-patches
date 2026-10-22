package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.Fingerprint

// react-native-webview keeps its library names. Kick's chat is a page in one of its WebViews.

/** Smali descriptor of the extension's chat page class. */
internal const val KICK_CHAT = "Lio/github/jordanwoodson/extension/shared/kick/KickChat;"

internal object WebViewDestroyFingerprint : Fingerprint(
    definingClass = "Lcom/reactnativecommunity/webview/RNCWebView;", name = "destroy", returnType = "V",
    parameters = emptyList(),
)

internal object WebViewMessageFingerprint : Fingerprint(
    definingClass = "Lcom/reactnativecommunity/webview/RNCWebView;", name = "onMessage", returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
)

internal object WebViewConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reactnativecommunity/webview/RNCWebView;",
    name = "<init>", returnType = "V", parameters = listOf("Lcom/facebook/react/uimanager/ThemedReactContext;"),
)

internal object PageStartedFingerprint : Fingerprint(
    definingClass = "Lcom/reactnativecommunity/webview/RNCWebViewClient;",
    name = "onPageStarted", returnType = "V",
    parameters = listOf("Landroid/webkit/WebView;", "Ljava/lang/String;", "Landroid/graphics/Bitmap;"),
)

internal object PageFinishedFingerprint : Fingerprint(
    definingClass = "Lcom/reactnativecommunity/webview/RNCWebViewClient;",
    name = "onPageFinished", returnType = "V",
    parameters = listOf("Landroid/webkit/WebView;", "Ljava/lang/String;"),
)

// React Native's OkHttp client provider keeps its library name.

internal object ClientBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/react/modules/network/OkHttpClientProvider;",
    name = "createClientBuilder", returnType = "Lokhttp3/OkHttpClient\$Builder;", parameters = emptyList(),
)
