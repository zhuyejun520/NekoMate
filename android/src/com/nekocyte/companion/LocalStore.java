package com.nekocyte.companion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** 本地缓存：好友、会话、消息、AI 好友、动态。 */
public class LocalStore extends SQLiteOpenHelper {
    private static final String DB = "nekomate_local.db";
    private static final int VERSION = 2;

    public LocalStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (user_id INTEGER, conv_id INTEGER, json TEXT, updated_at INTEGER, PRIMARY KEY(user_id, conv_id))");
        db.execSQL("CREATE TABLE messages (user_id INTEGER, conv_id INTEGER, msg_id INTEGER, json TEXT, PRIMARY KEY(user_id, msg_id))");
        db.execSQL("CREATE TABLE friends (user_id INTEGER, friend_id INTEGER, json TEXT, PRIMARY KEY(user_id, friend_id))");
        createExtraTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createExtraTables(db);
    }

    private static void createExtraTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_friends (user_id INTEGER, ai_id INTEGER, json TEXT, PRIMARY KEY(user_id, ai_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS moments (user_id INTEGER, moment_id INTEGER, json TEXT, PRIMARY KEY(user_id, moment_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS personas (user_id INTEGER, persona_id INTEGER, json TEXT, PRIMARY KEY(user_id, persona_id))");
    }

    public static void saveConversations(Context context, JSONArray arr) {
        if (arr == null) return;
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        int uid = Session.userId();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            ContentValues v = new ContentValues();
            v.put("user_id", uid);
            v.put("conv_id", c.optInt("id"));
            v.put("json", c.toString());
            v.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("conversations", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        store.close();
    }

    public static JSONArray loadConversations(Context context) {
        JSONArray out = new JSONArray();
        LocalStore store = new LocalStore(context);
        Cursor c = store.getReadableDatabase().query("conversations", new String[]{"json"},
                "user_id=?", new String[]{String.valueOf(Session.userId())}, null, null, "updated_at DESC");
        while (c.moveToNext()) {
            try {
                out.put(new JSONObject(c.getString(0)));
            } catch (Exception ignored) {
            }
        }
        c.close();
        store.close();
        return out;
    }

    public static void saveMessages(Context context, int convId, JSONArray arr) {
        if (arr == null || convId <= 0) return;
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        int uid = Session.userId();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null || m.optLong("id") == 0) continue;
            ContentValues v = new ContentValues();
            v.put("user_id", uid);
            v.put("conv_id", convId);
            v.put("msg_id", m.optLong("id"));
            v.put("json", m.toString());
            db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        // 每个会话保留最近 300 条，避免本地无限增长。
        db.execSQL("DELETE FROM messages WHERE user_id=? AND conv_id=? AND msg_id NOT IN " +
                        "(SELECT msg_id FROM messages WHERE user_id=? AND conv_id=? ORDER BY msg_id DESC LIMIT 300)",
                new Object[]{uid, convId, uid, convId});
        store.close();
    }

    public static JSONArray loadMessages(Context context, int convId, int limit) {
        JSONArray out = new JSONArray();
        LocalStore store = new LocalStore(context);
        Cursor c = store.getReadableDatabase().query("messages", new String[]{"json"},
                "user_id=? AND conv_id=?", new String[]{String.valueOf(Session.userId()), String.valueOf(convId)},
                null, null, "msg_id ASC", String.valueOf(Math.max(1, limit)));
        while (c.moveToNext()) {
            try {
                out.put(new JSONObject(c.getString(0)));
            } catch (Exception ignored) {
            }
        }
        c.close();
        store.close();
        return out;
    }

    public static void clearConversation(Context context, int convId) {
        LocalStore store = new LocalStore(context);
        store.getWritableDatabase().delete("messages", "user_id=? AND conv_id=?",
                new String[]{String.valueOf(Session.userId()), String.valueOf(convId)});
        store.close();
    }

    public static void removeConversation(Context context, int convId) {
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        String[] args = new String[]{String.valueOf(Session.userId()), String.valueOf(convId)};
        db.delete("messages", "user_id=? AND conv_id=?", args);
        db.delete("conversations", "user_id=? AND conv_id=?", args);
        store.close();
    }

    public static void clear(Context context) {
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        String uid = String.valueOf(Session.userId());
        db.delete("conversations", "user_id=?", new String[]{uid});
        db.delete("messages", "user_id=?", new String[]{uid});
        db.delete("friends", "user_id=?", new String[]{uid});
        db.delete("ai_friends", "user_id=?", new String[]{uid});
        db.delete("moments", "user_id=?", new String[]{uid});
        db.delete("personas", "user_id=?", new String[]{uid});
        store.close();
    }

    public static void saveAiFriends(Context context, JSONArray arr) {
        saveList(context, "ai_friends", "ai_id", "id", arr);
    }

    public static JSONArray loadAiFriends(Context context) {
        return loadList(context, "ai_friends", "ai_id");
    }

    public static void saveMoments(Context context, JSONArray arr) {
        saveList(context, "moments", "moment_id", "id", arr);
    }

    public static JSONArray loadMoments(Context context) {
        return loadList(context, "moments", "moment_id");
    }

    public static void savePersonas(Context context, JSONArray arr) {
        saveList(context, "personas", "persona_id", "id", arr);
    }

    public static JSONArray loadPersonas(Context context) {
        return loadList(context, "personas", "persona_id");
    }

    private static void saveList(Context context, String table, String idColumn, String jsonIdKey, JSONArray arr) {
        if (arr == null) return;
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        int uid = Session.userId();
        db.beginTransaction();
        try {
            db.delete(table, "user_id=?", new String[]{String.valueOf(uid)});
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                ContentValues v = new ContentValues();
                v.put("user_id", uid);
                v.put(idColumn, item.optInt(jsonIdKey));
                v.put("json", item.toString());
                db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            store.close();
        }
    }

    private static JSONArray loadList(Context context, String table, String idColumn) {
        JSONArray out = new JSONArray();
        LocalStore store = new LocalStore(context);
        Cursor c = store.getReadableDatabase().query(table, new String[]{"json"},
                "user_id=?", new String[]{String.valueOf(Session.userId())}, null, null, idColumn + " DESC");
        while (c.moveToNext()) {
            try {
                out.put(new JSONObject(c.getString(0)));
            } catch (Exception ignored) {
            }
        }
        c.close();
        store.close();
        return out;
    }

    public static void saveFriends(Context context, JSONArray arr) {
        if (arr == null) return;
        LocalStore store = new LocalStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        int uid = Session.userId();
        db.delete("friends", "user_id=?", new String[]{String.valueOf(uid)});
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            ContentValues v = new ContentValues();
            v.put("user_id", uid);
            v.put("friend_id", f.optInt("id"));
            v.put("json", f.toString());
            db.insertWithOnConflict("friends", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        store.close();
    }

    public static JSONArray loadFriends(Context context) {
        JSONArray out = new JSONArray();
        LocalStore store = new LocalStore(context);
        Cursor c = store.getReadableDatabase().query("friends", new String[]{"json"},
                "user_id=?", new String[]{String.valueOf(Session.userId())}, null, null, "friend_id ASC");
        while (c.moveToNext()) {
            try {
                out.put(new JSONObject(c.getString(0)));
            } catch (Exception ignored) {
            }
        }
        c.close();
        store.close();
        return out;
    }
}
