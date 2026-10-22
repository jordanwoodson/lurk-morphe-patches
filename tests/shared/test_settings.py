#!/usr/bin/env python3
"""Run the production settings framework on the JVM against real registries of all three apps.

    python3 tests/shared/test_settings.py                      # the stored registries (host suite)
    python3 tests/shared/test_settings.py <patched.apk> ...    # the registries inside patched APKs
    python3 tests/shared/test_settings.py --update <patched.apk> ...   # refresh tests/shared/registry/

Each app's settings patch writes SettingsScreen.json() into Settings.registryJson() as a dex string.
tests/shared/registry/<app>.json holds that string as extracted from a patched APK; --update rewrites
them. The checks compile the real Settings, Registry and Logger against org.json and SharedPreferences
doubles (no Android SDK, APK or device) and cover:

- the JSON as SettingsScreen.json() writes it: unique keys, section order, titles sorted, each child
  right after its parent, choice defaults among the choices, switch defaults, restart flags;
- Registry.parse round trip: every field Java reads equals what Python reads from the same JSON;
- defaults, stored values and wrongly typed stored values for every registered key;
- failing closed: a missing, malformed, partly malformed or empty registry turns every switch off
  and every choice and text to "" (0 as a number), whatever was stored.
"""
import json
import re
import sys
import tempfile
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from host_java import PACKAGE, run  # noqa: E402
from json_double import JSON_DOUBLE  # noqa: E402

HERE = Path(__file__).resolve().parent
REGISTRIES = HERE / 'registry'
SECTIONS = ['Privacy', 'Interface', 'Playback', 'Chat', 'Premium', 'Advanced']
SETTINGS = PACKAGE + 'settings/'

