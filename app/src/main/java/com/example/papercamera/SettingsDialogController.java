package com.domonation.camera;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

final class SettingsDialogController {
    private static final String KEY_TREE = "save_tree";
    private static final String KEY_REVIEW = "review_before_save";
    private static final String KEY_THUMB = "exif_thumbnail";
    private static final String KEY_LAPSE = "timelapse_seconds";
    private static final String KEY_LAPSE_FPS = "timelapse_fps";
    private static final String KEY_LAPSE_CUSTOM = "timelapse_seconds_custom";
    private static final String KEY_LAPSE_FPS_CUSTOM = "timelapse_fps_custom";
    private static final String KEY_VIDEO_AUDIO = "video_audio";
    private static final String KEY_VOLUME_SHUTTER = "volume_shutter";
    private static final String KEY_GALLERY_ROWS = "gallery_rows";
    private static final String KEY_GALLERY_COLUMNS = "gallery_columns";

    interface Host {
        void chooseFolder();
        void useDefaultFolder();
        void onSettingsDismissed();
    }

    private final ComponentActivity activity;
    private final SharedPreferences prefs;
    private final Host host;

    SettingsDialogController(ComponentActivity activity, SharedPreferences prefs, Host host) {
        this.activity = activity;
        this.prefs = prefs;
        this.host = host;
    }

