package com.nekocyte.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

public class Notify {
    public static final String CH_SERVICE = "neko_service";
    public static final String CH_MSG = "neko_msg_high_v2";
    public static final String CH_MSG_OLD = "neko_msg";
    public static final int SERVICE_ID = 1101;

    public static void init(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel service = new NotificationChannel(CH_SERVICE, "后台消息服务", NotificationManager.IMPORTANCE_LOW);
            service.setDescription("同步好友、群聊、AI 消息，并在开启陪伴时监听白名单应用");
            NotificationChannel msg = new NotificationChannel(CH_MSG, "聊天消息", NotificationManager.IMPORTANCE_HIGH);
            msg.setDescription("好友、群聊、AI 好友发来的新消息（声音 + 悬浮提醒）");
            msg.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            msg.enableVibration(true);
            msg.setVibrationPattern(new long[]{0, 300, 180, 300});
            msg.enableLights(true);
            msg.setLightColor(0xFF7A5CFF);
            msg.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            msg.setShowBadge(true);
            nm.createNotificationChannel(service);
            nm.createNotificationChannel(msg);
            // 旧频道是 DEFAULT 重要性，旧通知会继续静默；新消息统一使用 HIGH 频道。
            try {
                nm.deleteNotificationChannel(CH_MSG_OLD);
            } catch (Exception ignored) {
            }
        }
    }

    public static Notification service(Context c, String text) {
        return service(c, "夜音正在陪伴", text);
    }

    public static Notification service(Context c, String title, String text) {
        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(c, UsageMonitorService.class);
        stop.setAction(UsageMonitorService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(c, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH_SERVICE)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_notify),
                        "停止", stopPi).build());
        return b.build();
    }

    public static void message(final Context c, final String title, String text, final int convId, final String type) {
        if ("ai".equals(type) && Session.rootNotifyEnabled()) {
            final Context app = c.getApplicationContext();
            final String safeText = text == null ? "" : (text.length() > 500 ? text.substring(0, 500) : text);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (RootShell.isAvailable() && RootShell.postMessage(app, title, safeText, convId)) return;
                    normalMessage(app, title, safeText, convId, type);
                }
            }).start();
            return;
        }
        normalMessage(c, title, text, convId, type);
    }

    private static void normalMessage(Context c, String title, String text, int convId, String type) {
        postNormal(c, title, text, convId, type);
    }

    public static void postNormal(Context c, String title, String text, int convId, String type) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent open = new Intent(c, ChatActivity.class);
        open.putExtra("conv_id", convId);
        open.putExtra("title", title);
        open.putExtra("type", type == null ? "ai" : type);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, convId + 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH_MSG)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setVibrate(new long[]{0, 300, 180, 300})
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setColor(0xFF7A5CFF);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(convId + 2000, b.build());
    }
}
