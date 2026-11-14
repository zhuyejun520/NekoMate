package com.nekocyte.companion;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FriendsPage extends BasePage {
    public FriendsPage(MainActivity host) {
        super(host);
    }

    @Override
    public void refresh() {
        clear();
        final LinearLayout requests = Ui.column(host);
        final LinearLayout friends = Ui.column(host);
        box.addView(requests, full());
        box.addView(friends, full());
        final Map<Integer, JSONObject> convs = new LinkedHashMap<>();
        JSONArray cachedConvs = LocalStore.loadConversations(host);
        for (int i = 0; i < cachedConvs.length(); i++) {
            JSONObject c = cachedConvs.optJSONObject(i);
            if (c != null) convs.put(c.optInt("id"), c);
        }
        JSONArray cachedFriends = LocalStore.loadFriends(host);
        if (cachedFriends.length() > 0) {
            renderFriends(friends, cachedFriends, convs);
        } else {
            friends.addView(Ui.tv(host, "正在加载好友…", 12.5f, Ui.MUTED2, false));
        }

        Api.requests(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                renderRequests(requests, data.optJSONArray("incoming"));
            }

            @Override
            public void fail(String message) {
            }
        });

        Api.conversations(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("conversations");
                if (arr != null) {
                    LocalStore.saveConversations(host, arr);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c != null) convs.put(c.optInt("id"), c);
                    }
                }
                Api.friends(new Api.Cb() {
                    @Override
                    public void ok(JSONObject d2) {
                        LocalStore.saveFriends(host, d2.optJSONArray("friends"));
                        renderFriends(friends, d2.optJSONArray("friends"), convs);
                    }

                    @Override
                    public void fail(String message) {
                        JSONArray cache = LocalStore.loadFriends(host);
                        if (cache.length() > 0) {
                            renderFriends(friends, cache, convs);
                        } else {
                            friends.removeAllViews();
                            friends.addView(Ui.tv(host, message, 13, Ui.RED, false));
                        }
                    }
                });
            }

            @Override
            public void fail(String message) {
                JSONArray cached = LocalStore.loadConversations(host);
                for (int i = 0; i < cached.length(); i++) {
                    JSONObject c = cached.optJSONObject(i);
                    if (c != null) convs.put(c.optInt("id"), c);
                }
                Api.friends(new Api.Cb() {
                    @Override
                    public void ok(JSONObject d2) {
                        LocalStore.saveFriends(host, d2.optJSONArray("friends"));
                        renderFriends(friends, d2.optJSONArray("friends"), convs);
                    }

                    @Override
                    public void fail(String m) {
                        JSONArray cache = LocalStore.loadFriends(host);
                        if (cache.length() > 0) {
                            renderFriends(friends, cache, convs);
                        } else {
                            friends.removeAllViews();
                            friends.addView(Ui.tv(host, m, 13, Ui.RED, false));
                        }
                    }
                });
            }
        });
    }

    private void renderRequests(LinearLayout container, JSONArray incoming) {
        container.removeAllViews();
        if (incoming == null || incoming.length() == 0) return;

        LinearLayout head = Ui.row(host);
        head.addView(Ui.tv(host, "好友申请", 15, Ui.TEXT, true));
        head.addView(Ui.badge(host, String.valueOf(incoming.length()), Ui.ACCENT2));
        container.addView(head);

        for (int i = 0; i < incoming.length(); i++) {
            final JSONObject req = incoming.optJSONObject(i);
            if (req == null) continue;
            final JSONObject user = req.optJSONObject("user");
            LinearLayout card = Ui.card(host);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(host, 8);
            card.setLayoutParams(lp);

            LinearLayout top = Ui.row(host);
            top.addView(Ui.avatar(host, user == null ? "" : user.optString("avatar"), 40));
            LinearLayout names = Ui.column(host);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = Ui.dp(host, 10);
            names.setLayoutParams(nlp);
            names.addView(Ui.tv(host, user == null ? "用户" : user.optString("nickname"), 14, Ui.TEXT, true));
            String msg = req.optString("message", "");
            names.addView(Ui.tv(host, msg.isEmpty() ? "请求加你为好友" : msg, 11.5f, Ui.MUTED2, false));
            top.addView(names);
            card.addView(top);

            LinearLayout actions = Ui.row(host);
            actions.setGravity(Gravity.END);
            actions.setPadding(0, Ui.dp(host, 8), 0, 0);
            TextView accept = Ui.button(host, "同意", true);
            TextView reject = Ui.button(host, "拒绝", false);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.rightMargin = Ui.dp(host, 8);
            accept.setLayoutParams(alp);
            accept.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.acceptRequest(req.optInt("id"), new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(host, "已添加好友");
                            refresh();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(host, message);
                        }
                    });
                }
            });
            reject.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.rejectRequest(req.optInt("id"), new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            refresh();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(host, message);
                        }
                    });
                }
            });
            actions.addView(accept);
            actions.addView(reject);
            card.addView(actions);
            container.addView(card);
        }
    }

    private void renderFriends(LinearLayout container, JSONArray friends, Map<Integer, JSONObject> convs) {
        container.removeAllViews();
        if (friends == null || friends.length() == 0) {
            LinearLayout empty = Ui.card(host);
            TextView t = Ui.tv(host, "还没有好友\n搜索用户名，加个好友开始聊天吧", 13, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(host, 18), 0, Ui.dp(host, 12));
            empty.addView(t);
            TextView add = Ui.button(host, "＋ 添加好友", true);
            empty.addView(add);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.addFriendDialog();
                }
            });
            container.addView(empty, full());
        } else {
            List<JSONObject> ordered = new ArrayList<>();
            for (int i = 0; i < friends.length(); i++) {
                JSONObject f = friends.optJSONObject(i);
                if (f != null) ordered.add(f);
            }
            Collections.sort(ordered, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    JSONObject ca = convs.get(a.optInt("conversation_id", 0));
                    JSONObject cb = convs.get(b.optInt("conversation_id", 0));
                    boolean pa = ca != null && ca.optBoolean("pinned", false);
                    boolean pb = cb != null && cb.optBoolean("pinned", false);
                    if (pa != pb) return pa ? -1 : 1;
                    String ta = ca == null ? "" : ca.optString("last_message_at", "");
                    String tb = cb == null ? "" : cb.optString("last_message_at", "");
                    return tb.compareTo(ta);
                }
            });
            TextView head = Ui.tv(host, "好友 · " + ordered.size(), 15, Ui.TEXT, true);
            head.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 8));
            container.addView(head);

            for (JSONObject f : ordered) {
                final int convId = f.optInt("conversation_id", 0);
                String remark = Ui.str(f, "remark", "");
                final String name = remark.isEmpty()
                        ? Ui.str(f, "nickname", "好友") : remark;
                JSONObject conv = convs.get(convId);
                String preview = Ui.str(conv, "last_message_preview", "");
                int unread = conv == null ? 0 : conv.optInt("unread", 0);

                LinearLayout card = Ui.card(host);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(host, 8);
                card.setLayoutParams(lp);
                card.setClickable(true);

                LinearLayout row = Ui.row(host);
                String friendFrame = f.optString("avatar_frame", "");
                row.addView(friendFrame.isEmpty()
                        ? Ui.avatar(host, f.optString("avatar"), 46)
                        : Ui.avatarCircleFramed(host, f.optString("avatar"), friendFrame, 46));
                LinearLayout col = Ui.column(host);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                clp.leftMargin = Ui.dp(host, 10);
                col.setLayoutParams(clp);
                col.addView(Ui.tv(host, name, 14.5f, Ui.TEXT, true));
                col.addView(Ui.tv(host, preview.isEmpty() ? "@" + f.optString("username") : preview,
                        12, Ui.MUTED, false));
                row.addView(col);
                if (unread > 0) {
                    row.addView(Ui.badge(host, String.valueOf(unread), Ui.RED));
                }
                card.addView(row);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (convId > 0) host.openChat(convId, name, "direct", 0);
                    }
                });
                container.addView(card);
            }
        }
        renderGroups(container, convs);
    }

    private void renderGroups(LinearLayout container, Map<Integer, JSONObject> convs) {
        int count = 0;
        for (JSONObject conv : convs.values()) {
            if (conv != null && "group".equals(conv.optString("type"))) count++;
        }
        TextView head = Ui.tv(host, "群聊" + (count > 0 ? " · " + count : ""), 15, Ui.TEXT, true);
        head.setPadding(0, Ui.dp(host, 14), 0, Ui.dp(host, 8));
        container.addView(head);
        if (count == 0) {
            container.addView(Ui.tv(host, "还没有群聊，点右上角 ＋ 创建群聊", 12.5f, Ui.MUTED2, false));
            return;
        }
        for (JSONObject conv : convs.values()) {
            if (conv == null || !"group".equals(conv.optString("type"))) continue;
            final int convId = conv.optInt("id");
            final String name = Ui.str(conv, "title", "群聊");
            String preview = Ui.str(conv, "last_message_preview", "");
            int unread = conv.optInt("unread", 0);
            LinearLayout card = Ui.card(host);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(host, 8);
            card.setLayoutParams(lp);
            LinearLayout row = Ui.row(host);
            row.addView(Ui.avatar(host, "👥", 46));
            LinearLayout col = Ui.column(host);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            clp.leftMargin = Ui.dp(host, 10);
            col.setLayoutParams(clp);
            col.addView(Ui.tv(host, name, 14.5f, Ui.TEXT, true));
            col.addView(Ui.tv(host, preview, 12, Ui.MUTED, false));
            row.addView(col);
            if (unread > 0) row.addView(Ui.badge(host, String.valueOf(unread), Ui.RED));
            card.addView(row);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.openChat(convId, name, "group", 0);
                }
            });
            container.addView(card);
        }
    }
}
