package com.nekocyte.companion;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Api {
    public interface Cb {
        void ok(JSONObject data);

        void fail(String message);
    }

    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static JSONObject call(String method, String path, JSONObject body) throws Exception {
        String base = Session.server();
        if (base == null || base.isEmpty()) throw new Exception("请先填写服务器地址");
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        if (!Session.token().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + Session.token());
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        JSONObject obj;
        try {
            obj = text == null || text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            obj = new JSONObject();
        }
        if (code >= 400) {
            throw new Exception(obj.optString("detail", "请求失败（" + code + "）"));
        }
        return obj;
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return sb.toString().trim();
    }

    private static void async(final String method, final String path, final JSONObject body, final Cb cb) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = call(method, path, body);
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(o);
                        }
                    });
                } catch (final Exception e) {
                    DebugLog.e("api", method + " " + path + " failed: " + e.getMessage(), e);
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.fail(e.getMessage() == null ? "网络请求失败" : e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public static void login(String username, String password, Cb cb) {
        async("POST", "/api/v1/auth/login", obj("username", username, "password", password), cb);
    }

    public static void register(String username, String password, String nickname, Cb cb) {
        async("POST", "/api/v1/auth/register", obj("username", username, "password", password, "nickname", nickname), cb);
    }

    public static void me(Cb cb) {
        async("GET", "/api/v1/auth/me", null, cb);
    }

    public static void updateProfile(JSONObject body, Cb cb) {
        async("PUT", "/api/v1/auth/profile", body, cb);
    }

    public static void reviewProfile(String field, String text, Cb cb) {
        async("POST", "/api/v1/auth/profile/review", obj("field", field, "text", text), cb);
    }

    public static void privateKey(Cb cb) {
        async("GET", "/api/v1/ai/private-key", null, cb);
    }

    public static void savePrivateKey(String name, String baseUrl, String apiKey, String model, Cb cb) {
        async("PUT", "/api/v1/ai/private-key",
                obj("name", name, "base_url", baseUrl, "api_key", apiKey, "model", model), cb);
    }

    public static void deletePrivateKey(Cb cb) {
        async("DELETE", "/api/v1/ai/private-key", null, cb);
    }

    public static void friends(Cb cb) {
        async("GET", "/api/v1/friends", null, cb);
    }

    public static void friendSettings(int conversationId, Cb cb) {
        async("GET", "/api/v1/conversations/" + conversationId + "/settings", null, cb);
    }

    public static void updateFriendSettings(int conversationId, Boolean muted, Boolean pinned, Cb cb) {
        JSONObject body = new JSONObject();
        try {
            if (muted != null) body.put("muted", muted);
            if (pinned != null) body.put("pinned", pinned);
        } catch (Exception ignored) {
        }
        async("PUT", "/api/v1/conversations/" + conversationId + "/settings", body, cb);
    }

    public static void clearConversation(int conversationId, Cb cb) {
        async("POST", "/api/v1/conversations/" + conversationId + "/clear", new JSONObject(), cb);
    }

    public static void recallMessage(int conversationId, long messageId, Cb cb) {
        async("POST", "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/recall",
                new JSONObject(), cb);
    }

    public static void setRemark(int friendId, String remark, Cb cb) {
        async("PUT", "/api/v1/friends/" + friendId, obj("remark", remark), cb);
    }

    public static void deleteFriend(int friendId, Cb cb) {
        async("DELETE", "/api/v1/friends/" + friendId, null, cb);
    }

    public static void blockFriend(int friendId, Cb cb) {
        async("POST", "/api/v1/friends/" + friendId + "/block", new JSONObject(), cb);
    }

    public static void unblockFriend(int friendId, Cb cb) {
        async("DELETE", "/api/v1/friends/" + friendId + "/block", null, cb);
    }

    public static void requests(Cb cb) {
        async("GET", "/api/v1/friends/requests", null, cb);
    }

    public static void search(String q, Cb cb) {
        try {
            async("GET", "/api/v1/friends/search?q=" + URLEncoder.encode(q, "UTF-8"), null, cb);
        } catch (Exception e) {
            if (cb != null) cb.fail("搜索失败");
        }
    }

    public static void sendFriendRequest(String username, Cb cb) {
        async("POST", "/api/v1/friends/requests", obj("username", username, "message", "你好，加个好友吧"), cb);
    }

    public static void acceptRequest(int id, Cb cb) {
        async("POST", "/api/v1/friends/requests/" + id + "/accept", new JSONObject(), cb);
    }

    public static void rejectRequest(int id, Cb cb) {
        async("POST", "/api/v1/friends/requests/" + id + "/reject", new JSONObject(), cb);
    }

    public static void conversations(Cb cb) {
        async("GET", "/api/v1/conversations", null, cb);
    }

    public static void direct(int userId, Cb cb) {
        async("POST", "/api/v1/conversations/direct", obj("user_id", userId), cb);
    }

    public static void group(String title, List<Integer> ids, Cb cb) {
        JSONArray arr = new JSONArray();
        for (Integer id : ids) arr.put(id);
        async("POST", "/api/v1/conversations/group", obj("title", title, "member_ids", arr), cb);
    }

    public static void messages(int conversationId, int limit, Cb cb) {
        async("GET", "/api/v1/conversations/" + conversationId + "/messages?limit=" + limit, null, cb);
    }

    public static void sendMessage(int conversationId, String text, Cb cb) {
        async("POST", "/api/v1/conversations/" + conversationId + "/messages", obj("content", text), cb);
    }

    public static void setComposing(int conversationId, boolean active, Cb cb) {
        async("POST", "/api/v1/conversations/" + conversationId + "/composing",
                obj("active", active), cb);
    }

    public static void sendImageMessage(int conversationId, String imageUrl, String caption, Cb cb) {
        JSONObject body = obj("content", caption == null ? "" : caption, "content_type", "image", "image_url", imageUrl);
        async("POST", "/api/v1/conversations/" + conversationId + "/messages", body, cb);
    }

    public static void sendStickerMessage(int conversationId, String stickerUrl, String words, Cb cb) {
        JSONObject body = obj("content_type", "sticker", "sticker_url", stickerUrl, "words", words);
        async("POST", "/api/v1/conversations/" + conversationId + "/messages", body, cb);
    }

    public static void sendAudioMessage(int conversationId, String audioUrl, int durationSeconds, Cb cb) {
        JSONObject body = obj("content_type", "audio", "audio_url", audioUrl, "duration", durationSeconds);
        async("POST", "/api/v1/conversations/" + conversationId + "/messages", body, cb);
    }

    public static void transcribeAudio(String audioUrl, Cb cb) {
        async("POST", "/api/v1/speech/transcribe", obj("audio_url", audioUrl), cb);
    }

    public static void searchStickers(String words, int page, int limit, Cb cb) {
        final String keyword = words == null ? "" : words.trim();
        final String query = keyword.isEmpty()
                ? "type=1&limit=" + Math.max(1, Math.min(20, limit))
                : "type=2&words=" + URLEncoder.encode(keyword) + "&page=" + Math.max(1, page)
                + "&limit=" + Math.max(1, Math.min(20, limit));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject result = externalStickerSearch(query, keyword.isEmpty() ? "随机表情" : keyword);
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(result);
                        }
                    });
                } catch (final Exception e) {
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.fail(e.getMessage() == null ? "表情包搜索失败" : e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static JSONObject externalStickerSearch(String query, String words) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.ruseo.cn/api/bqb?" + query).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "NekoMate/1.4 Android");
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        JSONObject root = new JSONObject(text);
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new Exception(root.optString("msg", "表情包接口返回异常"));
        JSONArray res = data.optJSONArray("res");
        if (res == null || res.length() == 0) throw new Exception("没有搜到表情包");
        JSONObject out = new JSONObject();
        out.put("res", res);
        out.put("words", words);
        return out;
    }

    public static void read(int conversationId) {
        async("POST", "/api/v1/conversations/" + conversationId + "/read", new JSONObject(), null);
    }

    public static void conversationDetail(int conversationId, Cb cb) {
        async("GET", "/api/v1/conversations/" + conversationId, null, cb);
    }

    public static void addGroupMembers(int conversationId, JSONObject body, Cb cb) {
        async("POST", "/api/v1/conversations/" + conversationId + "/members", body, cb);
    }

    public static void removeGroupAi(int conversationId, int aiId, Cb cb) {
        async("DELETE", "/api/v1/conversations/" + conversationId + "/ai-members/" + aiId, null, cb);
    }

    public static void aiFriends(Cb cb) {
        async("GET", "/api/v1/ai/friends", null, cb);
    }

    public static void personas(Cb cb) {
        async("GET", "/api/v1/ai/personas", null, cb);
    }

    public static void createPersona(JSONObject body, Cb cb) {
        async("POST", "/api/v1/ai/personas", body, cb);
    }

    public static void updatePersona(int personaId, JSONObject body, Cb cb) {
        async("PUT", "/api/v1/ai/personas/" + personaId, body, cb);
    }

    public static void deletePersona(int personaId, Cb cb) {
        async("DELETE", "/api/v1/ai/personas/" + personaId, null, cb);
    }

    public static void updatePersonaAvatar(int personaId, String avatar, Cb cb) {
        async("PUT", "/api/v1/ai/personas/" + personaId + "/avatar", obj("avatar", avatar), cb);
    }

    public static void personaWorld(int page, String query, String author, Cb cb) {
        StringBuilder path = new StringBuilder("/api/v1/ai/personas/world?page=").append(Math.max(1, page));
        if (query != null && !query.trim().isEmpty()) {
            path.append("&q=").append(URLEncoder.encode(query.trim()));
        }
        if (author != null && !author.trim().isEmpty()) {
            path.append("&author=").append(URLEncoder.encode(author.trim()));
        }
        async("GET", path.toString(), null, cb);
    }

    public static void myPersonas(Cb cb) {
        async("GET", "/api/v1/ai/personas/mine", null, cb);
    }

    public static void submitPersona(int personaId, Cb cb) {
        async("POST", "/api/v1/ai/personas/" + personaId + "/submit", new JSONObject(), cb);
    }

    public static void pendingPersonas(Cb cb) {
        async("GET", "/api/v1/ai/personas/pending", null, cb);
    }

    public static void adminPublicPersonas(String query, Cb cb) {
        String path = "/api/v1/ai/personas/admin/public";
        if (query != null && !query.trim().isEmpty()) path += "?q=" + URLEncoder.encode(query.trim());
        async("GET", path, null, cb);
    }

    public static void approvePersona(int personaId, Cb cb) {
        async("POST", "/api/v1/ai/personas/" + personaId + "/approve", new JSONObject(), cb);
    }

    public static void rejectPersona(int personaId, Cb cb) {
        async("POST", "/api/v1/ai/personas/" + personaId + "/reject", new JSONObject(), cb);
    }

    public static void adminUpdatePersona(int personaId, JSONObject body, Cb cb) {
        async("PUT", "/api/v1/ai/personas/" + personaId + "/admin", body, cb);
    }

    public static void adminDeletePersona(int personaId, Cb cb) {
        async("DELETE", "/api/v1/ai/personas/" + personaId + "/admin", null, cb);
    }

    public static void privateKnowledge(Cb cb) {
        async("GET", "/api/v1/ai/knowledge", null, cb);
    }

    public static void createPrivateKnowledge(String name, String description, String kind, Cb cb) {
        async("POST", "/api/v1/ai/knowledge",
                obj("name", name, "description", description, "kind", kind), cb);
    }

    public static void updatePrivateKnowledge(int kbId, String kind, Cb cb) {
        async("PUT", "/api/v1/ai/knowledge/" + kbId, obj("kind", kind), cb);
    }

    public static void deletePrivateKnowledge(int kbId, Cb cb) {
        async("DELETE", "/api/v1/ai/knowledge/" + kbId, null, cb);
    }

    public static void importPrivateText(int kbId, String title, String text, Cb cb) {
        async("POST", "/api/v1/ai/knowledge/" + kbId + "/import-text", obj("title", title, "text", text), cb);
    }

    public static void importPrivateImage(int kbId, String title, String imageUrl, Cb cb) {
        async("POST", "/api/v1/ai/knowledge/" + kbId + "/import-image", obj("title", title, "image_url", imageUrl), cb);
    }

    public static void linkPersonaKnowledge(int personaId, JSONArray kbIds, Cb cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("kb_ids", kbIds);
        } catch (Exception ignored) {
        }
        async("PUT", "/api/v1/ai/personas/" + personaId + "/knowledge", body, cb);
    }

    public static void storeItems(Cb cb) {
        async("GET", "/api/v1/store", null, cb);
    }

    public static void storeInventory(Cb cb) {
        async("GET", "/api/v1/store/inventory", null, cb);
    }

    public static void purchaseStoreItem(int itemId, Cb cb) {
        async("POST", "/api/v1/store/purchase", obj("item_id", itemId), cb);
    }

    public static void adminStickerPacks(Cb cb) {
        async("GET", "/api/v1/store/admin/sticker-packs", null, cb);
    }

    public static void adminStickerPack(int packId, Cb cb) {
        async("GET", "/api/v1/store/admin/sticker-packs/" + packId, null, cb);
    }

    public static void createStickerPack(String name, String logo, Cb cb) {
        async("POST", "/api/v1/store/admin/sticker-packs", obj("name", name, "logo", logo), cb);
    }

    public static void addStickerPackItem(int packId, String url, Cb cb) {
        async("POST", "/api/v1/store/admin/sticker-packs/" + packId + "/items", obj("url", url), cb);
    }

    public static void setStickerWords(int itemId, String words, Cb cb) {
        async("PUT", "/api/v1/store/admin/sticker-items/" + itemId, obj("words", words), cb);
    }

    public static void deleteStickerItem(int itemId, Cb cb) {
        async("DELETE", "/api/v1/store/admin/sticker-items/" + itemId, null, cb);
    }

    public static void publishStickerPack(int packId, Cb cb) {
        async("POST", "/api/v1/store/admin/sticker-packs/" + packId + "/publish", new JSONObject(), cb);
    }

    public static void updateStickerPack(int packId, String name, String logo, Cb cb) {
        JSONObject body = new JSONObject();
        try {
            if (name != null) body.put("name", name);
            if (logo != null) body.put("logo", logo);
        } catch (Exception ignored) {
        }
        async("PUT", "/api/v1/store/admin/sticker-packs/" + packId, body, cb);
    }

    public static void unpublishStickerPack(int packId, Cb cb) {
        async("POST", "/api/v1/store/admin/sticker-packs/" + packId + "/unpublish", new JSONObject(), cb);
    }

    public static void deleteStickerPack(int packId, Cb cb) {
        async("DELETE", "/api/v1/store/admin/sticker-packs/" + packId, null, cb);
    }

    public static void adminAvatarFrames(Cb cb) {
        async("GET", "/api/v1/store/admin/avatar-frames", null, cb);
    }

    public static void createAvatarFrame(String name, int price, String image, Cb cb) {
        JSONObject body = obj("name", name, "price", price, "image", image);
        async("POST", "/api/v1/store/admin/avatar-frames", body, cb);
    }

    public static void updateAvatarFrame(int frameId, String name, Integer price,
                                         String image, Boolean enabled, Cb cb) {
        JSONObject body = new JSONObject();
        try {
            if (name != null) body.put("name", name);
            if (price != null) body.put("price", price);
            if (image != null) body.put("image", image);
            if (enabled != null) body.put("enabled", enabled);
        } catch (Exception ignored) {
        }
        async("PUT", "/api/v1/store/admin/avatar-frames/" + frameId, body, cb);
    }

    public static void deleteAvatarFrame(int frameId, Cb cb) {
        async("DELETE", "/api/v1/store/admin/avatar-frames/" + frameId, null, cb);
    }

    public static void checkinStatus(Cb cb) {
        async("GET", "/api/v1/store/checkin", null, cb);
    }

    public static void checkin(Cb cb) {
        async("POST", "/api/v1/store/checkin", new JSONObject(), cb);
    }

    public static void transferCoins(int conversationId, int amount, String note, Cb cb) {
        async("POST", "/api/v1/store/transfer",
                obj("conversation_id", conversationId, "amount", amount, "note", note), cb);
    }

    public static void sendRedPacket(int conversationId, int amount, String note, Cb cb) {
        async("POST", "/api/v1/store/redpacket",
                obj("conversation_id", conversationId, "amount", amount, "note", note), cb);
    }

    public static void claimRedPacket(long messageId, Cb cb) {
        async("POST", "/api/v1/store/redpacket/" + messageId + "/claim", new JSONObject(), cb);
    }

    public static void returnMoney(long messageId, Cb cb) {
        async("POST", "/api/v1/store/money/" + messageId + "/return", new JSONObject(), cb);
    }

    public static void addAi(int personaId, Cb cb) {
        async("POST", "/api/v1/ai/friends", obj("persona_id", personaId), cb);
    }

    public static void aiDetail(int id, Cb cb) {
        async("GET", "/api/v1/ai/friends/" + id, null, cb);
    }

    public static void updateAi(int id, JSONObject body, Cb cb) {
        async("PUT", "/api/v1/ai/friends/" + id, body, cb);
    }

    public static void deleteAi(int id, Cb cb) {
        async("DELETE", "/api/v1/ai/friends/" + id, null, cb);
    }

    public static void aiConversation(int id, Cb cb) {
        async("GET", "/api/v1/ai/friends/" + id + "/conversation", null, cb);
    }

    public static void whitelist(int aiId, Cb cb) {
        async("GET", "/api/v1/ai/friends/" + aiId + "/whitelist", null, cb);
    }

    public static void addWhitelist(int aiId, String pkg, String label, Cb cb) {
        async("POST", "/api/v1/ai/friends/" + aiId + "/whitelist",
                obj("package_name", pkg, "label", label, "enabled", true), cb);
    }

    public static void deleteWhitelist(int aiId, int itemId, Cb cb) {
        async("DELETE", "/api/v1/ai/friends/" + aiId + "/whitelist/" + itemId, null, cb);
    }

    public static void memories(int aiId, Cb cb) {
        async("GET", "/api/v1/ai/friends/" + aiId + "/memories", null, cb);
    }

    public static void deleteMemory(int aiId, int memoryId, Cb cb) {
        async("DELETE", "/api/v1/ai/friends/" + aiId + "/memories/" + memoryId, null, cb);
    }

    public static void proactive(int aiId, Cb cb) {
        async("GET", "/api/v1/ai/friends/" + aiId + "/proactive", null, cb);
    }

    public static void triggerProactive(int aiId, Cb cb) {
        async("POST", "/api/v1/ai/friends/" + aiId + "/proactive/trigger", new JSONObject(), cb);
    }

    public static void resetAi(int aiId, Cb cb) {
        async("POST", "/api/v1/ai/friends/" + aiId + "/reset",
                obj("clear_messages", true, "greeting", true), cb);
    }

    public static void usage(int aiId, JSONArray events, Cb cb) {
        async("POST", "/api/v1/ai/friends/" + aiId + "/usage-events", obj("events", events), cb);
    }

    public static void moments(Cb cb) {
        async("GET", "/api/v1/moments?scope=friends", null, cb);
    }

    public static void userMoments(int userId, Cb cb) {
        async("GET", "/api/v1/moments?scope=user&user_id=" + userId, null, cb);
    }

    public static void postMoment(String text, Cb cb) {
        async("POST", "/api/v1/moments", obj("content", text), cb);
    }

    public static void postMoment(String text, JSONArray images, Cb cb) {
        JSONObject body = obj("content", text);
        try {
            body.put("images", images == null ? new JSONArray() : images);
            body.put("visibility", "friends");
        } catch (Exception ignored) {
        }
        async("POST", "/api/v1/moments", body, cb);
    }

    public static void uploadFile(final File file, final Cb cb) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject o = upload(file);
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(o);
                        }
                    });
                } catch (final Exception e) {
                    DebugLog.e("api", "upload failed: " + e.getMessage(), e);
                    if (cb != null) main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.fail(e.getMessage() == null ? "上传失败" : e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private static JSONObject upload(File file) throws Exception {
        String base = Session.server();
        if (base == null || base.isEmpty()) throw new Exception("请先填写服务器地址");
        String boundary = "----NekoMate" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/v1/upload").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(8192);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (!Session.token().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + Session.token());
        }
        String filename = file.getName();
        if (!filename.toLowerCase().endsWith(".png")) filename = filename + ".png";
        try (OutputStream os = conn.getOutputStream()) {
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: image/png\r\n\r\n";
            os.write(head.getBytes(StandardCharsets.UTF_8));
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        JSONObject obj;
        try {
            obj = text == null || text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            obj = new JSONObject();
        }
        if (code >= 400) throw new Exception(obj.optString("detail", "上传失败（" + code + "）"));
        return obj;
    }

    public static void likeMoment(int id, Cb cb) {
        async("POST", "/api/v1/moments/" + id + "/like", new JSONObject(), cb);
    }

    public static void commentMoment(int id, String text, Cb cb) {
        async("POST", "/api/v1/moments/" + id + "/comments", obj("content", text), cb);
    }

    public static void deleteMoment(int id, Cb cb) {
        async("DELETE", "/api/v1/moments/" + id, null, cb);
    }

    public static void appVersion(int currentVersionCode, Cb cb) {
        async("GET", "/api/v1/app/version?platform=android&version_code=" + currentVersionCode, null, cb);
    }
}