# Platform boundaries the settings classes touch. Context hands out map-backed preferences per name.
ANDROID = {
    'android/util/Log.java': '''package android.util; public final class Log {
        public static int d(String t,String m){return 0;} public static int i(String t,String m){return 0;}
        public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;}
        public static int e(String t,String m){return 0;} public static int e(String t,String m,Throwable e){return 0;} }''',
    'android/os/Bundle.java': 'package android.os; public class Bundle {}',
    'android/os/Build.java': '''package android.os; public class Build {
        public static final String MANUFACTURER="Google",MODEL="Pixel Test";
        public static class VERSION {public static final int SDK_INT=36; public static final String RELEASE="16";}
        public static class VERSION_CODES {public static final int N_MR1=25,O=26,P=28,Q=29,R=30,S=31,TIRAMISU=33;} }''',
    'android/content/Context.java': '''package android.content; public class Context {
        public static final int MODE_PRIVATE=0; public static final String ACCESSIBILITY_SERVICE="accessibility",AUDIO_SERVICE="audio";
        public static java.util.function.Function<String,SharedPreferences> preferences;
        public Object getSystemService(String s){return null;} public <T>T getSystemService(Class<T> c){return null;}
        public Object getContentResolver(){return null;}
        public android.content.res.Resources getResources(){return new android.content.res.Resources();}
        public SharedPreferences getSharedPreferences(String name,int mode){return preferences.apply(name);}
        public Context getApplicationContext(){return this;}
        public String getPackageName(){return "app.test";}
        public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
        public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
        public void startActivity(Intent intent){} }''',
    'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
        interface OnSharedPreferenceChangeListener {void onSharedPreferenceChanged(SharedPreferences p,String key);}
        interface Editor {Editor putString(String k,String v);Editor putInt(String k,int v);Editor putLong(String k,long v);
          Editor putBoolean(String k,boolean v);Editor remove(String k);Editor clear();boolean commit();void apply();}
        java.util.Map<String,?> getAll(); String getString(String k,String d); int getInt(String k,int d);
        long getLong(String k,long d); boolean getBoolean(String k,boolean d); boolean contains(String k); Editor edit();
        void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
        void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); }''',
    'android/content/ComponentName.java': 'package android.content; public class ComponentName {}',
    'android/content/Intent.java': '''package android.content; public class Intent {
        public static final String ACTION_VIEW="android.intent.action.VIEW"; public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
        public Intent(){} public Intent(String a){} public Intent(Context c,Class<?> t){}
        public Intent addFlags(int f){return this;} public Intent setClassName(Context c,String n){return this;}
        public ComponentName getComponent(){return null;}
        public static Intent makeRestartActivityTask(ComponentName c){return new Intent();} }''',
    'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager {
        public android.content.Intent getLaunchIntentForPackage(String p){return null;} }''',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo {public int icon;}',
    'android/content/pm/ShortcutInfo.java': '''package android.content.pm; public class ShortcutInfo {
        public String getId(){return "";}
        public static class Builder {public Builder(android.content.Context c,String id){}
          public Builder setShortLabel(CharSequence s){return this;} public Builder setLongLabel(CharSequence s){return this;}
          public Builder setIcon(android.graphics.drawable.Icon i){return this;} public Builder setIntent(android.content.Intent i){return this;}
          public ShortcutInfo build(){return new ShortcutInfo();}} }''',
    'android/content/pm/ShortcutManager.java': '''package android.content.pm; public class ShortcutManager {
        public java.util.List<ShortcutInfo> getDynamicShortcuts(){return new java.util.ArrayList<>();}
        public boolean addDynamicShortcuts(java.util.List<ShortcutInfo> s){return true;}
        public boolean updateShortcuts(java.util.List<ShortcutInfo> s){return true;} }''',
    'android/graphics/drawable/Icon.java': '''package android.graphics.drawable; public class Icon {
        public static Icon createWithResource(android.content.Context c,int r){return new Icon();} }''',
    'android/app/Activity.java': '''package android.app; public class Activity extends android.content.ContextWrapper {
        public boolean isInPictureInPictureMode(){return false;} public android.view.Window getWindow(){return null;}
        public boolean isFinishing(){return false;} }''',
    'android/app/Application.java': '''package android.app; public class Application extends android.content.ContextWrapper {
        public interface ActivityLifecycleCallbacks {
          void onActivityCreated(Activity a,android.os.Bundle b); void onActivityStarted(Activity a);
          void onActivityResumed(Activity a); void onActivityPaused(Activity a); void onActivityStopped(Activity a);
          void onActivitySaveInstanceState(Activity a,android.os.Bundle b); void onActivityDestroyed(Activity a);}
        public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){} }''',
    # The screen itself is UI; Settings only names its class.
    SETTINGS + 'SettingsActivity.java': '''package io.github.jordanwoodson.extension.shared.settings;
        public class SettingsActivity extends android.app.Activity {}''',
    # Production classes replace the doubles other host tests use.
    SETTINGS + 'Settings.java': None,
    PACKAGE + 'Logger.java': None,
    # Package access for the probe, which lives in the default package.
    SETTINGS + 'Access.java': '''package io.github.jordanwoodson.extension.shared.settings;
        import java.lang.reflect.Field;
        public final class Access {
          public static Object parse(String json){return Registry.parse(json);}
          public static void install(Object registry)throws Exception{
            Field f=Settings.class.getDeclaredField("registry");f.setAccessible(true);f.set(null,registry);}
          public static void context(android.content.Context context)throws Exception{
            Field f=Settings.class.getDeclaredField("context");f.setAccessible(true);f.set(null,context);}
          public static boolean readable(Object r){return ((Registry)r).readable;}
          public static int size(Object r){return ((Registry)r).entries.size();}
          public static String name(Object r){Registry x=(Registry)r;return x.name+"\\u001f"+x.version+"\\u001f"+x.app;}
          public static String dump(Object r){
            StringBuilder out=new StringBuilder();
            for(Registry.Entry e:((Registry)r).entries){
              out.append(String.join("\\u001f",e.key,e.type,e.title,e.summary,e.section,String.valueOf(e.parent),
                e.defaultValue,String.valueOf(e.restart),String.join("\\u001e",e.choiceLabels),String.join("\\u001e",e.choiceValues)));
              out.append('\\n');}
            return out.toString();}
          public static String[][] entries(Object r){
            java.util.List<String[]> out=new java.util.ArrayList<>();
            for(Registry.Entry e:((Registry)r).entries)out.add(new String[]{e.key,e.type,e.defaultValue,
              e.choiceValues.length>1?e.choiceValues[e.choiceValues[0].equals(e.defaultValue)?1:0]:""});
            return out.toArray(new String[0][]);}
          public static String getString(String key){return Settings.getString(key);}
        }''',
}

