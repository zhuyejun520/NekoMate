package com.nekocyte.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public class Session {
    private static SharedPreferences sp;

    public static void init(Context c) {
        sp = c.getSharedPreferences("nekocyte", Context.MODE_PRIVATE);
    }

    private static SharedPreferences p() {
        if (sp == null) init(App.I);
        return sp;
    }

    public static String server() {
        String s = p().getString("server", "http://127.0.0.1:8080");
        if (s == null || s.trim().isEmpty()) s = "http://127.0.0.1:8080";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static void setServer(String s) {
        p().edit().putString("server", s == null ? "" : s.trim()).apply();
    }

    public static String token() {
        return p().getString("token", "");
    }

    public static int userId() {
        return p().getInt("user_id", 0);
    }

    private static String clean(String v) {
        return v == null || "null".equals(v) ? "" : v;
    }

    public static String nickname() {
        String v = clean(p().getString("nickname", "我"));
        return v.isEmpty() ? "我" : v;
    }

    public static String username() {
        return clean(p().getString("username", ""));
    }

    public static String city() {
        return clean(p().getString("city", ""));
    }

    public static String avatar() {
        return clean(p().getString("avatar", ""));
    }

    public static String bio() {
        return clean(p().getString("bio", ""));
    }

    public static String gender() {
        String value = clean(p().getString("gender", "secret"));
        return value.isEmpty() ? "secret" : value;
    }

    public static String tagsJson() {
        return p().getString("tags", "[]");
    }

    public static boolean filePickerEnabled() {
        return p().getBoolean("file_picker_enabled", false);
    }

    public static void setFilePickerEnabled(boolean enabled) {
        p().edit().putBoolean("file_picker_enabled", enabled).apply();
    }

    public static String pendingCameraUri() {
        return p().getString("pending_camera_uri", "");
    }

    public static void setPendingCameraUri(String uri) {
        p().edit().putString("pending_camera_uri", uri == null ? "" : uri).apply();
    }

    public static boolean rootNotifyEnabled() {
        return p().getBoolean("root_notify_enabled", false);
    }

    public static void setRootNotifyEnabled(boolean enabled) {
        p().edit().putBoolean("root_notify_enabled", enabled).apply();
    }

    public static String rootNotifyToken() {
        String token = p().getString("root_notify_token", "");
        if (token == null || token.isEmpty()) {
            token = java.util.UUID.randomUUID().toString().replace("-", "");
            p().edit().putString("root_notify_token", token).apply();
        }
        return token;
    }

    public static boolean isAdmin() {
        String role = p().getString("role", "user");
        return "admin".equals(role) || "super_admin".equals(role);
    }

    public static boolean loggedIn() {
        return token() != null && !token().isEmpty();
    }

    private static String s(JSONObject o, String key, String def) {
        if (o == null || o.isNull(key)) return def;
        String v = o.optString(key, def);
        return v == null || "null".equals(v) ? def : v;
    }

    public static void saveAuth(String token, JSONObject user) {
        SharedPreferences.Editor e = p().edit()
                .putString("token", token)
                .putInt("user_id", user.optInt("id", 0))
                .putString("nickname", s(user, "nickname", "我"))
                .putString("username", s(user, "username", ""))
                .putString("city", s(user, "city", ""))
                .putString("avatar", s(user, "avatar", ""))
                .putString("bio", s(user, "bio", ""))
                .putString("gender", s(user, "gender", "secret"))
                .putString("role", s(user, "role", "user"))
                .putString("tags", user.optJSONArray("tags") == null ? "[]" : user.optJSONArray("tags").toString());
        e.apply();
    }

    public static void updateProfile(JSONObject user) {
        p().edit()
                .putString("nickname", s(user, "nickname", nickname()))
                .putString("username", s(user, "username", username()))
                .putString("city", s(user, "city", city()))
                .putString("avatar", s(user, "avatar", avatar()))
                .putString("bio", s(user, "bio", bio()))
                .putString("gender", s(user, "gender", gender()))
                .putString("role", s(user, "role", p().getString("role", "user")))
                .putString("tags", user.optJSONArray("tags") == null ? tagsJson() : user.optJSONArray("tags").toString())
                .apply();
    }

    public static void clear() {
        String srv = server();
        String favorites = p().getString("sticker_favorites", "[]");
        boolean filePicker = p().getBoolean("file_picker_enabled", false);
        p().edit().clear()
                .putString("server", srv)
                .putString("sticker_favorites", favorites)
                .putBoolean("file_picker_enabled", filePicker)
                .apply();
    }

    public static int activeAi() {
        return p().getInt("active_ai", 0);
    }

    public static void setActiveAi(int id) {
        p().edit().putInt("active_ai", id).apply();
    }

    public static boolean serviceEnabled() {
        return p().getBoolean("service_enabled", false);
    }

    public static void setServiceEnabled(boolean enabled) {
        p().edit().putBoolean("service_enabled", enabled).apply();
    }

    public static boolean dark() {
        return p().getBoolean("dark_theme", false);
    }

    public static void setDark(boolean dark) {
        p().edit().putBoolean("dark_theme", dark).apply();
    }

    public static String chatBg() {
        return p().getString("chat_bg", "");
    }

    public static void setChatBg(String path) {
        p().edit().putString("chat_bg", path == null ? "" : path).apply();
    }

    public static int ignoredUpdate() {
        return p().getInt("ignored_update", 0);
    }

    public static void setIgnoredUpdate(int versionCode) {
        p().edit().putInt("ignored_update", versionCode).apply();
    }

    public static long downloadId() {
        return p().getLong("download_id", -1L);
    }

    public static void setDownloadId(long id) {
        p().edit().putLong("download_id", id).apply();
    }

    public static boolean rootEnabled(int aiId) {
        return p().getBoolean("root_" + aiId, false);
    }

    public static void setRootEnabled(int aiId, boolean enabled) {
        p().edit().putBoolean("root_" + aiId, enabled).apply();
    }

    public static String whitelist(int aiId) {
        return p().getString("wl_" + aiId, "[]");
    }

    public static void setWhitelist(int aiId, String json) {
        p().edit().putString("wl_" + aiId, json == null ? "[]" : json).apply();
    }

    public static long lastNotified(int convId) {
        return p().getLong("lastmsg_" + convId, 0L);
    }

    public static void setLastNotified(int convId, long messageId) {
        p().edit().putLong("lastmsg_" + convId, messageId).apply();
    }

    public static JSONArray favorites() {
        try {
            return new JSONArray(p().getString("sticker_favorites", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void addFavorite(String url, String words) {
        if (url == null || url.isEmpty()) return;
        JSONArray old = favorites();
        JSONArray out = new JSONArray();
        try {
            JSONObject item = new JSONObject();
            item.put("url", url);
            item.put("words", words == null ? "表情" : words);
            out.put(item);
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (url.equals(o.optString("url"))) continue;
                out.put(o);
                if (out.length() >= 60) break;
            }
        } catch (Exception ignored) {
        }
        p().edit().putString("sticker_favorites", out.toString()).apply();
    }

    public static String storeTheme() {
        return p().getString("store_theme", "");
    }

    public static void setStoreTheme(String json) {
        p().edit().putString("store_theme", json == null ? "" : json).apply();
    }

    public static String storeFont() {
        return p().getString("store_font", "");
    }

    public static void setStoreFont(String json) {
        p().edit().putString("store_font", json == null ? "" : json).apply();
    }

    public static String storeBubble() {
        return p().getString("store_bubble", "");
    }

    public static void setStoreBubble(String json) {
        p().edit().putString("store_bubble", json == null ? "" : json).apply();
    }

    public static String storeFrame() {
        return p().getString("store_frame", "");
    }

    public static void setStoreFrame(String json) {
        p().edit().putString("store_frame", json == null ? "" : json).apply();
    }
}
