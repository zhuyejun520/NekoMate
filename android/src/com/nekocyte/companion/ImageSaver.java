package com.nekocyte.companion;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 把网络图片保存到系统相册（Android 10+ 走 MediaStore，旧系统走公共 Pictures）。 */
public class ImageSaver {
    public interface Callback {
        void ok(String name);

        void fail(String message);
    }

    public static void save(final Activity activity, final String url, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = download(url);
                    if (data == null || data.length == 0) throw new Exception("图片内容为空");
                    String suffix = url != null && url.toLowerCase(Locale.ROOT).contains(".png") ? "png" : "jpg";
                    String mime = "png".equals(suffix) ? "image/png" : "image/jpeg";
                    String name = "NekoMate_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                            + "_" + (System.currentTimeMillis() % 1000) + "." + suffix;
                    if (Build.VERSION.SDK_INT >= 29) {
                        saveModern(activity, name, mime, data);
                    } else {
                        saveLegacy(activity, name, mime, data);
                    }
                    final String saved = name;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.ok(saved);
                        }
                    });
                } catch (final Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.fail(e.getMessage() == null ? "保存失败" : e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private static byte[] download(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) throw new Exception("图片地址为空");
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma < 0) throw new Exception("图片数据无效");
            return Base64.decode(raw.substring(comma + 1), Base64.DEFAULT);
        }
        String target = raw.startsWith("/") ? Session.server() + raw : raw;
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoInput(true);
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void saveModern(Context context, String name, String mime, byte[] data) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NekoMate");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("无法写入相册");
        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) throw new Exception("无法写入相册文件");
        out.write(data);
        out.close();
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private static void saveLegacy(Context context, String name, String mime, byte[] data) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NekoMate");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建相册目录");
        File target = new File(dir, name);
        FileOutputStream out = new FileOutputStream(target);
        out.write(data);
        out.close();
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
    }
}
