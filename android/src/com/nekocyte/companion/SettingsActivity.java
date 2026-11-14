package com.nekocyte.companion;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends BaseActivity {
    private static final int REQ_AVATAR = 51;
    private static final int REQ_BG = 52;
    private static final int REQ_EXPORT = 53;

    private FrameLayout avatarBox;
    private EditText nameEdit;
    private EditText bioEdit;
    private EditText tagsEdit;
    private TextView genderRow;
    private String selectedGender = "secret";
    private String avatarUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedGender = Session.gender();
        avatarUrl = Session.avatar();

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
        TextView title = Ui.tv(this, "设置", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, pad, pad, Ui.dp(this, 30));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        body.addView(section("个人资料"));
        LinearLayout profileCard = Ui.card(this);
        LinearLayout avatarRow = Ui.row(this);
        avatarBox = new FrameLayout(this);
        avatarBox.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 78)));
        avatarRow.addView(avatarBox);
        TextView changeAvatar = Ui.button(this, "更换头像", false);
        LinearLayout.LayoutParams calp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        calp.leftMargin = Ui.dp(this, 14);
        changeAvatar.setLayoutParams(calp);
        changeAvatar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseImage(REQ_AVATAR);
            }
        });
        avatarRow.addView(changeAvatar);
        profileCard.addView(avatarRow);

        nameEdit = input("昵称", false);
        profileCard.addView(label("昵称"));
        profileCard.addView(nameEdit);
        nameEdit.setText(Session.nickname());

        profileCard.addView(label("性别"));
        genderRow = Ui.tv(this, "", 14, Ui.TEXT, false);
        genderRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        genderRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseGender();
            }
        });
        profileCard.addView(genderRow);

        profileCard.addView(label("个性签名"));
        bioEdit = input("写一句个性签名", true);
        bioEdit.setText(Session.bio());
        profileCard.addView(bioEdit);

        profileCard.addView(label("个人标签（逗号分隔）"));
        tagsEdit = input("例如：猫奴, 夜猫子, 喜欢下雨", false);
        tagsEdit.setText(tagsText(Session.tagsJson()));
        profileCard.addView(tagsEdit);

        TextView save = Ui.button(this, "保存资料", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        slp.topMargin = Ui.dp(this, 12);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProfile();
            }
        });
        profileCard.addView(save);
        body.addView(profileCard);

        body.addView(section("外观"));
        LinearLayout look = Ui.card(this);
        look.addView(row("主题", Session.dark() ? "深色" : "浅色", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTheme();
            }
        }));
        look.addView(row("聊天背景", Session.chatBg().isEmpty() ? "默认" : "已设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseBackground();
            }
        }));
        body.addView(look);

        body.addView(section("通用"));
        LinearLayout general = Ui.card(this);
        Switch filePicker = new Switch(this);
        filePicker.setText("显示文件管理导入");
        filePicker.setTextColor(Ui.TEXT);
        filePicker.setTextSize(14);
        filePicker.setChecked(Session.filePickerEnabled());
        filePicker.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Session.setFilePickerEnabled(isChecked);
                DebugLog.i("settings", "file picker enabled=" + isChecked);
            }
        });
        general.addView(filePicker);
        general.addView(Ui.tv(this, "默认关闭。开启后，相册、头像、聊天背景会额外显示「从文件管理选择」。", 11.5f, Ui.MUTED2, false));

        final Switch rootNotify = new Switch(this);
        rootNotify.setText("Root 通知增强（实验）");
        rootNotify.setTextColor(Ui.TEXT);
        rootNotify.setTextSize(14);
        rootNotify.setChecked(Session.rootNotifyEnabled());
        rootNotify.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, final boolean isChecked) {
                Session.setRootNotifyEnabled(isChecked);
                if (!isChecked) {
                    Ui.toast(SettingsActivity.this, "已关闭 Root 通知增强");
                    return;
                }
                Ui.toast(SettingsActivity.this, "正在检查 root 权限…");
                RootShell.checkAsync(SettingsActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        if (!RootShell.isAvailable()) {
                            Session.setRootNotifyEnabled(false);
                            rootNotify.setChecked(false);
                            Ui.toast(SettingsActivity.this, "未获取到 root，继续使用普通通知");
                            return;
                        }
                        RootShell.applyPowerSettingsAsync(getPackageName());
                        Ui.toast(SettingsActivity.this, "Root 通知增强已开启");
                    }
                });
            }
        });
        general.addView(rootNotify);
        general.addView(Ui.tv(this, "默认关闭。开启后，AI 好友消息会尝试通过 su 调用系统通知命令，失败自动回退普通通知。", 11.5f, Ui.MUTED2, false));
        general.addView(row("专属模型 Key", "添加自己的 OpenAI 兼容 Key，用于私人角色卡和私人知识库", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PrivateKeyActivity.class));
            }
        }));
        body.addView(general);

        body.addView(section("诊断"));
        LinearLayout diag = Ui.card(this);
        diag.addView(row("照片和视频权限", permissionText(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        }));
        diag.addView(row("通知与悬浮通知", notificationText(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotificationSettings();
            }
        }));
        diag.addView(row("导出调试日志", "只导出最近 4000 行，避免旧日志淹没新问题", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        }));
        diag.addView(row("清除调试日志", "删除本地调试日志和上一份滚动日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DebugLog.clear(SettingsActivity.this);
                Ui.toast(SettingsActivity.this, "调试日志已清除");
            }
        }));
        diag.addView(row("清除本地缓存", "好友、群聊、消息、AI 好友、动态、头像与图片缓存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearCache();
            }
        }));
        body.addView(diag);

        body.addView(section("账号"));
        LinearLayout account = Ui.card(this);
        TextView logout = Ui.tv(this, "退出登录", 14, Ui.RED, false);
        logout.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        logout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLogout();
            }
        });
        account.addView(logout);
        body.addView(account);

        TextView version = Ui.tv(this, "NekoMate " + UpdateManager.VERSION_NAME + " · " + UpdateManager.VERSION_CODE, 11, Ui.MUTED2, false);
        version.setPadding(0, Ui.dp(this, 18), 0, 0);
        body.addView(version);
        setContentView(root);
        renderAvatar();
        renderGender();
    }

    private TextView section(String text) {
        TextView t = Ui.tv(this, text, 11.5f, Ui.MUTED, true);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView label(String text) {
        TextView t = Ui.tv(this, text, 11.5f, Ui.MUTED2, false);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        return t;
    }

    private EditText input(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        e.setSingleLine(!multiline);
        if (multiline) e.setMinLines(2);
        return e;
    }

    private View row(String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        row.addView(Ui.tv(this, title, 14, Ui.TEXT, false));
        TextView sub = Ui.tv(this, subtitle, 11.5f, Ui.MUTED2, false);
        row.addView(sub);
        row.setOnClickListener(click);
        return row;
    }

    private void renderAvatar() {
        avatarBox.removeAllViews();
        int avatarSize = Ui.hasFrame() ? 78 : 64;
        avatarBox.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, avatarSize), Ui.dp(this, avatarSize)));
        avatarBox.addView(Ui.avatarCircleFramed(this, avatarUrl, avatarSize),
                new FrameLayout.LayoutParams(Ui.dp(this, avatarSize), Ui.dp(this, avatarSize)));
    }

    private void renderGender() {
        if ("male".equals(selectedGender)) genderRow.setText("男");
        else if ("female".equals(selectedGender)) genderRow.setText("女");
        else genderRow.setText("保密");
    }

    private void chooseGender() {
        final String[] values = {"male", "female", "secret"};
        String[] labels = {"男", "女", "保密"};
        int checked = 2;
        for (int i = 0; i < values.length; i++) if (values[i].equals(selectedGender)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("性别")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedGender = values[which];
                        renderGender();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void chooseTheme() {
        final String[] items = {"浅色", "深色"};
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(items, Session.dark() ? 1 : 0, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Session.setDark(which == 1);
                        dialog.dismiss();
                        Intent main = new Intent(SettingsActivity.this, MainActivity.class);
                        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(main);
                        finish();
                    }
                })
                .show();
    }

    private void chooseBackground() {
        final boolean filePicker = Session.filePickerEnabled();
        final String[] items = filePicker
                ? new String[]{"从相册选择", "从文件管理选择", "恢复默认"}
                : new String[]{"从相册选择", "恢复默认"};
        new AlertDialog.Builder(this)
                .setTitle("聊天背景")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == items.length - 1) {
                            Session.setChatBg("");
                            Ui.toast(SettingsActivity.this, "已恢复默认背景");
                            return;
                        }
                        try {
                            Intent intent = which == 0
                                    ? MediaUtil.pickFromGallery(SettingsActivity.this, false, 1)
                                    : MediaUtil.pickFromDocuments(SettingsActivity.this, false);
                            startActivityForResult(intent, REQ_BG);
                        } catch (Exception e) {
                            Ui.toast(SettingsActivity.this, "没有可用的图片选择器");
                        }
                    }
                })
                .show();
    }

    private void chooseImage(int request) {
        if (!Session.filePickerEnabled()) {
            try {
                startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), request);
            } catch (Exception e) {
                Ui.toast(this, "没有可用的图片选择器");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择头像")
                .setItems(new String[]{"从相册选择", "从文件管理选择"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = which == 0
                                    ? MediaUtil.pickFromGallery(SettingsActivity.this, false, 1)
                                    : MediaUtil.pickFromDocuments(SettingsActivity.this, false);
                            startActivityForResult(intent, REQ_AVATAR);
                        } catch (Exception e) {
                            Ui.toast(SettingsActivity.this, "没有可用的图片选择器");
                        }
                    }
                })
                .show();
    }

    private void saveProfile() {
        final String name = nameEdit.getText().toString().trim();
        final String bio = bioEdit.getText().toString().trim();
        final String tags = tagsEdit.getText().toString().trim();
        if (name.isEmpty()) {
            Ui.toast(this, "昵称不能为空");
            return;
        }
        if (bio.length() > 255) {
            Ui.toast(this, "个性签名最多 255 字");
            return;
        }
        commitProfile(name, bio, tags);
    }

    private void commitProfile(String name, String bio, String tagsText) {
        JSONObject body = new JSONObject();
        JSONArray tags = new JSONArray();
        try {
            body.put("nickname", name);
            body.put("bio", bio);
            body.put("avatar", avatarUrl);
            body.put("gender", selectedGender);
            String[] parts = tagsText.split("[,，\\s]+");
            for (String p : parts) {
                String v = p.trim();
                if (!v.isEmpty() && v.length() <= 16 && !contains(tags, v)) tags.put(v);
                if (tags.length() >= 8) break;
            }
            body.put("tags", tags);
        } catch (Exception ignored) {
        }
        Api.updateProfile(body, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONObject user = data.optJSONObject("user");
                if (user != null) Session.updateProfile(user);
                Ui.toast(SettingsActivity.this, "资料已保存");
                nameEdit.setText(Session.nickname());
                renderAvatar();
            }

            @Override
            public void fail(String message) {
                Ui.toast(SettingsActivity.this, message);
            }
        });
    }

    private boolean contains(JSONArray arr, String value) {
        for (int i = 0; i < arr.length(); i++) if (value.equals(arr.optString(i))) return true;
        return false;
    }

    private String tagsText(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(arr.optString(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String permissionText() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean video = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (Build.VERSION.SDK_INT >= 34) {
                boolean selected = checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
                if (selected) return "已授权部分照片";
            }
            return images && video ? "已授权" : "未授权，点击去系统设置";
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                ? "已授权" : "未授权，点击去系统设置";
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private String notificationText() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean enabled = nm == null || nm.areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 33) {
            boolean permission = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!permission || !enabled) return "未开启，点击设置通知权限";
        } else if (!enabled) {
            return "未开启，点击设置通知权限";
        }
        return "已开启；可在这里检查声音和悬浮通知";
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void exportLog() {
        try {
            DebugLog.i("settings", "export debug log");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "nekomate-debug-" + System.currentTimeMillis() + ".txt");
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception e) {
            Ui.toast(this, "没有可用的文件保存器");
        }
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清除本地缓存")
                .setMessage("会删除好友、群聊、消息、AI 好友、动态和头像缓存；账号、设置和常用表情包保留。确定清除？")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LocalStore.clear(SettingsActivity.this);
                        AvatarCache.clear();
                        ImageCache.clear();
                        AudioCache.clear();
                        Ui.toast(SettingsActivity.this, "本地缓存已清除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("常用表情包会保留在本机，聊天缓存也会保留。确定退出？")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UsageMonitorService.stop(SettingsActivity.this);
                        Session.clear();
                        Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finishAffinity();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_EXPORT) {
            if (data != null && data.getData() != null) {
                try {
                    DebugLog.writeToUri(this, data.getData());
                    Ui.toast(this, "调试日志已导出");
                } catch (Exception e) {
                    Ui.toast(this, "导出失败：" + e.getMessage());
                }
            }
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null) return;
        if (requestCode == REQ_AVATAR) uploadAvatar(uri);
        else if (requestCode == REQ_BG) setBackground(uri);
    }

    private void uploadAvatar(Uri uri) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File dir = new File(getCacheDir(), "avatar");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Ui.toast(this, "正在上传头像…");
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(SettingsActivity.this, "头像上传失败");
                        return;
                    }
                    avatarUrl = url;
                    renderAvatar();
                    JSONObject body = new JSONObject();
                    try {
                        body.put("avatar", url);
                    } catch (Exception ignored) {
                    }
                    Api.updateProfile(body, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            JSONObject user = data.optJSONObject("user");
                            if (user != null) Session.updateProfile(user);
                            Ui.toast(SettingsActivity.this, "头像已更新");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(SettingsActivity.this, message);
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(SettingsActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "头像上传失败：" + e.getMessage());
        }
    }

    private void setBackground(Uri uri) {
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File out = new File(getFilesDir(), "chat_bg.jpg");
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            Session.setChatBg(out.getAbsolutePath());
            Ui.toast(this, "聊天背景已更新");
        } catch (Exception e) {
            Ui.toast(this, "背景设置失败：" + e.getMessage());
        }
    }
}
