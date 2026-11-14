package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 角色卡大世界：首页、搜索、我的角色卡、管理员审核与管理。 */
public class PersonaWorldActivity extends BaseActivity {
    private LinearLayout tabs;
    private LinearLayout content;
    private int currentTab = 0;
    private int page = 1;
    private String lastQuery = "";
    private String lastAuthor = "";
    private int pendingAdminAvatarId = 0;
    private final String[] tabNames = {"首页", "搜索", "我的角色卡", "管理"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, "角色卡大世界", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        root.addView(tabs);
        for (int i = 0; i < tabNames.length; i++) {
            if (i == 3 && !Session.isAdmin()) continue;
            final int index = i;
            TextView tab = Ui.button(this, tabNames[i], false);
            tab.setTextSize(13);
            tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = Ui.dp(this, 5);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(index);
                }
            });
            tabs.addView(tab);
        }

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 30));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        showTab(0);
    }

    private void showTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView tab = (TextView) tabs.getChildAt(i);
            tab.setTextColor(i == index ? Ui.ACCENT : Ui.MUTED);
        }
        content.removeAllViews();
        page = 1;
        if (index == 0) loadWorldPage(1, "", "");
        else if (index == 1) showSearch();
        else if (index == 2) showMine();
        else showAdmin();
    }

    private void loadWorldPage(final int targetPage, final String query, final String author) {
        content.removeAllViews();
        content.addView(Ui.tv(this, "正在加载角色卡…", 12.5f, Ui.MUTED2, false));
        Api.personaWorld(targetPage, query, author, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                page = data.optInt("page", targetPage);
                renderGrid(data.optJSONArray("personas"), data.optInt("pages", 1), data.optInt("total", 0));
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(PersonaWorldActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void showSearch() {
        content.removeAllViews();
        EditText query = new EditText(this);
        query.setHint("搜索角色名、人设关键词");
        query.setSingleLine(true);
        query.setText(lastQuery);
        styleInput(query);
        EditText author = new EditText(this);
        author.setHint("按作者搜索（用户名/昵称，可留空）");
        author.setSingleLine(true);
        author.setText(lastAuthor);
        styleInput(author);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.topMargin = Ui.dp(this, 6);
        content.addView(query, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        content.addView(author, lp);
        TextView search = Ui.button(this, "搜索", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        slp.topMargin = Ui.dp(this, 8);
        search.setLayoutParams(slp);
        content.addView(search);
        final LinearLayout results = Ui.column(this);
        content.addView(results);
        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lastQuery = query.getText().toString().trim();
                lastAuthor = author.getText().toString().trim();
                Api.personaWorld(1, lastQuery, lastAuthor, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        page = data.optInt("page", 1);
                        results.removeAllViews();
                        renderGridInto(results, data.optJSONArray("personas"),
                                data.optInt("pages", 1), data.optInt("total", 0));
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(PersonaWorldActivity.this, message);
                    }
                });
            }
        });
    }

    private void styleInput(EditText e) {
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
    }

    private void showMine() {
        content.removeAllViews();
        content.addView(Ui.tv(this, "我的角色卡", 16, Ui.TEXT, true));
        Api.myPersonas(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                content.removeAllViews();
                content.addView(Ui.tv(PersonaWorldActivity.this, "我的角色卡", 16, Ui.TEXT, true));
                JSONArray arr = data.optJSONArray("personas");
                if (arr == null || arr.length() == 0) {
                    content.addView(Ui.tv(PersonaWorldActivity.this, "还没有角色卡。先在 AI 好友页创建私人角色卡。", 12.5f, Ui.MUTED, false));
                    return;
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null) renderMineCard(p);
                }
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(PersonaWorldActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void renderMineCard(final JSONObject p) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        LinearLayout row = Ui.row(this);
        row.addView(Ui.avatar(this, p.optString("avatar"), 46));
        LinearLayout col = Ui.column(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(this, 10);
        col.setLayoutParams(clp);
        col.addView(Ui.tv(this, p.optString("name", "角色卡"), 15, Ui.TEXT, true));
        TextView tag = Ui.tv(this, Ui.str(p, "tagline", ""), 11.5f, Ui.MUTED2, false);
        tag.setMaxLines(1);
        tag.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(tag);
        String status = p.optString("review_status", p.optBoolean("is_public") ? "approved" : "private");
        col.addView(Ui.tv(this, "状态：" + statusText(status) + " · 使用 " + p.optInt("usage_count") + " 次", 11, Ui.MUTED, false));
        row.addView(col);

        boolean isPublic = p.optBoolean("is_public", false);
        boolean pending = "pending".equals(status);
        LinearLayout actionCol = Ui.column(this);
        actionCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.dp(this, 8);
        actionCol.setLayoutParams(alp);
        if (!pending) {
            TextView add = compactButton("添加", true);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    addAi(p.optInt("id"));
                }
            });
            actionCol.addView(add);
        }
        if (!isPublic) {
            TextView submit = compactButton("投稿", false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 32));
            slp.topMargin = Ui.dp(this, 5);
            submit.setLayoutParams(slp);
            submit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    submitPersona(p.optInt("id"));
                }
            });
            actionCol.addView(submit);
        }
        if (actionCol.getChildCount() > 0) row.addView(actionCol);
        card.addView(row);

        if (pending) {
            card.addView(Ui.tv(this, "已提交，等待管理员审核", 11.5f, Ui.ACCENT, false));
        } else if ("rejected".equals(status)) {
            card.addView(Ui.tv(this, "审核未通过，可修改后重新投稿", 11.5f, Ui.RED, false));
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pending) addAi(p.optInt("id"));
            }
        });
        content.addView(card);
    }

    private TextView compactButton(String text, boolean primary) {
        TextView button = Ui.button(this, text, primary);
        button.setTextSize(12.5f);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 32)));
        return button;
    }

    private String statusText(String status) {
        if ("pending".equals(status)) return "待审核";
        if ("approved".equals(status)) return "已公开";
        if ("rejected".equals(status)) return "未通过";
        if ("private".equals(status) || "draft".equals(status)) return "私人";
        return status;
    }

    private void submitPersona(int id) {
        Api.submitPersona(id, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                Ui.toast(PersonaWorldActivity.this, data.optString("message", "已提交审核"));
                showMine();
            }

            @Override
            public void fail(String message) {
                Ui.toast(PersonaWorldActivity.this, message);
            }
        });
    }

    private void showAdmin() {
        content.removeAllViews();
        content.addView(Ui.tv(this, "待审核", 16, Ui.TEXT, true));
        Api.pendingPersonas(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("personas");
                if (arr == null || arr.length() == 0) {
                    content.addView(Ui.tv(PersonaWorldActivity.this, "没有待审核的角色卡", 12, Ui.MUTED, false));
                } else {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.optJSONObject(i);
                        if (p != null) renderAdminCard(p, true);
                    }
                }
                loadAdminPublic();
            }

            @Override
            public void fail(String message) {
                content.addView(Ui.tv(PersonaWorldActivity.this, message, 13, Ui.RED, false));
                loadAdminPublic();
            }
        });
    }

    private void loadAdminPublic() {
        TextView head = Ui.tv(this, "公共角色卡管理", 16, Ui.TEXT, true);
        head.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        content.addView(head);
        Api.adminPublicPersonas("", new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("personas");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null && p.optBoolean("enabled", true)) renderAdminCard(p, false);
                }
            }

            @Override
            public void fail(String message) {
                content.addView(Ui.tv(PersonaWorldActivity.this, message, 12, Ui.RED, false));
            }
        });
    }

    private void renderAdminCard(final JSONObject p, final boolean pending) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        card.addView(Ui.tv(this, p.optString("name", "角色卡") + (pending ? " · 待审核" : ""), 14, Ui.TEXT, true));
        card.addView(Ui.tv(this, Ui.str(p, "tagline", ""), 11.5f, Ui.MUTED, false));
        JSONObject author = p.optJSONObject("author");
        card.addView(Ui.tv(this, "作者：" + (author == null ? "未知" : author.optString("nickname", "")), 11, Ui.MUTED2, false));
        LinearLayout actions = Ui.row(this);
        if (pending) {
            TextView approve = Ui.button(this, "通过", true);
            approve.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.approvePersona(p.optInt("id"), new Api.Cb() {
                        @Override public void ok(JSONObject data) { Ui.toast(PersonaWorldActivity.this, "已通过"); showAdmin(); }
                        @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
                    });
                }
            });
            actions.addView(approve);
            TextView reject = Ui.button(this, "拒绝", false);
            reject.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.rejectPersona(p.optInt("id"), new Api.Cb() {
                        @Override public void ok(JSONObject data) { Ui.toast(PersonaWorldActivity.this, "已拒绝"); showAdmin(); }
                        @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
                    });
                }
            });
            actions.addView(reject);
        } else {
            TextView edit = Ui.button(this, "编辑", false);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    adminEditDialog(p);
                }
            });
            actions.addView(edit);
            TextView avatarButton = Ui.button(this, "头像", false);
            avatarButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingAdminAvatarId = p.optInt("id");
                    startActivityForResult(MediaUtil.pickFromGallery(PersonaWorldActivity.this, false, 1), 51);
                }
            });
            actions.addView(avatarButton);
            TextView delete = Ui.button(this, "删除", false);
            delete.setTextColor(Ui.RED);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(PersonaWorldActivity.this)
                            .setTitle("删除公共角色卡")
                            .setMessage("删除后不可恢复，确定删除「" + p.optString("name") + "」？")
                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) { adminDelete(p.optInt("id")); }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
            actions.addView(delete);
        }
        card.addView(actions);
        content.addView(card);
    }

    private void adminDelete(int id) {
        Api.adminDeletePersona(id, new Api.Cb() {
            @Override public void ok(JSONObject data) { Ui.toast(PersonaWorldActivity.this, "已删除"); showAdmin(); }
            @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
        });
    }

    private void adminEditDialog(final JSONObject p) {
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        final EditText name = field(root, "名称", p.optString("name", ""));
        final EditText avatar = field(root, "头像 URL / emoji", p.optString("avatar", ""));
        final EditText tagline = field(root, "一句话人设", p.optString("tagline", ""));
        final EditText desc = field(root, "身份背景", p.optString("description", ""));
        final EditText prompt = field(root, "角色设定 system prompt", p.optString("system_prompt", ""));
        final EditText personality = field(root, "性格", p.optString("personality", ""));
        final EditText style = field(root, "说话方式", p.optString("speaking_style", ""));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("编辑公共角色卡")
                .setView(scroll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("name", name.getText().toString().trim());
                            body.put("avatar", avatar.getText().toString().trim());
                            body.put("tagline", tagline.getText().toString().trim());
                            body.put("description", desc.getText().toString().trim());
                            body.put("system_prompt", prompt.getText().toString().trim());
                            body.put("personality", personality.getText().toString().trim());
                            body.put("speaking_style", style.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        Api.adminUpdatePersona(p.optInt("id"), body, new Api.Cb() {
                            @Override public void ok(JSONObject data) { Ui.toast(PersonaWorldActivity.this, "已保存"); showAdmin(); }
                            @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        TextView label = Ui.tv(this, hint, 11.5f, Ui.MUTED2, false);
        label.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        parent.addView(label);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        e.setSingleLine(hint.contains("URL") || hint.contains("名称") || hint.contains("人设"));
        parent.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void renderGrid(JSONArray arr, int pages, int total) {
        content.removeAllViews();
        content.addView(Ui.tv(this, "推荐角色卡 · " + total, 16, Ui.TEXT, true));
        renderGridInto(content, arr, pages, total);
    }

    private void renderGridInto(LinearLayout parent, JSONArray arr, int pages, int total) {
        if (arr == null || arr.length() == 0) {
            parent.addView(Ui.tv(this, "没有找到角色卡", 12.5f, Ui.MUTED, false));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < arr.length(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
                parent.addView(row);
            }
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            row.addView(personaCard(p));
        }
        if (pages > 1) {
            LinearLayout pager = Ui.row(this);
            pager.setGravity(Gravity.CENTER);
            TextView prev = Ui.button(this, "上一页", false);
            prev.setEnabled(page > 1);
            prev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { loadWorldPage(Math.max(1, page - 1), lastQuery, lastAuthor); }
            });
            TextView info = Ui.tv(this, " " + page + " / " + pages + " ", 12, Ui.MUTED, false);
            TextView next = Ui.button(this, "下一页", false);
            next.setEnabled(page < pages);
            next.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { loadWorldPage(Math.min(pages, page + 1), lastQuery, lastAuthor); }
            });
            pager.addView(prev);
            pager.addView(info);
            pager.addView(next);
            parent.addView(pager);
        }
    }

    private View personaCard(final JSONObject p) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = Ui.dp(this, 6);
        card.setLayoutParams(lp);
        card.addView(Ui.avatar(this, p.optString("avatar"), 48));
        card.addView(Ui.tv(this, p.optString("name", "角色卡"), 14, Ui.TEXT, true));
        TextView tag = Ui.tv(this, Ui.str(p, "tagline", ""), 11.5f, Ui.MUTED2, false);
        tag.setMaxLines(2);
        tag.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(tag);
        JSONObject author = p.optJSONObject("author");
        card.addView(Ui.tv(this, "作者：" + (author == null ? "未知" : author.optString("nickname", "")), 10.5f, Ui.MUTED, false));
        card.addView(Ui.tv(this, "使用 " + p.optInt("usage_count") + " 次", 10.5f, Ui.MUTED2, false));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addAi(p.optInt("id")); }
        });
        return card;
    }

    private void addAi(int personaId) {
        Api.addAi(personaId, new Api.Cb() {
            @Override public void ok(JSONObject data) { Ui.toast(PersonaWorldActivity.this, "已添加为 AI 好友"); }
            @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 51 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null || pendingAdminAvatarId <= 0) return;
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File dir = new File(getCacheDir(), "persona_admin");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            final int id = pendingAdminAvatarId;
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject d) {
                    String url = d.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(PersonaWorldActivity.this, "头像上传失败");
                        return;
                    }
                    JSONObject body = new JSONObject();
                    try {
                        body.put("avatar", url);
                    } catch (Exception ignored) {
                    }
                    Api.adminUpdatePersona(id, body, new Api.Cb() {
                        @Override public void ok(JSONObject d2) { Ui.toast(PersonaWorldActivity.this, "头像已更新"); showAdmin(); }
                        @Override public void fail(String message) { Ui.toast(PersonaWorldActivity.this, message); }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(PersonaWorldActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "头像上传失败：" + e.getMessage());
        }
    }
}
