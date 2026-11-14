package com.nekocyte.companion;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UsageMonitorService extends Service {
    public static final String ACTION_START = "com.nekocyte.companion.START";
    public static final String ACTION_STOP = "com.nekocyte.companion.STOP";
    public static final String ACTION_ROOT_NOTIFY = "com.nekocyte.companion.ROOT_NOTIFY";

    private ScheduledExecutorService scheduler;
    private String lastPackage = "";
    private long lastSentAt = 0;
    private int aiId = 0;
    private String lastForeground = "";
    private boolean firstPoll = true;
    private boolean explicitStop = false;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, UsageMonitorService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
            // Some ROMs block background FGS starts (e.g. after boot); the user can reopen the app.
        }
    }

    public static void stop(Context c) {
        Session.setServiceEnabled(false);
        c.stopService(new Intent(c, UsageMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.init(this);
    }

    private String decodeExtra(Intent intent, String base64Key, String plainKey) {
        String value = intent.getStringExtra(plainKey);
        String encoded = intent.getStringExtra(base64Key);
        if (encoded != null && !encoded.isEmpty()) {
            try {
                value = new String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            explicitStop = true;
            Session.setServiceEnabled(false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_ROOT_NOTIFY.equals(intent.getAction())) {
            String token = intent.getStringExtra("token");
            if (token == null || !token.equals(Session.rootNotifyToken())) {
                DebugLog.i("root", "root notify rejected: bad token");
                return START_NOT_STICKY;
            }
            String title = decodeExtra(intent, "title_b64", "title");
            String text = decodeExtra(intent, "text_b64", "text");
            int convId = intent.getIntExtra("conv_id", 0);
            if (title == null || text == null) return START_NOT_STICKY;
            // A root-triggered service still has to enter foreground before posting.
            android.app.Notification fg = Notify.service(this, "夜音消息服务", "正在投递 AI 消息通知");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.SERVICE_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(Notify.SERVICE_ID, fg);
            }
            DebugLog.i("root", "foreground service notification");
            Notify.postNormal(this, title, text, convId, "ai");
            // Continue into the normal startup path so a service that was just
            // revived by root also starts polling.
        }
        explicitStop = false;
        aiId = Session.activeAi();
        if (!Session.loggedIn()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean companion = Session.serviceEnabled() && aiId > 0;
        android.app.Notification notification = Notify.service(
                this,
                companion ? "夜音正在陪伴" : "夜音消息服务",
                companion ? "正在监听白名单应用，同时同步聊天消息" : "正在后台同步好友、群聊和 AI 消息");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notify.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(Notify.SERVICE_ID, notification);
        }
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(2);
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkUsage();
                    } catch (Exception ignored) {
                    }
                }
            }, 1, 2, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        pollMessages();
                    } catch (Exception ignored) {
                    }
                }
            }, 3, 10, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void checkUsage() {
        if (!Session.serviceEnabled() || aiId <= 0) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;

        String pkg = null;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 12000, now);
            if (events != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                long best = 0;
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    int t = e.getEventType();
                    if (t == 1 || t == 23) { // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                        if (e.getTimeStamp() >= best) {
                            best = e.getTimeStamp();
                            pkg = e.getPackageName();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (pkg == null || pkg.isEmpty()) {
            if (RootUtil.hasRoot()) {
                String fg = RootUtil.foregroundViaRoot();
                if (fg != null && !fg.isEmpty()) pkg = fg;
            }
        }
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return;
        if (!pkg.equals(lastForeground)) {
            lastForeground = pkg;
        }

        JSONObject item = whitelistItem(pkg);
        if (item == null) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPackage) && now - lastSentAt < 60000) return;
        lastPackage = pkg;
        lastSentAt = now;

        String label = item.optString("label", pkg);
        JSONArray arr = new JSONArray();
        JSONObject ev = new JSONObject();
        try {
            ev.put("package", pkg);
            ev.put("label", label);
            ev.put("ts", now);
            ev.put("event", "foreground");
            arr.put(ev);
        } catch (Exception ignored) {
        }
        Api.usage(aiId, arr, null);
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Notify.SERVICE_ID, Notify.service(this, "看到你在用「" + label + "」"));
        } catch (Exception ignored) {
        }
    }

    private JSONObject whitelistItem(String pkg) {
        try {
            JSONArray arr = new JSONArray(Session.whitelist(aiId));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!o.optBoolean("enabled", true)) continue;
                if (pkg.equals(o.optString("package_name"))) return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void pollMessages() {
        DebugLog.i("service", "poll conversations");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NekoMate:poll");
                lock.acquire(20000);
            }
        } catch (Exception ignored) {
        }
        Api.conversations(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("conversations");
                if (arr == null) return;
                DebugLog.i("service", "conversations=" + arr.length());
                firstPoll = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    JSONObject last = c.optJSONObject("last_message");
                    if (last == null) continue;
                    long id = last.optLong("id");
                    int convId = c.optInt("id");
                    long seen = Session.lastNotified(convId);
                    // 首次见到该会话时只建立基线，避免安装后把历史消息全部弹一遍。
                    if (seen <= 0) {
                        Session.setLastNotified(convId, id);
                        continue;
                    }
                    if (id <= seen) continue;
                    String senderType = last.optString("sender_type", "user");
                    int senderId = last.optInt("sender_id", 0);
                    Session.setLastNotified(convId, id);
                    if (c.optBoolean("muted", false)) continue;
                    if ("system".equals(senderType)) continue;
                    if ("user".equals(senderType) && senderId == Session.userId()) continue;
                    if (convId == ChatActivity.currentConvId) continue;

                    String type = c.optString("type", "direct");
                    String title = c.optString("title", "");
                    if (title == null || title.isEmpty() || "null".equals(title)) {
                        JSONObject target = c.optJSONObject("target");
                        title = target == null ? "新消息" : target.optString("nickname", "新消息");
                    }
                    String content = last.optString("content", "");
                    if ("image".equals(last.optString("content_type"))) content = "🖼 图片";
                    String senderName = last.optString("sender_name", "");
                    if ("group".equals(type) && senderName != null && !senderName.isEmpty()
                            && !"null".equals(senderName)) {
                        content = senderName + "：" + content;
                    }
                    Notify.message(UsageMonitorService.this, title, content, convId, type);
                }
            }

            @Override
            public void fail(String message) {
                DebugLog.i("service", "conversations failed: " + message);
            }
        });
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart(3000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (!explicitStop) scheduleRestart(5000);
        super.onDestroy();
    }

    private void scheduleRestart(long delayMs) {
        if (!Session.loggedIn()) return;
        try {
            Intent restart = new Intent(this, UsageMonitorService.class);
            restart.setAction(ACTION_START);
            PendingIntent pi = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, 77, restart,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.getService(this, 77, restart,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMs, pi);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
