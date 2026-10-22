import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;


/** Real scheduling/cache/image decisions; controlled HTTP tokens and Android drawing only. */
public final class PronounCompletionProbe {
 static java.util.function.Consumer<Boolean> enabled=value->{try{
  Object values=Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings").getField("values").get(null);
  values.getClass().getMethod("put",Object.class,Object.class).invoke(values,"badges_pronouns",String.valueOf(value));
 }catch(Exception e){throw new RuntimeException(e);}};
 static java.util.function.Function<Boolean,String> response=names->names?"N":"U";
 static final String ROOT="io.github.jordanwoodson.extension.shared.twitch.";
 static Object field(String type,String name)throws Exception{Field f=Class.forName(ROOT+type).getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 interface Condition {boolean get()throws Exception;}
 static void await(Condition condition,String why)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.get()&&System.nanoTime()<end)Thread.sleep(5);check(condition.get(),why);}
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  boolean namesFirst=args[0].startsWith("names"),retired=args[0].endsWith("retired"),off=args[0].endsWith("off"),shared=args[0].endsWith("native");
  CountDownLatch namesRelease=new CountDownLatch(1),userRelease=new CountDownLatch(1),entered=new CountDownLatch(2);
  URL.setURLStreamHandlerFactory(p->"https".equals(p)?new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
   public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public int getResponseCode(){return 200;}
   public InputStream getInputStream()throws IOException{boolean names=url.getPath().endsWith("/pronouns");entered.countDown();try{if(!(names?namesRelease:userRelease).await(4,TimeUnit.SECONDS))throw new IOException("fixture response timeout");}catch(InterruptedException e){throw new IOException(e);}return new ByteArrayInputStream(response.apply(names).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  };}}:null);
  enabled.accept(true);AtomicBoolean active=new AtomicBoolean(true);
  Class<?> type=Class.forName(ROOT+"TwitchPronouns");Method request=type.getDeclaredMethod("requestRn",String.class,BooleanSupplier.class);request.setAccessible(true);
  request.invoke(null,"alice",(BooleanSupplier)active::get);check(entered.await(3,TimeUnit.SECONDS),"both requests started");
  if(shared){Method badge=type.getDeclaredMethod("badge",String.class);badge.setAccessible(true);badge.invoke(null,"alice");}
  ThreadPoolExecutor lookups=(ThreadPoolExecutor)field("TwitchPronouns","LOOKUPS"),pool=(ThreadPoolExecutor)field("TwitchThirdParty","POOL");
  (namesFirst?namesRelease:userRelease).countDown();
  await(()->namesFirst?field("TwitchPronouns","names")!=null:((Map<?,?>)field("TwitchPronouns","USERS")).containsKey("alice"),"first response published");
  await(()->(namesFirst?pool:lookups).getActiveCount()==0,"first response fully completed before releasing second");
  if(retired||shared)active.set(false);if(off)enabled.accept(false);
  (namesFirst?userRelease:namesRelease).countDown();
  await(()->lookups.getActiveCount()==0&&pool.getActiveCount()==0&&lookups.getQueue().isEmpty()&&pool.getQueue().isEmpty(),"completion workers idle");
  Object user=((Map<?,?>)field("TwitchPronouns","USERS")).get("alice");Field primary=user.getClass().getDeclaredField("primary");primary.setAccessible(true);check("hehim".equals(primary.get(user)),"valid user result");
  check(((Map<?,?>)field("TwitchPronouns","names")).containsKey("hehim"),"valid names result");
  Method cached=type.getDeclaredMethod("cachedRn",String.class);cached.setAccessible(true);String[] badge=(String[])cached.invoke(null,"alice");
  boolean expected=!retired&&!off;check(((Map<?,?>)field("TwitchPronouns","IMAGES")).isEmpty()!=expected,"only still-owned completion generates image");
  check((badge!=null)==expected,"successful active completion reaches passive snapshot in either order");
  if(expected)check("he/him".equals(badge[0]),"correct generated label");
  check(((Set<?>)field("TwitchPronouns","PENDING")).isEmpty()&&((Map<?,?>)field("TwitchPronouns","DEMAND")).isEmpty(),"request demand cleaned");
  check(pool.getQueue().isEmpty()&&lookups.getQueue().isEmpty(),"passive read schedules no work");
  pool.shutdown();lookups.shutdown();System.out.println("PASS pronoun completion "+args[0]);
 }
}
