#!/usr/bin/env python3
"""Actual Twitch runtime classes on JVM platform boundaries and local slow HTTP (no APK)."""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'shared'))
from host_java import run, STUBS, PACKAGE

EXTRA = {
    'android/net/Uri.java': '''package android.net; import java.net.*; import java.util.*;
      public class Uri { final URI u; Uri(String s){u=URI.create(s);} public static Uri parse(String s){return new Uri(s);}
      public String getHost(){return u.getHost();} public String getPath(){return u.getPath();}
      public Set<String> getQueryParameterNames(){return query().keySet();}
      public String getQueryParameter(String k){return query().get(k);}
      Map<String,String> query(){Map<String,String> m=new LinkedHashMap<>();if(u.getRawQuery()!=null)
        for(String s:u.getRawQuery().split("&")){String[] p=s.split("=",2);m.put(URLDecoder.decode(p[0],java.nio.charset.StandardCharsets.UTF_8),p.length==1?"":URLDecoder.decode(p[1],java.nio.charset.StandardCharsets.UTF_8));}return m;}
      public Builder buildUpon(){return new Builder(this);} public String toString(){return u.toString();}
      public static class Builder {Uri u;Map<String,String> m;Builder(Uri u){this.u=u;m=u.query();}
        public Builder clearQuery(){m.clear();return this;}public Builder appendQueryParameter(String k,String v){m.put(k,v);return this;}
        public Uri build(){String s=u.u.toString().split("\\\\?",2)[0];StringJoiner j=new StringJoiner("&");m.forEach((k,v)->j.add(k+"="+URLEncoder.encode(v,java.nio.charset.StandardCharsets.UTF_8)));return new Uri(s+(m.isEmpty()?"":"?"+j));}} }''',
    'org/json/JSONException.java': 'package org.json; public class JSONException extends Exception {public JSONException(String s){super(s);}}',
    # JSON is a platform boundary. Known response token supplies the complete chapter-query graph.
    'org/json/JSONObject.java': '''package org.json; import java.util.*; public class JSONObject {
      final Map<String,Object> m=new HashMap<>();public JSONObject(){} public JSONObject(String s)throws JSONException {
        if(s.equals("chapters")){m.put("data",new JSONObject().put("video",new JSONObject().put("moments",new JSONObject().put("edges",new JSONArray(new JSONObject().put("node",new JSONObject().put("positionMilliseconds",12000).put("description","Game").put("type","GAME_CHANGE")))))));}
        else if(s.startsWith("{\\\"expires\\\":")){m.put("expires",Long.parseLong(s.replaceAll("[^0-9]","")));}
        else throw new JSONException("invalid");}
      public JSONObject put(String k,Object v)throws JSONException{m.put(k,v);return this;}
      public JSONObject getJSONObject(String k)throws JSONException {JSONObject o=optJSONObject(k);if(o==null)throw new JSONException(k);return o;}
      public JSONObject optJSONObject(String k){return m.get(k) instanceof JSONObject?(JSONObject)m.get(k):null;}
      public JSONArray optJSONArray(String k){return m.get(k) instanceof JSONArray?(JSONArray)m.get(k):null;}
      public String getString(String k)throws JSONException{if(!m.containsKey(k))throw new JSONException(k);return m.get(k).toString();}
      public String optString(String k,String d){return m.containsKey(k)?m.get(k).toString():d;}
      public int optInt(String k,int d){return m.get(k) instanceof Number?((Number)m.get(k)).intValue():d;}
      public long optLong(String k,long d){return m.get(k) instanceof Number?((Number)m.get(k)).longValue():d;}
      public String toString(){return m.toString();} }''',
    'org/json/JSONArray.java': '''package org.json; public class JSONArray {final JSONObject[] a;
      public JSONArray(JSONObject... a){this.a=a;}public int length(){return a.length;}public JSONObject getJSONObject(int i){return a[i];}}''',
    PACKAGE+'Logger.java': 'package io.github.jordanwoodson.extension.shared; public class Logger {public static void d(String s){}public static void blocked(String s){}}',
    PACKAGE+'settings/Settings.java': STUBS[PACKAGE+'settings/Settings.java'].replace('new java.util.HashMap<>()','new java.util.concurrent.ConcurrentHashMap<>()'),
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return java.lang.System.nanoTime()/1000000;}}',
}

if __name__ == '__main__':
    helpers = ['twitch/TwitchBackupStream.java']
    for name in ['TwitchRequest']:
        if (Path(__file__).resolve().parents[2]/'extensions/shared/src/main/java'/PACKAGE/'twitch'/(name+'.java')).exists():
            helpers.append('twitch/'+name+'.java')
    run('TwitchAsyncProbe', helpers, Path(__file__).with_name('TwitchAsyncProbe.java').read_text(),
        sys.argv[1:] or ['stream-lock','stream-generation','headers','cancel-http','trickle','oversize','stale-source','first-stale','same-stale','partial-stale','all-stale','disabled','blocked-provider','cancel-provider','ad-timeout','cancel-chain','saturation'], extra=EXTRA)
