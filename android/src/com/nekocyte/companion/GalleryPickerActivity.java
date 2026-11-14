package com.nekocyte.companion;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 自建相册：MediaStore 查询全部图片，列表只加载缩略图，发送时才读取原图。 */
public class GalleryPickerActivity extends BaseActivity {
    private static final String EXTRA_MULTIPLE = "multiple";
    private static final String EXTRA_MAX = "max";
    private static final int REQ_PERMISSION = 101;
    private static final int REQ_DOCUMENTS = 102;

    private boolean multiple;
    private int maxCount;
    private final List<ImageItem> items = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private GridView grid;
    private TextView done;
    private TextView emptyText;
    private View emptyBox;
    private ProgressBar progress;
    private Adapter adapter;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private LruCache<String, Bitmap> cache;

    public static Intent intent(Context context, boolean multiple, int max) {
        Intent intent = new Intent(context, GalleryPickerActivity.class);
        intent.putExtra(EXTRA_MULTIPLE, multiple);
        intent.putExtra(EXTRA_MAX, max);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        multiple = getIntent().getBooleanExtra(EXTRA_MULTIPLE, false);
        maxCount = multiple ? Math.max(1, getIntent().getIntExtra(EXTRA_MAX, 9)) : 1;
        int memory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(Math.max(8 * 1024, memory / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, "相册", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        done = Ui.button(this, "完成", true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithSelection();
            }
        });
        bar.addView(done);
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(this, 3));
        grid.setVerticalSpacing(Ui.dp(this, 3));
        grid.setPadding(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        grid.setClipToPadding(false);
        grid.setBackgroundColor(Ui.BG);
        content.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34));
        plp.gravity = Gravity.CENTER;
        content.addView(progress, plp);
        emptyBox = buildEmpty();
        content.addView(emptyBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) return;
                toggle(items.get(position));
            }
        });
        updateDone();
        emptyBox.setVisibility(View.GONE);
        if (hasPermission()) loadImages();
        else requestPermission();
    }

    private View buildEmpty() {
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        emptyText = Ui.tv(this, "", 13, Ui.MUTED, false);
        emptyText.setGravity(Gravity.CENTER);
        box.addView(emptyText);
        TextView auth = Ui.button(this, "授权照片访问", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 180), Ui.dp(this, 44));
        lp.topMargin = Ui.dp(this, 12);
        auth.setLayoutParams(lp);
        auth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasPermission()) loadImages();
                else requestPermission();
            }
        });
        box.addView(auth);
        if (Session.filePickerEnabled()) {
            TextView docs = Ui.button(this, "用文件管理选择", false);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 180), Ui.dp(this, 44));
            dlp.topMargin = Ui.dp(this, 8);
            docs.setLayoutParams(dlp);
            docs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivityForResult(MediaUtil.pickFromDocuments(GalleryPickerActivity.this, multiple), REQ_DOCUMENTS);
                    } catch (Exception e) {
                        Ui.toast(GalleryPickerActivity.this, "没有可用的文件选择器");
                    }
                }
            });
            box.addView(docs);
        }
        box.setClickable(true);
        return box;
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) return true;
            return Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        emptyBox.setVisibility(View.VISIBLE);
        emptyText.setText("需要照片访问权限才能显示手机里的图片");
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_PERMISSION);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        if (hasPermission()) {
            emptyBox.setVisibility(View.GONE);
            loadImages();
        } else {
            emptyBox.setVisibility(View.VISIBLE);
            emptyText.setText("没有照片权限。可以再次授权，或用文件管理选择。");
        }
    }

    private void loadImages() {
        progress.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.GONE);
        DebugLog.i("gallery", "load images permission " + MediaUtil.permissionSummary(this));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final List<ImageItem> result = new ArrayList<>();
                Cursor cursor = null;
                try {
                    Uri collection = Build.VERSION.SDK_INT >= 29
                            ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    String[] projection = {
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.DATE_ADDED,
                            MediaStore.Images.Media.MIME_TYPE
                    };
                    String selection = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        selection = MediaStore.Images.Media.IS_PENDING + "=0";
                    }
                    cursor = getContentResolver().query(collection, projection, selection, null,
                            MediaStore.Images.Media.DATE_ADDED + " DESC");
                    if (cursor != null) {
                        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                        int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(idCol);
                            String name = cursor.getString(nameCol);
                            String mime = cursor.getString(mimeCol);
                            if (mime != null && mime.toLowerCase().contains("gif")) {
                                // GIF 也可以选择，缩略图取首帧。
                            }
                            result.add(new ImageItem(id, name, ContentUris.withAppendedId(collection, id)));
                        }
                    }
                } catch (Exception e) {
                    DebugLog.e("gallery", "query images failed", e);
                } finally {
                    if (cursor != null) cursor.close();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        items.clear();
                        items.addAll(result);
                        adapter.notifyDataSetChanged();
                        progress.setVisibility(View.GONE);
                        if (items.isEmpty()) {
                            emptyBox.setVisibility(View.VISIBLE);
                            emptyText.setText("没有找到图片。可以检查照片权限，或用文件管理选择。");
                        } else {
                            emptyBox.setVisibility(View.GONE);
                        }
                        DebugLog.i("gallery", "loaded " + items.size() + " images");
                    }
                });
            }
        });
    }

    private void toggle(ImageItem item) {
        if (!multiple) {
            selected.clear();
            selected.add(item.id);
            updateDone();
            finishWithSelection();
            return;
        }
        if (selected.contains(item.id)) {
            selected.remove(item.id);
        } else {
            if (selected.size() >= maxCount) {
                Ui.toast(this, "最多选择 " + maxCount + " 张");
                return;
            }
            selected.add(item.id);
        }
        adapter.notifyDataSetChanged();
        updateDone();
    }

    private void updateDone() {
        done.setEnabled(!selected.isEmpty());
        done.setText(selected.isEmpty() ? "完成" : "完成 (" + selected.size() + ")");
        done.setTextColor(selected.isEmpty() ? Ui.MUTED2 : Color.WHITE);
    }

    private void finishWithSelection() {
        if (selected.isEmpty()) {
            Ui.toast(this, "先选择图片");
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (ImageItem item : items) {
            if (selected.contains(item.id)) uris.add(item.uri);
        }
        if (uris.isEmpty()) return;
        Intent data = new Intent();
        data.setData(uris.get(0));
        if (uris.size() > 1) {
            ClipData clip = ClipData.newUri(getContentResolver(), "images", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clip.addItem(new ClipData.Item(uris.get(i)));
            }
            data.setClipData(clip);
        }
        DebugLog.i("gallery", "selected " + uris.size() + " images first=" + MediaUtil.describe(uris.get(0)));
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DOCUMENTS && resultCode == RESULT_OK && data != null) {
            setResult(RESULT_OK, data);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (cache != null) cache.evictAll();
        super.onDestroy();
    }

    private static class ImageItem {
        final long id;
        final String name;
        final Uri uri;

        ImageItem(long id, String name, Uri uri) {
            this.id = id;
            this.name = name;
            this.uri = uri;
        }
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            ImageView image;
            TextView check;
            if (convertView == null) {
                cell = new FrameLayout(GalleryPickerActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(GalleryPickerActivity.this, 116)));
                image = new ImageView(GalleryPickerActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(Ui.PANEL2);
                cell.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                check = new TextView(GalleryPickerActivity.this);
                check.setGravity(Gravity.CENTER);
                check.setTextSize(13);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        Ui.dp(GalleryPickerActivity.this, 24), Ui.dp(GalleryPickerActivity.this, 24));
                clp.gravity = Gravity.TOP | Gravity.RIGHT;
                clp.topMargin = Ui.dp(GalleryPickerActivity.this, 5);
                clp.rightMargin = Ui.dp(GalleryPickerActivity.this, 5);
                cell.addView(check, clp);
                cell.setTag(new Object[]{image, check});
            } else {
                cell = (FrameLayout) convertView;
                Object[] tags = (Object[]) cell.getTag();
                image = (ImageView) tags[0];
                check = (TextView) tags[1];
            }
            ImageItem item = items.get(position);
            image.setTag(item.uri.toString());
            image.setImageBitmap(null);
            image.setBackgroundColor(Ui.PANEL2);
            loadThumb(image, item);
            boolean chosen = selected.contains(item.id);
            check.setText(chosen ? "✓" : "");
            check.setTextColor(Color.WHITE);
            GradientDrawable badge = Ui.round(chosen ? Ui.ACCENT : Color.parseColor("#66000000"), 12);
            badge.setShape(GradientDrawable.OVAL);
            badge.setStroke(Ui.dp(GalleryPickerActivity.this, 1),
                    chosen ? Ui.ACCENT : Color.parseColor("#99FFFFFF"));
            check.setBackground(badge);
            return cell;
        }
    }

    private void loadThumb(final ImageView view, final ImageItem item) {
        final String key = item.uri.toString();
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        bitmap = getContentResolver().loadThumbnail(item.uri, new Size(240, 240), null);
                    } else {
                        bitmap = MediaStore.Images.Thumbnails.getThumbnail(
                                getContentResolver(), item.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    }
                } catch (Exception e) {
                    DebugLog.e("gallery", "thumbnail failed " + item.id, e);
                }
                if (bitmap == null) return;
                cache.put(key, bitmap);
                final Bitmap result = bitmap;
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(view.getTag())) view.setImageBitmap(result);
                    }
                });
            }
        });
    }
}
