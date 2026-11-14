package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.List;

/** 管理员在安卓端创建表情包组、上传图片、长按填 words、上架。 */
public class StickerAdminActivity extends BaseActivity {
    private static final int REQ_IMAGE = 71;
    private static final int REQ_LOGO = 72;
    private LinearLayout content;
    private int openPackId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", false);
        back.setTextSize(22);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (openPackId > 0) {
                    openPackId = 0;
                    loadPacks();
                } else finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, "表情包管理", 19, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tlp);
        bar.addView(title);
        TextView create = Ui.button(this, "＋ 新建组", true);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createDialog();
            }
        });
        bar.addView(create);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        int pad = Ui.dp(this, 12);
        content.setPadding(pad, pad, pad, Ui.dp(this, 28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        loadPacks();
    }

    private void loadPacks() {
        content.removeAllViews();
        content.addView(Ui.tv(this, "正在加载…", 12.5f, Ui.MUTED2, false));
        Api.adminStickerPacks(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                content.removeAllViews();
                JSONArray packs = data.optJSONArray("packs");
                if (packs == null || packs.length() == 0) {
                    content.addView(Ui.tv(StickerAdminActivity.this,
                            "还没有表情包组。\n点右上角新建组，然后上传图片并长按填写 words。", 13, Ui.MUTED, false));
                    return;
                }
                for (int i = 0; i < packs.length(); i++) {
                    JSONObject p = packs.optJSONObject(i);
                    if (p != null) renderPack(p);
                }
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(StickerAdminActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void renderPack(final JSONObject pack) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        LinearLayout row = Ui.row(this);
        row.addView(Ui.avatar(this, pack.optString("logo", "📦"), 42));
        LinearLayout col = Ui.column(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = Ui.dp(this, 10);
        col.setLayoutParams(clp);
        col.addView(Ui.tv(this, pack.optString("name", "表情包"), 14, Ui.TEXT, true));
        col.addView(Ui.tv(this, pack.optBoolean("published") ? "已上架" : "未上架",
                11, pack.optBoolean("published") ? Ui.GREEN : Ui.MUTED, false));
        row.addView(col);
        card.addView(row);
        int missing = pack.optInt("missing_words", 0);
        if (missing > 0) {
            card.addView(Ui.tv(this, "还有 " + missing + " 张图片没有 words，上架会失败",
                    11.5f, Ui.RED, false));
        }
        TextView open = Ui.button(this, "打开管理", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPack(pack.optInt("id"));
            }
        });
        card.addView(open);
        content.addView(card);
    }

    private void openPack(int packId) {
        openPackId = packId;
        content.removeAllViews();
        content.addView(Ui.tv(this, "正在加载…", 12.5f, Ui.MUTED2, false));
        Api.adminStickerPack(packId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONObject pack = data.optJSONObject("pack");
                if (pack == null) return;
                renderPackDetail(pack);
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(StickerAdminActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void renderPackDetail(final JSONObject pack) {
        content.removeAllViews();
        content.addView(Ui.tv(this, pack.optString("name"), 17, Ui.TEXT, true));
        boolean published = pack.optBoolean("published");
        content.addView(Ui.tv(this, published
                        ? "已上架：可重命名、换 logo、下架；修改图片或 words 请先下架"
                        : "未上架：上传图片后长按图片填 words，再点“上架”",
                11.5f, Ui.MUTED, false));
        LinearLayout actions = Ui.row(this);
        TextView upload = Ui.button(this, "上传图片", true);
        upload.setEnabled(!published);
        upload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        actions.addView(upload);
        TextView publish = Ui.button(this, "上架", false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.dp(this, 8);
        publish.setLayoutParams(plp);
        publish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Api.publishStickerPack(openPackId, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        Ui.toast(StickerAdminActivity.this, "上架成功");
                        openPack(openPackId);
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(StickerAdminActivity.this, message);
                    }
                });
            }
        });
        actions.addView(publish);
        content.addView(actions);

        LinearLayout manage = Ui.row(this);
        TextView rename = Ui.button(this, "重命名", false);
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameDialog(pack.optString("name", "表情包"));
            }
        });
        manage.addView(rename);
        TextView logo = Ui.button(this, "更换logo", false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Ui.dp(this, 8);
        logo.setLayoutParams(llp);
        logo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLogo();
            }
        });
        manage.addView(logo);
        if (published) {
            TextView unpublish = Ui.button(this, "下架", false);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ulp.leftMargin = Ui.dp(this, 8);
            unpublish.setLayoutParams(ulp);
            unpublish.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.unpublishStickerPack(openPackId, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(StickerAdminActivity.this, "已下架");
                            openPack(openPackId);
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(StickerAdminActivity.this, message);
                        }
                    });
                }
            });
            manage.addView(unpublish);
        }
        TextView delete = Ui.button(this, "删除组", false);
        delete.setTextColor(Ui.RED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = Ui.dp(this, 8);
        delete.setLayoutParams(dlp);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deletePack();
            }
        });
        manage.addView(delete);
        content.addView(manage);

        JSONArray items = pack.optJSONArray("items");
        if (items == null || items.length() == 0) {
            content.addView(Ui.tv(this, "还没有图片。", 12.5f, Ui.MUTED, false));
            return;
        }
        LinearLayout grid = Ui.column(this);
        LinearLayout row = null;
        for (int i = 0; i < items.length(); i++) {
            if (i % 3 == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.CENTER);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            LinearLayout cell = Ui.column(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cell.setLayoutParams(clp);
            StickerImageView image = new StickerImageView(this);
            image.load(item.optString("url"));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 96));
            image.setLayoutParams(ilp);
            final int itemId = item.optInt("id");
            final String oldWords = item.optString("words", "");
            image.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    wordsDialog(itemId, oldWords);
                    return true;
                }
            });
            cell.addView(image);
            cell.addView(Ui.tv(this, oldWords.isEmpty() ? "长按填 words" : oldWords,
                    10.5f, oldWords.isEmpty() ? Ui.RED : Ui.MUTED, false));
            if (row != null) row.addView(cell);
        }
        content.addView(grid);
    }

    private void wordsDialog(final int itemId, String oldWords) {
        EditText input = new EditText(this);
        input.setHint("这张表情的 words");
        input.setText(oldWords);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("填写表情 words")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String words = input.getText().toString().trim();
                        if (words.isEmpty()) {
                            Ui.toast(StickerAdminActivity.this, "words 不能为空");
                            return;
                        }
                        Api.setStickerWords(itemId, words, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                openPack(openPackId);
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(StickerAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createDialog() {
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);
        EditText name = new EditText(this);
        name.setHint("表情包组名称");
        name.setSingleLine(true);
        box.addView(name);
        EditText logo = new EditText(this);
        logo.setHint("组 logo：emoji 或图片 URL");
        logo.setSingleLine(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 8);
        logo.setLayoutParams(llp);
        box.addView(logo);
        new AlertDialog.Builder(this)
                .setTitle("新建表情包组")
                .setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            Ui.toast(StickerAdminActivity.this, "名称不能为空");
                            return;
                        }
                        Api.createStickerPack(n, logo.getText().toString().trim(), new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(StickerAdminActivity.this, "已创建组，请上传图片");
                                loadPacks();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(StickerAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickImage() {
        try {
            startActivityForResult(MediaUtil.pickFromGallery(this, true, 30), REQ_IMAGE);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的图片选择器");
        }
    }

    private void pickLogo() {
        try {
            startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), REQ_LOGO);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的图片选择器");
        }
    }

    private void renameDialog(String oldName) {
        EditText input = new EditText(this);
        input.setText(oldName);
        input.setSingleLine(true);
        input.setHint("表情包组名称");
        new AlertDialog.Builder(this)
                .setTitle("重命名表情包组")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            Ui.toast(StickerAdminActivity.this, "名称不能为空");
                            return;
                        }
                        Api.updateStickerPack(openPackId, name, null, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(StickerAdminActivity.this, "已重命名");
                                openPack(openPackId);
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(StickerAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deletePack() {
        new AlertDialog.Builder(this)
                .setTitle("删除表情包组")
                .setMessage("删除后组内图片和商店商品都会移除，确定删除？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.deleteStickerPack(openPackId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(StickerAdminActivity.this, "已删除表情包组");
                                openPackId = 0;
                                loadPacks();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(StickerAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadLogo(Uri uri) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            File dir = new File(getCacheDir(), "sticker_admin");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "logo_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(StickerAdminActivity.this, "logo 上传失败");
                        return;
                    }
                    Api.updateStickerPack(openPackId, null, url, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(StickerAdminActivity.this, "logo 已更新");
                            openPack(openPackId);
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(StickerAdminActivity.this, message);
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(StickerAdminActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "logo 上传失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            }
            if (uris.isEmpty() && data.getData() != null) {
                uris.add(data.getData());
            }
            uploadMany(uris);
        }
        if (requestCode == REQ_LOGO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadLogo(data.getData());
        }
    }

    private void uploadMany(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        final int total = uris.size();
        final int[] index = {0};
        final Runnable[] next = new Runnable[1];
        next[0] = new Runnable() {
            @Override
            public void run() {
                if (index[0] >= total) {
                    Ui.toast(StickerAdminActivity.this, "已上传 " + total + " 张图片");
                    openPack(openPackId);
                    return;
                }
                Uri uri = uris.get(index[0]++);
                upload(uri, next[0]);
            }
        };
        next[0].run();
    }

    private void upload(Uri uri, final Runnable onDone) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            File dir = new File(getCacheDir(), "sticker_admin");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "sticker_" + System.currentTimeMillis() + "_" + System.nanoTime() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(StickerAdminActivity.this, "图片上传失败");
                        if (onDone != null) onDone.run();
                        return;
                    }
                    Api.addStickerPackItem(openPackId, url, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            if (onDone != null) onDone.run();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(StickerAdminActivity.this, message);
                            if (onDone != null) onDone.run();
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(StickerAdminActivity.this, message);
                    if (onDone != null) onDone.run();
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "图片上传失败：" + e.getMessage());
            if (onDone != null) onDone.run();
        }
    }

    private void upload(Uri uri) {
        upload(uri, null);
    }
}
