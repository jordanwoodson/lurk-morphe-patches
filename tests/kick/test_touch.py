#!/usr/bin/env python3
"""Run Kick's production player touch handling (KickTouch) on the JVM with a small window double.

Usage: python3 tests/kick/test_touch.py
Requires the configured JDK; no APK, Android SDK or device. The probe builds a window with a player view (an
instance of the class the patch hands KickTouch) next to a chat column, sends the touch events MainActivity's
dispatchTouchEvent would, and checks which ones KickTouch takes.

Player gestures: off and unregistered take nothing; in landscape a vertical swipe on the right changes the media
volume and on the left the window's brightness, the app gets one cancel, and the brightness goes back to the
system's at the next touch in portrait; the middle of the video, its top and bottom edges, portrait,
picture-in-picture, TalkBack, a sideways drag, a small player, a hidden player and a touch beside the player are
left to Kick; a second finger ends a swipe.
"""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'shared'))
from host_java import run  # noqa: E402

DOUBLES = {
    'android/content/res/Configuration.java': '''package android.content.res; public class Configuration {
        public static final int ORIENTATION_PORTRAIT=1,ORIENTATION_LANDSCAPE=2; public int orientation=ORIENTATION_PORTRAIT; }''',
    'android/content/res/Resources.java': '''package android.content.res; public class Resources {
        public final Configuration configuration=new Configuration();
        public Configuration getConfiguration(){return configuration;}
        public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();} }''',
    'android/app/Activity.java': '''package android.app; public class Activity extends android.content.ContextWrapper {
        public boolean pip; public final android.content.res.Resources resources=new android.content.res.Resources();
        public final android.view.Window window=new android.view.Window();
        public final android.media.AudioManager audio=new android.media.AudioManager();
        public final android.view.accessibility.AccessibilityManager accessibility=new android.view.accessibility.AccessibilityManager();
        public boolean isInPictureInPictureMode(){return pip;} public android.view.Window getWindow(){return window;}
        @Override public android.content.res.Resources getResources(){return resources;}
        @Override public Object getSystemService(String s){return s.equals("audio")?audio:s.equals("accessibility")?accessibility:null;} }''',
    'android/view/Window.java': '''package android.view; public class Window {
        public final ViewGroup decor=new ViewGroup(); public int cancels;
        public WindowManager.LayoutParams attributes=new WindowManager.LayoutParams();
        public WindowManager.LayoutParams getAttributes(){return attributes;}
        public void setAttributes(WindowManager.LayoutParams p){attributes=p;} public View getDecorView(){return decor;}
        public boolean superDispatchTouchEvent(MotionEvent e){if(e.action==MotionEvent.ACTION_CANCEL)cancels++;return true;} }''',
    'android/view/View.java': '''package android.view; public class View implements ViewParent {
        public static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO=2;
        public int left,top,width,height,visibility; public ViewGroup parent; public int haptics;
        public View at(int l,int t,int w,int h){left=l;top=t;width=w;height=h;return this;}
        public int getWidth(){return width;} public int getHeight(){return height;}
        public int getVisibility(){return visibility;} public void setVisibility(int i){visibility=i;}
        public boolean isShown(){return visibility==VISIBLE&&(parent==null||parent.isShown());}
        public ViewParent getParent(){return parent;} public void getLocationOnScreen(int[] xy){xy[0]=left;xy[1]=top;}
        public boolean performHapticFeedback(int i){haptics++;return true;}
        public void setBackground(Object o){} public void setImportantForAccessibility(int i){}
        public void setPadding(int l,int t,int r,int b){} }''',
    'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
        public static class LayoutParams {public static final int WRAP_CONTENT=-2;}
        public final java.util.List<View> children=new java.util.ArrayList<>();
        public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);}
        public void addView(View v,Object p){children.add(v);v.parent=this;} public void add(View v){addView(v,null);}
        public void removeView(View v){children.remove(v);v.parent=null;} }''',
    'android/view/MotionEvent.java': '''package android.view; public class MotionEvent {
        public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
        public int action,pointers=1; public float x,y; public long downTime;
        public MotionEvent(int action,float x,float y){this.action=action;this.x=x;this.y=y;}
        public static MotionEvent obtain(MotionEvent e){MotionEvent c=new MotionEvent(e.action,e.x,e.y);c.pointers=e.pointers;return c;}
        public static MotionEvent obtain(long down,long time,int action,float x,float y,int meta){
          MotionEvent c=new MotionEvent(action,x,y);c.downTime=down;return c;}
        public void setAction(int a){action=a;} public void recycle(){}
        public int getActionMasked(){return action;} public int getPointerCount(){return pointers;}
        public int getPointerId(int i){return 0;} public int findPointerIndex(int i){return 0;}
        public long getDownTime(){return downTime;} public float getX(){return x;} public float getY(){return y;}
        public float getX(int i){return x;} public float getY(int i){return y;}
        public float getRawX(){return x;} public float getRawY(){return y;} }''',
    'android/media/AudioManager.java': '''package android.media; public class AudioManager {
        public static final int STREAM_MUSIC=3; public int volume=5;
        public int getStreamVolume(int i){return volume;} public int getStreamMaxVolume(int i){return 15;}
        public void setStreamVolume(int s,int v,int f){volume=v;} }''',
    'android/view/accessibility/AccessibilityManager.java': '''package android.view.accessibility;
        public class AccessibilityManager {public boolean talkBack; public boolean isTouchExplorationEnabled(){return talkBack;} }''',
    'android/widget/TextView.java': '''package android.widget; public class TextView extends android.view.View {
        public String text; public TextView(android.content.Context c){} public void setTextColor(int c){}
        public void setTextSize(int s){} public void setText(String s){text=s;} }''',
}

PROBE = r'''
import android.app.Activity;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import io.github.jordanwoodson.extension.shared.kick.KickTouch;
import io.github.jordanwoodson.extension.shared.settings.Settings;

