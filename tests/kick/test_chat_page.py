#!/usr/bin/env python3
"""Run Lurk's Kick chat page script (patches/src/main/resources/kick/chat/chat-page.js) in headless Chrome.

Usage: python3 tests/kick/test_chat_page.py
Requires Google Chrome or Chromium (CHROME overrides the binary) and the Python `websockets` package. Offline:
every request is answered by the test through the DevTools protocol, so the script sees a page at
https://chat.mobile.kick.com shaped like Kick's (the #app message list, its message, emote, link and highlight
elements; none of Kick's code), plus fixed Kick and 7TV responses. Checks the host guard, running once per page,
hidden highlight lines, ignored clears, 7TV words in existing and new messages (channel over global, exact words
only, links and Kick emotes untouched), the owner lookup, and that no request carries a referrer or cookies.
The Chrome profile lives under $MORPHE_WORK (default ~/build/lurk-morphe-patches) and is removed.
"""
import asyncio
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import urllib.request

import websockets

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "patches/src/main/resources/kick/chat/chat-page.js").read_text()
PAGE = "https://chat.mobile.kick.com/?config=%7B%22channelId%22%3A668%7D"
HTML = """<!doctype html><html><head></head><body><div id="app">
<div class="message-container"><span class="message-content">hi OMEGALUL EZ shared LUL, ok<span class="link"
 data-url="https://example.com/OMEGALUL">https://example.com/OMEGALUL</span> EZ<img class="emote"
 src="https://files.kick.com/emotes/1/fullsize" alt="kick"></span></div>
<div class="subscription-container">sub</div><div class="subgift-container">gift</div>
<div class="host-container">host</div><div class="reward-redeem-container">reward</div>
</div></body></html>"""
PIXEL = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
JSON_RESPONSES = {
    "https://kick.com/api/v2/channels/xqc": {"id": 668, "slug": "xqc", "user_id": 676},
    "https://7tv.io/v3/users/kick/676": {"emote_set": {"emotes": [
        {"id": "111111111111111111111111", "name": "OMEGALUL"},
        {"id": "222222222222222222222222", "name": "shared"},
    ]}},
    "https://7tv.io/v3/emote-sets/global": {"emotes": [
        {"id": "333333333333333333333333", "name": "EZ"},
        {"id": "444444444444444444444444", "name": "shared"},
        {"name": "no id"},
    ]},
}
HIGHLIGHTS = [".subscription-container", ".subgift-container", ".host-container", ".reward-redeem-container"]


def chrome():
    for name in (os.environ.get("CHROME"), "google-chrome", "chromium", "chromium-browser"):
        if name and shutil.which(name):
            return shutil.which(name)
    sys.exit("FAIL: no Chrome or Chromium (set CHROME)")


class Page:
    def __init__(self, ws):
        self.ws = ws
        self.next_id = 0
        self.pending = {}
        self.requests = []
        self.unexpected = []
        self.loaded = asyncio.Event()

    async def run(self):
        async for raw in self.ws:
            message = json.loads(raw)
            if "id" in message:
                self.pending.pop(message["id"]).set_result(message)
            elif message.get("method") == "Fetch.requestPaused":
                asyncio.create_task(self.answer(message["params"]))
            elif message.get("method") == "Page.loadEventFired":
                self.loaded.set()

    async def call(self, method, **params):
        self.next_id += 1
        future = asyncio.get_running_loop().create_future()
        self.pending[self.next_id] = future
        await self.ws.send(json.dumps({"id": self.next_id, "method": method, "params": params}))
        reply = await asyncio.wait_for(future, 20)
        if "error" in reply:
            raise RuntimeError(f"{method}: {reply['error']}")
        return reply["result"]

    async def answer(self, params):
        request = params["request"]
        url = request["url"]
        self.requests.append(request)
        headers = [{"name": "Access-Control-Allow-Origin", "value": "https://chat.mobile.kick.com"}]
        if url.startswith(PAGE.split("?")[0]):
            body, kind = HTML.encode(), "text/html"
        elif url in JSON_RESPONSES:
            body, kind = json.dumps(JSON_RESPONSES[url]).encode(), "application/json"
        elif url.startswith(("https://cdn.7tv.app/emote/", "https://files.kick.com/emotes/")):
            body, kind = PIXEL, "image/png"
        else:
            self.unexpected.append(url)
            await self.call("Fetch.failRequest", requestId=params["requestId"], errorReason="Failed")
            return
        headers.append({"name": "Content-Type", "value": kind})
        await self.call("Fetch.fulfillRequest", requestId=params["requestId"], responseCode=200,
                        responseHeaders=headers, body=base64.b64encode(body).decode())

    async def go(self, url):
        self.loaded.clear()
        await self.call("Page.navigate", url=url)
        await asyncio.wait_for(self.loaded.wait(), 20)

    async def eval(self, expression):
        result = await self.call("Runtime.evaluate", expression=expression, awaitPromise=True, returnByValue=True)
        if "exceptionDetails" in result:
            raise RuntimeError(json.dumps(result["exceptionDetails"])[:800])
        return result["result"].get("value")

    async def apply(self, options):
        return await self.eval(f"{SCRIPT}({json.dumps(options)})")

    async def until(self, expression):
        for _ in range(100):
            if await self.eval(f"!!({expression})"):
                return
            await asyncio.sleep(0.1)
        raise AssertionError(f"timed out waiting for {expression}")


