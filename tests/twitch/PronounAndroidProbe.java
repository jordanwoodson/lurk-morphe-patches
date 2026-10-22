import android.content.*;
import android.graphics.Typeface;
import java.lang.reflect.*;
import java.util.*;

/** Same completion-order assertions on ART, with real JsonReader/Canvas/PNG and isolated settings/HTTP. */
public final class PronounAndroidProbe {
 public static void main(String[] args){try{
  android.graphics.fonts.Font font=new android.graphics.fonts.Font.Builder(new java.io.File("/system/fonts/Roboto-Regular.ttf")).build();
  Method make=Typeface.class.getDeclaredMethod("createFromFamilies",String.class,android.graphics.fonts.FontFamily[].class);make.setAccessible(true);
  Typeface face=(Typeface)make.invoke(null,"sans-serif",new android.graphics.fonts.FontFamily[]{new android.graphics.fonts.FontFamily.Builder(font).build()});
  Method fonts=Typeface.class.getDeclaredMethod("setSystemFontMap",Map.class);fonts.setAccessible(true);fonts.invoke(null,Map.of("sans-serif",face,"serif",face,"monospace",face));
  Map<String,Object> prefs=new java.util.concurrent.ConcurrentHashMap<>();
  SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
   if(m.getName().equals("getBoolean")||m.getName().equals("getString"))return prefs.getOrDefault((String)a[0],a[1]);throw new UnsupportedOperationException(m.getName());});
  Context context=new ContextWrapper(null){@Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}};
  Field f=Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings").getDeclaredField("context");f.setAccessible(true);f.set(null,context);
  PronounCompletionProbe.enabled=value->prefs.put("badges_pronouns",value);
  PronounCompletionProbe.response=names->names?"{\"hehim\":{\"subject\":\"He\",\"object\":\"Him\"}}":"{\"pronoun_id\":\"hehim\"}";
  PronounCompletionProbe.main(args);
 }catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}}
}