public class KickTouchProbe {
  /** Stands for KickIvsPlayerV2View, the class the patch passes to KickTouch. */
  static class PlayerView extends ViewGroup {}
  static int failures;
  static void check(boolean ok,String what){System.out.println((ok?"ok  ":"FAIL ")+what);if(!ok)failures++;}

  static Activity activity; static PlayerView player;
  /** A 1600x720 landscape window: the player in the left two thirds, the chat column beside it. */
  static void window(){
    activity=new Activity(); activity.resources.configuration.orientation=Configuration.ORIENTATION_LANDSCAPE;
    activity.window.decor.at(0,0,1600,720);
    ViewGroup screen=new ViewGroup(); screen.at(0,0,1600,720); activity.window.decor.add(screen);
    player=new PlayerView(); player.at(0,0,1066,720); screen.add(player);
    View chat=new View(); chat.at(1066,0,534,720); screen.add(chat);
  }
  static boolean send(int action,float x,float y){return KickTouch.dispatch(activity,new MotionEvent(action,x,y),PlayerView.class);}
  /** Down at (x, y), then moves by (dx, dy) in four steps and up; returns how many events KickTouch took. */
  static int drag(float x,float y,float dx,float dy){
    int taken=send(MotionEvent.ACTION_DOWN,x,y)?1:0;
    for(int i=1;i<=4;i++)if(send(MotionEvent.ACTION_MOVE,x+dx*i/4,y+dy*i/4))taken++;
    if(send(MotionEvent.ACTION_UP,x+dx,y+dy))taken++;
    return taken;
  }
  static boolean untouched(){return activity.audio.volume==5&&activity.window.attributes.screenBrightness<0&&activity.window.cancels==0;}

  public static void main(String[] a){
    gestures();
    if(failures>0){System.out.println("FAIL: "+failures+" check(s)");System.exit(1);}
    System.out.println("PASS: Kick player touch");
  }

