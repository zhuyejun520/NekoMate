package com.nekocyte.companion;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** 本地 debug 日志：自动滚动、导出最近日志，避免日志无限增长。 */
public class DebugLog {
    private static final String TAG = "NekoMate";
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final long SESSION_ROTATE_BYTES = 256 * 1024;
    private static final int DUMP_LINES = 4000;
    private static final Object LOCK = new Object();

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "nekomate-debug.log");
    }

    public static void d(String tag, String message) {
        write(App.I, "D", tag, message);
    }

    public static void i(String tag, String message) {
        write(App.I, "I", tag, message);
    }

    public static void e(String tag, String message) {
        write(App.I, "E", tag, message);
    }

    public static void e(String tag, String message, Throwable error) {
        write(App.I, "E", tag, message + " | " + (error == null ? "" : error.getClass().getSimpleName() + ": " + error.getMessage()));
    }

    /** Called once per app process start. Keep one short previous session for context. */
    public static void beginSession(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.exists() && target.length() > SESSION_ROTATE_BYTES) {
                    rotate(target, new File(target.getParentFile(), "nekomate-debug.old.log"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.exists()) target.delete();
                File old = new File(target.getParentFile(), "nekomate-debug.old.log");
                if (old.exists()) old.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private static void write(Context context, String level, String tag, String message) {
        if (context == null) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + "/" + tag + ": " + message;
        Log.println(level.equals("E") ? Log.ERROR : level.equals("D") ? Log.DEBUG : Log.INFO, TAG, line);
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.exists() && target.length() > MAX_BYTES) {
                    File old = new File(target.getParentFile(), "nekomate-debug.old.log");
                    rotate(target, old);
                }
                FileOutputStream out = new FileOutputStream(target, true);
                out.write((line + "\n").getBytes("UTF-8"));
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void rotate(File target, File old) {
        try {
            if (old.exists()) old.delete();
            if (!target.renameTo(old)) {
                // Rename can fail on some OEM file systems; never keep appending to an
                // oversized file, otherwise the newest lines become impossible to find.
                RandomAccessFile raf = new RandomAccessFile(target, "rw");
                raf.setLength(0);
                raf.close();
            }
        } catch (Exception e) {
            try {
                RandomAccessFile raf = new RandomAccessFile(target, "rw");
                raf.setLength(0);
                raf.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String dump(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("NekoMate debug log\n");
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        sb.append("version: ").append(UpdateManager.VERSION_NAME).append(" (").append(UpdateManager.VERSION_CODE).append(")\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("server: ").append(Session.server()).append("\n");
        sb.append("user_id: ").append(Session.userId()).append("\n\n");
        synchronized (LOCK) {
            try {
                File target = file(context);
                if (target.exists()) {
                    Deque<String> tail = new ArrayDeque<>();
                    BufferedReader reader = new BufferedReader(new FileReader(target));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (tail.size() >= DUMP_LINES) tail.removeFirst();
                        tail.addLast(line);
                    }
                    reader.close();
                    for (String value : tail) sb.append(value).append("\n");
                } else {
                    sb.append("(no log file)\n");
                }
            } catch (Exception e) {
                sb.append("read log failed: ").append(e).append("\n");
            }
        }
        return sb.toString();
    }

    public static void writeToUri(Context context, Uri uri) throws Exception {
        OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new Exception("open uri failed");
        out.write(dump(context).getBytes("UTF-8"));
        out.close();
    }
}
