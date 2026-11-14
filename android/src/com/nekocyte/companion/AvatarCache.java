package com.nekocyte.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 头像内存 + 磁盘缓存，避免每次切换页面都重新下载。 */
public class AvatarCache {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
            (int) Math.max(8 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    public static void load(final ImageView view, String url) {
        if (view == null || url == null || url.isEmpty()) return;
        final String full = url.startsWith("/") ? Session.server() + url : url;
        final String key = md5(full);
        view.setTag(full);
        Bitmap memory = MEMORY.get(key);
        if (memory != null) {
            view.setImageBitmap(memory);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                File file = cacheFile(key);
                if (file.exists()) {
                    bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) DebugLog.d("avatar", "disk hit " + key);
                }
                if (bitmap == null) {
                    bitmap = download(full, file);
                }
                if (bitmap == null) return;
                MEMORY.put(key, bitmap);
                final Bitmap result = bitmap;
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (full.equals(view.getTag())) view.setImageBitmap(result);
                    }
                });
            }
        });
    }

    private static Bitmap download(String url, File file) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "NekoMate/1.9 Android");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int sample = 1;
            while (options.outWidth / sample > 384 || options.outHeight / sample > 384) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap != null) {
                try {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(data);
                    fos.close();
                    DebugLog.d("avatar", "downloaded " + url + " bytes=" + data.length);
                } catch (Exception ignored) {
                }
            }
            return bitmap;
        } catch (Exception e) {
            DebugLog.e("avatar", "download failed " + url, e);
            return null;
        }
    }

    private static File cacheFile(String key) {
        File dir = new File(App.I.getFilesDir(), "avatar_cache");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".img");
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static void clear() {
        MEMORY.evictAll();
        try {
            File dir = new File(App.I.getFilesDir(), "avatar_cache");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
