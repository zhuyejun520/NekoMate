package com.nekocyte.companion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 本地头像框编辑器，保存时只导出透明 PNG 图层。 */
public class FrameEditorActivity extends Activity {
    public static final String EXTRA_URI = "uri";

    private FrameEditorView editor;
    private Bitmap frameBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView cancel = Ui.button(this, "取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        bar.addView(cancel);
        TextView title = Ui.tv(this, "编辑头像框", 19, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tlp);
        bar.addView(title);
        TextView save = Ui.button(this, "保存", true);
        save.setTextSize(12);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveEdited();
            }
        });
        bar.addView(save);
        root.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFFF4F1FA);
        editor = new FrameEditorView(this);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        elp.gravity = Gravity.CENTER;
        stage.addView(editor, elp);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 14));
        bottom.addView(Ui.tv(this, "双指缩放 · 单指拖动 · 保存为透明 PNG", 11.5f, Ui.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView reset = Ui.button(this, "重置", false);
        reset.setTextSize(11.5f);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editor.reset();
            }
        });
        bottom.addView(reset);
        root.addView(bottom);

        setContentView(root);
        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (raw == null || raw.isEmpty()) {
            Ui.toast(this, "没有选择图片");
            finish();
            return;
        }
        try {
            Uri uri = Uri.parse(raw);
            InputStream in = MediaUtil.open(this, uri);
            frameBitmap = BitmapFactory.decodeStream(in);
            in.close();
            if (frameBitmap == null) {
                Ui.toast(this, "图片读取失败");
                finish();
                return;
            }
            editor.setFrame(frameBitmap);
        } catch (Exception e) {
            Ui.toast(this, "图片读取失败：" + e.getMessage());
            finish();
        }
    }

    private void saveEdited() {
        try {
            Bitmap out = editor.exportFrame(512);
            if (out == null) {
                Ui.toast(this, "没有可保存的头像框");
                return;
            }
            File dir = new File(getCacheDir(), "frame_editor");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "edited_frame_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            out.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            out.recycle();
            Intent data = new Intent();
            data.putExtra("path", file.getAbsolutePath());
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            Ui.toast(this, "保存失败：" + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (frameBitmap != null && !frameBitmap.isRecycled()) frameBitmap.recycle();
    }
}
