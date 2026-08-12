package com.domonation.camera;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

import androidx.core.content.ContextCompat;

final class AppTheme {
    static final String KEY = "app_theme";
    static final int AUTO = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private AppTheme() { }

    static Context wrap(Context context) {
        int selected = context.getSharedPreferences("paper_camera", Context.MODE_PRIVATE)
                .getInt(KEY, AUTO);
        if (selected == AUTO) return context;
        Configuration override = new Configuration();
        override.uiMode = selected == DARK
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(override);
    }

    static void applySystemBars(Activity activity) {
        int paper = ContextCompat.getColor(activity, R.color.paper);
        activity.getWindow().setStatusBarColor(paper);
        activity.getWindow().setNavigationBarColor(paper);
        boolean dark = (activity.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }
}
