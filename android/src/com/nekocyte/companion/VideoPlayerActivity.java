package com.nekocyte.companion;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/** 内嵌视频页：B 站 iframe 播放器 / 抖音分享页，默认暂停。 */
public class VideoPlayerActivity extends android.app.Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 23) window.getDecorView().setSystemUiVisibility(0);

        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        TextView titleView = findViewById(R.id.video_title);
        titleView.setText(title == null ? "视频" : title);
        findViewById(R.id.video_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        web = findViewById(R.id.video_web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pauseSoon(view, 300);
                pauseSoon(view, 1200);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        if (url != null && !url.isEmpty()) {
            web.loadUrl(url);
        } else {
            Ui.toast(this, "视频地址为空");
        }
    }

    private void pauseSoon(final WebView view, long delay) {
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    view.evaluateJavascript(
                            "(function(){var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}}})();",
                            null);
                } catch (Exception ignored) {
                }
            }
        }, delay);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }
}
