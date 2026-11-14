package com.nekocyte.companion;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** 语音消息下载、离线缓存和单实例播放。 */
public final class AudioCache {
    public interface Listener {
        void onError(String message);

        default void onStart() {
        }

        default void onComplete() {
        }
    }

    private static final File DIR = new File(App.I.getCacheDir(), "audio_cache");
    private static MediaPlayer player;
    private static String playingKey = "";

    private AudioCache() {
    }

    private static String key(String url) {
        if (url == null) return "";
        return url.startsWith("/") ? Session.server() + url : url;
    }

    private static File fileFor(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            String ext = key.toLowerCase().contains(".wav") ? ".wav" : ".m4a";
            return new File(DIR, hex + ext);
        } catch (Exception e) {
            return new File(DIR, Integer.toHexString(key.hashCode()) + ".m4a");
        }
    }

    public static void prefetch(String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String key = key(url);
        final File target = fileFor(key);
        if (target.exists() && target.length() > 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download(key, target);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    public static void store(String url, File source) {
        if (url == null || url.trim().isEmpty() || source == null || !source.isFile()) return;
        final String key = key(url);
        final File target = fileFor(key);
        if (!DIR.exists()) DIR.mkdirs();
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(source);
            File temp = new File(target.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(temp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            if (target.exists()) target.delete();
            temp.renameTo(target);
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        File[] files = DIR.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    public static void play(final Context context, final String url, final Listener listener) {
        if (url == null || url.trim().isEmpty()) return;
        final String key = key(url);
        final File target = fileFor(key);
        if (target.exists() && target.length() > 0) {
            playFile(context, key, target, listener);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download(key, target);
                    android.os.Handler main = new android.os.Handler(context.getMainLooper());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            playFile(context, key, target, listener);
                        }
                    });
                } catch (Exception e) {
                    android.os.Handler main = new android.os.Handler(context.getMainLooper());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onError("语音加载失败");
                        }
                    });
                }
            }
        }).start();
    }

    private static void playFile(Context context, String key, File file, final Listener listener) {
        try {
            if (!DIR.exists()) DIR.mkdirs();
            if (player != null) {
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
                player.release();
                player = null;
            }
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(context, Uri.fromFile(file));
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                    if (listener != null) listener.onStart();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    mp.release();
                    if (player == mp) player = null;
                    if (listener != null) listener.onComplete();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    mp.release();
                    if (player == mp) player = null;
                    if (listener != null) listener.onError("语音播放失败");
                    return true;
                }
            });
            playingKey = key;
            player.prepareAsync();
        } catch (Exception e) {
            if (listener != null) listener.onError("语音播放失败");
        }
    }

    private static void download(String url, File target) throws Exception {
        if (!DIR.exists()) DIR.mkdirs();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        InputStream in = conn.getInputStream();
        File temp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
        if (target.exists()) target.delete();
        if (!temp.renameTo(target)) temp.delete();
    }
}
