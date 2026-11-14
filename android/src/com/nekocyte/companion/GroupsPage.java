package com.nekocyte.companion;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class GroupsPage extends BasePage {
    public GroupsPage(MainActivity host) {
        super(host);
    }

    @Override
    public void refresh() {
        clear();
        LinearLayout top = Ui.row(host);
        TextView add = Ui.button(host, "＋ 创建群聊", true);
        top.addView(add);
        box.addView(top, full());
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.createGroupDialog();
            }
        });

        final LinearLayout list = Ui.column(host);
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = Ui.dp(host, 10);
        box.addView(list, lp);
        list.addView(Ui.tv(host, "正在加载群聊…", 12.5f, Ui.MUTED2, false));

        Api.conversations(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                list.removeAllViews();
                JSONArray arr = data.optJSONArray("conversations");
                if (arr != null) LocalStore.saveConversations(host, arr);
                int count = 0;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null || !"group".equals(c.optString("type"))) continue;
                        count++;
                        render(list, c);
                    }
                }
                if (count == 0) {
                    LinearLayout empty = Ui.card(host);
                    TextView t = Ui.tv(host, "还没有群聊\n拉上好友，一起聊点新鲜事吧", 13, Ui.MUTED, false);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(0, Ui.dp(host, 18), 0, Ui.dp(host, 12));
                    empty.addView(t);
                    TextView b = Ui.button(host, "创建群聊", true);
                    empty.addView(b);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            host.createGroupDialog();
                        }
                    });
                    list.addView(empty, full());
                }
            }

            @Override
            public void fail(String message) {
                list.removeAllViews();
                JSONArray cached = LocalStore.loadConversations(host);
                int count = 0;
                for (int i = 0; i < cached.length(); i++) {
                    JSONObject c = cached.optJSONObject(i);
                    if (c == null || !"group".equals(c.optString("type"))) continue;
                    count++;
                    render(list, c);
                }
                if (count == 0) {
                    list.addView(Ui.tv(host, message, 13, Ui.RED, false));
                }
            }
        });
    }

    private void render(LinearLayout parent, final JSONObject c) {
        final int convId = c.optInt("id");
        String title = Ui.str(c, "title", "群聊");
        int members = c.optInt("members_count", c.optJSONArray("member_ids") == null
                ? 0 : c.optJSONArray("member_ids").length());
        String preview = Ui.str(c, "last_message_preview", "");
        int unread = c.optInt("unread", 0);

        LinearLayout card = Ui.card(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(host, 8);
        card.setLayoutParams(lp);
        card.setClickable(true);

        LinearLayout row = Ui.row(host);
        android.view.View avatar = Ui.avatar(host, "👥", 46);
        row.addView(avatar);
        LinearLayout col = Ui.column(host);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(host, 10);
        col.setLayoutParams(clp);
        col.addView(Ui.tv(host, title, 14.5f, Ui.TEXT, true));
        col.addView(Ui.tv(host, members + " 人 · " + (preview.isEmpty() ? "还没有消息" : preview),
                12, Ui.MUTED, false));
        row.addView(col);
        if (unread > 0) row.addView(Ui.badge(host, String.valueOf(unread), Ui.RED));
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openChat(convId, c.optString("title", "群聊"), "group", 0);
            }
        });
        parent.addView(card);
    }
}