def check(condition, what):
    if not condition:
        raise AssertionError(what)
    print(f"ok  {what}")


async def scenario(page):
    page.requests.clear()
    await page.call("Page.enable")
    await page.call("Fetch.enable", patterns=[{"urlPattern": "*"}])

    await page.go("about:blank")
    check(await page.apply({"ignoreClears": True}) == "not the chat page", "does nothing outside the chat page")

    await page.go(PAGE)
    check(await page.apply({}) == "nothing", "no switches, no changes")
    check(await page.eval("document.querySelectorAll('style').length") == 0, "no style added without switches")
    check(await page.eval("(() => { const app = document.getElementById('app'); app.innerHTML = ''; "
                          "return app.children.length; })()") == 0, "control: without the switch a clear empties chat")

    await page.go(PAGE)
    options = {"ignoreClears": True, "hide": HIGHLIGHTS, "sevenTv": True, "sevenTvChannel": "xqc", "sevenTvUser": None}
    check(await page.apply(options) == "hide " + " ".join(HIGHLIGHTS) + ", ignore clears, 7tv xqc", "reports what it applied")
    check(await page.apply(options) == "already applied", "applies once per page")

    hidden = await page.eval("Array.from(document.querySelectorAll('#app > div')).map(e => getComputedStyle(e).display)")
    check(hidden == ["block", "none", "none", "none", "none"], "highlight lines hidden, messages shown")

    await page.until("window.lurkChat.sevenTvCount && document.querySelector('img[alt=OMEGALUL]')")
    check(await page.eval("window.lurkChat.sevenTvCount") == [2, 3], "channel and global sets loaded")
    content = await page.eval("""Array.from(document.querySelector('.message-content').childNodes).map(n =>
        n.nodeType === 3 ? 'text:' + n.data : n.tagName + (n.classList.contains('link') ? ':link:' + n.textContent
            : ':' + n.alt + ':' + n.getAttribute('src') + ':' + n.className + ':' + n.referrerPolicy))""")
    seven = "https://cdn.7tv.app/emote/"
    check(content == [
        "text:hi ",
        f"IMG:OMEGALUL:{seven}111111111111111111111111/2x.webp:emote:no-referrer",
        "text: ",
        f"IMG:EZ:{seven}333333333333333333333333/2x.webp:emote:no-referrer",
        "text: ",
        f"IMG:shared:{seven}222222222222222222222222/2x.webp:emote:no-referrer",
        "text: LUL, ok",
        "SPAN:link:https://example.com/OMEGALUL",
        "text: ",
        f"IMG:EZ:{seven}333333333333333333333333/2x.webp:emote:no-referrer",
        "IMG:kick:https://files.kick.com/emotes/1/fullsize:emote:",
    ], "existing message: exact words become 7TV images, channel over global; links and Kick emotes stay")

    await page.eval("""(() => { const m = document.createElement('div'); m.className = 'message-container';
        m.innerHTML = '<span class="message-content">late EZ</span>'; document.getElementById('app').append(m); })()""")
    await page.until("document.querySelectorAll('#app > div:last-child img[alt=EZ]').length === 1")
    check(True, "a message added later is rewritten")

    check(await page.eval("(() => { const app = document.getElementById('app'); app.innerHTML = ''; "
                          "return app.children.length; })()") == 6, "a clear keeps the messages")
    check(await page.eval("(() => { const app = document.getElementById('app'); app.innerHTML = '<p>x</p>'; "
                          "return app.innerHTML; })()") == "<p>x</p>", "other assignments still work")

    lookups = [r for r in page.requests if r["url"].startswith("https://kick.com/")]
    third_party = [r for r in page.requests if "7tv" in r["url"] or "kick.com/api" in r["url"]]
    check(len(lookups) == 1, "one owner lookup on Kick")
    check(third_party and all(not {k.lower() for k in r["headers"]} & {"referer", "cookie"} for r in third_party),
          "no referrer or cookies on lookups and 7TV requests")
    # Control: the page's own fetches do send one, so the check above can see it.
    page.requests.clear()
    await page.eval("fetch('https://7tv.io/v3/emote-sets/global').then(r => r.status)")
    # Outstanding image requests can arrive after clear(); identify the control fetch itself.
    control = next(r for r in page.requests if r["url"] == "https://7tv.io/v3/emote-sets/global")
    check(any(k.lower() == "referer" for k in control["headers"]), "control: a default fetch has a referrer")

    page.requests.clear()
    await page.go(PAGE)
    await page.apply({"sevenTv": True, "sevenTvChannel": "xqc", "sevenTvUser": "676"})
    await page.until("window.lurkChat.sevenTvCount")
    check(not any(r["url"].startswith("https://kick.com/") for r in page.requests), "a known owner needs no lookup")

    page.requests.clear()
    await page.go(PAGE)
    await page.apply({"sevenTv": True, "sevenTvChannel": None, "sevenTvUser": None})
    await page.until("window.lurkChat.sevenTvCount")
    check(await page.eval("window.lurkChat.sevenTvCount") == [0, 3], "an unknown channel gets global emotes only")
    check(not any("/users/" in r["url"] or r["url"].startswith("https://kick.com/") for r in page.requests),
          "and asks neither Kick nor 7TV about a user")
    check(not page.unexpected, "no other requests: " + ", ".join(page.unexpected))


