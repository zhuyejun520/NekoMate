package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
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

/** 管理员头像框管理：上传原始 PNG、改名、调价、上下架、删除。 */
public class AvatarFrameAdminActivity extends BaseActivity {
    private static final int REQ_CREATE = 81;
    private static final int REQ_REPLACE = 82;
    private static final int REQ_EDIT_CREATE = 83;
    private static final int REQ_EDIT_REPLACE = 84;

    private LinearLayout content;
    private String pendingName = "";
    private int pendingPrice = 0;
    private int replaceFrameId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.themeActive() ? 0x00000000 : Ui.BG);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView back = Ui.button(this, "‹", false);
        back.setTextSize(22);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, "头像框管理", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tlp);
        bar.addView(title);
        TextView add = Ui.button(this, "上传头像框", true);
        add.setTextSize(11.5f);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createDialog();
            }
        });
        bar.addView(add);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        content.removeAllViews();
        content.addView(Ui.tv(this, "正在加载头像框…", 12.5f, Ui.MUTED2, false));
        Api.adminAvatarFrames(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                render(data.optJSONArray("frames"));
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(AvatarFrameAdminActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render(JSONArray frames) {
        content.removeAllViews();
        if (frames == null || frames.length() == 0) {
            content.addView(Ui.tv(this, "还没有头像框，点右上角上传。", 13, Ui.MUTED, false));
            return;
        }
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.optJSONObject(i);
            if (frame != null) renderFrame(frame);
        }
    }

    private void renderFrame(final JSONObject frame) {
        final int id = frame.optInt("id");
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);

        LinearLayout head = Ui.row(this);
        String image = frame.optString("image", "");
        if (!image.isEmpty()) {
            StickerImageView pic = new StickerImageView(this);
            pic.load(image);
            head.addView(pic, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        } else {
            TextView icon = Ui.tv(this, "◯", 28, Ui.ACCENT, false);
            icon.setGravity(Gravity.CENTER);
            head.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        }
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ilp.leftMargin = Ui.dp(this, 10);
        info.setLayoutParams(ilp);
        info.addView(Ui.tv(this, frame.optString("name", "头像框"), 15, Ui.TEXT, true));
        info.addView(Ui.tv(this, frame.optInt("price", 0) + " 金币 · "
                + (frame.optBoolean("enabled", false) ? "已上架" : "未上架"), 11.5f, Ui.MUTED, false));
        head.addView(info);
        card.addView(head);

        LinearLayout row1 = Ui.row(this);
        TextView rename = Ui.button(this, "重命名", false);
        rename.setTextSize(11.5f);
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameDialog(id, frame.optString("name", ""));
            }
        });
        row1.addView(rename);
        TextView price = Ui.button(this, "改价", false);
        price.setTextSize(11.5f);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p1.leftMargin = Ui.dp(this, 8);
        price.setLayoutParams(p1);
        price.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                priceDialog(id, frame.optInt("price", 0));
            }
        });
        row1.addView(price);
        TextView replace = Ui.button(this, "换图", false);
        replace.setTextSize(11.5f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p2.leftMargin = Ui.dp(this, 8);
        replace.setLayoutParams(p2);
        replace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceFrameId = id;
                pickImage(REQ_REPLACE);
            }
        });
        row1.addView(replace);
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1.topMargin = Ui.dp(this, 9);
        card.addView(row1, r1);

        LinearLayout row2 = Ui.row(this);
        boolean enabled = frame.optBoolean("enabled", false);
        TextView toggle = Ui.button(this, enabled ? "下架" : "上架", false);
        toggle.setTextSize(11.5f);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEnabled(id, !enabled);
            }
        });
        row2.addView(toggle);
        if (!frame.optBoolean("is_default", false)) {
            TextView delete = Ui.button(this, "删除", false);
            delete.setTextSize(11.5f);
            delete.setTextColor(Ui.RED);
            LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p3.leftMargin = Ui.dp(this, 8);
            delete.setLayoutParams(p3);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteFrame(id);
                }
            });
            row2.addView(delete);
        }
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = Ui.dp(this, 6);
        card.addView(row2, r2);
        content.addView(card);
    }

    private void createDialog() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        final EditText name = new EditText(this);
        name.setHint("头像框名称");
        name.setSingleLine(true);
        box.addView(name);
        final EditText price = new EditText(this);
        price.setHint("价格（金币）");
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        price.setSingleLine(true);
        box.addView(price);
        new AlertDialog.Builder(this)
                .setTitle("上传头像框")
                .setMessage("先填写名称和价格，然后从相册选择透明 PNG。")
                .setView(box)
                .setPositiveButton("选择 PNG", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            Ui.toast(AvatarFrameAdminActivity.this, "名称不能为空");
                            return;
                        }
                        pendingName = n;
                        try {
                            pendingPrice = Integer.parseInt(price.getText().toString().trim());
                        } catch (Exception e) {
                            pendingPrice = 0;
                        }
                        pickImage(REQ_CREATE);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameDialog(final int id, String oldName) {
        final EditText input = new EditText(this);
        input.setText(oldName);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("重命名头像框")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = input.getText().toString().trim();
                        if (n.isEmpty()) return;
                        Api.updateAvatarFrame(id, n, null, null, null, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AvatarFrameAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void priceDialog(final int id, int oldPrice) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(oldPrice));
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("修改价格")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int price;
                        try {
                            price = Integer.parseInt(input.getText().toString().trim());
                        } catch (Exception e) {
                            price = 0;
                        }
                        Api.updateAvatarFrame(id, null, price, null, null, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AvatarFrameAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setEnabled(final int id, boolean enabled) {
        Api.updateAvatarFrame(id, null, null, null, enabled, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                load();
            }

            @Override
            public void fail(String message) {
                Ui.toast(AvatarFrameAdminActivity.this, message);
            }
        });
    }

    private void deleteFrame(final int id) {
        new AlertDialog.Builder(this)
                .setTitle("删除头像框")
                .setMessage("删除后已购买记录也会移除，确定删除？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.deleteAvatarFrame(id, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AvatarFrameAdminActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickImage(int requestCode) {
        try {
            startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), requestCode);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_EDIT_CREATE || requestCode == REQ_EDIT_REPLACE) {
            String path = data.getStringExtra("path");
            if (path == null || path.isEmpty()) return;
            Uri edited = Uri.fromFile(new File(path));
            if (requestCode == REQ_EDIT_CREATE) {
                upload(edited, new FrameUrlCallback() {
                    @Override
                    public void ok(String url) {
                        Api.createAvatarFrame(pendingName, pendingPrice, url, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(AvatarFrameAdminActivity.this, "头像框已上传");
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AvatarFrameAdminActivity.this, message);
                            }
                        });
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(AvatarFrameAdminActivity.this, message);
                    }
                });
            } else {
                upload(edited, new FrameUrlCallback() {
                    @Override
                    public void ok(String url) {
                        Api.updateAvatarFrame(replaceFrameId, null, null, url, null, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(AvatarFrameAdminActivity.this, "头像框图片已更新");
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AvatarFrameAdminActivity.this, message);
                            }
                        });
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(AvatarFrameAdminActivity.this, message);
                    }
                });
            }
            return;
        }
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            ClipData.Item first = data.getClipData().getItemAt(0);
            if (first != null) uri = first.getUri();
        }
        if (uri == null) return;
        if (requestCode == REQ_CREATE || requestCode == REQ_REPLACE) {
            openEditor(uri, requestCode);
        }
    }

    private void openEditor(Uri uri, int requestCode) {
        try {
            MediaUtil.persist(this, uri);
            Intent editor = new Intent(this, FrameEditorActivity.class);
            editor.putExtra(FrameEditorActivity.EXTRA_URI, uri.toString());
            startActivityForResult(editor,
                    requestCode == REQ_CREATE ? REQ_EDIT_CREATE : REQ_EDIT_REPLACE);
        } catch (Exception e) {
            Ui.toast(this, "无法打开编辑器：" + e.getMessage());
        }
    }

    private interface FrameUrlCallback {
        void ok(String url);

        void fail(String message);
    }

    private void upload(Uri uri, final FrameUrlCallback callback) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            File dir = new File(getCacheDir(), "avatar_frames");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "frame_" + System.currentTimeMillis() + ".png");
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
                    if (url.isEmpty()) callback.fail("上传失败");
                    else callback.ok(url);
                }

                @Override
                public void fail(String message) {
                    callback.fail(message);
                }
            });
        } catch (Exception e) {
            callback.fail("上传失败：" + e.getMessage());
        }
    }
}
