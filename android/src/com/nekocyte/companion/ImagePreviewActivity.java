package com.nekocyte.companion;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

public class ImagePreviewActivity extends android.app.Activity {
    private String url;
    private TextView download;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(0);
        }
        ImageView image = findViewById(R.id.preview_image);
        url = getIntent().getStringExtra("url");
        ImageLoader.load(image, url);
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.preview_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        download = findViewById(R.id.preview_download);
        download.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSave();
            }
        });
    }

    private void requestSave() {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        save();
    }

    private void save() {
        if (url == null || url.isEmpty()) {
            Ui.toast(this, "图片地址为空");
            return;
        }
        download.setEnabled(false);
        download.setText("保存中…");
        ImageSaver.save(this, url, new ImageSaver.Callback() {
            @Override
            public void ok(String name) {
                download.setEnabled(true);
                download.setText("下载");
                Ui.toast(ImagePreviewActivity.this, "已保存到相册：Pictures/NekoMate/" + name);
            }

            @Override
            public void fail(String message) {
                download.setEnabled(true);
                download.setText("下载");
                Ui.toast(ImagePreviewActivity.this, "保存失败：" + message);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            save();
        } else if (requestCode == 7) {
            Ui.toast(this, "需要存储权限才能保存图片");
        }
    }
}
