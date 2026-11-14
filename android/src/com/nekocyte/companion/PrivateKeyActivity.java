package com.nekocyte.companion;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public class PrivateKeyActivity extends BaseActivity {
    private EditText nameEdit;
    private EditText baseEdit;
    private EditText keyEdit;
    private EditText modelEdit;
    private TextView status;

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
        TextView title = Ui.tv(this, "专属模型 Key", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        body.addView(Ui.tv(this, "添加你自己的 OpenAI 兼容 Key，用于私人角色卡、私人知识库和私人模型调用。普通角色卡仍然只在 Web 控制台管理。", 12.5f, Ui.MUTED, false));
        status = Ui.tv(this, "正在读取…", 11.5f, Ui.MUTED2, false);
        status.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        body.addView(status);

        nameEdit = input("名称，例如 我的 OpenRouter");
        baseEdit = input("Base URL，例如 https://api.openai.com/v1");
        keyEdit = input("API Key（保存后只显示掩码）");
        keyEdit.setSingleLine(true);
        modelEdit = input("模型名，例如 gpt-4o-mini");
        body.addView(label("名称"));
        body.addView(nameEdit);
        body.addView(label("Base URL"));
        body.addView(baseEdit);
        body.addView(label("API Key"));
        body.addView(keyEdit);
        body.addView(label("模型"));
        body.addView(modelEdit);

        TextView save = Ui.button(this, "保存专属 Key", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 14);
        save.setLayoutParams(lp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        body.addView(save);

        TextView delete = Ui.button(this, "删除专属 Key", false);
        delete.setTextColor(Ui.RED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        dlp.topMargin = Ui.dp(this, 10);
        delete.setLayoutParams(dlp);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Api.deletePrivateKey(new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        Ui.toast(PrivateKeyActivity.this, "已删除专属 Key");
                        load();
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(PrivateKeyActivity.this, message);
                    }
                });
            }
        });
        body.addView(delete);
        setContentView(root);
        load();
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        e.setSingleLine(true);
        return e;
    }

    private TextView label(String text) {
        TextView t = Ui.tv(this, text, 11.5f, Ui.MUTED2, false);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return t;
    }

    private void load() {
        Api.privateKey(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONObject p = data.optJSONObject("provider");
                if (p == null) {
                    status.setText("还没有专属 Key。私人角色卡创建/管理需要它。");
                    return;
                }
                nameEdit.setText(p.optString("name", ""));
                baseEdit.setText(p.optString("base_url", ""));
                modelEdit.setText(p.optString("model", ""));
                keyEdit.setText("");
                keyEdit.setHint("当前 Key：" + p.optString("masked_key", "****") + "（留空不修改）");
                status.setText("已保存专属 Key · " + p.optString("masked_key", ""));
            }

            @Override
            public void fail(String message) {
                status.setText(message);
            }
        });
    }

    private void save() {
        String name = nameEdit.getText().toString().trim();
        String base = baseEdit.getText().toString().trim();
        String key = keyEdit.getText().toString().trim();
        String model = modelEdit.getText().toString().trim();
        if (base.isEmpty()) {
            Ui.toast(this, "Base URL 不能为空");
            return;
        }
        status.setText("正在保存…");
        Api.savePrivateKey(name, base, key, model, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                Ui.toast(PrivateKeyActivity.this, "专属 Key 已保存");
                load();
            }

            @Override
            public void fail(String message) {
                status.setText(message);
                Ui.toast(PrivateKeyActivity.this, message);
            }
        });
    }
}
