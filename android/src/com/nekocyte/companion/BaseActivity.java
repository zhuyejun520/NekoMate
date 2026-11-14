package com.nekocyte.companion;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class BaseActivity extends Activity {
    private static final String THEME_BG_TAG = "nekomate_theme_bg";
    private String appliedTheme = "";

    @Override
    protected void attachBaseContext(Context newBase) {
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        int night = Session.dark() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Session.dark() ? R.style.Theme_Neko_Dark : R.style.Theme_Neko_Light);
        super.onCreate(savedInstanceState);
        Ui.init(this);
        appliedTheme = Session.storeTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!appliedTheme.equals(Session.storeTheme())) {
            recreate();
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        installThemeBackground();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        installThemeBackground();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        installThemeBackground();
    }

    private void installThemeBackground() {
        if (!Ui.themeActive()) return;
        FrameLayout content = findViewById(android.R.id.content);
        if (content == null || THEME_BG_TAG.equals(content.getTag())) return;
        content.setTag(THEME_BG_TAG);
        ThemeBackgroundView background = new ThemeBackgroundView(this);
        content.addView(background, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (content.getChildCount() > 1) {
            View root = content.getChildAt(1);
            root.setBackgroundColor(Color.TRANSPARENT);
        }
    }
}
