package com.nekocyte.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONObject;

public class UpdateManager {
    public static final int VERSION_CODE = 76;
    public static final String VERSION_NAME = "2.3.47";

    public static void check(final Activity activity) {
        Api.appVersion(VERSION_CODE, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                if (!data.optBoolean("update", false)) return;
                final JSONObject latest = data.optJSONObject("latest");
                if (latest == null) return;
                final int code = latest.optInt("version_code", 0);
                final boolean force = latest.optBoolean("force_update", false);
                if (code <= VERSION_CODE) return;
                if (!force && Session.ignoredUpdate() >= code) return;
                show(activity, latest, force);
            }

            @Override
            public void fail(String message) {
                // 更新检查失败不影响正常使用
            }
        });
    }

    private static void show(final Activity activity, final JSONObject latest, final boolean force) {
        final int code = latest.optInt("version_code", 0);
        final String name = latest.optString("version_name", "新版本");
        String changelog = latest.optString("changelog", "").trim();
        if (changelog.isEmpty()) changelog = "优化体验与稳定性。";
        long size = latest.optLong("file_size", 0);
        String sizeText = size > 0 ? String.format("%.1f MB", size / 1024.0 / 1024.0) : "未知大小";
        final String url = latest.optString("download_url", "");
        String message = changelog + "\n\n安装包：" + sizeText + "\n当前版本：v" + VERSION_NAME;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 v" + name)
                .setMessage(message)
                .setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(activity, url, name);
                    }
                });
        if (force) {
            builder.setCancelable(false);
            builder.setNegativeButton("退出应用", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    activity.finishAffinity();
                }
            });
        } else {
            builder.setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Session.setIgnoredUpdate(code);
                }
            });
        }
        builder.show();
    }

    private static void download(final Activity activity, String url, String versionName) {
        if (url == null || url.isEmpty()) {
            Ui.toast(activity, "更新地址为空，请联系管理员");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception ignored) {
            }
            Ui.toast(activity, "请先允许「夜音」安装未知应用，然后重新点击更新");
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Ui.toast(activity, "系统下载服务不可用");
                return;
            }
            String fileName = "NekoMate-" + versionName + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("夜音更新 v" + versionName);
            request.setDescription("正在下载安装包…");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
            long id = dm.enqueue(request);
            Session.setDownloadId(id);
            Ui.toast(activity, "已开始下载，完成后会自动打开安装界面");
        } catch (Exception e) {
            Ui.toast(activity, "下载失败：" + e.getMessage());
        }
    }

    public static void installDownloaded(Context context, long id) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(id);
            android.database.Cursor cursor = dm.query(query);
            if (cursor == null) return;
            boolean ok = false;
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                ok = status == DownloadManager.STATUS_SUCCESSFUL;
            }
            cursor.close();
            if (!ok) return;
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) return;
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(install);
        } catch (Exception e) {
            Ui.toast(context, "安装失败：" + e.getMessage());
        }
    }
}
