import android.app.Activity;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.reflect.*;
import java.util.*;

/** Real ART MotionEvent/View/Handler boundaries; isolated windows and controlled visibility, no account/UI input. */
public final class UiBoundaryProbe {
 static final String ROOT="io.github.jordanwoodson.extension.shared.twitch.";
 static final Map<String,Object> prefs=new HashMap<>();
 static Class<?> player,overlay;static Field current;
 static final class TestWindow extends Window {
  final FrameLayout decor;
  TestWindow(Context c){super(c);decor=new FrameLayout(c);}
  @Override public void addContentView(android.view.View a0,android.view.ViewGroup.LayoutParams a1){}
  @Override public void closeAllPanels(){}
  @Override public void closePanel(int a0){}
  @Override public android.view.View getCurrentFocus(){return null;}
  @Override public android.view.View getDecorView(){return decor;}
  @Override public android.view.LayoutInflater getLayoutInflater(){return null;}
  @Override public int getNavigationBarColor(){return 0;}
  @Override public int getStatusBarColor(){return 0;}
  @Override public int getVolumeControlStream(){return 0;}
  @Override public void invalidatePanelMenu(int a0){}
  @Override public boolean isFloating(){return false;}
  @Override public boolean isShortcutKey(int a0,android.view.KeyEvent a1){return false;}
  @Override protected void onActive(){}
  @Override public void onConfigurationChanged(android.content.res.Configuration a0){}
  @Override public void openPanel(int a0,android.view.KeyEvent a1){}
  @Override public android.view.View peekDecorView(){return decor;}
  @Override public boolean performContextMenuIdentifierAction(int a0,int a1){return false;}
  @Override public boolean performPanelIdentifierAction(int a0,int a1,int a2){return false;}
  @Override public boolean performPanelShortcut(int a0,int a1,android.view.KeyEvent a2,int a3){return false;}
  @Override public void restoreHierarchyState(android.os.Bundle a0){}
  @Override public android.os.Bundle saveHierarchyState(){return null;}
  @Override public void setBackgroundDrawable(android.graphics.drawable.Drawable a0){}
  @Override public void setChildDrawable(int a0,android.graphics.drawable.Drawable a1){}
  @Override public void setChildInt(int a0,int a1){}
  @Override public void setContentView(android.view.View a0){}
  @Override public void setContentView(android.view.View a0,android.view.ViewGroup.LayoutParams a1){}
  @Override public void setContentView(int a0){}
  @Override public void setDecorCaptionShade(int a0){}
  @Override public void setFeatureDrawable(int a0,android.graphics.drawable.Drawable a1){}
  @Override public void setFeatureDrawableAlpha(int a0,int a1){}
  @Override public void setFeatureDrawableResource(int a0,int a1){}
  @Override public void setFeatureDrawableUri(int a0,android.net.Uri a1){}
  @Override public void setFeatureInt(int a0,int a1){}
  @Override public void setNavigationBarColor(int a0){}
  @Override public void setResizingCaptionDrawable(android.graphics.drawable.Drawable a0){}
  @Override public void setStatusBarColor(int a0){}
  @Override public void setTitle(java.lang.CharSequence a0){}
  @Override public void setTitleColor(int a0){}
  @Override public void setVolumeControlStream(int a0){}
  @Override public boolean superDispatchGenericMotionEvent(android.view.MotionEvent a0){return false;}
  @Override public boolean superDispatchKeyEvent(android.view.KeyEvent a0){return false;}
  @Override public boolean superDispatchKeyShortcutEvent(android.view.KeyEvent a0){return false;}
  @Override public boolean superDispatchTouchEvent(android.view.MotionEvent a0){return false;}
  @Override public boolean superDispatchTrackballEvent(android.view.MotionEvent a0){return false;}
  @Override public void takeInputQueue(android.view.InputQueue.Callback a0){}
  @Override public void takeKeyEvents(boolean a0){}
  @Override public void takeSurface(android.view.SurfaceHolder.Callback2 a0){}
  @Override public void togglePanel(int a0,android.view.KeyEvent a1){}
 }
 static class Screen extends Activity {
  final Resources resources;final FrameLayout content;final Window window;boolean dead,pip,multi;
  Screen(Context base) throws Exception {
   attachBaseContext(base);
   Configuration config=new Configuration(base.getResources().getConfiguration());config.orientation=Configuration.ORIENTATION_LANDSCAPE;
   resources=new Resources(base.getAssets(),base.getResources().getDisplayMetrics(),config);
   window=new TestWindow(this);
   Field windowField=Activity.class.getDeclaredField("mWindow");windowField.setAccessible(true);windowField.set(this,window);
   setTheme(android.R.style.Theme_Material);window.getAttributes().screenBrightness=0.5f;
   content=new FrameLayout(this);content.setId(android.R.id.content);content.layout(0,0,1000,600);
   TextureView video=new TextureView(this){@Override public boolean isShown(){return true;}
    @Override public boolean getGlobalVisibleRect(Rect r,Point p){r.set(0,0,1000,600);return true;}};
   content.addView(video,new FrameLayout.LayoutParams(1000,600));video.layout(0,0,1000,600);
  }
  @Override public Resources getResources(){return resources==null?super.getResources():resources;}
  @Override public Window getWindow(){return window;}
  @Override public Object getSystemService(String name){return ACCESSIBILITY_SERVICE.equals(name)||AUDIO_SERVICE.equals(name)?null:super.getSystemService(name);}
  @Override public <T extends View>T findViewById(int id){return id==android.R.id.content?(T)content:null;}
  @Override public boolean isDestroyed(){return dead;}
  @Override public boolean isInPictureInPictureMode(){return pip;}
  @Override public boolean isInMultiWindowMode(){return multi;}
 }
 public static final class Token {public final String text;Token(String text){this.text=text;}@Override public String toString(){return "Token(text="+text+")";}}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Object field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static void message(Screen screen)throws Exception{overlay.getMethod("onMessage",Context.class,Object.class,List.class).invoke(null,screen,null,List.of(new Token("hello")));}
 static void show(Screen screen)throws Exception{Method m=overlay.getDeclaredMethod("show",Activity.class,String.class,String.class);m.setAccessible(true);m.invoke(null,screen,"viewer","hello");}
 static boolean touch(String method,Screen screen,long down,int action,float x,float y)throws Exception {
  MotionEvent event=MotionEvent.obtain(down,down+20,action,x,y,0);
  try{return (boolean)player.getMethod(method,ViewGroup.class,MotionEvent.class).invoke(null,screen.content,event);}finally{event.recycle();}
 }
 public static void main(String[] args) {
  try {
   if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
   android.graphics.fonts.Font font=new android.graphics.fonts.Font.Builder(new java.io.File("/system/fonts/Roboto-Regular.ttf")).build();
   Method makeFace=Typeface.class.getDeclaredMethod("createFromFamilies",String.class,android.graphics.fonts.FontFamily[].class);makeFace.setAccessible(true);
   Typeface face=(Typeface)makeFace.invoke(null,"sans-serif",new android.graphics.fonts.FontFamily[]{new android.graphics.fonts.FontFamily.Builder(font).build()});
   Method fonts=Typeface.class.getDeclaredMethod("setSystemFontMap",Map.class);fonts.setAccessible(true);fonts.invoke(null,Map.of("sans-serif",face,"serif",face,"monospace",face));
   Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
   Context system=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
   SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->prefs.getOrDefault((String)a[0],a[1]));
   Context isolated=new ContextWrapper(system.createPackageContext("com.android.shell",0)){@Override public SharedPreferences getSharedPreferences(String n,int m){return preferences;}};
   Class<?> settings=Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");Field f=settings.getDeclaredField("context");f.setAccessible(true);f.set(null,isolated);
   f=Class.forName(ROOT+"TwitchChatKeywords").getDeclaredField("application");f.setAccessible(true);f.set(null,isolated);
   player=Class.forName(ROOT+"TwitchPlayer");overlay=Class.forName(ROOT+"TwitchChatOverlay");current=overlay.getDeclaredField("overlay");current.setAccessible(true);
   prefs.put("chat_overlay",true);prefs.put("swipe_controls",true);
   Screen first=new Screen(isolated),second=new Screen(isolated);
   Handler handler=new Handler(Looper.getMainLooper());String scenario=args[0];
   if(scenario.equals("overlay-navigation")) {
    Method tagged,channel,dispose;
    try{tagged=overlay.getMethod("onMessage",Context.class,Object.class,List.class,Map.class,Object.class);
     channel=overlay.getMethod("channel",Object.class,Object.class);dispose=overlay.getMethod("dispose",Object.class);}
    catch(NoSuchMethodException e){throw new AssertionError("quiet feeder lifecycle seam missing",e);}
    Object oldOwner=new Object(),newOwner=new Object();
    channel.invoke(null,oldOwner,"1");
    tagged.invoke(null,first,null,List.of(new Token("old")),Map.of("room-id","1"),oldOwner);
    channel.invoke(null,oldOwner,"2");
    handler.post(()->{try{check(current.get(null)==null,"quiet model change retires queued old room");}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}});
    channel.invoke(null,newOwner,"3");
    tagged.invoke(null,first,null,List.of(new Token("new")),Map.of("room-id","3"),newOwner);
    handler.post(()->{try{Object owned=current.get(null);check(owned!=null,"new feeder owns overlay");
      channel.invoke(null,newOwner,"3");dispose.invoke(null,oldOwner);
      handler.post(()->{try{check(current.get(null)==owned,"old teardown and same-channel refresh preserve current owner");
       channel.invoke(null,newOwner,(Object)null);
       handler.post(()->finish(()->check(current.get(null)==null,"null model quietly removes current overlay"),scenario));
      }catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}});
     }catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}});
   } else if(scenario.equals("overlay-recreated")) {
    Method tagged=overlay.getMethod("onMessage",Context.class,Object.class,List.class,Map.class,Object.class);
    Method channel=overlay.getMethod("channel",Object.class,Object.class),dispose=overlay.getMethod("dispose",Object.class);
    Object owner=new Object();
    channel.invoke(null,owner,"1");
    tagged.invoke(null,first,null,List.of(new Token("old")),Map.of("room-id","1"),owner);
    dispose.invoke(null,owner);
    channel.invoke(null,owner,"1");
    tagged.invoke(null,first,null,List.of(new Token("recreated")),Map.of("room-id","1"),owner);
    handler.post(()->finish(()->check(current.get(null)!=null,"new stream model reactivates recreated viewer with same feeder"),scenario));
   } else if(scenario.equals("overlay-channel")) {
    Method tagged;
    try{tagged=overlay.getMethod("onMessage",Context.class,Object.class,List.class,Map.class);}
    catch(NoSuchMethodException e){throw new AssertionError("destination room ownership seam missing",e);}
    tagged.invoke(null,first,null,List.of(new Token("old room")),Map.of("room-id","1"));
    handler.post(()->{try{check(current.get(null)==null,"old room queued message retired before new room render");}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}});
    tagged.invoke(null,first,null,List.of(new Token("new room")),Map.of("room-id","2","source-room-id","99"));
    handler.post(()->finish(()->{View view=(View)current.get(null);check(view!=null,"new room message attached");Field room=view.getClass().getDeclaredField("room");room.setAccessible(true);check(room.get(view).equals("2"),"destination owns shared messages");},scenario));
   } else if(scenario.equals("overlay-unicode")) {
    Method text=overlay.getDeclaredMethod("text",List.class);text.setAccessible(true);
    String actual=(String)text.invoke(null,List.of(new Token("a".repeat(119)+"😀end")));
    check(actual.equals("a".repeat(119)+"😀…"),"overlay truncation keeps supplementary character");finish(()->{},scenario);
   } else if(scenario.equals("overlay-blocked")) {
    prefs.put("chat_keywords",true);prefs.put("chat_blocked_words","hello");message(first);
    handler.post(()->finish(()->check(current.get(null)==null,"blocked message never reaches overlay"),scenario));
   } else if(scenario.equals("overlay-portrait-owner")) {
    show(first);first.resources.getConfiguration().orientation=Configuration.ORIENTATION_PORTRAIT;
    message(first);show(second);Object owned=current.get(null);
    handler.post(()->finish(()->check(current.get(null)==owned,"old portrait callback keeps new owner"),scenario));
   } else if(scenario.equals("overlay-lifecycle")) {
    show(first);View view=(View)current.get(null);check(view.getParent()==first.content,"overlay attached to real view tree");
    check(!view.isClickable()&&!view.isFocusable(),"overlay does not take focus");
    MotionEvent e=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,200,100,0);
    check(!view.dispatchTouchEvent(e)&&!view.onTouchEvent(e),"touch passes through");e.recycle();
    Method add=view.getClass().getDeclaredMethod("add",String.class,String.class);add.setAccessible(true);
    for(int i=0;i<30;i++)add.invoke(view,"viewer","message");
    Field waiting=view.getClass().getDeclaredField("waiting");waiting.setAccessible(true);check(((Collection)waiting.get(view)).size()==20,"overlay queue bounded");
    prefs.put("chat_overlay",false);Method tick=view.getClass().getDeclaredMethod("check");tick.setAccessible(true);tick.invoke(view);
    handler.post(()->finish(()->{check(current.get(null)==null,"idle opt-out detaches");check(view.getParent()==null,"view removed from parent");},scenario));
   } else if(scenario.equals("overlay-off")) {
    message(first);prefs.put("chat_overlay",false);
    handler.post(()->finish(()->check(current.get(null)==null,"queued overlay must recheck opt-out"),scenario));
   } else if(scenario.equals("overlay-owner")) {
    message(first);first.dead=true;show(second);Object owned=current.get(null);check(owned!=null,"new activity owns real overlay view");
    handler.post(()->finish(()->check(current.get(null)==owned,"stale invalid activity must not detach new overlay"),scenario));
   } else if(scenario.equals("gesture-off")||scenario.equals("gesture-pointer")) {
    long down=SystemClock.uptimeMillis();
    check(!touch("swipeIntercept",first,down,MotionEvent.ACTION_DOWN,200,300),"down preserves stock");
    check(touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,200,100),"vertical edge motion claimed");
    if(scenario.equals("gesture-off"))prefs.put("swipe_controls",false);
    else touch("swipeTouch",first,down,MotionEvent.ACTION_POINTER_UP,200,100);
    touch("swipeTouch",first,down,MotionEvent.ACTION_MOVE,200,80);
    check((int)field(player,"swipe")==0,"claimed gesture cancelled on off/pointer loss");
    check(first.window.getAttributes().screenBrightness==0.5f,"cancelled motion never changes brightness");
    finish(()->{},scenario);
   } else if(scenario.equals("gesture-boundaries")) {
    long down=SystemClock.uptimeMillis();
    for(float[] point:new float[][]{{500,300},{200,10},{200,590}}) {
     touch("swipeIntercept",first,++down,MotionEvent.ACTION_DOWN,point[0],point[1]);
     check(!touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,point[0],point[1]-100),"center/status/seekbar stays stock");
    }
    first.pip=true;touch("swipeIntercept",first,++down,MotionEvent.ACTION_DOWN,200,300);
    check(!touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,200,100),"PiP stays stock");first.pip=false;
    first.resources.getConfiguration().orientation=Configuration.ORIENTATION_PORTRAIT;
    touch("swipeIntercept",first,++down,MotionEvent.ACTION_DOWN,200,300);
    check(!touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,200,100),"portrait stays stock");
    finish(()->{},scenario);
   } else if(scenario.equals("gesture-brightness")) {
    long down=SystemClock.uptimeMillis();touch("swipeIntercept",first,down,MotionEvent.ACTION_DOWN,200,300);
    check(touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,200,100),"brightness claim");
    touch("swipeTouch",first,down,MotionEvent.ACTION_MOVE,200,-1000);
    check(first.window.getAttributes().screenBrightness==1f,"brightness upper clamp");
    touch("swipeTouch",first,down,MotionEvent.ACTION_MOVE,200,2000);
    check(first.window.getAttributes().screenBrightness==0.01f,"brightness lower clamp");
    first.resources.getConfiguration().orientation=Configuration.ORIENTATION_PORTRAIT;
    first.content.layout(0,0,999,600);
    check(first.window.getAttributes().screenBrightness==-1f,"portrait layout returns brightness to system");
    finish(()->{},scenario);
   } else if(scenario.equals("gesture-cancel")) {
    long down=SystemClock.uptimeMillis();touch("swipeIntercept",first,down,MotionEvent.ACTION_DOWN,200,300);
    check(touch("swipeIntercept",first,down,MotionEvent.ACTION_MOVE,200,100),"vertical claim");
    check(!touch("swipeTouch",second,down,MotionEvent.ACTION_MOVE,200,80),"other view cannot consume owner gesture");
    touch("swipeTouch",first,down,MotionEvent.ACTION_CANCEL,200,100);
    check((int)field(player,"swipe")==0,"ACTION_CANCEL clears claim");
    touch("swipeIntercept",first,down+100,MotionEvent.ACTION_DOWN,200,300);
    check(!touch("swipeIntercept",first,down+100,MotionEvent.ACTION_MOVE,400,300),"horizontal move keeps stock");
    finish(()->{},scenario);
   } else throw new AssertionError(scenario);
   Looper.loop();
  }catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}
 }
 interface Check {void run()throws Exception;}
 static void finish(Check check,String scenario){try{check.run();System.out.println("PASS "+scenario);System.exit(0);}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}}
}
