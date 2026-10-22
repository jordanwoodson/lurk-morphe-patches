import android.content.Context;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.util.Base64;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Actual APK KickHttp -> KickChat -> evaluateJavascript; WebView and transport are boundaries. */
public final class ChatBoundaryProbe {
    static final String CHAT="io.github.jordanwoodson.extension.shared.kick.KickChat";
    static final String HTTP="io.github.jordanwoodson.extension.shared.kick.KickHttp";
    public static final class Capture extends WebView {
        String source, url;
        public Capture(Context c){super(c);}
        @Override public void evaluateJavascript(String js,ValueCallback<String> callback){source=js;}
        @Override public String getUrl(){return url;}
    }
    static Class<?> c(String s)throws Exception{return Class.forName(s);}
    static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=(target instanceof Class?(Class<?>)target:target.getClass()).getMethod(name,types);
        return m.invoke(target instanceof Class?null:target,args);
    }
    static void setting(String key,boolean value)throws Exception{ProbeSupport.setting(key,String.valueOf(value));}
    static String page(String config){return "https://chat.mobile.kick.com/?config="+android.net.Uri.encode(config);}
    static void lifecycle()throws Exception {
        setting("emotes_7tv",true);
        Capture old=(Capture)ProbeSupport.allocate(Capture.class), current=(Capture)ProbeSupport.allocate(Capture.class);
        old.url=current.url=page("{\"channelId\":668}");
        Class<?> chat=c(CHAT);Class<?>[] pageTypes={WebView.class,String.class};
        call(chat,"onPage",pageTypes,old,old.url);
        call(chat,"onPage",pageTypes,current,current.url);
        Map<?,?> pages=(Map<?,?>)ProbeSupport.field(chat,"PAGES").get(null);
        call(chat,"onPageDisposed",new Class<?>[]{WebView.class},old);
        ProbeSupport.check(!pages.containsKey(old) && pages.containsKey(current),"old WebView disposal preserves current page");
        ProbeSupport.check(((java.lang.ref.WeakReference<?>)ProbeSupport.field(chat,"active").get(null)).get()==current,"old disposal preserves current native authority");
        JSONObject options=new JSONObject(current.source.substring(current.source.lastIndexOf("\n(")+2,current.source.length()-2));
        JSONObject end=new JSONObject().put("type","lurk7tvDispose").put("generation","stale")
                .put("channelId",options.get("channelId")).put("slug",options.get("sevenTvChannel"));
        Class<?>[] messageTypes={WebView.class,String.class,String.class};
        call(chat,"onMessage",messageTypes,current,end.toString(),current.url);
        ProbeSupport.check(pages.containsKey(current),"stale disposal cannot invalidate current generation");
        end.put("generation",options.get("generation"));
        call(chat,"onMessage",messageTypes,current,end.toString(),"https://example.invalid/");
        ProbeSupport.check(pages.containsKey(current),"foreign disposal cannot invalidate current generation");
        call(chat,"onMessage",messageTypes,current,end.toString(),current.url);
        ProbeSupport.check(!pages.containsKey(current) && ((java.lang.ref.WeakReference<?>)ProbeSupport.field(chat,"active").get(null)).get()==null,"valid pagehide ends native authority");
        end.put("type","lurk7tvCatalog");call(chat,"onMessage",messageTypes,current,end.toString(),current.url);
        ProbeSupport.check(!pages.containsKey(current),"late catalog cannot resurrect disposed page");
        ProbeSupport.check(Boolean.FALSE.equals(call(chat,"onMessage",messageTypes,current,"{\"type\":\"stock\"}",current.url)),"ordinary stock bridge message falls through");
        call(chat,"onPage",pageTypes,current,current.url);
        call(chat,"onPageDisposed",new Class<?>[]{WebView.class},current);
        ProbeSupport.check(!pages.containsKey(current) && ((java.lang.ref.WeakReference<?>)ProbeSupport.field(chat,"active").get(null)).get()==null,"native destroy ends authority without next page");
        System.out.println("PASS: native WebView lifecycle, scoped disposal and late catalog guards");
    }
    static JSONObject emit(String name,String url)throws Exception {
        Capture capture=(Capture)ProbeSupport.allocate(Capture.class);
        call(c(CHAT),"onPage",new Class<?>[]{WebView.class,String.class},capture,url);
        if(capture.source==null)return null;
        System.out.println("CAPTURE " + name + " " + Base64.encodeToString(capture.source.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
        return new JSONObject(capture.source.substring(capture.source.lastIndexOf("\n(")+2,capture.source.length()-2));
    }
    static Object builder()throws Exception{return c("okhttp3.OkHttpClient$Builder").getConstructor().newInstance();}
    static void response(String url,String json,int status,boolean intercept)throws Exception {
        Object b=builder();
        ProbeSupport.check(call(c(HTTP),"withInterceptor",new Class<?>[]{c("okhttp3.OkHttpClient$Builder")},b)==b,"builder identity");
        Object client=call(b,"build",new Class<?>[]{});
        List<?> interceptors=(List<?>)call(client,"interceptors",new Class<?>[]{});
        ProbeSupport.check(interceptors.size()==(intercept?1:0),"provider interception switch");
        if(!intercept)return;
        Object rb=c("okhttp3.Request$Builder").getConstructor().newInstance();
        call(rb,"url",new Class<?>[]{String.class},url);
        Object request=call(rb,"build",new Class<?>[]{});
        Object body=call(c("okhttp3.ResponseBody"),"create",new Class<?>[]{c("okhttp3.MediaType"),String.class},null,json);
        Object sb=c("okhttp3.Response$Builder").getConstructor().newInstance();
        call(sb,"request",new Class<?>[]{c("okhttp3.Request")},request);
        call(sb,"protocol",new Class<?>[]{c("okhttp3.Protocol")},c("okhttp3.Protocol").getField("HTTP_1_1").get(null));
        call(sb,"code",new Class<?>[]{int.class},status);call(sb,"message",new Class<?>[]{String.class},"fixture");
        call(sb,"body",new Class<?>[]{c("okhttp3.ResponseBody")},body);
        Object response=call(sb,"build",new Class<?>[]{});
        int[] proceeds={0};
        Object chain=Proxy.newProxyInstance(c("okhttp3.Interceptor$Chain").getClassLoader(),new Class<?>[]{c("okhttp3.Interceptor$Chain")},(p,m,a)->{
            if(m.getName().equals("request"))return request;
            if(m.getName().equals("proceed")){ProbeSupport.check(a[0]==request,"request preserved");proceeds[0]++;return response;}
            throw new AssertionError("unexpected chain call " + m);
        });
        Object result=call(interceptors.get(0),"intercept",new Class<?>[]{c("okhttp3.Interceptor$Chain")},chain);
        ProbeSupport.check(result==response && proceeds[0]==1,"response identity and one proceed");
        ProbeSupport.check(json.equals(call(body,"string",new Class<?>[]{})),"peek preserves unread response body");
    }
    public static void main(String[] args){ProbeSupport.run("Kick native response-to-page options",()->{
        try(ZipFile apk=new ZipFile(args[0])){
            String script=new String(apk.getInputStream(apk.getEntry("assets/lurk-chat.js")).readAllBytes(),StandardCharsets.UTF_8);
            // Cache actual APK asset; no Android WebView renderer is needed to capture the bridge call.
            ProbeSupport.field(c(CHAT),"script").set(null,script+"\n");
        }
        for(String key:new String[]{"emotes_7tv","chat_ignore_clears","hide_sub_messages","hide_host_messages","hide_reward_redemptions"})setting(key,true);
        response("https://kick.com/api/v2/channels/xqc","{\"id\":668,\"slug\":\"xqc\",\"user_id\":676}",200,true);
        JSONObject options=emit("full",page("{\"channelId\":668}"));
        ProbeSupport.check(options.getString("sevenTvChannel").equals("xqc") && options.getString("sevenTvUser").equals("676"),"numeric IDs from full response");
        response("https://api.kick.com/api/v1/channels/xqc/info","{\"id\":\"669\",\"slug\":\"xqc\",\"user_id\":\"676\"}",200,true);
        options=emit("info",page("{\"channelId\":\"669\"}"));
        ProbeSupport.check(options.getString("sevenTvUser").equals("676"),"string IDs from info response");
        for(String url:new String[]{"https://evilkick.com/api/v2/channels/xqc","https://kick.com/api/v2/channels/xqc/nope"})
            response(url,"{\"id\":777,\"slug\":\"wrong\",\"user_id\":999}",200,true);
        response("https://kick.com/api/v2/channels/xqc","{\"id\":777,\"slug\":\"wrong\"}",500,true);
        response("https://kick.com/api/v2/channels/xqc","not-json",200,true);
        options=emit("unknown",page("{\"channelId\":777}"));
        ProbeSupport.check(options.isNull("sevenTvChannel") && options.isNull("sevenTvUser"),"unknown and rejected responses use globals only");
        ProbeSupport.check(emit("preview",page("{\"channelId\":668}")+"&preview=true")==null,"gift preview untouched");
        ProbeSupport.check(emit("foreign","https://example.invalid/")==null,"foreign page untouched");
        setting("emotes_7tv",false);response("https://kick.com/api/v2/channels/xqc","{}",200,false);
        options=emit("provider-off",page("malformed"));
        ProbeSupport.check(!options.has("sevenTv") && options.getBoolean("ignoreClears") && options.getJSONArray("hide").length()==4,"independent native switches with provider off");
        setting("emotes_7tv",true);
        options=emit("malformed",page("malformed"));
        ProbeSupport.check(options!=null && options.getBoolean("ignoreClears") && options.getJSONArray("hide").length()==4,"malformed config must retain unrelated ignoreClears/hide options");
        ProbeSupport.check(options.getBoolean("sevenTv") && options.isNull("sevenTvChannel"),"malformed config falls back to global emotes");
        options=emit("missing-config","https://chat.mobile.kick.com/");
        ProbeSupport.check(options.isNull("sevenTvChannel"),"missing config uses globals");
        options=emit("invalid-id",page("{\"channelId\":{}}"));
        ProbeSupport.check(options.isNull("sevenTvChannel"),"invalid ID type uses globals");
        setting("chat_ignore_clears",false);setting("hide_sub_messages",false);setting("hide_host_messages",false);setting("hide_reward_redemptions",false);
        options=emit("provider-only",page("{\"channelId\":668}"));
        ProbeSupport.check(!options.has("ignoreClears") && !options.has("hide"),"provider does not enable other switches");
        setting("emotes_7tv",false);ProbeSupport.check(emit("all-off",page("{}"))==null,"all switches off does not inject");
        String[] keys={"chat_ignore_clears","hide_sub_messages","hide_host_messages","hide_reward_redemptions"};
        String[] names={"clears-only","subs-only","hosts-only","rewards-only"};
        int[] selectors={0,2,1,1};
        for(int i=0;i<keys.length;i++){
            setting(keys[i],true);options=emit(names[i],page("{}"));
            ProbeSupport.check(!options.has("sevenTv") && options.has("ignoreClears")== (i==0),"independent clear/provider switches " + names[i]);
            ProbeSupport.check((options.has("hide")?options.getJSONArray("hide").length():0)==selectors[i],"independent hide selectors " + names[i]);
            setting(keys[i],false);
        }
        lifecycle();
    });}
}
