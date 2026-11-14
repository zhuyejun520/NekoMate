package com.nekocyte.companion;

import android.app.AppOpsManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RootUtil {
    private static Boolean cachedRoot;

    public static boolean hasRoot() {
        if (cachedRoot != null) return cachedRoot;
        String out = su("id");
        cachedRoot = out != null && out.contains("uid=0");
        return cachedRoot;
    }

    public static String su(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                if (br.ready() && (line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                } else if (!p.isAlive()) {
                    break;
                } else {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            try {
                p.waitFor();
            } catch (Exception ignored) {
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean grantUsageViaRoot(Context c) {
        String out = su("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
        return out != null;
    }

    public static String foregroundViaRoot() {
        String out = su("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        if (out == null) return null;
        for (String line : out.split("\n")) {
            int idx = line.indexOf("u0 ");
            if (idx >= 0) {
                String rest = line.substring(idx + 3).trim();
                int slash = rest.indexOf('/');
                if (slash > 0) return rest.substring(0, slash).trim();
            }
        }
        return null;
    }
}
