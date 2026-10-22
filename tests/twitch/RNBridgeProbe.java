import android.content.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Actual Android JSON/settings boundary, without touching application preferences or sessions. */
public final class RNBridgeProbe {
 public static void main(String[] args) {try {run(args);} catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}}
 static void run(String[] args) throws Exception {
  Class<?> helper;
  try {helper=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchRNChat");}
  catch(ClassNotFoundException e){throw new AssertionError("compiled RN chat native data bridge is missing",e);}
  Map<String,Object> prefs=new HashMap<>();
  for(String key:new String[]{"emotes_7tv","emotes_bttv","emotes_ffz","badges_ffz","badges_chatterino","badges_pronouns"})prefs.put(key,false);
  SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
   if(m.getName().equals("getBoolean")||m.getName().equals("getString"))return prefs.getOrDefault((String)a[0],a[1]);
   throw new UnsupportedOperationException(m.getName());
  });
  Context context=new ContextWrapper(null){@Override public SharedPreferences getSharedPreferences(String n,int m){return preferences;} @Override public Object getSystemService(String n){return null;} @Override public String getSystemServiceName(Class<?> c){return null;}};
  Class<?> settings=Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");
  Field f=settings.getDeclaredField("context");f.setAccessible(true);f.set(null,context);
  Class<?> keywords=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchChatKeywords");
  f=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchContext").getDeclaredField("application");f.setAccessible(true);f.set(null,context);
  Method exchange=helper.getMethod("exchange",Object.class,String.class);Object owner=new Object();
  JSONObject config=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"config\"}"));
  check(config.getInt("version")==1&&config.getBoolean("timestamps")&&config.getBoolean("deleted"),"included defaults");
  prefs.put("chat_timestamps",false);prefs.put("chat_show_deleted",false);
  config=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"config\"}"));
  check(!config.getBoolean("timestamps")&&!config.getBoolean("deleted"),"setting opt-out");
  prefs.put("chat_blocked_words","hide");
  JSONObject v=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"verdict\",\"user\":\"viewer\",\"body\":\"hide me\"}"));
  check(v.getInt("verdict")==2,"native keyword rules");
  check("{}".equals(exchange.invoke(null,owner,"invalid")),"malformed request fails closed");
  check("{}".equals(exchange.invoke(null,owner,"x".repeat(20001))),"oversized request rejected");
  JSONObject lease=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"acquire\",\"id\":\"row1\",\"channel\":\"42\",\"user\":\"viewer\",\"userId\":\"1\"}"));
  check(lease.optBoolean("active"),"RN row owner acquired");
  for(String[] identity:new String[][]{{"42","viewer","1"},{"43","viewer","1"},{"43","other","1"},{"43","other","2"}}){
   exchange.invoke(null,owner,new JSONObject().put("op","acquire").put("id","row1").put("channel",identity[0]).put("user",identity[1]).put("userId",identity[2]).toString());
   JSONObject catalog=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"catalog\",\"id\":\"row1\"}"));
   check(catalog.optString("id").equals("row1")&&catalog.optString("channel").equals(identity[0])&&catalog.optString("user").equals(identity[1])&&catalog.optString("userId").equals(identity[2]),"catalog is bound to exact current channel/user/userId lease");
  }
  exchange.invoke(null,owner,"{\"op\":\"release\",\"id\":\"row1\"}");
  check("{}".equals(exchange.invoke(null,owner,"{\"op\":\"catalog\",\"id\":\"row1\"}")),"disposed owner cannot read/schedule catalogs");
  exchange.invoke(null,owner,"{\"op\":\"acquire\",\"id\":\"row2\",\"channel\":\"42\",\"user\":\"viewer\"}");
  helper.getMethod("dispose",Object.class).invoke(null,owner);
  check("{}".equals(exchange.invoke(null,owner,"{\"op\":\"catalog\",\"id\":\"row2\"}")),"native invalidation retires remaining rows");
  prefs.put("chat_keywords",true);prefs.put("chat_highlight_vibrate",true);prefs.put("chat_highlight_words","bright");
  Field vibrated=Class.forName("io.github.jordanwoodson.extension.shared.ChatFilter").getDeclaredField("vibrated");vibrated.setAccessible(true);
  Set<?> ids=(Set<?>)vibrated.get(null);
  JSONObject watch=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"watch\",\"id\":\"store1\",\"channel\":\"theburntpeanut_247\"}"));
  check(watch.optBoolean("active"),"actual login-keyed live store acquired");
  for(String channel:new String[]{"bad/room","x".repeat(26),""})check(!new JSONObject((String)exchange.invoke(null,owner,new JSONObject().put("op","watch").put("id","invalid").put("channel",channel).toString())).optBoolean("active"),"invalid store identity rejected");
  check("{}".equals(exchange.invoke(null,owner,new JSONObject().put("op","acquire").put("id","not-numeric").put("channel","theburntpeanut_247").put("user","viewer").toString())),"provider acquisition still requires numeric channel ID");
  JSONObject live=new JSONObject().put("op","live").put("id","store1").put("channel","theburntpeanut_247").put("messageId","one").put("user","viewer").put("body","bright");
  exchange.invoke(null,owner,live.toString());check(ids.size()==1,"eligible new live highlight enters existing vibration dedup");
  exchange.invoke(null,owner,live.toString());check(ids.size()==1,"duplicate retained line does not vibrate twice");
  live.put("messageId","blocked").put("body","bright hide");exchange.invoke(null,owner,live.toString());check(ids.size()==1,"blocked verdict dominates highlight");
  live.put("messageId","wrong-room").put("body","bright").put("channel","43");exchange.invoke(null,owner,live.toString());check(ids.size()==1,"other channel cannot use store owner");
  prefs.put("chat_highlight_vibrate",false);live.put("channel","theburntpeanut_247").put("messageId","off");exchange.invoke(null,owner,live.toString());check(ids.size()==1,"vibration opt-out");
  prefs.put("chat_highlight_vibrate",true);exchange.invoke(null,owner,"{\"op\":\"unwatch\",\"id\":\"store1\"}");
  live.put("messageId","retired");exchange.invoke(null,owner,live.toString());check(ids.size()==1,"retired owner cannot vibrate");
  exchange.invoke(null,owner,"{\"op\":\"acquire\",\"id\":\"token-row\",\"channel\":\"42\",\"user\":\"viewer\"}");
  prefs.put("emotes_7tv",true); // Acquisition occurred with every provider off; polling must remain passive.
  JSONObject before=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"revision\"}"));
  check(before.optBoolean("active")&&before.has("token"),"active provider owners expose a small revision token");
  Class<?> emotes=Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchEmotes");
  Object empty=Array.newInstance(Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchEmotes$Emote"),0);
  Method put=emotes.getDeclaredMethod("put",String.class,empty.getClass(),long.class);put.setAccessible(true);put.invoke(null,"7:42",empty,60000L);
  JSONObject after=new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"revision\"}"));
  check(!before.getString("token").equals(after.getString("token")),"actual cache publication changes token");
  Field loading=emotes.getDeclaredField("LOADING");loading.setAccessible(true);check(((Set)loading.get(null)).isEmpty(),"revision reads never request providers");
  prefs.put("emotes_7tv",false);
  check(!new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"revision\"}")).optBoolean("active"),"opt-out retires polling");
  helper.getMethod("dispose",Object.class).invoke(null,owner);prefs.put("emotes_7tv",true);
  check(!new JSONObject((String)exchange.invoke(null,owner,"{\"op\":\"revision\"}")).optBoolean("active"),"module disposal retires polling");
  System.out.println("PASS Android RN bridge defaults, opt-out, keyword rules, bounded malformed input");
 }
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
}
