package com.nekocyte.companion;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MomentEditorActivity extends BaseActivity {
    private static final int REQ_PICK = 23;
    private static final int MAX_IMAGES = 9;

    private EditText content;
    private TextView count;
    private TextView publish;
    private TextView addImage;
    private TextView addFile;
    private LinearLayout imageBox;
    private final List<File> files = new ArrayList<>();
    private boolean posting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moment_editor);
        content = findViewById(R.id.moment_text);
        count = findViewById(R.id.count);
        publish = findViewById(R.id.publish);
        addImage = findViewById(R.id.add_image);
        addFile = findViewById(R.id.add_file);
        imageBox = findViewById(R.id.moment_images);

        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        addImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImages(false);
            }
        });
        addFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImages(true);
            }
        });
        publish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publish();
            }
        });
        content.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCount();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updateCount();
        updateAddButton();
    }

    private void updateCount() {
        count.setText(content.getText().length() + " / 20000");
    }

    private void updateAddButton() {
        boolean hide = files.size() >= MAX_IMAGES;
        addImage.setVisibility(hide ? View.GONE : View.VISIBLE);
        addFile.setVisibility(hide || !Session.filePickerEnabled() ? View.GONE : View.VISIBLE);
    }

    private void pickImages(boolean fromFiles) {
        if (files.size() >= MAX_IMAGES) return;
        try {
            Intent intent = fromFiles
                    ? MediaUtil.pickFromDocuments(this, true)
                    : MediaUtil.pickFromGallery(this, true, MAX_IMAGES - files.size());
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        DebugLog.i("moment", "image result clip=" + (data.getClipData() != null) + " uri=" + MediaUtil.describe(data.getData()));
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount() && files.size() < MAX_IMAGES; i++) {
                addImage(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addImage(data.getData());
        }
        updateAddButton();
    }

    private void addImage(Uri uri) {
        if (uri == null || files.size() >= MAX_IMAGES) return;
        try {
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) {
                Ui.toast(this, "图片读取失败，请重新选择");
                return;
            }
            File dir = new File(getCacheDir(), "moment");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "img_" + System.currentTimeMillis() + "_" + files.size() + ".png");
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            files.add(out);
            addThumb(out);
            MediaUtil.persist(this, uri);
        } catch (Exception e) {
            DebugLog.e("moment", "addImage failed " + MediaUtil.describe(uri), e);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String auth = MediaUtil.authority(uri);
            if (auth.contains("coloros") || auth.contains("oplus") || auth.contains("oppo")) {
                Ui.toast(this, "OPPO 文件管理返回的地址读不出来，请改用「从文件管理选择」或相册");
            } else if (msg.contains("ENOENT") || msg.contains("No such file")) {
                Ui.toast(this, "图片地址不可读，请改用「从文件管理选择」或相册重新选择");
            } else {
                Ui.toast(this, "读取图片失败：" + msg);
            }
        }
    }

    private void addThumb(final File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        final ImageView image = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88));
        lp.rightMargin = Ui.dp(this, 8);
        image.setLayoutParams(lp);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        image.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                files.remove(file);
                imageBox.removeView(image);
                updateAddButton();
                Ui.toast(MomentEditorActivity.this, "已移除图片");
                return true;
            }
        });
        imageBox.addView(image);
    }

    private void publish() {
        if (posting) return;
        final String text = content.getText().toString().trim();
        if (text.isEmpty()) {
            Ui.toast(this, "写点什么再发布吧");
            return;
        }
        posting = true;
        publish.setEnabled(false);
        publish.setText("发布中");
        final JSONArray urls = new JSONArray();
        uploadNext(0, text, urls);
    }

    private void uploadNext(final int index, final String text, final JSONArray urls) {
        if (index >= files.size()) {
            Api.postMoment(text, urls, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    Ui.toast(MomentEditorActivity.this, "已发布");
                    setResult(RESULT_OK);
                    finish();
                }

                @Override
                public void fail(String message) {
                    reset();
                    Ui.toast(MomentEditorActivity.this, message);
                }
            });
            return;
        }
        Api.uploadFile(files.get(index), new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                String url = data.optString("url", "");
                if (url.isEmpty()) {
                    reset();
                    Ui.toast(MomentEditorActivity.this, "图片上传失败");
                    return;
                }
                try {
                    urls.put(url);
                } catch (Exception ignored) {
                }
                uploadNext(index + 1, text, urls);
            }

            @Override
            public void fail(String message) {
                reset();
                Ui.toast(MomentEditorActivity.this, message);
            }
        });
    }

    private void reset() {
        posting = false;
        publish.setEnabled(true);
        publish.setText("发布");
    }
}