async def resilient_scenario(page):
    await page.call("Page.enable")
    await page.call("Fetch.enable", patterns=[{"urlPattern": "*"}])
    # A hung owner must not hold the independently successful global catalog hostage.
    await page.go(PAGE)
    await page.eval("""window.fetch = ((original) => (url, opts) => url.includes('/channels/')
        ? new Promise(() => {}) : original(url, opts))(window.fetch);
        window.WebSocket = class { close() {} };""")
    await page.apply({"sevenTv": True, "sevenTvChannel": "xqc"})
    await page.until("document.querySelector('img[alt=EZ]')")
    check(True, "successful globals render while owner request never settles")

    await page.go(PAGE)
    await page.eval("""window.events = [];
        window.ReactNativeWebView = {postMessage: s => events.push(JSON.parse(s))};
        window.WebSocket = class {
            constructor(url) { this.url=url; window.socket=this; this.sent=[]; setTimeout(()=>this.onopen(),0); }
            send(s) { this.sent.push(JSON.parse(s)); }
            close() { this.closed=true; if(this.onclose) this.onclose(); }
        };
        window.liveSet = {id:'555555555555555555555555', emotes:[
            {id:'333333333333333333333333',name:'EZ'},
            {id:'666666666666666666666666',name:'hat',flags:1},
            {id:'777777777777777777777777',name:'spark',data:{flags:256}}
        ]};
        window.fetch = (url, opts) => Promise.resolve(new Response(JSON.stringify(liveSet),{status:200}));
        document.querySelector('.message-content').textContent = 'EZ hat spark | hat EZ\\nhat EZ link hat';""")
    await page.eval("""const nativeMessage = document.createElement('span'); nativeMessage.className='message-content';
        nativeMessage.innerHTML='<img class="emote" id="native-base" src="https://files.kick.com/emotes/1/fullsize"> hat';
        window.nativeClicks=0; nativeMessage.firstChild.addEventListener('click',()=>nativeClicks++);
        document.getElementById('app').appendChild(nativeMessage);""")
    await page.apply({"sevenTv": True, "generation": "document-one", "channelId": "668"})
    await page.until("document.querySelector('[data-lurk-layer]')")
    check(await page.eval("document.querySelector('[data-lurk-layer]').querySelectorAll('img').length") == 3,
          "active-set and emote-data zero-width flags layer in order")
    check(await page.eval("document.querySelectorAll('[data-lurk-layer]').length") == 2,
          "standalone overlays and newline/text boundaries do not attach to unrelated bases")
    check(await page.eval("socket.sent[0].d.condition.object_id") == '555555555555555555555555',
          "EventAPI subscription is limited to the active set")
    check(await page.eval("events[events.length-1].emotes.length") == 3,
          "catalog crosses the existing WebView bridge without draft or message data")
    await page.eval("""liveSet.emotes = [{id:'333333333333333333333333',name:'hat'}];
        socket.onmessage({data:JSON.stringify({op:0,s:1,d:{type:'emote_set.update',body:{id:liveSet.id}}})});""")
    await page.until("document.querySelectorAll('img[data-lurk-emote]').length === 5")
    check(await page.eval("document.querySelectorAll('[data-lurk-layer]').length") == 0,
          "live removal unwraps old layers and re-renders existing words")
    check(await page.eval("document.querySelector('.message-content').textContent.includes('EZ')"),
          "removed emote becomes readable original text")
    await page.eval("document.querySelector('img[data-lurk-emote]').dispatchEvent(new Event('error'))")
    await asyncio.sleep(0.05)
    check(await page.eval("document.querySelector('.message-content').firstElementChild === null"),
          "failed image stays readable instead of observer retrying forever")
    await page.eval("document.getElementById('native-base').click()")
    check(await page.eval("nativeClicks") == 1, "native base image and its listener survive overlay refresh")
    await page.eval("window.dispatchEvent(new Event('pagehide'))")
    check(await page.eval("socket.closed"), "page teardown closes EventAPI")

    # A page initially hidden has no set IDs to refetch when it becomes visible.
    await page.go(PAGE)
    await page.eval("""Object.defineProperty(document,'hidden',{configurable:true,value:true});
        window.fetch=(url,opts)=>Promise.resolve(new Response(JSON.stringify({id:'555555555555555555555555',emotes:[
            {id:'333333333333333333333333',name:'EZ',data:{animated:true}},
            {id:'444444444444444444444444',name:'OMEGALUL',data:{animated:false}}
        ]}),{status:200}));window.WebSocket=class {close(){}};""")
    await page.apply({"sevenTv":True,"nonAnimatedEmotes":True})
    await page.eval("Object.defineProperty(document,'hidden',{value:false});document.dispatchEvent(new Event('visibilitychange'))")
    await page.until("document.querySelector('img[alt=EZ]')")
    check(await page.eval("document.querySelector('img[alt=EZ]').src.endsWith('/2x_static.webp')"),
          "visible resume loads missing catalog and animated emotes use a static filename")
    check(await page.eval("document.querySelector('img[alt=OMEGALUL]').src.endsWith('/2x.webp')"),
          "naturally static emotes keep their supported original filename")

    # Header and body stalls share the same deadline, with exactly three transient attempts.
    await page.go(PAGE)
    await page.eval("""window.attempts=0; window.delays=[];
        const timer=window.setTimeout;
        window.setTimeout=(fn,ms,...args)=>{delays.push(ms);return timer(fn,ms/1000,...args);};
        window.fetch=(url,opts)=>{
            attempts++;
            if(url.includes('/global')) return Promise.resolve(new Response(new ReadableStream({start(){}}),{status:200}));
            return Promise.resolve(new Response('',{status:429,headers:{'Retry-After':'2'}}));
        };""")
    await page.apply({"sevenTv":True,"sevenTvUser":"676"})
    await asyncio.sleep(0.15)
    check(await page.eval("attempts") == 6, "body stall and 429 each stop after three attempts")
    check(await page.eval("delays.includes(2000)"), "429 Retry-After is honored within the cap")
    await page.eval("window.dispatchEvent(new Event('pagehide'))")
    attempts = await page.eval("attempts")
    await asyncio.sleep(0.05)
    check(await page.eval("attempts") == attempts, "no work after teardown")


