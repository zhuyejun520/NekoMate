package com.nekocyte.companion;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** 可选的 root 增强：通过 su 调用系统通知命令，失败时调用方回退普通通知。 */
public final class RootShell {
    private static Boolean available;

    private RootShell() {
    }

    public static synchronized boolean isAvailable() {
        if (available != null) return available;
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            reader.close();
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) process.destroy();
            available = finished && out.toString().contains("uid=0");
        } catch (Exception e) {
            available = false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
        DebugLog.i("root", "available=" + available);
        return available;
    }

    public static void checkAsync(final Context context, final Runnable onResult) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = isAvailable();
                if (onResult != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            onResult.run();
                        }
                    });
                }
            }
        }).start();
    }

    public static boolean postMessage(Context context, String title, String text, int convId) {
        String pkg = context.getPackageName();
        // Base64 avoids shell quoting problems when the AI text contains spaces,
        // quotes, newlines or emoji.
        String title64 = android.util.Base64.encodeToString(
                (title == null ? "" : title).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        String text64 = android.util.Base64.encodeToString(
                (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        String command = "am start-foreground-service"
                + " -a " + quote(UsageMonitorService.ACTION_ROOT_NOTIFY)
                + " -n " + pkg + "/.UsageMonitorService"
                + " --es token " + quote(Session.rootNotifyToken())
                + " --es title_b64 " + quote(title64)
                + " --es text_b64 " + quote(text64)
                + " --ei conv_id " + convId;
        boolean ok = run(command);
        DebugLog.i("root", "start notification service ok=" + ok);
        return ok;
    }

    public static void applyPowerSettings(String packageName) {
        run("dumpsys deviceidle whitelist +" + packageName);
        run("cmd appops set " + packageName + " RUN_IN_BACKGROUND allow");
    }

    public static void applyPowerSettingsAsync(final String packageName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                applyPowerSettings(packageName);
            }
        }).start();
    }

    private static boolean run(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            process.waitFor(5, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            DebugLog.i("root", "shell failed: " + e.getMessage());
            return false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String quote(String value) {
        if (value == null) value = "";
        return "'" + value.replace("'", "'\\''").replace("\n", " ") + "'";
    }
}
