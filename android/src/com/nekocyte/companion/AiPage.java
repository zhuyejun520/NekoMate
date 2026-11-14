package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class AiPage extends BasePage {
    public AiPage(MainActivity host) {
        super(host);
    }

    @Override
    public void refresh() {
        clear();
        LinearLayout top = Ui.row(host);
        TextView add = Ui.button(host, "＋ 添加 AI 好友", true);
        top.addView(add);
        box.addView(top, full());
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.addAiDialog();
            }
        });

        final LinearLayout privateBox = Ui.column(host);
        LinearLayout.LayoutParams plp = full();
        plp.topMargin = Ui.dp(host, 8);
        box.addView(privateBox, plp);
        loadPrivate(top, privateBox);

        final LinearLayout list = Ui.column(host);
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = Ui.dp(host, 10);
        box.addView(list, lp);

        final Map<Integer, JSONObject> convs = new HashMap<>();
        JSONArray cachedConvs = LocalStore.loadConversations(host);
        for (int i = 0; i < cachedConvs.length(); i++) {
            JSONObject c = cachedConvs.optJSONObject(i);
            if (c != null) convs.put(c.optInt("id"), c);
        }
        JSONArray cachedAi = LocalStore.loadAiFriends(host);
        if (cachedAi.length() > 0) {
            renderAiList(list, cachedAi, convs);
        } else {
            list.addView(Ui.tv(host, "正在加载…", 12.5f, Ui.MUTED2, false));
        }

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
                load(list, convs);
            }

            @Override
            public void fail(String message) {
                load(list, convs);
            }
        });
    }

    private void loadPrivate(final LinearLayout top, final LinearLayout privateBox) {
        Api.personas(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                privateBox.removeAllViews();
                boolean canCreate = data.optBoolean("can_create_private", false);
                JSONArray arr = data.optJSONArray("personas");
                boolean hasButton = false;
                if (canCreate) {
                    TextView create = Ui.button(host, "＋ 创建私人角色卡", false);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.leftMargin = Ui.dp(host, 8);
                    create.setLayoutParams(clp);
                    create.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            personaDialog(null);
                        }
                    });
                    top.addView(create);
                    hasButton = true;
                }
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p == null) continue;
                    if (p.optInt("owner_id", 0) != Session.userId()) continue;
                    if (!hasButton) {
                        TextView create = Ui.button(host, "＋ 创建私人角色卡", false);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        clp.leftMargin = Ui.dp(host, 8);
                        create.setLayoutParams(clp);
                        create.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                personaDialog(null);
                            }
                        });
                        top.addView(create);
                        hasButton = true;
                    }
                    renderPrivate(privateBox, p);
                }
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void renderPrivate(LinearLayout parent, final JSONObject p) {
        LinearLayout card = Ui.card(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(host, 8);
        card.setLayoutParams(lp);
        LinearLayout row = Ui.row(host);
        row.addView(Ui.avatar(host, Ui.str(p, "avatar", "🦊"), 40));
        LinearLayout col = Ui.column(host);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(host, 10);
        col.setLayoutParams(clp);
        col.addView(Ui.tv(host, Ui.str(p, "name", "私人角色"), 14, Ui.TEXT, true));
        TextView privateTag = Ui.tv(host, "私人角色卡 · " + Ui.str(p, "tagline", ""), 11.5f, Ui.MUTED2, false);
        privateTag.setMaxLines(1);
        privateTag.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(privateTag);
        row.addView(col);
        TextView more = Ui.button(host, "⋮", false);
        more.setTextSize(22);
        more.setPadding(Ui.dp(host, 10), 0, Ui.dp(host, 10), 0);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                privatePersonaMenu(p);
            }
        });
        row.addView(more);
        card.addView(row);
        parent.addView(card);
    }

    private void privatePersonaMenu(final JSONObject p) {
        new AlertDialog.Builder(host)
                .setTitle(Ui.str(p, "name", "私人角色卡"))
                .setItems(new String[]{"换头像", "编辑", "知识库", "删除"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            host.pendingPersonaAvatarId = p.optInt("id");
                            host.startActivityForResult(MediaUtil.pickFromGallery(host, false, 1), 24);
                        } else if (which == 1) {
                            personaDialog(p);
                        } else if (which == 2) {
                            android.content.Intent intent = new android.content.Intent(host, PrivateKbActivity.class);
                            intent.putExtra("persona_id", p.optInt("id"));
                            intent.putExtra("persona_name", Ui.str(p, "name", "私人角色"));
                            JSONArray kbIds = p.optJSONArray("kb_ids");
                            intent.putExtra("kb_ids", kbIds == null ? "[]" : kbIds.toString());
                            host.startActivity(intent);
                        } else {
                            confirmDeletePrivatePersona(p);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeletePrivatePersona(final JSONObject p) {
        new AlertDialog.Builder(host)
                .setTitle("删除私人角色卡")
                .setMessage("删除后不可恢复，已用它创建的 AI 好友需要先删除。")
                .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.deletePersona(p.optInt("id"), new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(host, "已删除");
                                refresh();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(host, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void personaDialog(final JSONObject existing) {
        LinearLayout root = Ui.column(host);
        int pad = Ui.dp(host, 16);
        root.setPadding(pad, pad, pad, pad);
        final EditText name = field(root, "角色名", existing == null ? "" : Ui.str(existing, "name", ""));
        final EditText avatar = field(root, "头像 emoji（例如 🦊）", existing == null ? "🦊" : Ui.str(existing, "avatar", "🦊"));
        final EditText tagline = field(root, "一句话人设", existing == null ? "" : Ui.str(existing, "tagline", ""));
        final EditText desc = area(root, "身份背景 / 年龄 / 职业 / 生活状态", existing == null ? "" : Ui.str(existing, "description", ""));
        final EditText personality = area(root, "性格", existing == null ? "" : Ui.str(existing, "personality", ""));
        final EditText style = area(root, "说话方式（短句、口头禅、语气）", existing == null ? "" : Ui.str(existing, "speaking_style", ""));
        final EditText prompt = area(root, "角色设定（system prompt）", existing == null ? "" : Ui.str(existing, "system_prompt", ""));
        final EditText greeting = field(root, "打招呼语", existing == null ? "" : Ui.str(existing, "greeting", ""));

        ScrollView scroll = new ScrollView(host);
        scroll.addView(root);
        new AlertDialog.Builder(host)
                .setTitle(existing == null ? "创建私人角色卡" : "编辑私人角色卡")
                .setView(scroll)
                .setPositiveButton(existing == null ? "创建" : "保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            Ui.toast(host, "角色名不能为空");
                            return;
                        }
                        JSONObject body = new JSONObject();
                        try {
                            body.put("name", n);
                            body.put("avatar", avatar.getText().toString().trim());
                            body.put("tagline", tagline.getText().toString().trim());
                            body.put("description", desc.getText().toString().trim());
                            body.put("personality", personality.getText().toString().trim());
                            body.put("speaking_style", style.getText().toString().trim());
                            body.put("system_prompt", prompt.getText().toString().trim());
                            body.put("greeting", greeting.getText().toString().trim());
                            body.put("catchphrases", new JSONArray());
                            body.put("enabled", true);
                        } catch (Exception ignored) {
                        }
                        Api.Cb cb = new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(host, existing == null ? "私人角色卡已创建" : "已保存");
                                refresh();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(host, message);
                            }
                        };
                        if (existing == null) Api.createPersona(body, cb);
                        else Api.updatePersona(existing.optInt("id"), body, cb);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        EditText e = new EditText(host);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(host, 12), 0, Ui.dp(host, 12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 44));
        lp.topMargin = Ui.dp(host, 8);
        parent.addView(e, lp);
        return e;
    }

    private EditText area(LinearLayout parent, String hint, String value) {
        EditText e = new EditText(host);
        e.setHint(hint);
        e.setText(value);
        e.setMinLines(2);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(host, 12), Ui.dp(host, 8), Ui.dp(host, 12), Ui.dp(host, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(host, 8);
        parent.addView(e, lp);
        return e;
    }

    private void load(final LinearLayout list, final Map<Integer, JSONObject> convs) {
        Api.aiFriends(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("ai_friends");
                if (arr != null) LocalStore.saveAiFriends(host, arr);
                renderAiList(list, arr, convs);
            }

            @Override
            public void fail(String message) {
                if (list.getChildCount() == 0 || (list.getChildCount() == 1
                        && list.getChildAt(0) instanceof TextView)) {
                    list.removeAllViews();
                    list.addView(Ui.tv(host, message, 13, Ui.RED, false));
                }
            }
        });
    }

    private void renderAiList(LinearLayout list, JSONArray arr, Map<Integer, JSONObject> convs) {
        list.removeAllViews();
        if (arr == null || arr.length() == 0) {
            LinearLayout empty = Ui.card(host);
            TextView t = Ui.tv(host, "还没有 AI 好友\n从角色卡里挑一个，让她住进你的手机", 13, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(host, 18), 0, Ui.dp(host, 12));
            empty.addView(t);
            TextView b = Ui.button(host, "选择角色卡", true);
            empty.addView(b);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.addAiDialog();
                }
            });
            list.addView(empty, full());
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject ai = arr.optJSONObject(i);
            if (ai == null) continue;
            JSONArray wl = ai.optJSONArray("whitelist");
            Session.setWhitelist(ai.optInt("id"), wl == null ? "[]" : wl.toString());
            Session.setRootEnabled(ai.optInt("id"), ai.optBoolean("root_enabled", false));
            render(list, ai, convs);
        }
    }

    private void render(LinearLayout parent, final JSONObject ai, Map<Integer, JSONObject> convs) {
        final int aiId = ai.optInt("id");
        final int convId = ai.optInt("conversation_id");
        JSONObject persona = ai.optJSONObject("persona");
        // 身份背景只用于角色设定，不在好友卡片展开，避免长文本撑坏列表布局。
        String tagline = Ui.str(persona, "tagline", "");
        JSONObject conv = convs.get(convId);
        String preview = Ui.str(conv, "last_message_preview", "");
        boolean root = ai.optBoolean("root_enabled", false);

        LinearLayout card = Ui.card(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(host, 10);
        card.setLayoutParams(lp);
        card.setClickable(true);

        LinearLayout row = Ui.row(host);
        row.addView(Ui.avatar(host, ai.optString("avatar"), 50));
        LinearLayout col = Ui.column(host);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(host, 12);
        col.setLayoutParams(clp);
        LinearLayout nameRow = Ui.row(host);
        nameRow.addView(Ui.tv(host, ai.optString("name"), 16, Ui.TEXT, true));
        nameRow.addView(Ui.badge(host, root ? "root 已开" : "root 未开", root ? Ui.GREEN : Ui.MUTED2));
        JSONObject affinitySettings = ai.optJSONObject("settings");
        if (affinitySettings != null && affinitySettings.optBoolean("affinity_enabled", true)) {
            int affinity = affinitySettings.optInt("affinity", 0);
            nameRow.addView(Ui.badge(host, "好感度 " + affinity, Ui.ACCENT2));
        }
        col.addView(nameRow);
        if (!tagline.isEmpty()) {
            TextView taglineView = Ui.tv(host, tagline, 12, Ui.MUTED, false);
            taglineView.setMaxLines(1);
            taglineView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(taglineView);
        }
        TextView previewView = Ui.tv(host, preview.isEmpty() ? "还没有聊过，去打个招呼吧" : preview, 12, Ui.MUTED2, false);
        previewView.setMaxLines(1);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(previewView);
        row.addView(col);
        card.addView(row);

        LinearLayout actions = Ui.row(host);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, Ui.dp(host, 10), 0, 0);
        TextView chat = Ui.button(host, "聊天", true);
        TextView settings = Ui.button(host, "设置", false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(host, 8);
        settings.setLayoutParams(slp);
        chat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (convId > 0) host.openChat(convId, ai.optString("name"), "ai", aiId);
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(host, AiSettingsActivity.class);
                i.putExtra("ai_id", aiId);
                host.startActivity(i);
            }
        });
        actions.addView(chat);
        actions.addView(settings);
        card.addView(actions);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (convId > 0) host.openChat(convId, ai.optString("name"), "ai", aiId);
            }
        });
        parent.addView(card);
    }
}
