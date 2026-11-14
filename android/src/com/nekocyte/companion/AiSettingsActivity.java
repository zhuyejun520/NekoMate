package com.nekocyte.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AiSettingsActivity extends BaseActivity {
    private int aiId;
    private int convId;
    private LinearLayout container;
    private boolean rendering = false;
    private TextView rootStateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        container = findViewById(R.id.container);
        aiId = getIntent().getIntExtra("ai_id", 0);
        if (aiId <= 0) {
            Ui.toast(this, "AI 好友不存在");
            finish();
            return;
        }
        Session.setActiveAi(aiId);
        load();
    }

    private void load() {
        container.removeAllViews();
        addHeader();
        Api.aiDetail(aiId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                render(data.optJSONObject("ai_friend"));
            }

            @Override
            public void fail(String message) {
                Ui.toast(AiSettingsActivity.this, message);
            }
        });
    }

    private void addHeader() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        TextView back = Ui.tv(this, "‹", 30, Ui.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);
        TextView title = Ui.tv(this, "AI 好友设置", 20, Ui.TEXT, true);
        bar.addView(title);
        container.addView(bar);
    }

    private void render(JSONObject ai) {
        if (ai == null) {
            Ui.toast(this, "加载失败");
            return;
        }
        convId = ai.optInt("conversation_id", convId);
        container.removeAllViews();
        addHeader();

        JSONObject persona = ai.optJSONObject("persona");
        LinearLayout profile = Ui.card(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = Ui.dp(this, 12);
        profile.setLayoutParams(plp);
        LinearLayout profileRow = Ui.row(this);
        profileRow.addView(Ui.avatar(this, ai.optString("avatar"), 56));
        LinearLayout pcol = Ui.column(this);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pclp.leftMargin = Ui.dp(this, 12);
        pcol.setLayoutParams(pclp);
        pcol.addView(Ui.tv(this, ai.optString("name"), 19, Ui.TEXT, true));
        pcol.addView(Ui.tv(this, persona == null ? "" : persona.optString("tagline"), 12, Ui.MUTED, false));
        rootStateView = Ui.tv(this, ai.optBoolean("root_enabled", false) ? "root 已开启" : "root 未开启",
                11.5f, ai.optBoolean("root_enabled", false) ? Ui.GREEN : Ui.MUTED2, false);
        pcol.addView(rootStateView);
        profileRow.addView(pcol);
        profile.addView(profileRow);
        container.addView(profile);

        renderRoot(ai);
        renderAffinity(ai);
        renderSplitReplies(ai);
        renderUserSegment(ai);
        renderService();
        renderProactiveSettings(ai);
        renderWhitelist(ai);
        loadProactive();
        loadMemories();
        renderDanger();
    }

    private void renderRoot(final JSONObject ai) {
        final boolean enabled = ai.optBoolean("root_enabled", false);
        LinearLayout card = section("root 权限");
        final Switch sw = new Switch(this);
        sw.setText("允许 AI 感知应用使用");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(14);
        sw.setChecked(enabled);
        card.addView(sw);

        final TextView status = Ui.tv(this, "", 11.5f, Ui.MUTED2, false);
        status.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(status);
        refreshRootStatus(status);

        final TextView grant = Ui.button(this, "打开「使用情况访问」设置", false);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        card.addView(grant);

        final TextView rootGrant = Ui.button(this, "用 root 自动授权", false);
        rootGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = RootUtil.grantUsageViaRoot(AiSettingsActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(AiSettingsActivity.this, ok ? "已尝试授权，请重新检测" : "未获得 root");
                                refreshRootStatus(status);
                            }
                        });
                    }
                }).start();
            }
        });
        card.addView(rootGrant);

        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (rendering) return;
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean can = RootUtil.hasUsageAccess(AiSettingsActivity.this) || RootUtil.hasRoot();
                            if (can && !RootUtil.hasUsageAccess(AiSettingsActivity.this) && RootUtil.hasRoot()) {
                                RootUtil.grantUsageViaRoot(AiSettingsActivity.this);
                                can = RootUtil.hasUsageAccess(AiSettingsActivity.this) || RootUtil.hasRoot();
                            }
                            final boolean allowed = can;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!allowed) {
                                        rendering = true;
                                        sw.setChecked(false);
                                        rendering = false;
                                        new AlertDialog.Builder(AiSettingsActivity.this)
                                                .setTitle("需要授权")
                                                .setMessage("请先授予「使用情况访问」权限，或确认手机已 root。\n\n授权后 AI 才能知道你在用什么应用。")
                                                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface dialog, int which) {
                                                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                                                    }
                                                })
                                                .setNegativeButton("取消", null)
                                                .show();
                                        return;
                                    }
                                    setRoot(true);
                                }
                            });
                        }
                    }).start();
                } else {
                    setRoot(false);
                }
            }
        });
        container.addView(card);
    }

    private void refreshRootStatus(final TextView status) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean root = RootUtil.hasRoot();
                final boolean access = RootUtil.hasUsageAccess(AiSettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText((root ? "✓ 已检测到 root" : "• 未检测到 root")
                                + (access ? "　✓ 使用情况权限已授予" : "　• 使用情况权限未授予"));
                        status.setTextColor(access ? Ui.GREEN : Ui.MUTED2);
                    }
                });
            }
        }).start();
    }

    private void setRoot(final boolean enabled) {
        JSONObject body = new JSONObject();
        try {
            body.put("root_enabled", enabled);
        } catch (Exception ignored) {
        }
        Api.updateAi(aiId, body, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                Session.setRootEnabled(aiId, enabled);
                if (rootStateView != null) {
                    rootStateView.setText(enabled ? "root 已开启" : "root 未开启");
                    rootStateView.setTextColor(enabled ? Ui.GREEN : Ui.MUTED2);
                }
                if (enabled && Session.serviceEnabled()) {
                    Session.setActiveAi(aiId);
                    UsageMonitorService.start(AiSettingsActivity.this);
                }
                if (!enabled) UsageMonitorService.start(AiSettingsActivity.this);
                Ui.toast(AiSettingsActivity.this, enabled ? "root 感知已开启" : "root 感知已关闭，消息通知仍开启");
            }

            @Override
            public void fail(String message) {
                Ui.toast(AiSettingsActivity.this, message);
            }
        });
    }

    private void renderAffinity(final JSONObject ai) {
        JSONObject s = ai.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        final int value = s.optInt("affinity", 0);
        LinearLayout card = section("好感度");
        card.addView(Ui.tv(this, "当前好感度：" + value + " / 100", 14, Ui.TEXT, true));
        card.addView(Ui.tv(this, "开启后，推理模型会在每次聊天后判断好感度变化；普通对话不变，明显表达喜欢、依赖、感谢、分享重要情绪时才会增加。好感度会影响 AI 的语气和亲近程度。", 11.5f, Ui.MUTED2, false));
        final Switch sw = new Switch(this);
        sw.setText("启用好感度系统");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(14);
        sw.setChecked(s.optBoolean("affinity_enabled", true));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (rendering) return;
                try {
                    JSONObject settings = new JSONObject();
                    settings.put("affinity_enabled", isChecked);
                    JSONObject body = new JSONObject();
                    body.put("settings", settings);
                    Api.updateAi(aiId, body, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(AiSettingsActivity.this, isChecked ? "好感度系统已开启" : "好感度系统已关闭");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(AiSettingsActivity.this, message);
                            load();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
        card.addView(sw);
        TextView reset = Ui.button(this, "重置好感度为 0", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(AiSettingsActivity.this)
                        .setTitle("重置好感度")
                        .setMessage("把当前好感度恢复为 0？")
                        .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    JSONObject settings = new JSONObject();
                                    settings.put("affinity", 0);
                                    JSONObject body = new JSONObject();
                                    body.put("settings", settings);
                                    Api.updateAi(aiId, body, new Api.Cb() {
                                        @Override
                                        public void ok(JSONObject data) {
                                            Ui.toast(AiSettingsActivity.this, "好感度已重置");
                                            load();
                                        }

                                        @Override
                                        public void fail(String message) {
                                            Ui.toast(AiSettingsActivity.this, message);
                                        }
                                    });
                                } catch (Exception ignored) {
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        card.addView(reset);
        container.addView(card);
    }

    private void renderSplitReplies(JSONObject ai) {
        JSONObject s = ai.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        LinearLayout card = section("回复方式");
        card.addView(Ui.tv(this, "开启后，AI 会把一段回复拆成几条短消息发送，而不是每次只回一条长消息。", 11.5f, Ui.MUTED2, false));
        final Switch sw = new Switch(this);
        sw.setText("分段式回复");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(14);
        sw.setChecked(s.optBoolean("split_replies", false));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (rendering) return;
                try {
                    JSONObject settings = new JSONObject();
                    settings.put("split_replies", isChecked);
                    JSONObject body = new JSONObject();
                    body.put("settings", settings);
                    Api.updateAi(aiId, body, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(AiSettingsActivity.this, isChecked ? "分段式回复已开启" : "分段式回复已关闭");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(AiSettingsActivity.this, message);
                            load();
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
        card.addView(sw);
        container.addView(card);
    }

    private void renderUserSegment(JSONObject ai) {
        JSONObject s = ai.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        LinearLayout card = section("用户分段回复");
        card.addView(Ui.tv(this, "你连续发几条消息时，AI 会等你停下来再合并成一段回复。填 0 关闭。", 11.5f, Ui.MUTED2, false));
        final EditText seconds = new EditText(this);
        seconds.setHint("等待秒数 0-30");
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setSingleLine(true);
        seconds.setText(String.valueOf(s.optInt("user_segment_delay", 0)));
        card.addView(seconds);
        TextView save = Ui.button(this, "保存等待时间", false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int value;
                try {
                    value = Integer.parseInt(seconds.getText().toString().trim());
                } catch (Exception e) {
                    value = 0;
                }
                value = Math.max(0, Math.min(30, value));
                final int delaySeconds = value;
                try {
                    JSONObject settings = new JSONObject();
                    settings.put("user_segment_delay", delaySeconds);
                    JSONObject body = new JSONObject();
                    body.put("settings", settings);
                    Api.updateAi(aiId, body, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(AiSettingsActivity.this, delaySeconds == 0 ? "已关闭用户分段等待" : "已设置为 " + delaySeconds + " 秒");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(AiSettingsActivity.this, message);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
        card.addView(save);
        container.addView(card);
    }

    private void renderService() {
        LinearLayout card = section("后台陪伴与消息");
        final Switch sw = new Switch(this);
        sw.setText("在后台监听白名单应用");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(14);
        sw.setChecked(Session.serviceEnabled());
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (rendering) return;
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean can = RootUtil.hasUsageAccess(AiSettingsActivity.this) || RootUtil.hasRoot();
                            final boolean allowed = can;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!allowed) {
                                        rendering = true;
                                        sw.setChecked(false);
                                        rendering = false;
                                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                                        return;
                                    }
                                    Session.setActiveAi(aiId);
                                    Session.setServiceEnabled(true);
                                    UsageMonitorService.start(AiSettingsActivity.this);
                                    Ui.toast(AiSettingsActivity.this, "后台陪伴已开启");
                                }
                            });
                        }
                    }).start();
                } else {
                    Session.setServiceEnabled(false);
                    UsageMonitorService.start(AiSettingsActivity.this);
                    Ui.toast(AiSettingsActivity.this, "后台应用检测已关闭，聊天消息仍会通知");
                }
            }
        });
        card.addView(sw);
        card.addView(Ui.tv(this, "开启后，你打开白名单里的应用时，夜音会收到通知并主动回应。", 11.5f, Ui.MUTED2, false));
        card.addView(Ui.tv(this, "好友、群聊、AI 消息的后台通知始终开启；关闭上面的开关只会停止应用使用检测。", 11.5f, Ui.MUTED2, false));
        container.addView(card);
    }

    private void renderProactiveSettings(JSONObject ai) {
        JSONObject s = ai.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        LinearLayout card = section("主动消息参数");
        card.addView(Ui.tv(this, "空闲多久结束会话、多久后主动找你，都可以调。", 11.5f, Ui.MUTED2, false));

        final EditText idle = numberField("空闲分钟", s.optInt("idle_minutes", 30));
        final EditText min = numberField("最小间隔分钟", s.optInt("min_delay_minutes", 60));
        final EditText max = numberField("最大间隔分钟", s.optInt("max_delay_minutes", 180));
        final EditText streak = numberField("最多连续主动次数", s.optInt("max_streak", 3));
        final EditText quietStart = textField("免打扰开始，例如 23:00", s.optString("quiet_start", ""));
        final EditText quietEnd = textField("免打扰结束，例如 08:00", s.optString("quiet_end", ""));
        card.addView(Ui.tv(this, "空闲多少分钟结束这次会话", 11.5f, Ui.MUTED, false));
        card.addView(idle);
        card.addView(Ui.tv(this, "多久之后主动找你（最小值）", 11.5f, Ui.MUTED, false));
        card.addView(min);
        card.addView(Ui.tv(this, "多久之后主动找你（最大值）", 11.5f, Ui.MUTED, false));
        card.addView(max);
        card.addView(Ui.tv(this, "连续主动几次后停止", 11.5f, Ui.MUTED, false));
        card.addView(streak);
        card.addView(Ui.tv(this, "免打扰时间段（留空表示关闭；这段时间内不会主动发消息）", 11.5f, Ui.MUTED, false));
        card.addView(quietStart);
        card.addView(quietEnd);

        TextView save = Ui.button(this, "保存参数", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    JSONObject settings = new JSONObject();
                    settings.put("idle_minutes", Integer.parseInt(idle.getText().toString().trim()));
                    settings.put("min_delay_minutes", Integer.parseInt(min.getText().toString().trim()));
                    settings.put("max_delay_minutes", Integer.parseInt(max.getText().toString().trim()));
                    settings.put("max_streak", Integer.parseInt(streak.getText().toString().trim()));
                    String qs = quietStart.getText().toString().trim();
                    String qe = quietEnd.getText().toString().trim();
                    if (qs.isEmpty() != qe.isEmpty()) {
                        Ui.toast(AiSettingsActivity.this, "免打扰开始和结束需要同时填写，或同时留空");
                        return;
                    }
                    if (!qs.isEmpty()) {
                        String timePattern = "([01][0-9]|2[0-3]):[0-5][0-9]";
                        if (!qs.matches(timePattern) || !qe.matches(timePattern)) {
                            Ui.toast(AiSettingsActivity.this, "时间格式请用 HH:mm，例如 23:00");
                            return;
                        }
                    }
                    settings.put("quiet_start", qs);
                    settings.put("quiet_end", qe);
                    JSONObject body = new JSONObject();
                    body.put("settings", settings);
                    Api.updateAi(aiId, body, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(AiSettingsActivity.this, "参数已保存");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(AiSettingsActivity.this, message);
                        }
                    });
                } catch (Exception e) {
                    Ui.toast(AiSettingsActivity.this, "请填写数字");
                }
            }
        });
        card.addView(save);
        container.addView(card);
    }

    private EditText numberField(String hint, int value) {
        return field(hint, String.valueOf(value), InputType.TYPE_CLASS_NUMBER);
    }

    private EditText textField(String hint, String value) {
        return field(hint, value, InputType.TYPE_CLASS_TEXT);
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.topMargin = Ui.dp(this, 8);
        e.setLayoutParams(lp);
        return e;
    }

    private void renderWhitelist(final JSONObject ai) {
        LinearLayout card = section("白名单应用");
        card.addView(Ui.tv(this, "这些应用被打开时，AI 会知道，并可能主动说话。", 11.5f, Ui.MUTED2, false));
        JSONArray arr = ai.optJSONArray("whitelist");
        if (arr == null) arr = new JSONArray();
        Session.setWhitelist(aiId, arr.toString());
        if (arr.length() == 0) {
            card.addView(Ui.tv(this, "还没有白名单应用", 12.5f, Ui.MUTED, false));
        } else {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                LinearLayout row = Ui.row(this);
                row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                LinearLayout info = Ui.column(this);
                info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                info.addView(Ui.tv(this, w.optString("label", w.optString("package_name")), 13.5f, Ui.TEXT, true));
                info.addView(Ui.tv(this, w.optString("package_name"), 10.5f, Ui.MUTED2, false));
                row.addView(info);
                final int itemId = w.optInt("id");
                TextView del = Ui.button(this, "删除", false);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Api.deleteWhitelist(aiId, itemId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AiSettingsActivity.this, message);
                            }
                        });
                    }
                });
                row.addView(del);
                card.addView(row);
            }
        }
        TextView add = Ui.button(this, "＋ 添加白名单应用", true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addWhitelistDialog();
            }
        });
        card.addView(add);
        container.addView(card);
    }

    private void showInstalledApps(final EditText pkg, final EditText label) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        final List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String p = ri.activityInfo.packageName;
            if (p == null || p.equals(getPackageName())) continue;
            String name = ri.loadLabel(pm).toString();
            if (name == null || name.trim().isEmpty()) name = p;
            apps.add(new String[]{name, p});
        }
        Collections.sort(apps, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        String[] items = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            items[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已安装应用")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pkg.setText(apps.get(which)[1]);
                        label.setText(apps.get(which)[0]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addWhitelistDialog() {
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);
        final EditText pkg = new EditText(this);
        pkg.setHint("包名，例如 com.sankuai.meituan");
        pkg.setSingleLine(true);
        pkg.setBackgroundResource(R.drawable.bg_input);
        pkg.setTextColor(Ui.TEXT);
        pkg.setHintTextColor(Ui.MUTED2);
        pkg.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        pkg.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));

        final EditText label = new EditText(this);
        label.setHint("备注，例如 外卖");
        label.setSingleLine(true);
        label.setBackgroundResource(R.drawable.bg_input);
        label.setTextColor(Ui.TEXT);
        label.setHintTextColor(Ui.MUTED2);
        label.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        llp.topMargin = Ui.dp(this, 10);
        label.setLayoutParams(llp);

        TextView pick = Ui.button(this, "从已安装应用选择", false);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.bottomMargin = Ui.dp(this, 10);
        pick.setLayoutParams(pickLp);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInstalledApps(pkg, label);
            }
        });
        root.addView(pick);
        root.addView(pkg);
        root.addView(label);

        new AlertDialog.Builder(this)
                .setTitle("添加白名单应用")
                .setView(root)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String p = pkg.getText().toString().trim();
                        String l = label.getText().toString().trim();
                        if (p.isEmpty()) {
                            Ui.toast(AiSettingsActivity.this, "包名不能为空");
                            return;
                        }
                        Api.addWhitelist(aiId, p, l.isEmpty() ? p : l, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AiSettingsActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadProactive() {
        Api.proactive(aiId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                LinearLayout card = section("主动消息状态");
                String phase = data.optString("phase", "active");
                String phaseText = "active".equals(phase) ? "聊天进行中"
                        : "waiting".equals(phase) ? "等待主动时机" : "已暂停（等用户先开口）";
                card.addView(Ui.tv(AiSettingsActivity.this, "状态：" + phaseText, 13, Ui.TEXT, false));
                card.addView(Ui.tv(AiSettingsActivity.this, "连续主动：" + data.optInt("streak") + " 次", 12, Ui.MUTED, false));
                String next = data.optString("next_proactive_at", "");
                if (!next.isEmpty()) {
                    card.addView(Ui.tv(AiSettingsActivity.this, "下次主动：" + Ui.time(next), 12, Ui.MUTED, false));
                }
                TextView trigger = Ui.button(AiSettingsActivity.this, "立即让她主动说一句", true);
                trigger.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Api.triggerProactive(aiId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject d) {
                                JSONObject m = d.optJSONObject("message");
                                Ui.toast(AiSettingsActivity.this,
                                        m == null ? "已发送" : m.optString("content"));
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(AiSettingsActivity.this, message);
                            }
                        });
                    }
                });
                card.addView(trigger);
                container.addView(card);
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void loadMemories() {
        Api.memories(aiId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("memories");
                LinearLayout card = section("记忆");
                if (arr == null || arr.length() == 0) {
                    card.addView(Ui.tv(AiSettingsActivity.this, "还没有重要记忆，多聊聊她就会记住你", 12.5f, Ui.MUTED, false));
                } else {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m == null) continue;
                        LinearLayout row = Ui.row(AiSettingsActivity.this);
                        row.setPadding(0, Ui.dp(AiSettingsActivity.this, 8), 0, Ui.dp(AiSettingsActivity.this, 8));
                        LinearLayout info = Ui.column(AiSettingsActivity.this);
                        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        info.addView(Ui.tv(AiSettingsActivity.this, m.optString("content"), 13, Ui.TEXT, false));
                        info.addView(Ui.tv(AiSettingsActivity.this,
                                ("long".equals(m.optString("kind")) ? "长期" : "近期")
                                        + " · 重要度 " + m.optDouble("importance", 0.5),
                                10.5f, Ui.MUTED2, false));
                        row.addView(info);
                        final int mid = m.optInt("id");
                        TextView del = Ui.button(AiSettingsActivity.this, "删除", false);
                        del.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Api.deleteMemory(aiId, mid, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject d) {
                                        load();
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(AiSettingsActivity.this, message);
                                    }
                                });
                            }
                        });
                        row.addView(del);
                        card.addView(row);
                    }
                }
                container.addView(card);
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void renderDanger() {
        LinearLayout card = section("会话管理");
        TextView reset = Ui.button(this, "重置这次对话（保留长期记忆）", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(AiSettingsActivity.this)
                        .setTitle("重置对话")
                        .setMessage("会清空当前聊天记录，长期记忆保留。")
                        .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Api.resetAi(aiId, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        Ui.toast(AiSettingsActivity.this, "已重置");
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(AiSettingsActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        card.addView(reset);

        TextView delete = Ui.button(this, "删除这个 AI 好友", false);
        delete.setTextColor(Ui.RED);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(AiSettingsActivity.this)
                        .setTitle("删除 AI 好友")
                        .setMessage("聊天记录、记忆、白名单都会删除，且无法恢复。")
                        .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Api.deleteAi(aiId, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        Session.setServiceEnabled(false);
                                        Session.setActiveAi(0);
                                        if (convId > 0) LocalStore.clearConversation(AiSettingsActivity.this, convId);
                                        if (convId > 0) LocalStore.removeConversation(AiSettingsActivity.this, convId);
                                        UsageMonitorService.start(AiSettingsActivity.this);
                                        Ui.toast(AiSettingsActivity.this, "已删除");
                                        finish();
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(AiSettingsActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        card.addView(delete);
        container.addView(card);
    }

    private LinearLayout section(String title) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(lp);
        TextView t = Ui.tv(this, title, 15, Ui.TEXT, true);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        card.addView(t);
        return card;
    }
}
