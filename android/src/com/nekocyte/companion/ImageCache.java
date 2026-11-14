package com.nekocyte.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** 图片内存 + 磁盘缓存，避免实时刷新时重复下载造成闪烁，并支持离线显示。 */
public final class ImageCache {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY;
    private static final LruCache<String, byte[]> BYTES;
    private static final File DIR;

    public interface BytesCallback {
        void ok(byte[] data);
    }

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        MEMORY = new LruCache<String, Bitmap>(Math.max(8 * 1024, maxKb / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        BYTES = new LruCache<String, byte[]>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value.length;
            }
        };
        DIR = new File(App.I.getCacheDir(), "image_cache");
        if (!DIR.exists()) DIR.mkdirs();
    }

    private ImageCache() {
    }

    private static String key(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.startsWith("/")) value = Session.server() + value;
        return value;
    }

    private static File file(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return new File(DIR, hex.toString() + ".img");
        } catch (Exception e) {
            return new File(DIR, Integer.toHexString(key.hashCode()) + ".img");
        }
    }

    public static void load(final ImageView view, final String url) {
        if (view == null || url == null || url.trim().isEmpty()) return;
        final String key = key(url);
        view.setTag(key);
        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        final File target = file(key);
        if (target.exists() && target.length() > 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bitmap = BitmapFactory.decodeFile(target.getAbsolutePath());
                    if (bitmap != null) MEMORY.put(key, bitmap);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (key.equals(view.getTag()) && bitmap != null) view.setImageBitmap(bitmap);
                        }
                    });
                }
            }).start();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = download(key);
                    if (data.length == 0) return;
                    write(target, data);
                    final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bitmap != null) MEMORY.put(key, bitmap);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (key.equals(view.getTag()) && bitmap != null) view.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception e) {
                    DebugLog.i("image", "load failed " + key + " " + e.getMessage());
                }
            }
        }).start();
    }

    public static void prefetch(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String key = key(url);
        if (MEMORY.get(key) != null) return;
        final File target = file(key);
        if (target.exists() && target.length() > 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = download(key);
                    if (data.length > 0) write(target, data);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    public static void bytes(final String url, final BytesCallback callback) {
        if (url == null || url.trim().isEmpty()) return;
        final String key = key(url);
        byte[] cached = BYTES.get(key);
        if (cached != null) {
            callback.ok(cached);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File target = file(key);
                    byte[] data = null;
                    if (target.exists() && target.length() > 0) {
                        data = read(target);
                    }
                    if (data == null || data.length == 0) {
                        data = download(key);
                        if (data.length > 0) write(target, data);
                    }
                    if (data == null || data.length == 0) return;
                    BYTES.put(key, data);
                    final byte[] result = data;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.ok(result);
                        }
                    });
                } catch (Exception e) {
                    DebugLog.i("image", "bytes failed " + key + " " + e.getMessage());
                }
            }
        }).start();
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "NekoMate/2.2 Android");
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void write(File file, byte[] data) throws Exception {
        File temp = new File(file.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        out.write(data);
        out.close();
        if (file.exists()) file.delete();
        if (!temp.renameTo(file)) {
            temp.delete();
        }
    }

    private static byte[] read(File file) throws Exception {
        InputStream in = new java.io.FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    public static void clear() {
        MEMORY.evictAll();
        BYTES.evictAll();
        File[] files = DIR.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