PROBE = r'''
import android.content.Context;
import android.content.SharedPreferences;
import io.github.jordanwoodson.extension.shared.settings.Access;
import io.github.jordanwoodson.extension.shared.settings.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class SettingsProbe {
  /** SharedPreferences as Android implements it for the parts Settings uses: typed reads cast. */
  static final class Preferences implements SharedPreferences {
    final Map<String,Object> values=new HashMap<>();
    final List<OnSharedPreferenceChangeListener> listeners=new ArrayList<>();
    public Map<String,?> getAll(){return new HashMap<>(values);}
    public String getString(String k,String d){Object v=values.get(k);return v==null?d:(String)v;}
    public int getInt(String k,int d){Object v=values.get(k);return v==null?d:(Integer)v;}
    public long getLong(String k,long d){Object v=values.get(k);return v==null?d:(Long)v;}
    public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v==null?d:(Boolean)v;}
    public boolean contains(String k){return values.containsKey(k);}
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.remove(l);}
    public Editor edit(){
      Map<String,Object> pending=new HashMap<>();Set<String> removed=new HashSet<>();boolean[] clear={false};
      return new Editor(){
        public Editor putString(String k,String v){pending.put(k,v);return this;}
        public Editor putInt(String k,int v){pending.put(k,v);return this;}
        public Editor putLong(String k,long v){pending.put(k,v);return this;}
        public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
        public Editor remove(String k){removed.add(k);return this;}
        public Editor clear(){clear[0]=true;return this;}
        public boolean commit(){
          Set<String> changed=new HashSet<>();
          if(clear[0]){changed.addAll(values.keySet());values.clear();}
          for(String k:removed){if(values.remove(k)!=null)changed.add(k);}
          for(Map.Entry<String,Object> e:pending.entrySet()){values.put(e.getKey(),e.getValue());changed.add(e.getKey());}
          for(String k:changed)for(OnSharedPreferenceChangeListener l:new ArrayList<>(listeners))l.onSharedPreferenceChanged(Preferences.this,k);
          return true;}
        public void apply(){commit();}
      };}
  }

  static final Map<String,Preferences> FILES=new HashMap<>();
  static int checks;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
  static Preferences prefs(){return FILES.computeIfAbsent("lurk_settings",n->new Preferences());}
  static String read(String path)throws Exception{return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);}
  static Object install(String json)throws Exception{Object r=Access.parse(json);Access.install(r);return r;}

  public static void main(String[] args)throws Exception{
    Context.preferences=name->FILES.computeIfAbsent(name,n->new Preferences());
    String[] a=args[0].split("\\|");
    switch(a[0]){
      case "registry":registry(a[1],a[2]);break;
      case "defaults":Access.context(new Context());defaults(read(a[1]));break;
      case "stored":Access.context(new Context());stored(read(a[1]));break;
      case "nocontext":nocontext(read(a[1]));break;
      case "empty":Access.context(new Context());empty(a.length>1?read(a[1]):null);break;
      default:throw new AssertionError(args[0]);
    }
    System.out.println("PASS settings "+a[0]+(a.length>1?" "+Paths.get(a[1]).getFileName():"")+": "+checks+" checks");
  }

  /** Registry.parse reads the JSON the patches wrote; Python compares every field with its own reading. */
  static void registry(String fixture,String dump)throws Exception{
    Object r=Access.parse(read(fixture));
    check(Access.readable(r),"registry unreadable");
    check(Access.size(r)>0,"registry empty");
    Files.write(Paths.get(dump),(Access.name(r)+"\n"+Access.dump(r)).getBytes(StandardCharsets.UTF_8));
  }

  /** With nothing stored, every registered key reads as its patch-time default. */
  static void defaults(String json)throws Exception{
    Object r=install(json);
    for(String[] e:Access.entries(r)){
      String key=e[0],type=e[1],def=e[2];
      check(Settings.isRegistered(key),key+" not registered");
      if(type.equals("switch")){
        check(def.equals("true")||def.equals("false"),key+": switch default "+def);
        check(Settings.isEnabled(key)==Boolean.parseBoolean(def),key+": default not read");
        check(Settings.getText(key).equals(def),key+": a switch's text is its default");
      }else{
        check(!Settings.isEnabled(key),key+": a "+type+" is not a switch");
        check(Access.getString(key).equals(def),key+": default "+def+" read as "+Access.getString(key));
        check(Settings.getText(key).equals(def),key+": text");
        int number;try{number=Integer.parseInt(def);}catch(NumberFormatException x){number=0;}
        check(Settings.getInt(key)==number,key+": number");
      }
    }
    unknownIsOff();
  }

  /** Values changed on the screen win over defaults; a value stored with the wrong type falls back. */
  static void stored(String json)throws Exception{
    Object r=install(json);
    Preferences p=prefs();
    for(String[] e:Access.entries(r)){
      String key=e[0],type=e[1],def=e[2],other=e[3];
      if(type.equals("switch")){
        boolean flipped=!Boolean.parseBoolean(def);
        p.edit().putBoolean(key,flipped).apply();
        check(Settings.isEnabled(key)==flipped,key+": stored switch not read");
        p.edit().putString(key,"oops").apply();
        check(Settings.isEnabled(key)==Boolean.parseBoolean(def),key+": wrongly typed switch didn't fall back");
      }else if(type.equals("choice")){
        check(!other.isEmpty(),key+": a choice needs two values");
        p.edit().putString(key,other).apply();
        check(Access.getString(key).equals(other),key+": stored choice not read");
        int number;try{number=Integer.parseInt(other);}catch(NumberFormatException x){number=0;}
        check(Settings.getInt(key)==number,key+": stored choice as a number");
        p.edit().putBoolean(key,true).apply();
        check(Access.getString(key).equals(def),key+": wrongly typed choice didn't fall back");
      }else{
        p.edit().putString(key,"a, b").apply();
        check(Settings.getText(key).equals("a, b"),key+": stored text not read");
      }
    }
    // Values stored for keys this registry doesn't have stay unread.
    p.edit().putBoolean("lurk_test_unregistered",true).putString("lurk_test_choice","5").apply();
    unknownIsOff();
  }

  /** Before the app has a Context (content providers, probes) the defaults apply. */
  static void nocontext(String json)throws Exception{
    Object r=install(json);
    for(String[] e:Access.entries(r)){
      if(e[1].equals("switch"))check(Settings.isEnabled(e[0])==Boolean.parseBoolean(e[2]),e[0]+": default without context");
      else check(Access.getString(e[0]).equals(e[2]),e[0]+": default without context");
    }
    unknownIsOff();
  }

  /** Any registry that can't be read in full is empty and turns everything off, whatever was stored. */
  static void empty(String valid)throws Exception{
    List<String> broken=new ArrayList<>(Arrays.asList(null,"","{","[]","{\"settings\":[]}","{\"settings\":{}}",
      "{\"name\":\"Lurk\",\"settings\":[{\"key\":\"a\"}]}"));
    if(valid!=null){
      // One damaged entry spoils the whole registry: all or nothing.
      broken.add(valid.replaceFirst("\"type\":\"switch\"","\"tipe\":\"switch\""));
      broken.add(valid.substring(0,valid.length()/2));
    }
    Set<String> keys=new TreeSet<>(Arrays.asList("lurk_test_on","block_ads","disable_analytics","sleep_timer","video_quality"));
    if(valid!=null)for(String[] e:Access.entries(Access.parse(valid)))keys.add(e[0]);
    Preferences p=prefs();
    for(String key:keys)p.edit().putBoolean(key,true).apply();
    for(String json:broken){
      Object r=install(json);
      check(!Access.readable(r),"readable: "+json);
      check(Access.size(r)==0,"entries survived: "+json);
      for(String key:keys){
        check(!Settings.isRegistered(key),key+" registered in a broken registry");
        check(!Settings.isEnabled(key),key+" on in a broken registry");
      }
      for(String key:keys)p.edit().putString(key,"5").apply();
      for(String key:keys){
        check(Access.getString(key).isEmpty(),key+": stored choice read from a broken registry");
        check(Settings.getInt(key)==0,key+": number from a broken registry");
        check(Settings.getText(key).isEmpty(),key+": text from a broken registry");
      }
      for(String key:keys)p.edit().putBoolean(key,true).apply();
    }
  }

  static void unknownIsOff(){
    for(String key:new String[]{"lurk_test_unregistered","lurk_test_choice",""}){
      check(!Settings.isRegistered(key),key+" registered");
      check(!Settings.isEnabled(key),key+" on without registration");
      check(Access.getString(key).isEmpty()&&Settings.getText(key).isEmpty(),key+" has a value without registration");
      check(Settings.getInt(key)==0,key+" has a number without registration");
    }
  }
}
'''