  static void gestures(){
    window(); Settings.values.clear();
    check(drag(900,400,0,-240)==0&&untouched(),"unregistered: a swipe on the right is Kick's");
    Settings.values.put("player_gestures","false");
    check(drag(900,400,0,-240)==0&&drag(100,400,0,-240)==0&&untouched(),"off: swipes on both sides are Kick's");

    Settings.values.put("player_gestures","true");
    window();
    check(!send(MotionEvent.ACTION_DOWN,900,400),"on: the touch down still reaches Kick");
    check(!send(MotionEvent.ACTION_MOVE,900,396)&&activity.window.cancels==0,"a move inside the touch slop is Kick's");
    check(send(MotionEvent.ACTION_MOVE,900,340)&&activity.window.cancels==1,"past the slop it becomes a swipe and Kick gets one cancel");
    // A third of the player's height from where the swipe was claimed: a third of the 15 volume steps.
    check(send(MotionEvent.ACTION_MOVE,900,100)&&activity.audio.volume==10&&activity.window.cancels==1,"up on the right raises the media volume: "+activity.audio.volume);
    check(send(MotionEvent.ACTION_MOVE,900,580)&&activity.audio.volume==0,"down lowers it, not below zero: "+activity.audio.volume);
    check(send(MotionEvent.ACTION_UP,900,580),"the swipe's end is taken too");
    check(activity.window.attributes.screenBrightness<0,"the right side leaves the brightness alone");
    check(!send(MotionEvent.ACTION_DOWN,900,400)&&!send(MotionEvent.ACTION_UP,900,400),"a tap afterwards is Kick's");

    window();
    check(drag(100,500,0,-360)==5,"a swipe on the left is taken after the touch down");
    float brightness=activity.window.attributes.screenBrightness;
    // From the system's 128 of 255, up by the 270 of 720 px moved after the swipe was claimed.
    check(brightness>0.87f&&brightness<0.885f&&activity.audio.volume==5,"up on the left raises the window's brightness: "+brightness);
    activity.resources.configuration.orientation=Configuration.ORIENTATION_PORTRAIT;
    send(MotionEvent.ACTION_DOWN,100,500); send(MotionEvent.ACTION_UP,100,500);
    check(activity.window.attributes.screenBrightness<0,"back in portrait the next touch hands brightness back to the system");

    window();
    check(drag(533,400,0,-240)==0&&untouched(),"the middle of the video keeps Kick's own swipes");
    check(drag(900,60,0,300)==0&&untouched(),"the top edge is Kick's");
    check(drag(900,650,0,-300)==0&&untouched(),"the bottom edge (seek bar) is Kick's");
    check(drag(1300,400,0,-240)==0&&untouched(),"a swipe on the chat column is Kick's");
    check(drag(900,400,-300,-40)==0&&untouched(),"a sideways drag is Kick's");
    send(MotionEvent.ACTION_DOWN,900,400); send(MotionEvent.ACTION_MOVE,860,400);
    check(!send(MotionEvent.ACTION_MOVE,860,200)&&untouched(),"turning upwards after a sideways start stays Kick's");
    send(MotionEvent.ACTION_UP,860,200);

    window(); activity.resources.configuration.orientation=Configuration.ORIENTATION_PORTRAIT;
    check(drag(900,400,0,-240)==0&&untouched(),"portrait: nothing is taken");
    window(); activity.pip=true;
    check(drag(900,400,0,-240)==0&&untouched(),"picture-in-picture: nothing is taken");
    window(); activity.accessibility.talkBack=true;
    check(drag(900,400,0,-240)==0&&untouched(),"TalkBack: nothing is taken");
    window(); player.at(0,0,700,400);
    check(drag(600,200,0,-120)==0&&untouched(),"a player under half the window wide: nothing is taken");
    window(); player.visibility=View.GONE;
    check(drag(900,400,0,-240)==0&&untouched(),"a hidden player: nothing is taken");
    window(); MotionEvent two=new MotionEvent(MotionEvent.ACTION_DOWN,900,400); two.pointers=2;
    check(!KickTouch.dispatch(activity,two,PlayerView.class)&&!send(MotionEvent.ACTION_MOVE,900,200)&&untouched(),"two fingers down: nothing is taken");

    window();
    send(MotionEvent.ACTION_DOWN,900,400); send(MotionEvent.ACTION_MOVE,900,300);
    check(send(MotionEvent.ACTION_POINTER_DOWN,900,300),"a second finger ends a swipe");
    int volume=activity.audio.volume;
    check(!send(MotionEvent.ACTION_MOVE,900,100)&&activity.audio.volume==volume,"and the rest of that touch is Kick's");
    check(!KickTouch.dispatch(null,new MotionEvent(0,0,0),PlayerView.class)&&!KickTouch.dispatch(activity,null,PlayerView.class)
        &&!KickTouch.dispatch(activity,new MotionEvent(0,900,400),null),"missing arguments take nothing");
  }
}
'''


def main():
    run('KickTouchProbe', ['kick/KickTouch.java'], PROBE, extra=DOUBLES)


if __name__ == '__main__':
    main()
