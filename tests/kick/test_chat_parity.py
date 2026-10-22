#!/usr/bin/env python3
"""Chat keywords and Chat timestamps on Kick behave as on Twitch for the same inputs.

Usage: python3 tests/kick/test_chat_parity.py   (needs a JDK and Node.js; no APK, device or network)

Keywords: the production KickChat (a Chat keywords batch from its chat page, through onPage and onMessage, the way the
page sends it) and Twitch's TwitchChatKeywords.classify (which its native and React Native chats both use) run on the
JVM against the shared ChatFilter, with the same Lurk lists and the same messages; both verdicts must equal the
expected one. The fixtures cover mentions of the account (its login or name and its display name or kick.com handle,
and never your own messages), highlight words and phrases, blocked words and users ("@" and case ignored), case
folding (also beyond ASCII), whole-word matching and blocks winning over highlights, with mentions on and off.
Twitch's login and display name stand for Kick's name shown in chat and kick.com handle.

Timestamps: Twitch's native chat formats with SimpleDateFormat and the pattern TwitchChat.timestampPattern returns for
its stock "H:mm" (the patch requires that pattern in Twitch's code); its React Native chat's 12-hour time comes from
Lurk's chat.js. Kick's chat page writes times in its config's locale and Lurk's chat page script rewrites them
(tests/kick/test_chat_parity.js runs the real script in Node). For each moment, both clocks and six page locales,
Kick's time must equal Twitch's.

Known difference, not tested as parity: Kick also hides a blocked user by kick.com handle, which has no Twitch
counterpart (Twitch matches the login only).
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / 'shared'))
from host_java import PACKAGE, run  # noqa: E402
from json_double import JSON_DOUBLE  # noqa: E402

MOMENTS = [[0, 5], [9, 5], [12, 0], [12, 30], [13, 5], [23, 59]]

# The Android surface the compiled helpers use, beyond tests/shared/host_java.py's.
STUBS = {
    'android/text/Spanned.java': '''package android.text; public interface Spanned extends CharSequence {
        int SPAN_EXCLUSIVE_EXCLUSIVE=33; <T> T[] getSpans(int start,int end,Class<T> type); }''',
    'android/text/Spannable.java': '''package android.text; public interface Spannable extends Spanned {
        void setSpan(Object what,int start,int end,int flags); }''',
    'android/text/SpannableString.java': '''package android.text; public class SpannableString implements Spannable {
        private final String text; public SpannableString(CharSequence s){text=s.toString();}
        public int length(){return text.length();} public char charAt(int i){return text.charAt(i);}
        public CharSequence subSequence(int a,int b){return text.subSequence(a,b);} public String toString(){return text;}
        @SuppressWarnings("unchecked") public <T> T[] getSpans(int a,int b,Class<T> t){return (T[])java.lang.reflect.Array.newInstance(t,0);}
        public void setSpan(Object w,int a,int b,int f){} }''',
    'android/text/SpannableStringBuilder.java': '''package android.text; public class SpannableStringBuilder extends SpannableString {
        public SpannableStringBuilder(CharSequence s){super(s);} }''',
    'android/text/SpannedString.java': '''package android.text; public class SpannedString extends SpannableString {
        public SpannedString(CharSequence s){super(s);} }''',
    'android/text/TextPaint.java': '''package android.text; public class TextPaint {
        public void setStrikeThruText(boolean b){} public int getAlpha(){return 255;} public void setAlpha(int a){} }''',
    'android/text/style/CharacterStyle.java': '''package android.text.style; public abstract class CharacterStyle {
        public abstract void updateDrawState(android.text.TextPaint paint); }''',
    'android/text/style/UpdateAppearance.java': 'package android.text.style; public interface UpdateAppearance {}',
    'android/view/View.java': '''package android.view; public class View {
        public static final int VISIBLE=0,GONE=8;
        public android.content.Context getContext(){return new android.content.Context();}
        public ViewGroup.LayoutParams getLayoutParams(){return null;} public void setLayoutParams(ViewGroup.LayoutParams p){}
        public void setVisibility(int v){} public void setBackgroundColor(int c){} public View getDecorView(){return this;} }''',
    'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
        public static class LayoutParams {public static final int MATCH_PARENT=-1,WRAP_CONTENT=-2; public int height;}
        public static class MarginLayoutParams extends LayoutParams {public MarginLayoutParams(int w,int h){height=h;}} }''',
    'android/widget/TextView.java': '''package android.widget; public class TextView extends android.view.View {
        public CharSequence getText(){return "";} }''',
    'android/content/Context.java': '''package android.content; public class Context {
        public static final int MODE_PRIVATE=0;
        public Object getSystemService(String s){return null;} public <T>T getSystemService(Class<T> c){return null;}
        public Context getApplicationContext(){return this;}
        public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
        public SharedPreferences getSharedPreferences(String s,int m){throw new AssertionError("unexpected preferences "+s);} }''',
    'android/content/res/AssetManager.java': '''package android.content.res; public class AssetManager {
        public java.io.InputStream open(String name)throws java.io.IOException{throw new java.io.FileNotFoundException(name);} }''',
    'android/net/Uri.java': '''package android.net; public class Uri {
        private final java.net.URI uri; private Uri(java.net.URI u){uri=u;}
        public static Uri parse(String s){try{return new Uri(new java.net.URI(s));}catch(java.net.URISyntaxException e){throw new IllegalArgumentException(e);}}
        public String getScheme(){return uri.getScheme();} public String getHost(){return uri.getHost();}
        public int getPort(){return uri.getPort();} public String getUserInfo(){return uri.getUserInfo();}
        public String getQueryParameter(String name){String q=uri.getRawQuery();if(q==null)return null;
          for(String part:q.split("&")){int at=part.indexOf('=');String k=at<0?part:part.substring(0,at);
            if(java.net.URLDecoder.decode(k,java.nio.charset.StandardCharsets.UTF_8).equals(name))
              return at<0?"":java.net.URLDecoder.decode(part.substring(at+1),java.nio.charset.StandardCharsets.UTF_8);}
          return null;} }''',
    'android/util/Log.java': '''package android.util; public class Log {
        public static int w(String t,String m){return 0;} public static int i(String t,String m){return 0;} }''',
    'android/webkit/ValueCallback.java': 'package android.webkit; public interface ValueCallback<T> {void onReceiveValue(T value);}',
    'android/webkit/WebView.java': '''package android.webkit; public class WebView extends android.view.View {
        public String getUrl(){return null;} public void evaluateJavascript(String script,ValueCallback<String> callback){} }''',
    PACKAGE + 'Logger.java': '''package io.github.jordanwoodson.extension.shared; public class Logger {
        public static final String TAG="lurk";
        public static void d(String s){} public static void i(String s){} public static void w(String s){}
        public static void w(String s,Throwable t){} public static void blocked(String s){} }''',
    # Neither is reached by Chat keywords or the clock; KickChat names them for 7TV.
    PACKAGE + 'kick/KickCatalog.java': '''package io.github.jordanwoodson.extension.shared.kick; final class KickCatalog {
        static org.json.JSONObject sanitize(org.json.JSONObject d,String g,Object c,Object s){return null;} }''',
    PACKAGE + 'kick/KickComposer.java': '''package io.github.jordanwoodson.extension.shared.kick; final class KickComposer {
        static void send(String kind,String json){} }''',
    PACKAGE + 'twitch/TwitchChatStyle.java': '''package io.github.jordanwoodson.extension.shared.twitch; final class TwitchChatStyle {
        static boolean isStockBackground(android.view.View row){return true;} }''',
}

PROBE = r'''
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import io.github.jordanwoodson.extension.shared.kick.KickChat;
import io.github.jordanwoodson.extension.shared.settings.Settings;
import org.json.*;

public class ChatParityProbe {
  // The signed-in account: Twitch's login and display name, Kick's name shown in chat and kick.com handle.
  static final String NAME = "rin_chan", ALIAS = "Rin";
  static final String PAGE_URL = "https://chat.mobile.kick.com/?config=" + java.net.URLEncoder.encode(
      "{\"chatroomId\":1,\"channelId\":7,\"selfUser\":{\"id\":42,\"slug\":\"rin\",\"username\":\"rin_chan\"}}",
      java.nio.charset.StandardCharsets.UTF_8);

  public static class Page extends WebView {
    String last;
    @Override public String getUrl(){return PAGE_URL;}
    @Override public void evaluateJavascript(String script,ValueCallback<String> callback){last=script;}
  }
  static class Preferences implements SharedPreferences {
    public String getString(String key,String fallback){
      return key.equals("name")?NAME:key.equals("DisplayName")?ALIAS:fallback;}
    public Editor edit(){throw new AssertionError("Twitch's preferences are only read");}
  }
  static class App extends Context {
    @Override public SharedPreferences getSharedPreferences(String name,int mode){
      if(!name.equals("twitch_user_prefs"))throw new AssertionError(name);return new Preferences();}
  }

  static Method twitchClassify;
  static String generation;

  static int twitch(String sender,String text)throws Exception{return (Integer)twitchClassify.invoke(null,sender,text);}

  static int kick(Page page,String sender,String text)throws Exception{
    JSONObject item=new JSONObject().put("id","m1").put("name",sender).put("slug",sender).put("text",text);
    JSONObject batch=new JSONObject().put("type","lurkKeywords").put("generation",generation)
        .put("items",new JSONArray().put(item));
    page.last=null;
    if(!KickChat.onMessage(page,batch.toString(),PAGE_URL))throw new AssertionError("KickChat didn't take the batch");
    if(page.last==null)return 0;
    String verdicts=page.last.substring(page.last.indexOf("verdicts(")+9,page.last.lastIndexOf(");"));
    return new JSONObject(verdicts).optInt("m1");
  }

  public static void main(String[] args)throws Exception{
    // args[0]: a directory with moments.json, where jvm.json goes.
    Path out=Paths.get(args[0],"jvm.json");
    Settings.values.put("chat_keywords","true");
    Settings.values.put("chat_highlight_words","GG, well played, ünïcode");
    Settings.values.put("chat_blocked_words","spoiler, bad phrase");
    Settings.values.put("chat_blocked_users","Spammer, @Troll");
    Settings.values.put("chat_highlight_vibrate","false");
    Settings.values.put("chat_timestamps","true");
    Class<?> context=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchContext");
    Field application=context.getDeclaredField("application");application.setAccessible(true);application.set(null,new App());
    twitchClassify=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchChatKeywords")
        .getDeclaredMethod("classify",String.class,String.class);
    twitchClassify.setAccessible(true);

    // Kick: the chat page as KickChat opens it, with Lurk's page script cached (only the options matter here).
    Field script=KickChat.class.getDeclaredField("script");script.setAccessible(true);script.set(null,"\n");
    Page page=new Page();
    KickChat.onPage(page,PAGE_URL);
    JSONObject options=new JSONObject(page.last.substring(page.last.lastIndexOf("\n(")+2,page.last.length()-2));
    generation=options.getString("generation");

    String[][] fixtures={
      // sender, text, verdict with mentions on, verdict with mentions off (0 none, 1 highlight, 2 hide)
      {"someone","hello there","0","0"},
      {"someone","hey @rin_chan","1","0"},
      {"someone","RIN_CHAN are you there?","1","0"},
      {"someone","Rin, look","1","0"},
      {"someone","rinse and repeat","0","0"},
      {"someone","rin_chans unite","0","0"},
      {"rin_chan","I am rin_chan, hi Rin","0","0"},
      {"rin","hey rin_chan","1","0"},
      {"someone","gg ez","1","1"},
      {"someone","eggs and GGs","0","0"},
      {"someone","that was WELL PLAYED","1","1"},
      {"someone","well, played","0","0"},
      {"someone","ÜNÏCODE rocks","1","1"},
      {"someone","no spoiler please","2","2"},
      {"someone","Spoiler!","2","2"},
      {"someone","spoilers ahead","0","0"},
      {"someone","gg but SPOILER","2","2"},
      {"someone","@rin_chan spoiler","2","2"},
      {"someone","a Bad Phrase here","2","2"},
      {"spammer","hi","2","2"},
      {"SPAMMER","hi","2","2"},
      {"spammers","hi","0","0"},
      {"troll","gg","2","2"},
      {"someone","troll spammer","0","0"},
    };
    StringBuilder report=new StringBuilder("{\"keywords\":[");
    int checks=0;
    for(String mentions:new String[]{"true","false"}){
      Settings.values.put("chat_highlight_mentions",mentions);
      for(String[] f:fixtures){
        int expected=Integer.parseInt(mentions.equals("true")?f[2]:f[3]);
        int onTwitch=twitch(f[0],f[1]), onKick=kick(page,f[0],f[1]);
        if(onTwitch!=expected||onKick!=expected)throw new AssertionError("mentions "+mentions+", "+f[0]+": \""+f[1]+
            "\": Twitch "+onTwitch+", Kick "+onKick+", expected "+expected);
        if(checks++>0)report.append(',');
        report.append(new JSONArray().put(mentions).put(f[0]).put(f[1]).put(onTwitch).put(onKick));
      }
    }
    report.append("],\"twitch\":{");

    // Twitch's native clock: the pattern TwitchChat.timestampPattern makes of Twitch's own "H:mm", per choice.
    Method pattern=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchChat")
        .getMethod("timestampPattern",String.class);
    JSONArray moments=new JSONArray(new String(Files.readAllBytes(Paths.get(args[0],"moments.json")),"UTF-8"));
    for(String clock:new String[]{"0","1"}){
      Settings.values.put("chat_timestamp_format",clock);
      SimpleDateFormat format=new SimpleDateFormat((String)pattern.invoke(null,"H:mm"),Locale.ROOT);
      JSONArray times=new JSONArray();
      for(int i=0;i<moments.length();i++){
        JSONArray moment=(JSONArray)moments.get(i);
        Calendar day=new GregorianCalendar(2026,0,15,(Integer)moment.get(0),(Integer)moment.get(1));
        times.put(format.format(day.getTime()));
      }
      report.append(clock.equals("0")?"":",").append('"').append(clock).append("\":").append(times);
    }
    // Kick's page gets the clock from KickChat's options, as on Twitch: Twitch's key and values.
    for(String clock:new String[]{"0","1"}){
      Settings.values.put("chat_timestamp_format",clock);
      page.last=null;KickChat.onPageStarted(page,PAGE_URL);
      JSONObject sent=new JSONObject(page.last.substring(page.last.lastIndexOf("\n(")+2,page.last.length()-2));
      if(!sent.optBoolean("timestamps")||sent.optInt("clock",-1)!=Integer.parseInt(clock))
        throw new AssertionError("KickChat passes clock "+clock+": "+sent);
    }
    Settings.values.put("chat_timestamps","false");
    page.last=null;KickChat.onPageStarted(page,PAGE_URL);
    if(page.last!=null&&new JSONObject(page.last.substring(page.last.lastIndexOf("\n(")+2,page.last.length()-2)).has("clock"))
      throw new AssertionError("KickChat passes a clock without Chat timestamps");
    report.append("}}");
    Files.write(out,report.toString().getBytes("UTF-8"));
    System.out.println("JVM side: "+checks+" keyword verdicts equal on Twitch and Kick; Twitch's native clock formatted");
  }
}
'''


def main():
    work = Path(os.environ.get('MORPHE_WORK', Path.home() / 'build/lurk-morphe-patches')) / 'ChatParityProbe'
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work) as scratch:
        scratch = Path(scratch)
        moments = scratch / 'moments.json'
        moments.write_text(json.dumps(MOMENTS))
        jvm = scratch / 'jvm.json'
        run('ChatParityProbe', ['ChatFilter.java', 'twitch/TwitchChatKeywords.java', 'twitch/TwitchChat.java',
                                'twitch/TwitchContext.java', 'kick/KickChat.java'],
            PROBE, [str(scratch)], extra=JSON_DOUBLE | STUBS)
        page = scratch / 'page.json'
        subprocess.run(['node', str(HERE / 'test_chat_parity.js'), str(ROOT / 'patches/src/main/resources/kick/chat/chat-page.js'),
                        str(ROOT / 'patches/src/main/runtime/twitch/chat.js'), str(moments), str(page)], check=True)
        twitch = json.loads(jvm.read_text())['twitch']
        pages = json.loads(page.read_text())
        failures = []
        for clock in ('0', '1'):
            for locale, times in pages['kick'].items():
                if times[clock] != twitch[clock]:
                    failures.append(f'clock {clock}, Kick page in {locale}: {times[clock]} but Twitch shows {twitch[clock]}')
        if pages['twitchRn']['1'] != twitch['1']:
            failures.append(f"Twitch's React Native 12-hour clock {pages['twitchRn']['1']} differs from its native {twitch['1']}")
        for failure in failures:
            print('FAIL ' + failure)
        if failures:
            sys.exit(1)
        print(f"PASS: Kick's Chat keywords and Chat timestamps match Twitch's: {len(json.loads(jvm.read_text())['keywords'])} "
              f"keyword verdicts, and {len(MOMENTS)} moments on the 24-hour ({', '.join(twitch['0'])}) and 12-hour "
              f"({', '.join(twitch['1'])}) clocks in {len(pages['kick'])} page locales")


if __name__ == '__main__':
    main()
