import android.content.Context;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;

/** Executes the emitted click/reload bridges and stock x4 on ART; only platform/player edges are doubles. */
public final class RefreshProbe {
    private static final String EXT = "io.github.jordanwoodson.extension.shared.rumble.RumblePlayer";
    private static final String PLAYER = "com.rumble.videoplayer.player.a";
    interface Body { Object call(Method m, Object[] args) throws Throwable; }
    static Object proxy(String type, Body body) throws Exception {
        Class<?> c = Class.forName(type);
        return Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p,m,a) -> {
            switch(m.getName()) {
                case "equals": return p == a[0];
                case "hashCode": return System.identityHashCode(p);
                case "toString": return "RefreshProbe boundary " + type;
                default: return body.call(m,a);
            }
        });
    }
    static Object state(String type, Object[] value) throws Exception {
        return proxy(type, (m,a) -> {
            if(m.getName().equals("getValue")) return value[0];
            if(m.getName().equals("setValue")){ value[0]=a[0]; return null; }
            throw new AssertionError("unexpected state call " + m);
        });
    }
    static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Method m=(target instanceof Class ? (Class<?>)target : target.getClass()).getDeclaredMethod(name,types);
        m.setAccessible(true);
        try { return m.invoke(target instanceof Class ? null : target,args); }
        catch(InvocationTargetException e) {
            Throwable t=e.getCause(); if(t instanceof Error)throw (Error)t;
            if(t instanceof Exception)throw (Exception)t; throw e;
        }
    }
    static Object ext(String name,Class<?>[] types,Object...args)throws Exception {
        return call(Class.forName(EXT),name,types,args);
    }
    static long videoId;
    static final class Fixture {
        final Object player, source, video, callback;
        final List<String> events=new ArrayList<>();
        final Object[] status;
        long position; int saves, logs; Throwable fail; RuntimeException loggerFailure;
        Fixture(Context context,long position)throws Exception {
            this.position=position;
            player=ProbeSupport.allocate(Class.forName(PLAYER));
            video=ProbeSupport.allocate(Class.forName("Ng.o"));
            ProbeSupport.set(video,"a",++videoId);
            source=Class.forName("Pg.l").getConstructor(String.class,String.class,int.class,int.class,String.class,String.class,boolean.class)
                .newInstance("https://example.invalid/refresh.m3u8","hls",720,1234,"720p","",true);
            callback=proxy("kotlin.jvm.functions.Function2",(m,a)->{saves++;return null;});
            status=new Object[]{Class.forName("Pg.j$a").getConstructor(boolean.class).newInstance(false)};
            ProbeSupport.set(player,"f",callback);
            ProbeSupport.set(player,"q",proxy("kotlin.jvm.functions.Function2",(m,a)->{
                logs++; if(loggerFailure!=null)throw loggerFailure;
                throw new AssertionError("unexpected stock reload exception",(Throwable)a[1]);
            }));
            ProbeSupport.set(player,"l1",state("Z0.G2",status));
            ProbeSupport.set(player,"k1",state("Z0.a1",status));
            ProbeSupport.set(player,"H1",state("Z0.G2",new Object[]{ProbeSupport.field(Class.forName("Pg.k"),"d").get(null)}));
            ProbeSupport.set(player,"I1",state("Z0.a1",new Object[]{false}));
            ProbeSupport.set(player,"x0",ProbeSupport.field(Class.forName("Pg.i"),"F").get(null));
            ProbeSupport.set(player,"e0",Class.forName("Mg.a").getConstructor(Context.class).newInstance(context));
            ProbeSupport.set(player,"x",proxy("androidx.media3.exoplayer.ExoPlayer",(m,a)->{
                switch(m.getName()) {
                    case "M1": return this.position;
                    case "I0":
                        events.add("load");
                        ProbeSupport.check("https://example.invalid/refresh.m3u8".equals(
                            ProbeSupport.get(ProbeSupport.get(a[0],"b"),"a").toString()),"real media item retains source URL");
                        if(fail!=null)throw fail; this.position=0; return null;
                    case "y": events.add("prepare");return null;
                    case "w0": ProbeSupport.check(Boolean.TRUE.equals(a[0]),"playWhenReady true");events.add("play");return null;
                    case "b0": this.position=(Long)a[0];events.add("seek");return null;
                    default: throw new AssertionError("unexpected Exo boundary " + m);
                }
            }));
            // Execute the injected video-registration hook through the stock chooser entry.
            ProbeSupport.set(player,"h0",ProbeSupport.allocate(Class.forName("Mg.e")));
            call(player,"n4",new Class<?>[]{Class.forName("Ng.o")},video);
            ProbeSupport.set(player,"y0",source);
        }
        void reload()throws Exception {call(Class.forName(PLAYER),"lurkReload",new Class<?>[]{Object.class},player);}
        void restored()throws Exception {
            ProbeSupport.check(ProbeSupport.get(player,"f")==callback,"original quality callback restored after reload");
            ProbeSupport.check(saves==0,"refresh does not persist a quality selection");
            ProbeSupport.check(ProbeSupport.get(player,"y0")==source,"original source retained");
        }
        void click()throws Exception {
            Object modifier=errorModifier(modifierCompanion(),video);
            Object onClick=ProbeSupport.get(modifier,"h");
            Class.forName("kotlin.jvm.functions.Function0").getMethod("invoke").invoke(onClick);
        }
    }
    /** Run the actual error-panel prefix, stopping at the first Composer observation of its modifier. */
    static Object errorModifier(Object modifier,Object video)throws Exception {
        Object[] composer={null}, captured={null};
        RuntimeException stop=new RuntimeException("stop before Compose rendering");
        composer[0]=proxy("Z0.r",(m,a)->{
            if(m.getName().equals("j"))return composer[0];
            if(m.getName().equals("Z")){captured[0]=a[0];throw stop;}
            throw new AssertionError("unexpected Composer prefix call " + m);
        });
        Object fn=proxy("kotlin.jvm.functions.Function0",(m,a)->null);
        try {
            call(Class.forName("Sg.E"),"b",new Class<?>[]{Class.forName("t1.o"),Class.forName("Ng.o"),
                Class.forName("Rg.Q"),Class.forName("kotlin.jvm.functions.Function0"),
                Class.forName("kotlin.jvm.functions.Function0"),Class.forName("Z0.r"),int.class,int.class},
                modifier,video,ProbeSupport.field(Class.forName("Rg.Q"),"B").get(null),fn,fn,composer[0],0,0);
            throw new AssertionError("error panel did not reach Composer boundary");
        } catch(RuntimeException e){if(e!=stop)throw e;}
        ProbeSupport.check(captured[0]!=null,"error panel passes modified parameter to Composer");
        return captured[0];
    }
    public static void main(String[] args) {
        ProbeSupport.run("actual refresh click, stock seek, quality preservation and exceptional restoration",()->{
            if(Looper.myLooper()==null)Looper.prepareMainLooper();
            Object thread=call(Class.forName("android.app.ActivityThread"),"systemMain",new Class<?>[]{});
            Context context=(Context)call(thread,"getSystemContext",new Class<?>[]{});
            // Keep the real audio Context local; extension settings must use registry defaults.
            ProbeSupport.set(thread,"mInitialApplication",null);
            Object noop=ext("noQualityCallback",new Class<?>[]{});
            Class.forName("kotlin.jvm.functions.Function2").getMethod("r",Object.class,Object.class).invoke(noop,null,false);
            // No registered player is harmless, including the actual click lambda.
            Object empty=ext("refreshable",new Class<?>[]{Object.class,Object.class},modifierCompanion(),null);
            Class.forName("kotlin.jvm.functions.Function0").getMethod("invoke").invoke(ProbeSupport.get(empty,"h"));
            for(long position:new long[]{0,123456,4294967400L}) {
                Fixture a=new Fixture(context,position), b=new Fixture(context,42);
                a.click(); a.restored();
                ProbeSupport.check(a.position==position,"reload restores full long position " + position);
                ProbeSupport.check(a.events.equals(Arrays.asList("load","prepare","play","seek")),"stock reload order " + a.events);
                ProbeSupport.check(b.events.isEmpty(),"video selects its player despite lastPlayer pointing elsewhere");
                ProbeSupport.check(Class.forName("Pg.j$g").isInstance(a.status[0]),"fatal state becomes loading");
                a.events.clear();a.click();a.restored();
                ProbeSupport.check(a.events.size()==4,"repeated tap reloads once");
                call(a.player,"x4",new Class<?>[]{Class.forName("Pg.l")},a.source);
                ProbeSupport.check(a.saves==1,"direct stock quality switch calls restored callback");
            }
            Fixture f=new Fixture(context,123456);
            ProbeSupport.set(f.player,"y0",null);f.reload();
            ProbeSupport.check(f.events.isEmpty() && ProbeSupport.get(f.player,"f")==f.callback,"null source is a no-op");
            ProbeSupport.set(f.player,"y0",f.source);
            f.fail=new IllegalStateException("player boundary failure");
            ProbeSupport.set(f.player,"q",proxy("kotlin.jvm.functions.Function2",(m,a)->{f.logs++;return null;}));
            f.reload();f.restored();ProbeSupport.check(f.logs==1,"stock Exception catch control");
            f.loggerFailure=new IllegalStateException("logger sentinel");
            ProbeSupport.set(f.player,"q",proxy("kotlin.jvm.functions.Function2",(m,a)->{throw f.loggerFailure;}));
            try {f.reload();throw new AssertionError("logger failure must escape");}
            catch(IllegalStateException e){ProbeSupport.check(e==f.loggerFailure,"original logger failure preserved");}
            f.restored();
            f.fail=new AssertionError("Exo Error sentinel");
            try {f.reload();throw new AssertionError("Error must escape");}
            catch(AssertionError e){ProbeSupport.check(e==f.fail,"original Error preserved");}
            f.restored();
            Object modifier=modifierCompanion();ProbeSupport.setting("refresh_stream","false");
            ProbeSupport.check(ext("refreshable",new Class<?>[]{Object.class,Object.class},modifier,f.video)==modifier,"off preserves exact modifier");
            ProbeSupport.check(errorModifier(modifier,f.video)==modifier,"off emitted error panel has no refresh click");
        });
    }
    private static Object modifierCompanion()throws Exception {
        Class<?> modifier=Class.forName("t1.o");
        for(Field f:modifier.getDeclaredFields())if(Modifier.isStatic(f.getModifiers()) && modifier.isAssignableFrom(f.getType())){f.setAccessible(true);return f.get(null);}
        throw new AssertionError("no Modifier companion");
    }
}