async def native_scenario(page, captures):
    """Run exactly the strings captured from production WebView.evaluateJavascript on ART."""
    expected = {"full", "info", "unknown", "provider-off", "malformed", "provider-only",
                "missing-config", "invalid-id", "clears-only", "subs-only", "hosts-only", "rewards-only"}
    check(set(captures) == expected, "complete native capture scenarios")
    for name, script in captures.items():
        page.requests.clear()
        await page.go(PAGE)
        await page.eval(script)
        single = name in ("clears-only", "subs-only", "hosts-only", "rewards-only")
        provider = name != "provider-off" and not single
        clears = name not in ("provider-only", "subs-only", "hosts-only", "rewards-only")
        display = ["block", "none", "none", "none", "none"]
        if name in ("provider-only", "clears-only"):
            display = ["block"] * 5
        elif single:
            display = ["block"] * 5
            for index in {"subs-only": [1, 2], "hosts-only": [3], "rewards-only": [4]}[name]:
                display[index] = "none"
        if provider:
            expected_count = [0, 3] if name in ("unknown", "malformed", "missing-config", "invalid-id") else [2, 3]
            await page.until("JSON.stringify(window.lurkChat.sevenTvCount) === " + json.dumps(json.dumps(expected_count, separators=(",", ":"))))
            check(await page.eval("window.lurkChat.sevenTvCount") == expected_count,
                  f"native {name}: actual response/config selects expected emotes")
        else:
            check(not any("7tv" in r["url"] for r in page.requests),
                  f"native {name}: no provider requests")
        hidden = await page.eval("Array.from(document.querySelectorAll('#app > div')).map(e => getComputedStyle(e).display)")
        check(hidden == display,
              f"native {name}: independent hide switches")
        remaining = await page.eval("(() => {const app=document.getElementById('app'); app.innerHTML=''; return app.children.length;})()")
        check(remaining == (5 if clears else 0), f"native {name}: independent clear switch")
        check(not any(r["url"].startswith("https://kick.com/") for r in page.requests),
              f"native {name}: known owner or globals require no owner lookup")
    check(not page.unexpected, "native captures make no unexpected requests")


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-captures", type=Path, help="bin/test-kick-chat ART capture log")
    args = parser.parse_args()
    work = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches"))
    work.mkdir(parents=True, exist_ok=True)
    profile = Path(tempfile.mkdtemp(prefix="chat-page-", dir=work))
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    process = subprocess.Popen([chrome(), "--headless=new", f"--remote-debugging-port={port}",
                                f"--user-data-dir={profile}", "--no-first-run", "about:blank"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            try:
                targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list"))
                url = next(t["webSocketDebuggerUrl"] for t in targets if t["type"] == "page")
                break
            except (OSError, StopIteration):
                await asyncio.sleep(0.1)
        else:
            sys.exit("FAIL: Chrome didn't start")
        async with websockets.connect(url, max_size=None) as ws:
            page = Page(ws)
            reader = asyncio.create_task(page.run())
            try:
                await resilient_scenario(page)
                await scenario(page)
                if args.native_captures:
                    captures = {}
                    for line in args.native_captures.read_text().splitlines():
                        if line.startswith("CAPTURE "):
                            _, name, encoded = line.split()
                            check(name not in captures, f"unique native capture {name}")
                            captures[name] = base64.b64decode(encoded).decode()
                    await native_scenario(page, captures)
            finally:
                reader.cancel()
    finally:
        process.terminate()
        process.wait()
        shutil.rmtree(profile, ignore_errors=True)
    print("PASS: chat page script")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except AssertionError as e:
        sys.exit(f"FAIL: {e}")
