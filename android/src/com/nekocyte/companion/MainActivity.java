package com.nekocyte.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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

public class MainActivity extends BaseActivity {
    private final BasePage[] pages = new BasePage[3];
    private FrameLayout content;
    private TextView title;
    private TextView subtitle;
    private TextView headerName;
    private FrameLayout headerAvatar;
    private LiquidDockView dock;
    private int current = 0;
    public int pendingPersonaAvatarId = 0;
    private BroadcastReceiver downloadReceiver;

    private static final String[] TITLES = {"消息", "AI好友", "动态"};
    private static final String[] SUBS = {"好友、群聊和好友申请", "会记住你的 AI 伴侣", "分享今天的小事"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        if (Ui.themeActive()) content.setBackgroundColor(0x00000000);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        headerName = findViewById(R.id.header_name);
        headerAvatar = findViewById(R.id.header_avatar);
        dock = findViewById(R.id.dock);
        content.setPadding(0, 0, 0, Ui.dp(this, 82));
        syncAvatarFrame();

        pages[0] = new FriendsPage(this);
        pages[1] = new AiPage(this);
        pages[2] = new MomentsPage(this);
        for (BasePage p : pages) {
            p.setVisibility(View.GONE);
            content.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        dock.setOnTabSelectedListener(new LiquidDockView.OnTabSelectedListener() {
            @Override
            public void onSelected(int index) {
                select(index);
            }
        });

        findViewById(R.id.action_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actionAdd();
            }
        });
        LineIconView checkinEntry = findViewById(R.id.checkin_entry);
        checkinEntry.setType("calendar");
        checkinEntry.setIconColor(Ui.TEXT);
        checkinEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCheckin();
            }
        });
        LineIconView storeEntry = findViewById(R.id.store_entry);
        storeEntry.setType("cart");
        storeEntry.setIconColor(Ui.TEXT);
        storeEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, StoreActivity.class));
            }
        });
        findViewById(R.id.profile_entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MyActivity.class));
            }
        });

        select(0);
        askNotificationPermission();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                askMediaPermission();
            }
        }, 1500);
        UpdateManager.check(this);
        UsageMonitorService.start(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id > 0 && id == Session.downloadId()) {
                        UpdateManager.installDownloaded(MainActivity.this, id);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHeaderProfile();
        if (pages[current] != null) pages[current].refresh();
        Api.me(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONObject u = data.optJSONObject("user");
                if (u != null) {
                    Session.updateProfile(u);
                    renderHeaderProfile();
                }
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void renderHeaderProfile() {
        if (headerName == null) return;
        String name = Session.nickname();
        if (name == null || name.isEmpty() || "null".equals(name)) name = Session.username();
        headerName.setText(name);
        if (headerAvatar != null) {
            headerAvatar.removeAllViews();
            int avatarSize = Ui.hasFrame() ? 48 : 40;
            ViewGroup.LayoutParams hlp = headerAvatar.getLayoutParams();
            hlp.width = Ui.dp(this, avatarSize);
            hlp.height = Ui.dp(this, avatarSize);
            headerAvatar.setLayoutParams(hlp);
            headerAvatar.addView(Ui.avatarCircleFramed(this, Session.avatar(), avatarSize),
                    new FrameLayout.LayoutParams(Ui.dp(this, avatarSize), Ui.dp(this, avatarSize)));
        }
    }

    public void select(int index) {
        if (pages[current] != null) pages[current].onHide();
        current = Math.max(0, Math.min(2, index));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == current ? View.VISIBLE : View.GONE);
        }
        if (dock != null) dock.setSelectedIndex(current);
        title.setText(TITLES[current]);
        subtitle.setText(SUBS[current]);
        pages[current].refresh();
        pages[current].onShow();
    }

    private void showCheckin() {
        Api.checkinStatus(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                boolean checked = data.optBoolean("checked_today");
                int streak = data.optInt("streak");
                String msg = "连续签到：" + streak + " 天\n金币：" + data.optInt("coins", 0);
                if (checked) msg += "\n今天已经签到过了";
                final AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("每日签到")
                        .setMessage(msg)
                        .setNegativeButton("关闭", null)
                        .create();
                if (!checked) {
                    dialog.setButton(DialogInterface.BUTTON_POSITIVE, "签到", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            Api.checkin(new Api.Cb() {
                                @Override
                                public void ok(JSONObject result) {
                                    int reward = result.optInt("reward");
                                    int bonus = result.optInt("bonus");
                                    Ui.toast(MainActivity.this, "签到成功，获得 " + (reward + bonus)
                                            + " 金币" + (bonus > 0 ? "（含连续奖励）" : ""));
                                    showCheckin();
                                }

                                @Override
                                public void fail(String message) {
                                    Ui.toast(MainActivity.this, message);
                                }
                            });
                        }
                    });
                }
                dialog.show();
            }

            @Override
            public void fail(String message) {
                Ui.toast(MainActivity.this, message);
            }
        });
    }

    private void actionAdd() {
        if (current == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("新建")
                    .setItems(new String[]{"添加好友", "创建群聊"}, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            if (which == 0) addFriendDialog();
                            else createGroupDialog();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else if (current == 1) {
            openPersonaWorld();
        } else {
            postMomentDialog();
        }
    }

    public void openChat(int convId, String title, String type, int aiId) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("conv_id", convId);
        i.putExtra("title", title);
        i.putExtra("type", type);
        i.putExtra("ai_id", aiId);
        startActivity(i);
    }

    public void addFriendDialog() {
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);

        final EditText input = new EditText(this);
        input.setHint("输入对方用户名");
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED2);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        root.addView(input);

        TextView search = Ui.button(this, "搜索用户", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 10);
        search.setLayoutParams(slp);
        root.addView(search);

        final LinearLayout results = Ui.column(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 10);
        root.addView(results, rlp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加好友")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();

        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = input.getText().toString().trim();
                if (q.isEmpty()) {
                    Ui.toast(MainActivity.this, "请输入用户名或昵称");
                    return;
                }
                results.removeAllViews();
                results.addView(Ui.tv(MainActivity.this, "搜索中…", 12.5f, Ui.MUTED, false));
                Api.search(q, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        results.removeAllViews();
                        JSONArray arr = data.optJSONArray("users");
                        if (arr == null || arr.length() == 0) {
                            results.addView(Ui.tv(MainActivity.this, "没有找到用户", 13, Ui.MUTED, false));
                            return;
                        }
                        for (int i = 0; i < arr.length(); i++) {
                            final JSONObject u = arr.optJSONObject(i);
                            if (u == null) continue;
                            LinearLayout row = Ui.card(MainActivity.this);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            lp.bottomMargin = Ui.dp(MainActivity.this, 8);
                            row.setLayoutParams(lp);

                            LinearLayout left = Ui.row(MainActivity.this);
                            left.setLayoutParams(new LinearLayout.LayoutParams(0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                            left.addView(Ui.avatar(MainActivity.this, u.optString("avatar"), 42));
                            LinearLayout names = Ui.column(MainActivity.this);
                            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            nlp.leftMargin = Ui.dp(MainActivity.this, 10);
                            names.setLayoutParams(nlp);
                            names.addView(Ui.tv(MainActivity.this, u.optString("nickname"), 14, Ui.TEXT, true));
                            names.addView(Ui.tv(MainActivity.this, "@" + u.optString("username"), 11, Ui.MUTED2, false));
                            left.addView(names);
                            row.addView(left);

                            if (u.optBoolean("is_friend", false)) {
                                row.addView(Ui.badge(MainActivity.this, "已是好友", Ui.GREEN));
                            } else {
                                TextView add = Ui.button(MainActivity.this, "添加", true);
                                add.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        Api.sendFriendRequest(u.optString("username"), new Api.Cb() {
                                            @Override
                                            public void ok(JSONObject data) {
                                                Ui.toast(MainActivity.this, "好友申请已发送");
                                                dialog.dismiss();
                                                pages[0].refresh();
                                            }

                                            @Override
                                            public void fail(String message) {
                                                Ui.toast(MainActivity.this, message);
                                            }
                                        });
                                    }
                                });
                                row.addView(add);
                            }
                            results.addView(row);
                        }
                    }

                    @Override
                    public void fail(String message) {
                        results.removeAllViews();
                        results.addView(Ui.tv(MainActivity.this, message, 13, Ui.RED, false));
                    }
                });
            }
        });
    }

    public void addAiDialog() {
        startActivity(new Intent(this, PersonaWorldActivity.class));
    }

    public void openPersonaWorld() {
        startActivity(new Intent(this, PersonaWorldActivity.class));
    }

    public void createGroupDialog() {
        Api.friends(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                final JSONArray friends = data.optJSONArray("friends");
                if (friends == null || friends.length() == 0) {
                    Ui.toast(MainActivity.this, "先添加好友，才能建群");
                    return;
                }
                LinearLayout root = Ui.column(MainActivity.this);
                int pad = Ui.dp(MainActivity.this, 18);
                root.setPadding(pad, pad, pad, pad);

                final EditText name = new EditText(MainActivity.this);
                name.setHint("群聊名称");
                name.setSingleLine(true);
                name.setBackgroundResource(R.drawable.bg_input);
                name.setPadding(Ui.dp(MainActivity.this, 12), 0, Ui.dp(MainActivity.this, 12), 0);
                name.setTextColor(Ui.TEXT);
                name.setHintTextColor(Ui.MUTED2);
                name.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(MainActivity.this, 46)));
                root.addView(name);

                final List<CheckBox> checks = new ArrayList<>();
                for (int i = 0; i < friends.length(); i++) {
                    JSONObject f = friends.optJSONObject(i);
                    if (f == null) continue;
                    CheckBox cb = new CheckBox(MainActivity.this);
                    String remark = f.optString("remark", "");
                    cb.setText((remark.isEmpty() ? f.optString("nickname") : remark)
                            + "  @" + f.optString("username"));
                    cb.setTextColor(Ui.TEXT);
                    cb.setTag(f.optInt("id"));
                    cb.setPadding(0, Ui.dp(MainActivity.this, 4), 0, Ui.dp(MainActivity.this, 4));
                    checks.add(cb);
                    root.addView(cb);
                }

                ScrollView scroll = new ScrollView(MainActivity.this);
                scroll.addView(root);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("创建群聊")
                        .setView(scroll)
                        .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String title = name.getText().toString().trim();
                                if (title.isEmpty()) {
                                    Ui.toast(MainActivity.this, "群名不能为空");
                                    return;
                                }
                                List<Integer> ids = new ArrayList<>();
                                for (CheckBox cb : checks) {
                                    if (cb.isChecked()) ids.add((Integer) cb.getTag());
                                }
                                if (ids.isEmpty()) {
                                    Ui.toast(MainActivity.this, "至少选择一个好友");
                                    return;
                                }
                                Api.group(title, ids, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        Ui.toast(MainActivity.this, "群聊已创建");
                                        select(2);
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(MainActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void fail(String message) {
                Ui.toast(MainActivity.this, message);
            }
        });
    }

    public void postMomentDialog() {
        startActivityForResult(new Intent(this, MomentEditorActivity.class), 22);
    }

    public void profileDialog() {
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);
        root.addView(Ui.tv(this, "账号：" + Session.username(), 12.5f, Ui.MUTED2, false));

        final EditText nickname = new EditText(this);
        nickname.setHint("昵称");
        nickname.setSingleLine(true);
        nickname.setText(Session.nickname());
        nickname.setBackgroundResource(R.drawable.bg_input);
        nickname.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        nickname.setTextColor(Ui.TEXT);
        nickname.setHintTextColor(Ui.MUTED2);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        nlp.topMargin = Ui.dp(this, 10);
        root.addView(nickname, nlp);

        final EditText city = new EditText(this);
        city.setHint("城市（用于 AI 聊天气）");
        city.setSingleLine(true);
        city.setText(Session.city());
        city.setBackgroundResource(R.drawable.bg_input);
        city.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        city.setTextColor(Ui.TEXT);
        city.setHintTextColor(Ui.MUTED2);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        clp.topMargin = Ui.dp(this, 10);
        root.addView(city, clp);

        root.addView(Ui.tv(this, "服务器：" + Session.server(), 11, Ui.MUTED2, false));

        TextView themeBtn = Ui.button(this, "切换主题（当前：" + (Session.dark() ? "深色" : "浅色") + "）", false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 12);
        root.addView(themeBtn, tlp);
        themeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTheme();
            }
        });

        TextView bgBtn = Ui.button(this, Session.chatBg().isEmpty() ? "选择聊天背景（PNG/图片）" : "更换聊天背景", false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        root.addView(bgBtn, blp);
        bgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickChatBackground();
            }
        });
        if (!Session.chatBg().isEmpty()) {
            TextView clearBg = Ui.button(this, "恢复默认聊天背景", false);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp2.topMargin = Ui.dp(this, 8);
            root.addView(clearBg, clp2);
            clearBg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Session.setChatBg("");
                    Ui.toast(MainActivity.this, "已恢复默认背景");
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle("我的")
                .setView(root)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("nickname", nickname.getText().toString().trim());
                            body.put("city", city.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        Api.updateProfile(body, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                JSONObject u = data.optJSONObject("user");
                                if (u != null) Session.updateProfile(u);
                                Ui.toast(MainActivity.this, "已保存");
                                pages[current].refresh();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(MainActivity.this, message);
                            }
                        });
                    }
                })
                .setNeutralButton("退出登录", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        UsageMonitorService.stop(MainActivity.this);
                        Session.clear();
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
        }
    }

    private void askMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 10);
            }
        } else if (Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 10);
        }
    }

    private void chooseTheme() {
        final String[] items = {"浅色（默认）", "深色"};
        new AlertDialog.Builder(this)
                .setTitle("选择主题")
                .setSingleChoiceItems(items, Session.dark() ? 1 : 0, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Session.setDark(which == 1);
                        dialog.dismiss();
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickChatBackground() {
        new AlertDialog.Builder(this)
                .setTitle("选择聊天背景")
                .setItems(new String[]{"从相册选择", "从文件管理选择"}, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            Intent intent = which == 0
                                    ? MediaUtil.pickFromGallery(MainActivity.this, false, 1)
                                    : MediaUtil.pickFromDocuments(MainActivity.this, false);
                            startActivityForResult(intent, 21);
                        } catch (Exception e) {
                            Ui.toast(MainActivity.this, "没有可用的图片选择器");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadPersonaAvatar(Uri uri) {
        final int personaId = pendingPersonaAvatarId;
        if (personaId <= 0) return;
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File dir = new File(getCacheDir(), "persona");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "persona_" + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Ui.toast(this, "正在上传角色头像…");
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        Ui.toast(MainActivity.this, "上传失败");
                        return;
                    }
                    Api.updatePersonaAvatar(personaId, url, new Api.Cb() {
                        @Override
                        public void ok(JSONObject d) {
                            Ui.toast(MainActivity.this, "角色头像已更新");
                            if (pages[1] != null) pages[1].refresh();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(MainActivity.this, message);
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    Ui.toast(MainActivity.this, message);
                }
            });
        } catch (Exception e) {
            Ui.toast(this, "头像上传失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 22 && resultCode == RESULT_OK) {
            pages[2].refresh();
            return;
        }
        if (requestCode == 24 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                uri = data.getClipData().getItemAt(0).getUri();
            }
            if (uri != null) uploadPersonaAvatar(uri);
            return;
        }
        if (requestCode == 21 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                uri = data.getClipData().getItemAt(0).getUri();
            }
            if (uri == null) return;
            try {
                InputStream in = MediaUtil.open(this, uri);
                if (in == null) throw new Exception("图片读取失败");
                File out = new File(getFilesDir(), "chat_bg.png");
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close();
                in.close();
                MediaUtil.persist(this, uri);
                Session.setChatBg(out.getAbsolutePath());
                Ui.toast(this, "聊天背景已更新，进入聊天查看");
            } catch (Exception e) {
                Ui.toast(this, "设置失败：" + e.getMessage());
            }
        }
    }

    private void syncAvatarFrame() {
        try {
            if (!Ui.hasFrame()) return;
            JSONObject body = new JSONObject();
            String frame = Session.storeFrame();
            body.put("avatar_frame", frame);
            Api.updateProfile(body, new Api.Cb() {
                @Override
                public void ok(JSONObject response) {
                }

                @Override
                public void fail(String message) {
                }
            });
        } catch (Exception ignored) {
        }
    }}