def registry_from_apk(apk):
    """The registry JSON the settings patch put into Settings.registryJson(), from the APK's dex files."""
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)):
            data = archive.read(name)
            start = data.find(b'{"name":"Lurk","version":')
            if start < 0:
                continue
            raw = data[start:data.index(b'\0', start)].replace(b'\xc0\x80', b'\0')
            # Dex strings are MUTF-8: characters outside the BMP are two encoded surrogates.
            return raw.decode('utf-8', 'surrogatepass').encode('utf-16', 'surrogatepass').decode('utf-16')
    raise SystemExit(f'{apk}: no Lurk settings registry in the dex files')


def check_layout(app, root):
    """The order and shape SettingsScreen.json() promises."""
    assert root['name'] == 'Lurk' and isinstance(root['version'], str), app
    assert root['app'] and isinstance(root['settings'], list) and root['settings'], f'{app}: no settings'
    entries = root['settings']
    keys = [entry['key'] for entry in entries]
    assert len(keys) == len(set(keys)), f'{app}: duplicate keys {sorted(k for k in keys if keys.count(k) > 1)}'
    by_key = {entry['key']: entry for entry in entries}
    top = []
    for index, entry in enumerate(entries):
        key = entry['key']
        assert re.fullmatch(r'[a-z0-9_]+', key), f'{app}: key {key!r}'
        assert entry['type'] in ('switch', 'choice', 'text'), f'{app} {key}: type'
        assert entry['title'].strip() and isinstance(entry['summary'], str), f'{app} {key}: title or summary'
        assert entry['section'] in SECTIONS, f'{app} {key}: unknown section {entry["section"]}'
        assert isinstance(entry['restart'], bool), f'{app} {key}: restart flag'
        assert isinstance(entry['default'], str), f'{app} {key}: default'
        if entry['type'] == 'switch':
            assert entry['default'] in ('true', 'false'), f'{app} {key}: switch default'
        if entry['type'] == 'choice':
            values = [value for _, value in entry['choices']]
            assert len(values) >= 2 and len(set(values)) == len(values), f'{app} {key}: choices'
            assert entry['default'] in values, f'{app} {key}: default {entry["default"]} is not a choice'
        else:
            assert 'choices' not in entry, f'{app} {key}: choices on a {entry["type"]}'
        parent = entry['parent']
        if parent is None:
            top.append(entry)
            continue
        assert parent in by_key, f'{app} {key}: parent {parent} is not registered'
        assert by_key[parent]['parent'] is None, f'{app} {key}: parent {parent} has a parent itself'
        assert by_key[parent]['type'] == 'switch', f'{app} {key}: parent {parent} is not a switch'
        assert by_key[parent]['section'] == entry['section'], f'{app} {key}: not in its parent\'s section'
        # Only the parent and its other children come before it, back to the parent.
        between = entries[keys.index(parent) + 1:index]
        assert all(other['parent'] == parent for other in between), f'{app} {key}: not right after {parent}'
    order = [(SECTIONS.index(entry['section']), entry['title']) for entry in top]
    assert order == sorted(order), f'{app}: top-level entries not in section, then title order'


