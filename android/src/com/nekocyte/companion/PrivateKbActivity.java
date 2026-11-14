package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** 私人角色卡的知识库管理：TXT、聊天截图 OCR、角色绑定。 */
public class PrivateKbActivity extends BaseActivity {
    private static final int REQ_TXT = 41;
    private static final int REQ_IMAGE = 42;

    private int personaId;
    private String personaName;
    private final Set<Integer> linked = new LinkedHashSet<>();
    private LinearLayout list;
    private int pendingKbId = 0;
    private String pendingKbName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        personaId = getIntent().getIntExtra("persona_id", 0);
        personaName = getIntent().getStringExtra("persona_name");
        if (personaName == null) personaName = "私人角色";
        JSONArray ids = null;
        try {
            String raw = getIntent().getStringExtra("kb_ids");
            if (raw != null && !raw.isEmpty()) ids = new JSONArray(raw);
        } catch (Exception ignored) {
        }
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                int id = ids.optInt(i);
                if (id > 0) linked.add(id);
            }
        }

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", false);
        back.setTextSize(24);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, personaName + " · 知识库", 17, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 8);
        title.setLayoutParams(tlp);
        bar.addView(title);
        root.addView(bar);

        TextView create = Ui.button(this, "＋ 新建私人知识库", true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(this, 16);
        clp.rightMargin = Ui.dp(this, 16);
        create.setLayoutParams(clp);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createDialog();
            }
        });
        root.addView(create);

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        int pad = Ui.dp(this, 16);
        list.setPadding(pad, pad, pad, Ui.dp(this, 30));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        list.removeAllViews();
        list.addView(Ui.tv(this, "正在加载知识库…", 12.5f, Ui.MUTED2, false));
        Api.privateKnowledge(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                list.removeAllViews();
                JSONArray arr = data.optJSONArray("knowledge_bases");
                if (arr == null || arr.length() == 0) {
                    list.addView(Ui.tv(PrivateKbActivity.this, "还没有私人知识库。\n可以导入 TXT，或导入聊天记录长截图让视觉模型识别。", 13, Ui.MUTED, false));
                    return;
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject kb = arr.optJSONObject(i);
                    if (kb != null) render(kb);
                }
            }

            @Override
            public void fail(String message) {
                list.removeAllViews();
                list.addView(Ui.tv(PrivateKbActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render(final JSONObject kb) {
        final int kbId = kb.optInt("id");
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        card.addView(Ui.tv(this, Ui.str(kb, "name", "知识库"), 15, Ui.TEXT, true));
        card.addView(Ui.tv(this, Ui.str(kb, "description", ""), 11.5f, Ui.MUTED, false));
        final String kind = "style".equals(kb.optString("kind")) ? "style" : "fact";
        card.addView(Ui.tv(this, "类型：" + ("style".equals(kind) ? "语气模仿" : "资料库"),
                11, Ui.MUTED2, false));
        card.addView(Ui.tv(this,
                "文档 " + kb.optInt("documents") + " · 片段 " + kb.optInt("chunks"), 11, Ui.MUTED2, false));

        Switch link = new Switch(this);
        link.setText("启用给「" + personaName + "」");
        link.setTextColor(Ui.TEXT);
        link.setTextSize(13);
        link.setChecked(linked.contains(kbId));
        link.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (isChecked) linked.add(kbId);
                else linked.remove(kbId);
                saveLinks();
            }
        });
        card.addView(link);

        LinearLayout actions = Ui.row(this);
        TextView switchKind = Ui.button(this,
                "style".equals(kind) ? "设为资料库" : "设为语气模仿", false);
        switchKind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String next = "style".equals(kind) ? "fact" : "style";
                Api.updatePrivateKnowledge(kbId, next, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        boolean rebuilt = data.optBoolean("rebuilt", false);
                        Ui.toast(PrivateKbActivity.this, "style".equals(next)
                                ? (rebuilt ? "已切换并重新学习语气" : "已切换为语气模仿；请导入聊天记录学习语气")
                                : "已切换为资料库");
                        load();
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(PrivateKbActivity.this, message);
                    }
                });
            }
        });
        actions.addView(switchKind);
        TextView txt = Ui.button(this, "导入TXT", false);
        txt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingKbId = kbId;
                pendingKbName = Ui.str(kb, "name", "知识库");
                pickTxt();
            }
        });
        TextView image = Ui.button(this, "导入聊天截图", false);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin = Ui.dp(this, 8);
        image.setLayoutParams(ilp);
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingKbId = kbId;
                pendingKbName = Ui.str(kb, "name", "知识库");
                pickImageSource();
            }
        });
        TextView delete = Ui.button(this, "删除", false);
        delete.setTextColor(Ui.RED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(this, 8);
        delete.setLayoutParams(dlp);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(PrivateKbActivity.this)
                        .setTitle("删除知识库")
                        .setMessage("删除后不可恢复，确定删除「" + Ui.str(kb, "name", "知识库") + "」？")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Api.deletePrivateKnowledge(kbId, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        linked.remove(kbId);
                                        Ui.toast(PrivateKbActivity.this, "已删除");
                                        load();
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(PrivateKbActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        actions.addView(txt);
        actions.addView(image);
        actions.addView(delete);
        card.addView(actions);
        list.addView(card);
    }

    private void createDialog() {
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        final EditText name = new EditText(this);
        name.setHint("知识库名称");
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.MUTED2);
        name.setBackgroundResource(R.drawable.bg_input);
        name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        root.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        final Spinner kind = new Spinner(this);
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"资料库", "语气模仿"});
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kind.setAdapter(kindAdapter);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        klp.topMargin = Ui.dp(this, 8);
        root.addView(kind, klp);
        final EditText desc = new EditText(this);
        desc.setHint("描述（可选）");
        desc.setSingleLine(true);
        desc.setTextColor(Ui.TEXT);
        desc.setHintTextColor(Ui.MUTED2);
        desc.setBackgroundResource(R.drawable.bg_input);
        desc.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        dlp.topMargin = Ui.dp(this, 8);
        root.addView(desc, dlp);
        new AlertDialog.Builder(this)
                .setTitle("新建私人知识库")
                .setView(root)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            Ui.toast(PrivateKbActivity.this, "名称不能为空");
                            return;
                        }
                        Api.createPrivateKnowledge(n, desc.getText().toString().trim(),
                                kind.getSelectedItemPosition() == 1 ? "style" : "fact", new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(PrivateKbActivity.this, "已创建");
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(PrivateKbActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveLinks() {
        JSONArray ids = new JSONArray();
        for (Integer id : linked) ids.put(id);
        Api.linkPersonaKnowledge(personaId, ids, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                Ui.toast(PrivateKbActivity.this, "角色知识库已更新");
            }

            @Override
            public void fail(String message) {
                Ui.toast(PrivateKbActivity.this, message);
                load();
            }
        });
    }

    private void pickTxt() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/markdown", "application/octet-stream"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_TXT);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的文件选择器");
        }
    }

    private void pickImageSource() {
        if (!Session.filePickerEnabled()) {
            try {
                startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), REQ_IMAGE);
            } catch (Exception e) {
                Ui.toast(this, "没有可用的图片选择器");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("导入聊天截图")
                .setItems(new String[]{"从相册选择", "从文件管理选择"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = which == 0
                                    ? MediaUtil.pickFromGallery(PrivateKbActivity.this, false, 1)
                                    : MediaUtil.pickFromDocuments(PrivateKbActivity.this, false);
                            startActivityForResult(intent, REQ_IMAGE);
                        } catch (Exception e) {
                            Ui.toast(PrivateKbActivity.this, "没有可用的图片选择器");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) return;
        if (requestCode == REQ_TXT) importTxt(uri);
        else if (requestCode == REQ_IMAGE) importImage(uri);
    }

    private void importTxt(Uri uri) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("文件读取失败");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0 && sb.length() < 300000) sb.append(buf, 0, n);
            reader.close();
            String text = sb.toString();
            if (text.trim().isEmpty()) {
                Ui.toast(this, "文本为空");
                return;
            }
            Ui.toast(this, "正在导入…");
            Api.importPrivateText(pendingKbId, pendingKbName + " · TXT", text, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    Ui.toast(PrivateKbActivity.this, "导入完成：识别 " + data.optInt("samples") + " 条");
                    load();
                }

                @Override
                public void fail(String message) {
                    Ui.toast(PrivateKbActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "导入失败：" + e.getMessage());
        }
    }

    private void importImage(Uri uri) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File dir = new File(getCacheDir(), "kb");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "kb_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Ui.toast(this, "正在上传并识别聊天截图…");
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(PrivateKbActivity.this, "上传失败");
                        return;
                    }
                    Api.importPrivateImage(pendingKbId, pendingKbName + " · 截图", url, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(PrivateKbActivity.this, "识别完成：" + data.optInt("samples") + " 条");
                            load();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(PrivateKbActivity.this, message);
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(PrivateKbActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "导入失败：" + e.getMessage());
        }
    }
}
