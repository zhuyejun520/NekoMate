package com.nekocyte.companion;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

public class FriendSettingsActivity extends BaseActivity {
    public static final String ACTION = "action";
    public static final String ACTION_CLEARED = "cleared";
    public static final String ACTION_DELETED = "deleted";
    public static final String ACTION_BLOCKED = "blocked";

    private int convId;
    private int peerId;
    private String peerName = "好友";
    private String remark = "";
    private boolean blocked;
    private boolean updatingSwitches;
    private LinearLayout content;
    private Switch muteSwitch;
    private Switch pinSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        convId = getIntent().getIntExtra("conv_id", 0);
        String fallback = getIntent().getStringExtra("title");
        if (fallback == null) fallback = "好友";

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
        TextView title = Ui.tv(this, "聊天设置", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 30));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        content.addView(Ui.tv(this, "正在加载…", 13, Ui.MUTED2, false));
        load();
    }

    private void load() {
        Api.friendSettings(convId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                render(data);
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(FriendSettingsActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render(JSONObject data) {
        content.removeAllViews();
        JSONObject peer = data.optJSONObject("peer");
        peerId = data.optInt("peer_id", 0);
        peerName = peer == null ? "好友" : Ui.str(peer, "nickname", "好友");
        remark = data.isNull("remark") ? "" : data.optString("remark", "");
        blocked = data.optBoolean("blocked", false);
        boolean muted = data.optBoolean("muted", false);
        boolean pinned = data.optBoolean("pinned", false);

        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this);
        row.addView(Ui.avatar(this, peer == null ? "" : peer.optString("avatar"), 50));
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 12);
        info.setLayoutParams(lp);
        info.addView(Ui.tv(this, remark.isEmpty() ? peerName : remark, 17, Ui.TEXT, true));
        if (!remark.isEmpty()) info.addView(Ui.tv(this, "昵称：" + peerName, 11.5f, Ui.MUTED2, false));
        if (peer != null) info.addView(Ui.tv(this, "@" + peer.optString("username"), 11.5f, Ui.MUTED, false));
        row.addView(info);
        card.addView(row);
        content.addView(card);

        content.addView(section("资料"));
        LinearLayout profile = Ui.card(this);
        profile.addView(row("好友备注", remark.isEmpty() ? "未设置" : remark, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRemark();
            }
        }));
        profile.addView(row("查看动态", "只看 " + peerName + " 的动态", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(FriendSettingsActivity.this, UserMomentsActivity.class);
                intent.putExtra("user_id", peerId);
                intent.putExtra("name", peerName);
                startActivity(intent);
            }
        }));
        content.addView(profile);

        content.addView(section("聊天"));
        LinearLayout chat = Ui.card(this);
        updatingSwitches = true;
        muteSwitch = new Switch(this);
        muteSwitch.setText("消息免打扰");
        muteSwitch.setTextColor(Ui.TEXT);
        muteSwitch.setTextSize(14);
        muteSwitch.setChecked(muted);
        muteSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                Api.updateFriendSettings(convId, isChecked, null, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        Ui.toast(FriendSettingsActivity.this, "免打扰已更新");
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(FriendSettingsActivity.this, message);
                        load();
                    }
                });
            }
        });
        chat.addView(muteSwitch);
        pinSwitch = new Switch(this);
        pinSwitch.setText("设为置顶聊天");
        pinSwitch.setTextColor(Ui.TEXT);
        pinSwitch.setTextSize(14);
        pinSwitch.setChecked(pinned);
        pinSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                Api.updateFriendSettings(convId, null, isChecked, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        Ui.toast(FriendSettingsActivity.this, "置顶已更新");
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(FriendSettingsActivity.this, message);
                        load();
                    }
                });
            }
        });
        chat.addView(pinSwitch);
        updatingSwitches = false;
        chat.addView(row("删除聊天记录", "只删除你这边，对方仍保留", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        }));
        content.addView(chat);

        content.addView(section("好友关系"));
        LinearLayout relation = Ui.card(this);
        if (blocked) {
            relation.addView(row("解除拉黑", "恢复后仍可重新发消息", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.unblockFriend(peerId, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            Ui.toast(FriendSettingsActivity.this, "已解除拉黑");
                            load();
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(FriendSettingsActivity.this, message);
                        }
                    });
                }
            }));
        } else {
            relation.addView(row("拉黑好友", "拉黑后不能互相发送消息", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmBlock();
                }
            }));
            relation.addView(row("删除好友", "删除双方好友关系", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete();
                }
            }));
        }
        content.addView(relation);
    }

    private TextView section(String text) {
        TextView t = Ui.tv(this, text, 11.5f, Ui.MUTED, true);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        return t;
    }

    private View row(String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        row.addView(Ui.tv(this, title, 14, Ui.TEXT, false));
        if (subtitle != null && !subtitle.isEmpty()) {
            row.addView(Ui.tv(this, subtitle, 11.5f, Ui.MUTED2, false));
        }
        row.setOnClickListener(click);
        return row;
    }

    private void editRemark() {
        final EditText input = new EditText(this);
        input.setText(remark);
        input.setSingleLine(true);
        input.setHint("给好友设置备注");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED2);
        int pad = Ui.dp(this, 16);
        input.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        new AlertDialog.Builder(this)
                .setTitle("好友备注")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.setRemark(peerId, input.getText().toString().trim(), new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                Ui.toast(FriendSettingsActivity.this, "备注已保存");
                                load();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(FriendSettingsActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("删除聊天记录")
                .setMessage("只删除你本地的聊天记录，对方仍会保留。确定继续？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.clearConversation(convId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                LocalStore.clearConversation(FriendSettingsActivity.this, convId);
                                setResult(RESULT_OK, new Intent().putExtra(ACTION, ACTION_CLEARED));
                                finish();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(FriendSettingsActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除好友")
                .setMessage("删除后双方好友关系解除，确定删除「" + peerName + "」？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.deleteFriend(peerId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                setResult(RESULT_OK, new Intent().putExtra(ACTION, ACTION_DELETED));
                                finish();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(FriendSettingsActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBlock() {
        new AlertDialog.Builder(this)
                .setTitle("拉黑好友")
                .setMessage("拉黑后双方不能再发送消息，好友关系会解除。确定拉黑「" + peerName + "」？")
                .setPositiveButton("拉黑", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.blockFriend(peerId, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                setResult(RESULT_OK, new Intent().putExtra(ACTION, ACTION_BLOCKED));
                                finish();
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(FriendSettingsActivity.this, message);
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
