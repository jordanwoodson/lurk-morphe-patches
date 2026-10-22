package io.github.jordanwoodson.extension.shared.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toolbar;

import java.util.HashMap;
import java.util.Map;

/**
 * The Lurk settings screen: a switch for each patch that can be turned off at runtime, grouped as the
 * patches registered them. Declared in the app's manifest by the app's settings patch.
 */
@SuppressWarnings("deprecation")
public final class SettingsActivity extends Activity {
    // Fixed, so the fragment finds its container again when the activity is recreated.
    private static final int CONTENT_ID = 0x4c75726b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);

        TypedArray attributes = obtainStyledAttributes(
                new int[]{android.R.attr.colorBackground, android.R.attr.homeAsUpIndicator});
        int background = attributes.getColor(0, night ? Color.BLACK : Color.WHITE);
        Drawable up = attributes.getDrawable(1);
        attributes.recycle();

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(Settings.registry().name);
        if (up != null) toolbar.setNavigationIcon(up);
        toolbar.setNavigationContentDescription("Back");
        toolbar.setNavigationOnClickListener(view -> finish());

        FrameLayout content = new FrameLayout(this);
        content.setId(CONTENT_ID);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.addView(toolbar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        // Drawn edge to edge on every version (Android 15+ insists): keep clear of the status bar and
        // cutouts here, and of the navigation bar in the list.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
            return insets;
        });
        setContentView(root);
        drawEdgeToEdge(!night);

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(CONTENT_ID, new SettingsFragment())
                    .commit();
        }
    }

    private void drawEdgeToEdge(boolean light) {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(light ? lightBars : 0, lightBars);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    public static final class SettingsFragment extends PreferenceFragment {
        private static final String STATE_RESTART = "restart";

        private Preference restart;
        private boolean restartShown;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            build(savedInstanceState != null && savedInstanceState.getBoolean(STATE_RESTART));
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // The app draws edge to edge, so keep the last rows clear of the navigation bar.
            ListView list = view.findViewById(android.R.id.list);
            if (list == null) return;
            list.setClipToPadding(false);
            int bottom = list.getPaddingBottom();
            list.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        bottom + insets.getSystemWindowInsetBottom());
                return insets;
            });
            list.requestApplyInsets();
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(STATE_RESTART, restartShown);
        }

        private void build(boolean showRestart) {
            Context context = getActivity();
            Registry registry = Settings.registry();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);

            Preference about = new Preference(context);
            about.setTitle(registry.version.isEmpty() ? registry.name : registry.name + " " + registry.version);
            about.setSummary(!registry.readable
                    ? registry.name + "'s settings could not be read; every patch is off."
                    : "Turn patches off or on without patching again. Changes apply the next time "
                    + registry.app + " loads the screen they affect.");
            about.setSelectable(false);
            about.setOrder(0);
            screen.addPreference(about);

            restart = new Preference(context);
            restart.setTitle("Restart " + registry.app);
            restart.setSummary("Some of your changes apply after a restart.");
            restart.setOrder(1);
            restart.setOnPreferenceClickListener(preference -> {
                Settings.restart(getActivity());
                return true;
            });
            restartShown = false;
            if (showRestart) showRestart();

            Map<String, PreferenceCategory> sections = new HashMap<>();
            int order = 2;
            for (Registry.Entry entry : registry.entries) {
                PreferenceCategory section = sections.get(entry.section);
                if (section == null) {
                    section = new PreferenceCategory(context);
                    section.setTitle(entry.section);
                    section.setOrder(order++);
                    screen.addPreference(section);
                    sections.put(entry.section, section);
                }
                Preference preference;
                if (Registry.CHOICE.equals(entry.type)) {
                    preference = choice(context, entry);
                } else if (Registry.TEXT.equals(entry.type)) {
                    preference = text(context, entry);
                } else {
                    preference = toggle(context, entry);
                }
                section.addPreference(preference);
                if (entry.parent != null) preference.setDependency(entry.parent);
            }

            if (!registry.entries.isEmpty()) {
                PreferenceCategory other = new PreferenceCategory(context);
                other.setOrder(order);
                screen.addPreference(other);
                Preference reset = new Preference(context);
                reset.setTitle("Reset to defaults");
                reset.setSummary("Go back to the settings chosen when " + registry.app + " was patched.");
                reset.setOnPreferenceClickListener(preference -> {
                    confirmReset();
                    return true;
                });
                other.addPreference(reset);
            }
        }

        private SwitchPreference toggle(Context context, Registry.Entry entry) {
            SwitchPreference preference = new SwitchPreference(context);
            preference.setKey(entry.key);
            preference.setPersistent(false);
            preference.setTitle(entry.title);
            preference.setSummary(summary(entry, null));
            preference.setChecked(Settings.isEnabled(entry.key));
            preference.setOnPreferenceChangeListener((changed, value) -> {
                Settings.putBoolean(entry.key, (Boolean) value);
                if (entry.restart) showRestart();
                return true;
            });
            return preference;
        }

        private ListPreference choice(Context context, Registry.Entry entry) {
            ListPreference preference = new ListPreference(context);
            preference.setKey(entry.key);
            preference.setPersistent(false);
            preference.setTitle(entry.title);
            preference.setDialogTitle(entry.title);
            preference.setEntries(entry.choiceLabels);
            preference.setEntryValues(entry.choiceValues);
            String value = Settings.getString(entry.key);
            preference.setValue(value);
            preference.setSummary(listSummary(summary(entry, entry.choiceLabel(value))));
            preference.setOnPreferenceChangeListener((changed, picked) -> {
                Settings.putString(entry.key, (String) picked);
                changed.setSummary(listSummary(summary(entry, entry.choiceLabel((String) picked))));
                if (entry.restart) showRestart();
                return true;
            });
            return preference;
        }

        /** ListPreference passes its summary through String.format, so a literal % (as in "85%") is doubled. */
        private static String listSummary(String summary) {
            return summary.replace("%", "%%");
        }

        /** Free text, edited in a dialog. Saved trimmed; an empty text is a valid value. */
        private EditTextPreference text(Context context, Registry.Entry entry) {
            EditTextPreference preference = new EditTextPreference(context);
            preference.setKey(entry.key);
            preference.setPersistent(false);
            preference.setTitle(entry.title);
            preference.setDialogTitle(entry.title);
            if (!entry.summary.isEmpty()) preference.setDialogMessage(entry.summary);
            preference.getEditText().setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            String value = Settings.getText(entry.key);
            preference.setText(value);
            preference.setSummary(summary(entry, textLabel(value)));
            preference.setOnPreferenceChangeListener((changed, typed) -> {
                String text = typed == null ? "" : typed.toString().trim();
                Settings.putString(entry.key, text);
                ((EditTextPreference) changed).setText(text);
                changed.setSummary(summary(entry, textLabel(text)));
                if (entry.restart) showRestart();
                // Already saved trimmed above; false keeps the preference from storing the untrimmed text.
                return false;
            });
            return preference;
        }

        private static String textLabel(String value) {
            if (value.isEmpty()) return "Not set";
            String line = value.replace('\n', ' ');
            return line.length() > 80 ? line.substring(0, 79) + "\u2026" : line;
        }

        private static String summary(Registry.Entry entry, String value) {
            StringBuilder summary = new StringBuilder();
            if (value != null) summary.append(value);
            if (!entry.summary.isEmpty()) {
                if (summary.length() > 0) summary.append('\n');
                summary.append(entry.summary);
            }
            if (entry.restart) {
                if (summary.length() > 0) summary.append(' ');
                summary.append("Needs a restart.");
            }
            return summary.toString();
        }

        private void showRestart() {
            if (restartShown) return;
            getPreferenceScreen().addPreference(restart);
            restartShown = true;
        }

        private void confirmReset() {
            Registry registry = Settings.registry();
            new AlertDialog.Builder(getActivity())
                    .setTitle("Reset to defaults?")
                    .setMessage("Every " + registry.name + " setting goes back to how it was when "
                            + registry.app + " was patched.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Reset", (dialog, which) -> {
                        Settings.reset();
                        build(true);
                    })
                    .show();
        }
    }
}
