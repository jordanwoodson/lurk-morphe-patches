import android.app.AlertDialog;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import io.github.jordanwoodson.extension.shared.twitch.TwitchVod;
import io.github.jordanwoodson.extension.shared.settings.Settings;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@SuppressWarnings("unchecked")
public class TwitchVodRequestsProbe {
    static AtomicInteger calls=new AtomicInteger(),running=new AtomicInteger(),maximum=new AtomicInteger();
    static volatile CountDownLatch gate=new CountDownLatch(1);
    static volatile boolean fail;
    static final List<Object> owners=new ArrayList<>();
    public static class Model {String id;Model(String id){this.id=id;}public String getId(){return id;}}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Object field(String name)throws Exception{Field f=TwitchVod.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void open(View b)throws Exception{Method m=TwitchVod.class.getDeclaredMethod("openChapters",View.class);m.setAccessible(true);m.invoke(null,b);}
    static void waitFor(BooleanSupplier condition,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end){Handler.drain();Thread.sleep(5);}check(condition.getAsBoolean(),message);}
    static Object load(String id){Object p=new Object();owners.add(p);TwitchVod.vodLoaded(p,new Model(id));return p;}
    static View button(Object presenter)throws Exception{
        View player=new View(),overlay=new View(),button=new View();overlay.root=player;button.root=player;
        TwitchVod.playerView(presenter,player);
        ((Map<View,WeakReference<View>>)field("BUTTONS")).put(button,new WeakReference<>(overlay));
        owners.add(player);owners.add(overlay);owners.add(button);return button;
    }
    static void install(){URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){
        protected URLConnection openConnection(URL url){return new HttpURLConnection(url){
            public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
            public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
            Integer status;public int getResponseCode()throws IOException{if(status!=null)return status;calls.incrementAndGet();int n=running.incrementAndGet();maximum.accumulateAndGet(n,Math::max);
                try{if(!gate.await(4,TimeUnit.SECONDS))throw new IOException("test gate expired");status=fail?503:200;return status;}
                catch(InterruptedException e){throw new IOException(e);}finally{running.decrementAndGet();}}
            public InputStream getInputStream(){return new ByteArrayInputStream("chapters".getBytes());}
        };}}:null);}
    static void settled()throws Exception{waitFor(()->running.get()==0,"requests still running");Thread.sleep(60);Handler.drain();}
    public static void main(String[] args)throws Exception{
        Settings.values.put("vod_chapters","true");install();String scenario=args[0];
        if(scenario.equals("queued-player-detach")) {
            List<View> players=new ArrayList<>();
            for(int i=1;i<=10;i++){Object p=load(""+i);View player=new View();players.add(player);owners.add(player);TwitchVod.playerView(p,player);}
            Handler.drain();waitFor(()->calls.get()==2,"two workers did not start");
            for(View player:players)player.detach();
            gate.countDown();settled();Thread.sleep(150);Handler.drain();
            check(calls.get()==2,"detached player prefetch started queued provider activity: "+calls.get());
        }else if(scenario.equals("shared-player-detach")) {
            Object p=load("1"),live=load("1");View retired=new View();TwitchVod.playerView(p,retired);owners.add(retired);
            View b=button(live);open(b);Handler.drain();waitFor(()->calls.get()==1,"shared request did not start");
            retired.detach();gate.countDown();settled();
            check(calls.get()==1&&AlertDialog.shown.size()==1&&AlertDialog.shown.get(0)==b.context,"detaching one owner cancelled another live overlay");
        }else if(scenario.equals("replaced-player-detach")) {
            Object p=load("1");View retired=new View();TwitchVod.playerView(p,retired);owners.add(retired);
            View b=button(p);open(b);Handler.drain();waitFor(()->calls.get()==1,"request did not start");
            retired.detach();gate.countDown();settled();
            check(AlertDialog.shown.size()==1,"old attachment retired the presenter's new player view");
        }else if(scenario.equals("failure-retention")){
            fail=true;gate.countDown();for(int i=1;i<=40;i++){load(""+i);final int expected=i;waitFor(()->calls.get()==expected,"failure fetch missing");settled();}
            load("1");waitFor(()->calls.get()==41,"old failure never evicted");
            check(((Map<?,?>)field("FAILURES")).size()<=32,"failure retention unbounded");
        }else if(scenario.equals("waiters")){
            Object p=load("1");for(int i=0;i<40;i++)open(button(p));waitFor(()->calls.get()>0,"no request");
            check(calls.get()==1,"waiting overlays duplicated network");gate.countDown();settled();
            check(AlertDialog.shown.size()<=8,"unbounded waiting UI references");
            View fresh=button(p);open(fresh);Handler.drain();check(AlertDialog.shown.contains(fresh.context),"evicted waiter cannot reopen from cache");
        }else if(scenario.equals("queued-disable")||scenario.equals("queued-navigation")){
            for(int i=1;i<=10;i++)load(""+i);waitFor(()->calls.get()==2,"workers did not start");
            if(scenario.equals("queued-disable"))Settings.values.put("vod_chapters","false");
            else for(Object p:owners)TwitchVod.vodLoaded(p,new Model("invalid"));
            // Invalid IDs must retire the previous VOD owner too.
            Handler.drain();gate.countDown();settled();Thread.sleep(100);
            check(calls.get()==2,"retired queued requests started provider activity: "+calls.get());
        }else if(scenario.equals("shared")){
            Object p=load("1"),other=load("1");View a=button(p),b=button(other);for(int i=0;i<15;i++){TwitchVod.vodLoaded(p,new Model("1"));open(a);}open(b);
            waitFor(()->calls.get()>0,"request did not start");Thread.sleep(100);
            check(calls.get()==1,"prefetch/open duplicated one VOD: "+calls.get());gate.countDown();
            waitFor(()->AlertDialog.shown.size()==2,"all waiting overlays did not receive one shared result");
            check(AlertDialog.shown.contains(a.context)&&AlertDialog.shown.contains(b.context),"result delivered to wrong overlay");
        }else if(scenario.equals("retry")){
            fail=true;gate.countDown();Object p=load("1");waitFor(()->calls.get()==1,"initial failure missing");settled();
            for(int i=0;i<10;i++)TwitchVod.vodLoaded(p,new Model("1"));Thread.sleep(100);
            check(calls.get()==1,"failure backoff did not suppress repeated calls: "+calls.get());
            fail=false;SystemClock.offset+=16000;TwitchVod.vodLoaded(p,new Model("1"));waitFor(()->calls.get()==2,"failure never retried");settled();
            View b=button(p);open(b);Handler.drain();check(AlertDialog.shown.size()==1,"retry success not cached");
        }else if(scenario.equals("eviction")){
            gate.countDown();for(int i=1;i<=40;i++){load(""+i);final int expected=i;waitFor(()->calls.get()==expected,"fetch missing");settled();}
            load("1");waitFor(()->calls.get()==41,"old cache entry was never evicted");
        }else if(scenario.equals("queue")){
            for(int i=1;i<=40;i++)load(""+i);waitFor(()->calls.get()>0,"no work");Thread.sleep(100);
            check(maximum.get()<=2,"unbounded chapter concurrency: "+maximum.get());gate.countDown();settled();Thread.sleep(200);
            check(calls.get()<=10,"unbounded pending chapter queue: "+calls.get());
        }else{
            Object p=load("1");View b=button(p);open(b);waitFor(()->calls.get()>0,"no initial request");
            if(scenario.equals("navigate"))TwitchVod.vodLoaded(p,new Model("2"));
            if(scenario.equals("detach"))b.attached=false;
            if(scenario.equals("disabled"))Settings.values.put("vod_chapters","false");
            if(scenario.equals("ownership")){View newPlayer=new View();newPlayer.root=b.root;TwitchVod.playerView(new Object(),newPlayer);owners.add(newPlayer);}
            gate.countDown();settled();Thread.sleep(100);Handler.drain();
            check(AlertDialog.shown.isEmpty(),"stale chapters shown after "+scenario);
        }
        gate.countDown();System.out.println("PASS: Twitch chapters "+scenario);System.exit(0);
    }
}