    void show() {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(color(R.color.paper));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.top_rule);
        Button close = textButton("×");
        close.setTextSize(30);
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = label("Settings");
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setBackgroundResource(R.drawable.top_rule);
        String[] names = {"General settings", "Photo settings", "Video settings", "Timelapse settings"};
        int[] icons = {R.drawable.ic_settings, R.drawable.ic_photo,
                R.drawable.ic_video, R.drawable.ic_timelapse};
        ImageButton[] tabButtons = new ImageButton[names.length];
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int[] selected = {0};
        for (int i = 0; i < names.length; i++) {
            ImageButton tab = tabButton(icons[i], names[i]);
            tab.setTag(i);
            tabButtons[i] = tab;
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(48), 1));
            tab.setOnClickListener(v -> {
                selected[0] = (int) v.getTag();
                styleTabs(tabButtons, selected[0]);
                populate(content, selected[0], dialog);
            });
        }
        page.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        ScrollView scroller = new ScrollView(activity);
        scroller.setFillViewport(true);
        scroller.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
            return true;
        });
        dialog.setOnDismissListener(d -> host.onSettingsDismissed());
        dialog.setContentView(page);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        styleTabs(tabButtons, 0);
        populate(content, 0, dialog);
    }

    private void populate(LinearLayout content, int page, Dialog dialog) {
        content.removeAllViews();
        content.setPadding(dp(20), dp(2), dp(20), dp(18));
        if (page == 0) {
            content.addView(sectionTitle("General"));
            content.addView(checkSetting("Review before save", KEY_REVIEW, false));
            content.addView(checkSetting("Volume buttons control shutter", KEY_VOLUME_SHUTTER, true));
            content.addView(sectionTitle("Gallery grid"));
            content.addView(note("Rows per page"));
            content.addView(radioSetting(KEY_GALLERY_ROWS, prefs.getInt(KEY_GALLERY_ROWS, 3)));
            content.addView(note("Columns per page"));
            content.addView(radioSetting(KEY_GALLERY_COLUMNS, prefs.getInt(KEY_GALLERY_COLUMNS, 3)));
            content.addView(sectionTitle("Save location"));
            TextView location = note(prefs.getString(KEY_TREE, null) == null ?
                    "Pictures/PaperCamera and Movies/PaperCamera" : "Selected folder (photos and videos)");
            content.addView(location);
            Button choose = settingsButton("CHOOSE FOLDER");
            choose.setOnClickListener(v -> { dialog.dismiss(); host.chooseFolder(); });
            content.addView(choose, matchHeight(48));
            Button defaults = settingsButton("USE DEFAULT FOLDERS");
            defaults.setOnClickListener(v -> {
                prefs.edit().remove(KEY_TREE).apply();
                location.setText("Pictures/PaperCamera and Movies/PaperCamera");
                host.useDefaultFolder();
            });
            content.addView(defaults, matchHeight(48));
        } else if (page == 1) {
            content.addView(sectionTitle("Photo"));
            content.addView(checkSetting("Embed EXIF thumbnail", KEY_THUMB, true));
            content.addView(note("The embedded thumbnail improves browsing on devices such as the Canon SELPHY."));
        } else if (page == 2) {
            content.addView(sectionTitle("Video"));
            content.addView(checkSetting("Record audio", KEY_VIDEO_AUDIO, true));
            content.addView(note("Turn this off for silent video without microphone permission."));
        } else {
            content.addView(sectionTitle("Timelapse"));
            content.addView(note("Each captured JPEG is saved, followed by an MP4 made from the same frames."));
            content.addView(sectionTitle("Capture interval (sec)"));
            content.addView(customNumberSetting(KEY_LAPSE, KEY_LAPSE_CUSTOM,
                    new String[]{"2", "5", "10"}, new int[]{2, 5, 10}, prefs.getInt(KEY_LAPSE, 5),
                    15, 1, 86400, "Seconds (1–86400)"));
            content.addView(sectionTitle("Video playback rate (FPS)"));
            content.addView(customNumberSetting(KEY_LAPSE_FPS, KEY_LAPSE_FPS_CUSTOM,
                    new String[]{"12", "24", "30"}, new int[]{12, 24, 30}, prefs.getInt(KEY_LAPSE_FPS, 24),
                    25, 1, 60, "FPS (1–60)"));
        }
    }

    private CheckBox checkSetting(String text, String key, boolean defaultValue) {
        CheckBox box = new CheckBox(activity);
        box.setText(text);
        box.setTextColor(color(R.color.ink));
        box.setTextSize(16);
        box.setMinHeight(dp(44));
        box.setChecked(prefs.getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        return box;
    }

    private RadioGroup radioSetting(String key, int selectedValue) {
        return radioSetting(key, new String[]{"2", "3", "4"}, new int[]{2, 3, 4}, selectedValue);
    }

    private RadioGroup radioSetting(String key, String[] labels, int[] values, int selectedValue) {
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int checkedId = View.NO_ID;
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = radio(labels[i], values[i]);
            group.addView(option, new RadioGroup.LayoutParams(0, dp(44), 1f));
            if (values[i] == selectedValue) checkedId = option.getId();
        }
        if (checkedId != View.NO_ID) group.check(checkedId);
        group.setOnCheckedChangeListener((radioGroup, id) -> {
            RadioButton checked = radioGroup.findViewById(id);
            if (checked != null) prefs.edit().putInt(key, (int) checked.getTag()).apply();
        });
        return group;
    }

    private View customNumberSetting(String key, String customKey, String[] labels, int[] values,
                                     int selectedValue, int customDefault, int min, int max, String hint) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int checkedId = View.NO_ID;
        boolean preset = false;
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = radio(labels[i], values[i]);
            group.addView(option, new RadioGroup.LayoutParams(0, dp(44), 1f));
            if (values[i] == selectedValue) { checkedId = option.getId(); preset = true; }
        }
        RadioButton custom = radio("Custom", null);
        group.addView(custom, new RadioGroup.LayoutParams(0, dp(44), 1.35f));
        if (!preset) checkedId = custom.getId();
        EditText entry = numberEntry(hint, prefs.getInt(customKey,
                preset ? customDefault : Math.max(min, Math.min(max, selectedValue))));
        entry.setVisibility(preset ? View.GONE : View.VISIBLE);
        group.check(checkedId);
        group.setOnCheckedChangeListener((radioGroup, id) -> {
            if (id == custom.getId()) {
                entry.setVisibility(View.VISIBLE);
                int value = validNumber(entry.getText().toString(), min, max,
                        prefs.getInt(customKey, customDefault));
                prefs.edit().putInt(key, value).putInt(customKey, value).apply();
            } else {
                RadioButton checked = radioGroup.findViewById(id);
                if (checked == null || !(checked.getTag() instanceof Integer)) return;
                entry.setVisibility(View.GONE);
                prefs.edit().putInt(key, (int) checked.getTag()).apply();
            }
        });
        entry.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable text) {
                if (group.getCheckedRadioButtonId() != custom.getId()) return;
                try {
                    int value = Integer.parseInt(text.toString());
                    if (value >= min && value <= max)
                        prefs.edit().putInt(key, value).putInt(customKey, value).apply();
                } catch (NumberFormatException ignored) { }
            }
        });
        wrapper.addView(group, matchHeight(44));
        wrapper.addView(entry, matchHeight(48));
        return wrapper;
    }

    private RadioButton radio(String text, Object tag) {
        RadioButton button = new RadioButton(activity);
        button.setId(View.generateViewId());
        button.setTag(tag);
        button.setText(text);
        button.setTextColor(color(R.color.ink));
        button.setTextSize(15);
        button.setMinHeight(dp(44));
        return button;
    }

    private EditText numberEntry(String hint, int value) {
        EditText entry = new EditText(activity);
        entry.setSingleLine(true);
        entry.setInputType(InputType.TYPE_CLASS_NUMBER);
        entry.setTextColor(color(R.color.ink));
        entry.setHintTextColor(color(R.color.mid_ink));
        entry.setTextSize(15);
        entry.setHint(hint);
        entry.setContentDescription("Custom " + hint);
        entry.setSelectAllOnFocus(true);
        entry.setText(String.valueOf(value));
        return entry;
    }

    private int validNumber(String text, int min, int max, int fallback) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(text))); }
        catch (NumberFormatException ignored) { return Math.max(min, Math.min(max, fallback)); }
    }

    private void styleTabs(ImageButton[] tabs, int selected) {
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == selected;
            tabs[i].setBackgroundColor(color(active ? R.color.ink : R.color.paper));
            tabs[i].setImageTintList(android.content.res.ColorStateList.valueOf(
                    color(active ? R.color.paper : R.color.ink)));
        }
    }

    private ImageButton tabButton(int icon, String description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStateListAnimator(null);
        return button;
    }

    private Button settingsButton(String text) {
        Button button = textButton(text);
        button.setTextSize(13);
        button.setBackgroundResource(R.drawable.top_rule);
        return button;
    }

    private Button textButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(color(R.color.ink));
        button.setTextSize(16);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.BLACK);
        view.setTextSize(17);
        view.setPadding(0, 14, 0, 4);
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = label(text);
        view.setTextSize(18);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private TextView note(String text) {
        TextView view = label(text);
        view.setTextSize(14);
        view.setTextColor(color(R.color.mid_ink));
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
    }

    private int color(int id) { return ContextCompat.getColor(activity, id); }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