def compare_dump(app, root, dump):
    lines = Path(dump).read_text(encoding='utf-8').split('\n')
    assert lines[0] == '\x1f'.join([root['name'], root['version'], root['app']]), f'{app}: header {lines[0]!r}'
    expected = []
    for entry in root['settings']:
        choices = entry.get('choices', [])
        expected.append('\x1f'.join([
            entry['key'], entry['type'], entry['title'], entry['summary'], entry['section'],
            'null' if entry['parent'] is None else entry['parent'],
            entry['default'], str(entry['restart']).lower(), '\x1e'.join(label for label, _ in choices),
            '\x1e'.join(value for _, value in choices)]))
    actual = [line for line in lines[1:] if line]
    for want, got in zip(expected, actual):
        assert want == got, f'{app}: Java read\n  {got!r}\nPython read\n  {want!r}'
    assert len(expected) == len(actual), f'{app}: Java read {len(actual)} entries, the JSON has {len(expected)}'


def main(argv):
    if argv[:1] == ['--update']:
        REGISTRIES.mkdir(exist_ok=True)
        for apk in argv[1:]:
            text = registry_from_apk(apk)
            root = json.loads(text)
            check_layout(root['app'], root)
            target = REGISTRIES / f'{root["app"].lower()}.json'
            target.write_text(text + '\n', encoding='utf-8')
            print(f'wrote {target.relative_to(HERE.parents[1])} ({len(root["settings"])} settings)')
        return
    sources = {}
    if argv:
        for apk in argv:
            text = registry_from_apk(apk)
            sources[json.loads(text)['app'].lower()] = text
    else:
        for path in sorted(REGISTRIES.glob('*.json')):
            sources[path.stem] = path.read_text(encoding='utf-8').strip()
        assert set(sources) == {'twitch', 'kick', 'rumble'}, f'stored registries: {sorted(sources)}'
    with tempfile.TemporaryDirectory(prefix='lurk-settings-') as scratch:
        scenarios, roots = [], {}
        for app, text in sources.items():
            root = json.loads(text)
            check_layout(app, root)
            roots[app] = root
            fixture = Path(scratch) / f'{app}.json'
            fixture.write_text(text, encoding='utf-8')
            scenarios += [f'registry|{fixture}|{Path(scratch) / (app + ".dump")}', f'defaults|{fixture}',
                          f'stored|{fixture}', f'nocontext|{fixture}', f'empty|{fixture}']
        scenarios.append('empty')
        run('SettingsProbe', ['settings/Settings.java', 'settings/Registry.java', 'settings/ResumedActivityTracker.java',
                              'Logger.java'], PROBE, scenarios, extra=JSON_DOUBLE | ANDROID)
        for app, root in roots.items():
            compare_dump(app, root, Path(scratch) / f'{app}.dump')
            print(f'PASS {app}: {len(root["settings"])} settings, layout and Java/Python reading agree')


if __name__ == '__main__':
    main(sys.argv[1:])
