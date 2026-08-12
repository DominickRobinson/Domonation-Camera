package com.domonation.camera;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

import androidx.core.content.ContextCompat;

final class AppTheme {
    private AppTheme() { }

    static Context wrap(Context context) {
        Configuration override = new Configuration();
        override.uiMode = Configuration.UI_MODE_NIGHT_NO;
        return context.createConfigurationContext(override);
    }

    static void applySystemBars(Activity activity) {
        int paper = ContextCompat.getColor(activity, R.color.paper);
        activity.getWindow().setStatusBarColor(paper);
        activity.getWindow().setNavigationBarColor(paper);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }
}
