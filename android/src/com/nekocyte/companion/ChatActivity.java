package com.nekocyte.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatActivity extends BaseActivity {
    public static int currentConvId = 0;

    private int convId;
    private int aiId;
    private String type = "direct";
    private String title = "聊天";

    private final List<Msg> messages = new ArrayList<>();
    private final Set<Long> ids = new HashSet<>();
    private MsgAdapter adapter;
    private ListView listView;
    private EditText input;
    private TextView typing;
    private TextView titleView;
    private String chatTitle = "";
    private TextView settings;
    private LineIconView pickSticker;
    private LineIconView toolbarToggle;
    private LineIconView voiceToggle;
    private TextView holdTalk;
    private TextView voiceHint;
    private Runnable hideVoiceHint;
    private VoiceRecorder voiceRecorder = new VoiceRecorder();
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private Runnable voiceMax;
    private boolean voiceMode = false;
    private View sendButton;
    private LinearLayout toolbarPanel;
    private LinearLayout emojiPanel;
    private EmojiPanel emojiPicker;
    private String peerAvatar = "";
    private final Map<Integer, String> memberAvatars = new HashMap<>();
    private final Map<Integer, String> aiAvatars = new HashMap<>();
    private boolean toolbarOpen = false;
    private boolean emojiOpen = false;
    private Uri cameraUri;
    private boolean pendingCamera = false;
    private long cameraLaunchAt = 0;
    private final Handler poller = new Handler(Looper.getMainLooper());
    private Runnable pollTask;
    private boolean sending = false;
    private final List<Msg> splitQueue = new ArrayList<>();
    private final Handler splitHandler = new Handler(Looper.getMainLooper());
    private long lastSplitReveal = 0;
    private boolean loadingHistory = true;
    private boolean historyReady = false;
    private boolean aiComposing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        convId = getIntent().getIntExtra("conv_id", 0);
        aiId = getIntent().getIntExtra("ai_id", 0);
        type = getIntent().getStringExtra("type");
        if (type == null || type.isEmpty()) type = "direct";
        title = getIntent().getStringExtra("title");
        if (title == null || title.isEmpty()) title = "聊天";

        listView = findViewById(R.id.messages);
        input = findViewById(R.id.input);
        typing = findViewById(R.id.typing);
        titleView = findViewById(R.id.title);
        settings = findViewById(R.id.settings);
        pickSticker = findViewById(R.id.pick_sticker);
        pickSticker.setType("emoji");
        sendButton = findViewById(R.id.send);
        toolbarToggle = findViewById(R.id.toolbar_toggle);
        toolbarToggle.setType("plus");
        voiceToggle = findViewById(R.id.voice_toggle);
        voiceToggle.setType("mic");
        holdTalk = findViewById(R.id.hold_talk);
        voiceHint = findViewById(R.id.voice_hint);
        toolbarPanel = findViewById(R.id.toolbar_panel);
        buildToolbar();
        emojiPanel = findViewById(R.id.emoji_panel);
        emojiPicker = new EmojiPanel(this, new EmojiPanel.Listener() {
            @Override
            public void onEmoji(String emoji) {
                insertEmoji(emoji);
            }

            @Override
            public void onSticker(String url, String words) {
                sendSticker(url, words);
            }
        });
        emojiPanel.addView(emojiPicker, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        peerAvatar = "";
        String savedCamera = Session.pendingCameraUri();
        if (!savedCamera.isEmpty()) {
            try {
                cameraUri = Uri.parse(savedCamera);
                pendingCamera = true;
            } catch (Exception ignored) {
                Session.setPendingCameraUri("");
            }
        }
        if (convId > 0) loadPeerAvatar();
        applyChatBackground();
        titleView.setText(title);
        chatTitle = title;
        settings.setVisibility(View.VISIBLE);
        toolbarToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setToolbarOpen(!toolbarOpen);
            }
        });

        adapter = new MsgAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("group".equals(type)) {
                    groupInfoDialog();
                    return;
                }
                if ("direct".equals(type)) {
                    Intent direct = new Intent(ChatActivity.this, FriendSettingsActivity.class);
                    direct.putExtra("conv_id", convId);
                    direct.putExtra("title", title);
                    startActivityForResult(direct, 33);
                    return;
                }
                Intent i = new Intent(ChatActivity.this, AiSettingsActivity.class);
                i.putExtra("ai_id", aiId);
                startActivity(i);
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateActionButton();
                if ("ai".equals(type)) {
                    setComposing(s.length() > 0);
                }
            }
        });
        updateActionButton();
        pickSticker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleEmojiPanel();
            }
        });
        input.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (emojiOpen) setEmojiOpen(false, true);
            }
        });
        voiceToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (voiceMode) {
                    setVoiceMode(false);
                } else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 42);
                } else {
                    setVoiceMode(true);
                }
            }
        });
        holdTalk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    startVoiceRecording();
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    finishVoiceRecording(false);
                    return true;
                }
                if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    finishVoiceRecording(true);
                    return true;
                }
                return false;
            }
        });

        if (convId == 0 && aiId > 0) {
            loadAiConversation();
        } else {
            loadMessages(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentConvId = convId;
        if (convId > 0) {
            if (historyReady) loadMessages(false);
            loadPeerAvatar();
            schedulePoll();
        }
        recoverCameraResult();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voiceMax != null) {
            voiceHandler.removeCallbacks(voiceMax);
            voiceMax = null;
        }
        voiceRecorder.cancel();
        if (voiceMode) holdTalk.setText("按住 说话");
        if (currentConvId == convId) currentConvId = 0;
        if ("ai".equals(type)) setComposing(false);
        poller.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentConvId == convId) currentConvId = 0;
        poller.removeCallbacksAndMessages(null);
        splitHandler.removeCallbacksAndMessages(null);
    }

    private void groupInfoDialog() {
        Api.conversationDetail(convId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                final JSONObject conv = data.optJSONObject("conversation");
                if (conv == null) return;
                JSONArray members = conv.optJSONArray("members");
                JSONArray ais = conv.optJSONArray("ai_members");
                int total = (members == null ? 0 : members.length()) + (ais == null ? 0 : ais.length());
                final AlertDialog[] holder = new AlertDialog[1];

                LinearLayout root = Ui.column(ChatActivity.this);
                int pad = Ui.dp(ChatActivity.this, 16);
                root.setPadding(pad, pad, pad, pad);
                root.addView(Ui.tv(ChatActivity.this, "群成员 · " + total, 13, Ui.MUTED, false));

                if (members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject m = members.optJSONObject(i);
                        if (m == null) continue;
                        LinearLayout row = Ui.row(ChatActivity.this);
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rlp.topMargin = Ui.dp(ChatActivity.this, 8);
                        row.setLayoutParams(rlp);
                        row.addView(Ui.avatar(ChatActivity.this, Ui.str(m, "avatar", "🙂"), 36));
                        LinearLayout col = Ui.column(ChatActivity.this);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                        clp.leftMargin = Ui.dp(ChatActivity.this, 10);
                        col.setLayoutParams(clp);
                        col.addView(Ui.tv(ChatActivity.this, Ui.str(m, "nickname", "好友"), 13.5f, Ui.TEXT, true));
                        String role = "owner".equals(m.optString("role")) ? "群主" : "成员";
                        col.addView(Ui.tv(ChatActivity.this, role + " · @" + Ui.str(m, "username", ""), 10.5f, Ui.MUTED2, false));
                        row.addView(col);
                        root.addView(row);
                    }
                }

                if (ais != null) {
                    for (int i = 0; i < ais.length(); i++) {
                        final JSONObject ai = ais.optJSONObject(i);
                        if (ai == null) continue;
                        LinearLayout row = Ui.row(ChatActivity.this);
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rlp.topMargin = Ui.dp(ChatActivity.this, 8);
                        row.setLayoutParams(rlp);
                        row.addView(Ui.avatar(ChatActivity.this, Ui.str(ai, "avatar", "🤖"), 36));
                        LinearLayout col = Ui.column(ChatActivity.this);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                        clp.leftMargin = Ui.dp(ChatActivity.this, 10);
                        col.setLayoutParams(clp);
                        col.addView(Ui.tv(ChatActivity.this, Ui.str(ai, "name", "AI 好友"), 13.5f, Ui.TEXT, true));
                        col.addView(Ui.tv(ChatActivity.this, "AI 好友 · " + Ui.str(ai, "persona_name", ""), 10.5f, Ui.MUTED2, false));
                        row.addView(col);
                        TextView remove = Ui.button(ChatActivity.this, "移除", false);
                        remove.setTextColor(Ui.RED);
                        final int aiId = ai.optInt("id");
                        remove.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                new AlertDialog.Builder(ChatActivity.this)
                                        .setTitle("移除 AI 好友")
                                        .setMessage("确定把「" + Ui.str(ai, "name", "AI 好友") + "」移出群聊？")
                                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                Api.removeGroupAi(convId, aiId, new Api.Cb() {
                                                    @Override
                                                    public void ok(JSONObject data) {
                                                        Ui.toast(ChatActivity.this, "已移除");
                                                        if (holder[0] != null) holder[0].dismiss();
                                                        groupInfoDialog();
                                                    }

                                                    @Override
                                                    public void fail(String message) {
                                                        Ui.toast(ChatActivity.this, message);
                                                    }
                                                });
                                            }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                            }
                        });
                        row.addView(remove);
                        root.addView(row);
                    }
                }

                TextView invite = Ui.button(ChatActivity.this, "＋ 邀请好友进群", true);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.topMargin = Ui.dp(ChatActivity.this, 14);
                root.addView(invite, ilp);
                TextView inviteAi = Ui.button(ChatActivity.this, "＋ 邀请 AI 好友进群", false);
                LinearLayout.LayoutParams ailp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ailp.topMargin = Ui.dp(ChatActivity.this, 8);
                root.addView(inviteAi, ailp);

                ScrollView scroll = new ScrollView(ChatActivity.this);
                scroll.addView(root);
                AlertDialog dialog = new AlertDialog.Builder(ChatActivity.this)
                        .setTitle(Ui.str(conv, "title", "群聊"))
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .create();
                holder[0] = dialog;
                invite.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        inviteFriendsDialog(conv, holder[0]);
                    }
                });
                inviteAi.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        inviteAiDialog(conv, holder[0]);
                    }
                });
                dialog.show();
            }

            @Override
            public void fail(String message) {
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void inviteFriendsDialog(final JSONObject conv, final AlertDialog info) {
        Api.friends(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("friends");
                if (arr == null) arr = new JSONArray();
                Set<Integer> existing = new HashSet<>();
                JSONArray mids = conv.optJSONArray("member_ids");
                if (mids != null) for (int i = 0; i < mids.length(); i++) existing.add(mids.optInt(i));
                final List<Integer> ids = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null) continue;
                    int id = f.optInt("id");
                    if (existing.contains(id)) continue;
                    ids.add(id);
                    String name = Ui.str(f, "nickname", "好友");
                    String user = Ui.str(f, "username", "");
                    labels.add(name + (user.isEmpty() ? "" : "  @" + user));
                }
                if (ids.isEmpty()) {
                    Ui.toast(ChatActivity.this, "没有可邀请的好友");
                    return;
                }
                final boolean[] checked = new boolean[ids.size()];
                new AlertDialog.Builder(ChatActivity.this)
                        .setTitle("邀请好友进群")
                        .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                                new DialogInterface.OnMultiChoiceClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                        checked[which] = isChecked;
                                    }
                                })
                        .setPositiveButton("邀请", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                JSONArray selected = new JSONArray();
                                for (int i = 0; i < ids.size(); i++) if (checked[i]) selected.put(ids.get(i));
                                if (selected.length() == 0) {
                                    Ui.toast(ChatActivity.this, "至少选择一位好友");
                                    return;
                                }
                                JSONObject body = new JSONObject();
                                try {
                                    body.put("user_ids", selected);
                                } catch (Exception ignored) {
                                }
                                Api.addGroupMembers(convId, body, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        Ui.toast(ChatActivity.this, "已邀请");
                                        if (info != null) info.dismiss();
                                        loadMessages(false);
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(ChatActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void fail(String message) {
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void inviteAiDialog(final JSONObject conv, final AlertDialog info) {
        Api.aiFriends(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("ai_friends");
                if (arr == null) arr = new JSONArray();
                Set<Integer> existing = new HashSet<>();
                JSONArray aids = conv.optJSONArray("ai_member_ids");
                if (aids != null) for (int i = 0; i < aids.length(); i++) existing.add(aids.optInt(i));
                final List<Integer> ids = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null) continue;
                    int id = f.optInt("id");
                    if (existing.contains(id)) continue;
                    ids.add(id);
                    labels.add(Ui.str(f, "name", "AI 好友"));
                }
                if (ids.isEmpty()) {
                    Ui.toast(ChatActivity.this, "没有可邀请的 AI 好友");
                    return;
                }
                final boolean[] checked = new boolean[ids.size()];
                new AlertDialog.Builder(ChatActivity.this)
                        .setTitle("邀请 AI 好友进群")
                        .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                                new DialogInterface.OnMultiChoiceClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                        checked[which] = isChecked;
                                    }
                                })
                        .setPositiveButton("邀请", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                JSONArray selected = new JSONArray();
                                for (int i = 0; i < ids.size(); i++) if (checked[i]) selected.put(ids.get(i));
                                if (selected.length() == 0) {
                                    Ui.toast(ChatActivity.this, "至少选择一位 AI 好友");
                                    return;
                                }
                                JSONObject body = new JSONObject();
                                try {
                                    body.put("ai_friend_ids", selected);
                                } catch (Exception ignored) {
                                }
                                Api.addGroupMembers(convId, body, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        Ui.toast(ChatActivity.this, "AI 好友已进群");
                                        if (info != null) info.dismiss();
                                        loadMessages(false);
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(ChatActivity.this, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void fail(String message) {
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void applyChatBackground() {
        String path = Session.chatBg();
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (!file.exists()) return;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) return;
            ImageView image = findViewById(R.id.chat_bg);
            View scrim = findViewById(R.id.chat_scrim);
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            scrim.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void loadAiConversation() {
        showTyping(true);
        Api.aiConversation(aiId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                showTyping(false);
                convId = data.optInt("conversation_id");
                titleView.setText(title);
        chatTitle = title;
                merge(data.optJSONArray("messages"));
                Api.read(convId);
                schedulePoll();
            }

            @Override
            public void fail(String message) {
                showTyping(false);
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void loadMessages(final boolean initial) {
        if (convId <= 0) return;
        loadingHistory = initial || !historyReady;
        final boolean[] loadedCache = {false};
        if (messages.isEmpty()) {
            JSONArray cached = LocalStore.loadMessages(this, convId, 100);
            if ("ai".equals(type) && cached.length() > 0) {
                boolean matches = false;
                boolean hasOtherAi = false;
                for (int i = 0; i < cached.length(); i++) {
                    JSONObject m = cached.optJSONObject(i);
                    if (m == null || !"ai".equals(m.optString("sender_type"))) continue;
                    JSONObject meta = m.optJSONObject("meta");
                    int cachedAiId = meta == null ? 0 : meta.optInt("ai_friend_id", 0);
                    if ((aiId > 0 && cachedAiId == aiId)
                            || title.equals(m.optString("sender_name", ""))) {
                        matches = true;
                    } else if (cachedAiId > 0 && aiId > 0 && cachedAiId != aiId) {
                        hasOtherAi = true;
                    }
                }
                if (!matches || hasOtherAi) {
                    DebugLog.e("cache", "drop AI cache of another friend conv=" + convId + " title=" + title);
                    LocalStore.clearConversation(this, convId);
                    cached = new JSONArray();
                }
            }
            if (cached.length() > 0) {
                merge(cached);
                loadedCache[0] = true;
                DebugLog.i("cache", "loaded " + cached.length() + " messages conv=" + convId);
            }
        }
        Api.messages(convId, 50, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("messages");
                if ((arr == null || arr.length() == 0) && loadedCache[0]) {
                    DebugLog.i("cache", "server empty, clear stale cache conv=" + convId);
                    messages.clear();
                    ids.clear();
                    LocalStore.clearConversation(ChatActivity.this, convId);
                    adapter.notifyDataSetChanged();
                } else {
                    merge(arr);
                    LocalStore.saveMessages(ChatActivity.this, convId, arr);
                }
                if (initial) {
                    historyReady = true;
                    loadingHistory = false;
                    Api.read(convId);
                }
            }

            @Override
            public void fail(String message) {
                if (initial) {
                    historyReady = true;
                    loadingHistory = false;
                }
                DebugLog.e("chat", "load messages failed conv=" + convId + " " + message);
                if (initial && messages.isEmpty()) Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void schedulePoll() {
        if (pollTask != null) poller.removeCallbacks(pollTask);
        pollTask = new Runnable() {
            @Override
            public void run() {
                if (convId > 0) loadMessages(false);
                poller.postDelayed(this, 3000);
            }
        };
        poller.postDelayed(pollTask, 3000);
    }

    private void prefetchMessageImages(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("content_type", "");
            if (!"image".equals(type) && !"sticker".equals(type) && !"audio".equals(type)) continue;
            JSONObject meta = o.optJSONObject("meta");
            String url = meta == null ? "" : meta.optString("url", "");
            if (!url.isEmpty()) {
                if ("audio".equals(type)) AudioCache.prefetch(url);
                else ImageCache.prefetch(url);
            }
        }
    }

    private void merge(JSONArray arr) {
        if (arr == null) return;
        LocalStore.saveMessages(this, convId, arr);
        prefetchMessageImages(arr);
        boolean added = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id");
            if (id == 0) continue;
            if (ids.contains(id)) {
                String incomingType = o.optString("content_type", "");
                if ("recalled".equals(incomingType)) {
                    for (Msg existing : messages) {
                        if (existing.id == id) {
                            existing.content = o.optString("content", "[消息已撤回]");
                            existing.contentType = "recalled";
                            break;
                        }
                    }
                    adapter.notifyDataSetChanged();
                }
                continue;
            }
            ids.add(id);
            Msg parsed = new Msg(o);
            boolean splitPart = "ai".equals(parsed.senderType)
                    && parsed.metaJson != null
                    && parsed.metaJson.optBoolean("split", false)
                    && parsed.metaJson.optInt("split_index", 0) > 0;
            if (splitPart && !loadingHistory) {
                splitQueue.add(parsed);
                continue;
            }
            messages.add(parsed);
            if ("ai".equals(parsed.senderType)) {
                boolean split = parsed.metaJson != null && parsed.metaJson.optBoolean("split", false);
                int splitIndex = split ? parsed.metaJson.optInt("split_index", 0) : 0;
                int splitTotal = split ? parsed.metaJson.optInt("split_total", 1) : 1;
                if (!split || splitIndex >= splitTotal - 1) showTyping(false);
                if (split) {
                    lastSplitReveal = System.currentTimeMillis();
                }
            }
            added = true;
        }
        if (!splitQueue.isEmpty()) revealNextSplit();
        if (!added) return;
        Collections.sort(messages, new Comparator<Msg>() {
            @Override
            public int compare(Msg a, Msg b) {
                return Long.compare(a.id, b.id);
            }
        });
        adapter.notifyDataSetChanged();
        if (!messages.isEmpty()) {
            scrollToBottom();
            listView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!messages.isEmpty()) listView.setSelection(messages.size() - 1);
                }
            }, 260);
            listView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!messages.isEmpty()) listView.setSelection(messages.size() - 1);
                }
            }, 650);
        }
        Api.read(convId);
    }

    private void revealNextSplit() {
        if (splitQueue.isEmpty()) return;
        long wait = 500 - (System.currentTimeMillis() - lastSplitReveal);
        if (wait > 0) {
            splitHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    revealNextSplit();
                }
            }, wait);
            return;
        }
        Msg next = splitQueue.remove(0);
        messages.add(next);
        Collections.sort(messages, new Comparator<Msg>() {
            @Override
            public int compare(Msg a, Msg b) {
                return Long.compare(a.id, b.id);
            }
        });
        lastSplitReveal = System.currentTimeMillis();
        if (next.metaJson != null) {
            int splitIndex = next.metaJson.optInt("split_index", 0);
            int splitTotal = next.metaJson.optInt("split_total", 1);
            if (splitIndex >= splitTotal - 1) showTyping(false);
        }
        adapter.notifyDataSetChanged();
        scrollToBottom();
        if (!splitQueue.isEmpty()) revealNextSplit();
    }

    private void scrollToBottom() {
        if (listView == null || messages.isEmpty()) return;
        final int position = messages.size() - 1;
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.setSelection(position);
                listView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!messages.isEmpty()) listView.setSelection(messages.size() - 1);
                    }
                }, 60);
            }
        });
    }

    private void buildToolbar() {
        if (toolbarPanel.getChildCount() > 0) return;
        // 固定 2 行 × 4 列；删除的功能用透明占位，保持布局不变。
        addToolbarRow(new String[][]{
                {"image", "照片"}, {"camera", "拍摄"}, {"transfer", "转账"}, {"redpacket", "红包"}
        });
        addToolbarRow(new String[][]{
                {"file", "文件"}, {null, null}, {null, null}, {null, null}
        });
    }

    private void addToolbarRow(String[][] items) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        for (String[] item : items) {
            if (item == null || item[0] == null) {
                View spacer = new View(this);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                        Ui.dp(this, 58), 1);
                spacer.setLayoutParams(slp);
                spacer.setBackgroundColor(0x00000000);
                row.addView(spacer);
                continue;
            }
            final String type = item[0];
            LinearLayout cell = Ui.column(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cell.setLayoutParams(lp);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            LineIconView icon = new LineIconView(this);
            icon.setType(type);
            icon.setIconColor(Ui.TEXT);
            cell.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
            TextView label = Ui.tv(this, item[1], 10.5f, Ui.MUTED2, false);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 4), 0, 0);
            cell.addView(label);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onToolbarItem(type);
                }
            });
            row.addView(cell);
        }
        toolbarPanel.addView(row);
    }

    private void onToolbarItem(String type) {
        if ("camera".equals(type)) {
            cameraUri = null;
            pendingCamera = false;
            Session.setPendingCameraUri("");
            startActivityForResult(new Intent(this, CameraCaptureActivity.class), 52);
            setToolbarOpen(false);
        } else if ("file".equals(type)) {
            if (!Session.filePickerEnabled()) {
                Ui.toast(this, "文件管理导入已在设置中关闭");
                return;
            }
            try {
                startActivityForResult(MediaUtil.pickFromDocuments(this, false), 31);
                setToolbarOpen(false);
            } catch (Exception e) {
                Ui.toast(this, "没有可用的文件选择器");
            }
        } else if ("image".equals(type)) {
            try {
                startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), 31);
                setToolbarOpen(false);
            } catch (Exception e) {
                Ui.toast(this, "没有可用的图片选择器");
            }
        } else if ("transfer".equals(type)) {
            moneyDialog(false);
        } else if ("redpacket".equals(type)) {
            moneyDialog(true);
        }
    }

    private void moneyDialog(final boolean redPacket) {
        if (!redPacket && !"direct".equals(type) && !"ai".equals(type)) {
            Ui.toast(this, "转账只能在私聊或 AI 会话中使用");
            return;
        }
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);
        final EditText amount = new EditText(this);
        amount.setHint("金币数量");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(amount);
        final EditText note = new EditText(this);
        note.setHint(redPacket ? "红包留言（可选）" : "转账备注（可选）");
        note.setSingleLine(true);
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle(redPacket ? "发金币红包" : "转账金币")
                .setView(box)
                .setPositiveButton(redPacket ? "塞进红包" : "转账", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int value;
                        try {
                            value = Integer.parseInt(amount.getText().toString().trim());
                        } catch (Exception e) {
                            Ui.toast(ChatActivity.this, "请输入金币数量");
                            return;
                        }
                        if (value <= 0) {
                            Ui.toast(ChatActivity.this, "金币数量必须大于 0");
                            return;
                        }
                        Api.Cb cb = new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                JSONObject m = data.optJSONObject("message");
                                if (m != null) {
                                    JSONArray one = new JSONArray();
                                    one.put(m);
                                    merge(one);
                                }
                                setToolbarOpen(false);
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(ChatActivity.this, message);
                            }
                        };
                        if (redPacket) {
                            Api.sendRedPacket(convId, value, note.getText().toString().trim(), cb);
                        } else {
                            Api.transferCoins(convId, value, note.getText().toString().trim(), cb);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncComposingFromInput() {
        if ("ai".equals(type)) {
            setComposing(input != null && input.getText().length() > 0);
        }
    }

    private void setComposing(boolean active) {
        if (!"ai".equals(type) || convId <= 0 || aiComposing == active) return;
        aiComposing = active;
        Api.setComposing(convId, active, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void showTyping(boolean show) {        if ("ai".equals(type)) {
            typing.setVisibility(View.GONE);
            if (titleView != null) {
                if (chatTitle.isEmpty()) chatTitle = title;
                titleView.setText(show ? "对方正在输入..." : chatTitle);
            }
        } else {
            typing.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void updateActionButton() {
        boolean hasText = !voiceMode && input.getText().toString().trim().length() > 0;
        sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
        toolbarToggle.setVisibility(hasText ? View.GONE : View.VISIBLE);
        if (hasText && toolbarOpen) setToolbarOpen(false);
    }

    private void setVoiceMode(boolean on) {
        voiceMode = on;
        if (on) {
            setEmojiOpen(false, false);
            setToolbarOpen(false);
            Ui.hideKeyboard(this);
            input.clearFocus();
            input.setVisibility(View.GONE);
            holdTalk.setVisibility(View.VISIBLE);
            voiceToggle.setType("keyboard");
            voiceToggle.setIconColor(Ui.ACCENT);
        } else {
            input.setVisibility(View.VISIBLE);
            holdTalk.setVisibility(View.GONE);
            voiceToggle.setType("mic");
            voiceToggle.setIconColor(Ui.MUTED);
            holdTalk.setText("按住 说话");
            input.requestFocus();
        }
        updateActionButton();
    }

    private void showVoiceHint(String text, long autoHideMs) {
        if (voiceHint == null) return;
        if (hideVoiceHint != null) {
            voiceHandler.removeCallbacks(hideVoiceHint);
            hideVoiceHint = null;
        }
        voiceHint.setText(text);
        voiceHint.setVisibility(View.VISIBLE);
        if (autoHideMs > 0) {
            hideVoiceHint = new Runnable() {
                @Override
                public void run() {
                    if (voiceHint != null) voiceHint.setVisibility(View.GONE);
                    hideVoiceHint = null;
                }
            };
            voiceHandler.postDelayed(hideVoiceHint, autoHideMs);
        }
    }

    private void hideVoiceHint() {
        if (hideVoiceHint != null) {
            voiceHandler.removeCallbacks(hideVoiceHint);
            hideVoiceHint = null;
        }
        if (voiceHint != null) voiceHint.setVisibility(View.GONE);
    }

    private void copyTranscript(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("语音文本", text));
        Ui.toast(this, "已复制转写文本");
    }

    private void startVoiceRecording() {
        if (!voiceMode || voiceRecorder.isRecording()) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 42);
            return;
        }
        if (voiceRecorder.start(this)) {
            holdTalk.setText("松开发送");
            showVoiceHint("正在录音…", 0);
            voiceMax = new Runnable() {
                @Override
                public void run() {
                    finishVoiceRecording(false);
                }
            };
            voiceHandler.postDelayed(voiceMax, 30000);
        } else {
            Ui.toast(this, "录音启动失败");
        }
    }

    private void finishVoiceRecording(boolean cancel) {
        if (voiceMax != null) {
            voiceHandler.removeCallbacks(voiceMax);
            voiceMax = null;
        }
        VoiceRecorder.Result result = null;
        if (!cancel) result = voiceRecorder.stop();
        else voiceRecorder.cancel();
        holdTalk.setText("按住 说话");
        if (cancel) {
            hideVoiceHint();
            return;
        }
        if (result == null) {
            hideVoiceHint();
            Ui.toast(this, "录音太短，请至少按住 1 秒");
            return;
        }
        showVoiceHint("正在发送…", 0);
        uploadVoice(result);
    }

    private void uploadVoice(final VoiceRecorder.Result result) {
        if (sending || convId <= 0) {
            result.file.delete();
            return;
        }
        sending = true;
        if ("ai".equals(type)) showTyping(true);
        Ui.toast(this, "正在发送语音…");
        Api.uploadFile(result.file, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                String url = data.optString("url", "");
                if (url.isEmpty()) {
                    sending = false;
                    showTyping(false);
                    result.file.delete();
                    hideVoiceHint();
                    Ui.toast(ChatActivity.this, "语音上传失败");
                    return;
                }
                AudioCache.store(url, result.file);
                Api.sendAudioMessage(convId, url, result.seconds, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        sending = false;
                        showTyping(data.optBoolean("pending"));
                        if ("ai".equals(type)) syncComposingFromInput();
                        result.file.delete();
                        JSONObject m = data.optJSONObject("message");
                        if (m != null) {
                            JSONArray one = new JSONArray();
                            one.put(m);
                            merge(one);
                        }
                        JSONObject reply = data.optJSONObject("reply");
                        if (reply != null) {
                            JSONArray one = new JSONArray();
                            one.put(reply);
                            merge(one);
                        }
                        showVoiceHint("已发送", 1200);
                    }

                    @Override
                    public void fail(String message) {
                        sending = false;
                        showTyping(false);
                        result.file.delete();
                        hideVoiceHint();
                        Ui.toast(ChatActivity.this, message);
                    }
                });
            }

            @Override
            public void fail(String message) {
                sending = false;
                showTyping(false);
                result.file.delete();
                hideVoiceHint();
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void setToolbarOpen(boolean open) {
        if (open && emojiOpen) setEmojiOpen(false, false);
        toolbarOpen = open;
        toolbarPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        toolbarToggle.setIconColor(open ? Ui.ACCENT : Ui.MUTED);
        if (open) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        }
    }

    private void toggleEmojiPanel() {
        setEmojiOpen(!emojiOpen, !emojiOpen);
    }

    private void setEmojiOpen(boolean open, boolean showKeyboard) {
        emojiOpen = open;
        emojiPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        pickSticker.setType(open ? "keyboard" : "emoji");
        pickSticker.setIconColor(open ? Ui.ACCENT : Ui.MUTED);
        if (open) {
            if (toolbarOpen) setToolbarOpen(false);
            emojiPicker.openDefault();
            Ui.hideKeyboard(this);
        } else if (showKeyboard) {
            input.requestFocus();
            input.postDelayed(new Runnable() {
                @Override
                public void run() {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }, 120);
        }
    }

    private void insertEmoji(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        int start = Math.max(0, input.getSelectionStart());
        input.getText().insert(start, emoji);
        input.setSelection(start + emoji.length());
    }

    private void loadPeerAvatar() {
        Api.conversationDetail(convId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONObject conv = data.optJSONObject("conversation");
                if (conv == null) return;
                JSONObject target = conv.optJSONObject("target");
                if (target != null) {
                    peerAvatar = target.optString("avatar", "");
                } else {
                    peerAvatar = conv.optString("avatar", "");
                }
                if ("group".equals(type) && peerAvatar.isEmpty()) peerAvatar = "👥";
                memberAvatars.clear();
                JSONArray users = conv.optJSONArray("user_members");
                if (users != null) {
                    for (int i = 0; i < users.length(); i++) {
                        JSONObject u = users.optJSONObject(i);
                        if (u != null) memberAvatars.put(u.optInt("id"), u.optString("avatar", ""));
                    }
                }
                aiAvatars.clear();
                JSONArray ais = conv.optJSONArray("ai_members");
                if (ais != null) {
                    for (int i = 0; i < ais.length(); i++) {
                        JSONObject a = ais.optJSONObject(i);
                        if (a != null) aiAvatars.put(a.optInt("id"), a.optString("avatar", ""));
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void fail(String message) {
            }
        });
    }

    private void openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCamera = true;
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 41);
            return;
        }
        launchSystemCamera();
    }

    private void launchSystemCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File photo = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            cameraUri = CameraFileProvider.uriFor(this, photo);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Ui.toast(this, "没有可用的相机应用");
                cameraUri = null;
                return;
            }
            pendingCamera = true;
            cameraLaunchAt = System.currentTimeMillis();
            Session.setPendingCameraUri(cameraUri.toString());
            DebugLog.i("camera", "launch uri=" + cameraUri);
            startActivityForResult(intent, 32);
            setToolbarOpen(false);
        } catch (Exception e) {
            Ui.toast(this, "打开相机失败：" + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) setVoiceMode(true);
            else Ui.toast(this, "需要麦克风权限才能发送语音");
            return;
        }
        if (requestCode != 41) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        DebugLog.i("camera", "permission result granted=" + granted);
        if (granted && checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchSystemCamera();
        } else {
            pendingCamera = false;
            Session.setPendingCameraUri("");
            Ui.toast(this, "需要相机权限才能拍照");
        }
    }

    private void pickImageSource() {
        if (!Session.filePickerEnabled()) {
            try {
                startActivityForResult(MediaUtil.pickFromGallery(this, false, 1), 31);
            } catch (Exception e) {
                Ui.toast(this, "没有可用的图片选择器");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发送图片")
                .setItems(new String[]{"从相册选择", "从文件管理选择"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = which == 0
                                    ? MediaUtil.pickFromGallery(ChatActivity.this, false, 1)
                                    : MediaUtil.pickFromDocuments(ChatActivity.this, false);
                            startActivityForResult(intent, 31);
                        } catch (Exception e) {
                            Ui.toast(ChatActivity.this, "没有可用的图片选择器");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void recoverCameraResult() {
        // 部分系统相机返回时不回调 onActivityResult；回到页面时兜底发送输出文件。
        if (pendingCamera && cameraUri != null && cameraHasData(cameraUri)) {
            pendingCamera = false;
            Session.setPendingCameraUri("");
            Uri ready = cameraUri;
            cameraUri = null;
            DebugLog.i("camera", "onResume fallback send " + ready);
            sendImage(ready);
        }
    }

    private boolean cameraHasData(Uri uri) {
        try {
            InputStream in = MediaUtil.open(this, uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, options);
            in.close();
            DebugLog.i("camera", "output size=" + options.outWidth + "x" + options.outHeight);
            return options.outWidth > 0 && options.outHeight > 0;
        } catch (Exception e) {
            DebugLog.i("camera", "read output failed: " + e.getMessage());
            return false;
        }
    }

    private boolean cameraThumbnail(Intent data) {
        if (data == null || data.getExtras() == null) return false;
        Object raw = data.getExtras().get("data");
        if (!(raw instanceof Bitmap)) return false;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "thumb_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream out = new FileOutputStream(file);
            ((Bitmap) raw).compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            DebugLog.i("camera", "use thumbnail " + file.length());
            sendImage(Uri.fromFile(file));
            return true;
        } catch (Exception e) {
            DebugLog.i("camera", "thumbnail save failed: " + e.getMessage());
            return false;
        }
    }

    private boolean findLatestCameraImage(long launchedAt) {
        if (launchedAt <= 0) return false;
        Cursor cursor = null;
        try {
            long since = Math.max(0, launchedAt / 1000 - 2);
            String selection = MediaStore.Images.Media.DATE_ADDED + ">=?";
            cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED},
                    selection,
                    new String[]{String.valueOf(since)},
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                DebugLog.i("camera", "fallback newest gallery image " + uri);
                sendImage(uri);
                return true;
            }
        } catch (Exception e) {
            DebugLog.i("camera", "gallery fallback failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 33) {
            if (resultCode == RESULT_OK && data != null) {
                String action = data.getStringExtra(FriendSettingsActivity.ACTION);
                if (FriendSettingsActivity.ACTION_CLEARED.equals(action)) {
                    messages.clear();
                    ids.clear();
                    adapter.notifyDataSetChanged();
                    loadMessages(true);
                } else if (FriendSettingsActivity.ACTION_DELETED.equals(action)
                        || FriendSettingsActivity.ACTION_BLOCKED.equals(action)) {
                    finish();
                }
            }
            return;
        }
        if (requestCode == 52) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                sendImage(data.getData());
            }
            return;
        }
        if (requestCode == 32) {
            pendingCamera = false;
            Session.setPendingCameraUri("");
            DebugLog.i("camera", "result code=" + resultCode + " uri=" + cameraUri);
            boolean hasData = cameraUri != null && cameraHasData(cameraUri);
            if (hasData) {
                sendImage(cameraUri);
            } else if (cameraThumbnail(data)) {
                // 系统相机回传缩略图时也能发送。
            } else if (findLatestCameraImage(cameraLaunchAt)) {
                // 部分 OEM 相机把照片写入自己的相册，不写 EXTRA_OUTPUT。
            } else if (cameraUri != null) {
                Ui.toast(this, "相机没有写入照片，请重拍");
            }
            cameraUri = null;
            return;
        }
        if (requestCode != 31 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        DebugLog.i("chat", "image result " + MediaUtil.describe(uri));
        if (uri != null) sendImage(uri);
    }

    private void sendImage(Uri uri) {
        if (sending) {
            DebugLog.i("chat", "sendImage skipped: already sending");
            Ui.toast(this, "正在发送上一条消息，请稍候");
            return;
        }
        if (convId <= 0) {
            DebugLog.i("chat", "sendImage skipped: convId=0");
            Ui.toast(this, "会话还没有准备好");
            return;
        }
        sending = true;
        final String caption = input.getText().toString().trim();
        DebugLog.i("chat", "sendImage " + MediaUtil.describe(uri));
        input.setText("");
        if ("ai".equals(type)) showTyping(true);
        Ui.toast(this, "正在发送图片…");
        try {
            MediaUtil.persist(this, uri);
            InputStream in = MediaUtil.open(this, uri);
            if (in == null) throw new Exception("图片读取失败");
            File dir = new File(getCacheDir(), "chat");
            if (!dir.exists()) dir.mkdirs();
            final File file = new File(dir, "img_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            if (file.length() <= 0) throw new Exception("图片文件为空");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            DebugLog.i("chat", "sendImage bytes=" + file.length()
                    + " size=" + bounds.outWidth + "x" + bounds.outHeight);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new Exception("图片内容为空或格式损坏");
            }
            BitmapFactory.Options checkOptions = new BitmapFactory.Options();
            checkOptions.inSampleSize = 4;
            Bitmap check = BitmapFactory.decodeFile(file.getAbsolutePath(), checkOptions);
            if (check == null) throw new Exception("图片解码失败或文件不完整");
            check.recycle();
            Api.uploadFile(file, new Api.Cb() {
                @Override
                public void ok(JSONObject data) {
                    String url = data.optString("url", "");
                    if (url.isEmpty()) {
                        sending = false;
                        showTyping(false);
                        Ui.toast(ChatActivity.this, "图片上传失败");
                        return;
                    }
                    Api.sendImageMessage(convId, url, caption, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            sending = false;
                            showTyping(data.optBoolean("pending"));
                            if ("ai".equals(type)) syncComposingFromInput();
                            JSONObject m = data.optJSONObject("message");
                            if (m != null) {
                                JSONArray one = new JSONArray();
                                one.put(m);
                                merge(one);
                            }
                            JSONObject reply = data.optJSONObject("reply");
                            if (reply != null) {
                                JSONArray one = new JSONArray();
                                one.put(reply);
                                merge(one);
                            }
                        }

                        @Override
                        public void fail(String message) {
                            sending = false;
                            showTyping(false);
                            Ui.toast(ChatActivity.this, message);
                        }
                    });
                }

                @Override
                public void fail(String message) {
                    sending = false;
                    showTyping(false);
                    Ui.toast(ChatActivity.this, message);
                }
            });
        } catch (Exception e) {
            sending = false;
            showTyping(false);
            Ui.toast(this, "图片读取失败：" + e.getMessage());
        }
    }

    private void sendSticker(String url, String words) {
        if (sending || convId <= 0 || url == null || url.isEmpty()) return;
        sending = true;
        if ("ai".equals(type)) showTyping(true);
        Api.sendStickerMessage(convId, url, words == null || words.isEmpty() ? "表情" : words, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                sending = false;
                showTyping(data.optBoolean("pending"));
                if ("ai".equals(type)) syncComposingFromInput();
                JSONObject m = data.optJSONObject("message");
                if (m != null) {
                    JSONArray one = new JSONArray();
                    one.put(m);
                    merge(one);
                }
                JSONObject reply = data.optJSONObject("reply");
                if (reply != null) {
                    JSONArray one = new JSONArray();
                    one.put(reply);
                    merge(one);
                }
            }

            @Override
            public void fail(String message) {
                sending = false;
                showTyping(false);
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void send() {
        if (sending || convId <= 0) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        sending = true;
        input.setText("");
        if ("ai".equals(type)) showTyping(true);
        Api.sendMessage(convId, text, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                sending = false;
                showTyping(data.optBoolean("pending"));
                JSONObject m = data.optJSONObject("message");
                if (m != null) {
                    JSONArray one = new JSONArray();
                    one.put(m);
                    merge(one);
                }
                JSONObject reply = data.optJSONObject("reply");
                if (reply != null) {
                    JSONArray one = new JSONArray();
                    one.put(reply);
                    merge(one);
                }
                if ("ai".equals(type)) syncComposingFromInput();
            }

            @Override
            public void fail(String message) {
                sending = false;
                showTyping(false);
                input.setText(text);
                Ui.toast(ChatActivity.this, message);
            }
        });
    }

    private void showMessageMenu(final Msg m) {
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();
        if ("audio".equals(m.contentType)) {
            labels.add("转文本");
            actions.add(5);
            if (!m.audioUrl.isEmpty()) {
                labels.add("复制语音链接");
                actions.add(6);
            }
        } else if ("image".equals(m.contentType) || "sticker".equals(m.contentType)) {
            if (!m.imageUrl.isEmpty()) {
                labels.add("复制图片链接");
                actions.add(2);
            }
            if ("sticker".equals(m.contentType)) {
                labels.add("收藏到常用表情包");
                actions.add(3);
            }
        } else {
            labels.add("复制");
            actions.add(1);
        }
        boolean canRecall = ("direct".equals(type) || "group".equals(type))
                && "user".equals(m.senderType) && m.senderId == Session.userId();
        if (canRecall) {
            labels.add("撤回");
            actions.add(4);
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("消息")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int action = actions.get(which);
                        if (action == 1) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("消息", m.content));
                                Ui.toast(ChatActivity.this, "已复制");
                            }
                        } else if (action == 2) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("图片链接", m.imageUrl));
                                Ui.toast(ChatActivity.this, "已复制图片链接");
                            }
                        } else if (action == 3) {
                            Session.addFavorite(m.imageUrl, m.words.isEmpty() ? "表情" : m.words);
                            Ui.toast(ChatActivity.this, "已收藏到常用表情包");
                        } else if (action == 5) {
                            Ui.toast(ChatActivity.this, "正在转换语音…");
                            Api.transcribeAudio(m.audioUrl, new Api.Cb() {
                                @Override
                                public void ok(JSONObject data) {
                                    String text = data.optString("text", "").trim();
                                    if (text.isEmpty()) {
                                        Ui.toast(ChatActivity.this, "没有识别到文字");
                                        return;
                                    }
                                    m.words = text;
                                    adapter.notifyDataSetChanged();
                                    showVoiceHint("转写完成", 1000);
                                }

                                @Override
                                public void fail(String message) {
                                    Ui.toast(ChatActivity.this, message);
                                }
                            });
                        } else if (action == 6) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("语音链接", m.audioUrl));
                                Ui.toast(ChatActivity.this, "已复制语音链接");
                            }
                        } else if (action == 4) {
                            Api.recallMessage(convId, m.id, new Api.Cb() {
                                @Override
                                public void ok(JSONObject data) {
                                    m.contentType = "recalled";
                                    m.content = "[消息已撤回]";
                                    adapter.notifyDataSetChanged();
                                    loadMessages(false);
                                }

                                @Override
                                public void fail(String message) {
                                    Ui.toast(ChatActivity.this, message);
                                }
                            });
                        }
                    }
                })
                .show();
    }

    private static class Msg {
        long id;
        String senderType;
        int senderId;
        String senderName;
        String content;
        String contentType;
        String createdAt;
        String imageUrl;
        String audioUrl;
        int audioDuration;
        String words;
        int aiFriendId;
        String senderAvatar;
        String senderFrame;
        JSONObject metaJson;
        int amount;
        String moneyNote;
        String moneyStatus;

        Msg(JSONObject o) {
            id = o.optLong("id");
            senderType = o.optString("sender_type", "user");
            senderId = o.optInt("sender_id");
            senderName = o.optString("sender_name", "");
            if ("null".equals(senderName)) senderName = "";
            content = o.optString("content", "");
            if ("null".equals(content)) content = "";
            contentType = o.optString("content_type", "text");
            createdAt = o.optString("created_at", "");
            JSONObject meta = o.optJSONObject("meta");
            metaJson = meta;
            amount = meta == null ? 0 : meta.optInt("amount", 0);
            moneyNote = meta == null ? "" : meta.optString("note", "");
            moneyStatus = meta == null ? "" : meta.optString("status", "");
            imageUrl = meta == null ? "" : meta.optString("url", "");
            if ("null".equals(imageUrl)) imageUrl = "";
            audioUrl = imageUrl;
            audioDuration = meta == null ? 0 : meta.optInt("duration", 0);
            words = meta == null ? "" : meta.optString("words", "");
            if ("null".equals(words)) words = "";
            aiFriendId = meta == null ? 0 : meta.optInt("ai_friend_id", 0);
            senderAvatar = meta == null ? "" : meta.optString("sender_avatar", "");
            if ("null".equals(senderAvatar)) senderAvatar = "";
            senderFrame = meta == null ? "" : meta.optString("sender_frame", "");
            if ("null".equals(senderFrame)) senderFrame = "";
        }
    }

    private View moneyCard(final Msg m, boolean mine) {
        boolean red = "redpacket".equals(m.contentType);
        LinearLayout card = Ui.column(ChatActivity.this);
        card.setPadding(Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 12),
                Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 12));
        card.setBackground(Ui.round(red ? 0xFFE94B3C : 0xFFF0A332, 14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(ChatActivity.this, 232), ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        LinearLayout head = Ui.row(ChatActivity.this);
        FrameLayout iconBox = new FrameLayout(ChatActivity.this);
        iconBox.setBackground(Ui.round(0x33FFFFFF, 20));
        LineIconView icon = new LineIconView(ChatActivity.this);
        icon.setType(red ? "redpacket" : "transfer");
        icon.setIconColor(0xFFFFFFFF);
        iconBox.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(ChatActivity.this, 30), Ui.dp(ChatActivity.this, 30), Gravity.CENTER));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(ChatActivity.this, 38), Ui.dp(ChatActivity.this, 38));
        head.addView(iconBox, ilp);
        LinearLayout texts = Ui.column(ChatActivity.this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(ChatActivity.this, 9);
        texts.setLayoutParams(tlp);
        texts.addView(Ui.tv(ChatActivity.this,
                red ? "恭喜发财，大吉大利" : "转账", 14.5f, Color.WHITE, true));
        texts.addView(Ui.tv(ChatActivity.this, m.moneyNote == null || m.moneyNote.isEmpty()
                ? (red ? "金币红包" : "金币转账") : m.moneyNote, 11.5f, 0xDDFFFFFF, false));
        head.addView(texts);
        card.addView(head);

        TextView amount = Ui.tv(ChatActivity.this, m.amount + " 金币", 21, Color.WHITE, true);
        amount.setPadding(0, Ui.dp(ChatActivity.this, 12), 0, 0);
        card.addView(amount);

        boolean open = "open".equals(m.moneyStatus) || "pending".equals(m.moneyStatus);
        String status;
        if ("refunded".equals(m.moneyStatus)) {
            status = "已退回";
        } else if (red) {
            if ("accepted".equals(m.moneyStatus)) status = "已收下";
            else if ("claimed".equals(m.moneyStatus)) status = "已领取";
            else status = open ? (mine ? "等待领取" : "点击领取") : "已领取";
        } else {
            if ("accepted".equals(m.moneyStatus)) status = "已收下";
            else if ("pending".equals(m.moneyStatus)) status = "待处理";
            else status = mine ? "已转出" : "已收款";
        }
        TextView statusView = Ui.tv(ChatActivity.this, status, 11.5f, 0xEEFFFFFF, false);
        statusView.setPadding(0, Ui.dp(ChatActivity.this, 6), 0, 0);
        card.addView(statusView);

        if (red && open && !mine) {
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Api.claimRedPacket(m.id, new Api.Cb() {
                        @Override
                        public void ok(JSONObject data) {
                            JSONObject message = data.optJSONObject("message");
                            if (message != null) {
                                JSONObject meta = message.optJSONObject("meta");
                                if (meta != null) {
                                    m.metaJson = meta;
                                    m.moneyStatus = meta.optString("status", "claimed");
                                } else {
                                    m.moneyStatus = "claimed";
                                }
                            } else {
                                m.moneyStatus = "claimed";
                            }
                            adapter.notifyDataSetChanged();
                            Ui.toast(ChatActivity.this, "领取成功 +" + data.optInt("amount", m.amount) + " 金币");
                        }

                        @Override
                        public void fail(String message) {
                            Ui.toast(ChatActivity.this, message);
                        }
                    });
                }
            });
        }
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showMoneyMenu(m);
                return true;
            }
        });
        return card;
    }

    private void showMoneyMenu(final Msg m) {
        if ("refunded".equals(m.moneyStatus)) {
            Ui.toast(this, "这笔交易已经退回");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("交易操作")
                .setItems(new String[]{"退回"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Api.returnMoney(m.id, new Api.Cb() {
                            @Override
                            public void ok(JSONObject data) {
                                JSONObject message = data.optJSONObject("message");
                                if (message != null) {
                                    JSONObject meta = message.optJSONObject("meta");
                                    if (meta != null) m.moneyStatus = meta.optString("status", "refunded");
                                } else {
                                    m.moneyStatus = "refunded";
                                }
                                adapter.notifyDataSetChanged();
                                Ui.toast(ChatActivity.this, "已退回");
                            }

                            @Override
                            public void fail(String message) {
                                Ui.toast(ChatActivity.this, message);
                            }
                        });
                    }
                })
                .show();
    }

    private String avatarFor(Msg m) {
        String value = peerAvatar;
        if (m.senderAvatar != null && !m.senderAvatar.isEmpty()) value = m.senderAvatar;
        if ("group".equals(type)) {
            if ("ai".equals(m.senderType) && m.aiFriendId > 0) {
                String aiAvatar = aiAvatars.get(m.aiFriendId);
                if (aiAvatar != null && !aiAvatar.isEmpty()) value = aiAvatar;
            } else if ("user".equals(m.senderType) && m.senderId > 0) {
                String userAvatar = memberAvatars.get(m.senderId);
                if (userAvatar != null && !userAvatar.isEmpty()) value = userAvatar;
            }
        }
        return value == null || value.isEmpty() ? "🙂" : value;
    }

    private class MsgAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return messages.size();
        }

        @Override
        public Object getItem(int position) {
            return messages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return messages.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Msg m = messages.get(position);
            LinearLayout root = new LinearLayout(ChatActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Ui.dp(ChatActivity.this, 4), Ui.dp(ChatActivity.this, 5),
                    Ui.dp(ChatActivity.this, 4), Ui.dp(ChatActivity.this, 5));

            if ("system".equals(m.senderType)) {
                TextView sys = Ui.tv(ChatActivity.this, m.content, 11.5f, Ui.MUTED2, false);
                sys.setGravity(Gravity.CENTER);
                root.addView(sys, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return root;
            }
            if ("recalled".equals(m.contentType)) {
                TextView revoked = Ui.tv(ChatActivity.this, "消息已撤回", 11.5f, Ui.MUTED2, false);
                revoked.setGravity(Gravity.CENTER);
                root.addView(revoked, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return root;
            }

            boolean mine = "user".equals(m.senderType) && m.senderId == Session.userId();
            LinearLayout holder = Ui.row(ChatActivity.this);
            holder.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
            holder.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout col = Ui.column(ChatActivity.this);
            if (mine) {
                col.setPadding(0, Ui.dp(ChatActivity.this, 8), 0, 0);
            }
            if (!mine && ("group".equals(type) || "ai".equals(type)) && !m.senderName.isEmpty()) {
                TextView name = Ui.tv(ChatActivity.this, m.senderName, 10.5f, Ui.MUTED2, false);
                name.setPadding(Ui.dp(ChatActivity.this, 6), 0, 0, Ui.dp(ChatActivity.this, 2));
                col.addView(name);
            }

            TextView bubble = new TextView(ChatActivity.this);
            String text = m.content;
            if ("audio".equals(m.contentType) && !m.audioUrl.isEmpty()) {
                final TextView audio = Ui.tv(ChatActivity.this,
                        "▶  " + Math.max(1, m.audioDuration) + "″", 14,
                        Ui.bubbleText(mine), false);
                audio.setGravity(Gravity.CENTER_VERTICAL);
                audio.setPadding(Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 10),
                        Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 10));
                audio.setBackground(Ui.bubble(mine));
                int width = Math.min(Ui.dp(ChatActivity.this, 220),
                        Ui.dp(ChatActivity.this, 90) + Math.min(600, m.audioDuration) * 3);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        width, ViewGroup.LayoutParams.WRAP_CONTENT);
                audio.setLayoutParams(alp);
                audio.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AudioCache.play(ChatActivity.this, m.audioUrl, new AudioCache.Listener() {
                            @Override
                            public void onStart() {
                                showVoiceHint("正在播放…", 0);
                            }

                            @Override
                            public void onComplete() {
                                hideVoiceHint();
                            }

                            @Override
                            public void onError(String message) {
                                hideVoiceHint();
                                Ui.toast(ChatActivity.this, message);
                            }
                        });
                    }
                });
                audio.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        showMessageMenu(m);
                        return true;
                    }
                });
                col.addView(audio);
                if (!m.words.isEmpty()) {
                    final TextView transcript = Ui.tv(ChatActivity.this, m.words, 14,
                            Ui.bubbleText(mine), false);
                    transcript.setPadding(Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 10),
                            Ui.dp(ChatActivity.this, 14), Ui.dp(ChatActivity.this, 10));
                    transcript.setBackground(Ui.bubble(mine));
                    transcript.setMaxWidth(Ui.dp(ChatActivity.this, 240));
                    transcript.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            copyTranscript(m.words);
                            return true;
                        }
                    });
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    tlp.topMargin = Ui.dp(ChatActivity.this, 4);
                    col.addView(transcript, tlp);
                }
            } else if (("transfer".equals(m.contentType) || "redpacket".equals(m.contentType))
                    && m.amount > 0) {
                col.addView(moneyCard(m, mine));
            } else if ("sticker".equals(m.contentType) && !m.imageUrl.isEmpty()) {
                StickerImageView sticker = new StickerImageView(ChatActivity.this);
                int size = Ui.dp(ChatActivity.this, 150);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, size);
                sticker.setLayoutParams(slp);
                sticker.load(m.imageUrl);
                sticker.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ImageLoader.preview(ChatActivity.this, m.imageUrl);
                    }
                });
                sticker.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (mine) {
                            showMessageMenu(m);
                        } else {
                            Session.addFavorite(m.imageUrl, m.words.isEmpty() ? "表情" : m.words);
                            Ui.toast(ChatActivity.this, "已收藏到常用表情包");
                        }
                        return true;
                    }
                });
                col.addView(sticker);
            } else if ("image".equals(m.contentType) && !m.imageUrl.isEmpty()) {
                LinearLayout imageBox = Ui.column(ChatActivity.this);
                imageBox.setPadding(Ui.dp(ChatActivity.this, 4), Ui.dp(ChatActivity.this, 4),
                        Ui.dp(ChatActivity.this, 4), Ui.dp(ChatActivity.this, 4));
                imageBox.setBackgroundResource(mine ? R.drawable.bg_bubble_me : R.drawable.bg_bubble_other);
                int size = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.52), Ui.dp(ChatActivity.this, 210));
                ImageView image = new ImageView(ChatActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
                image.setLayoutParams(ilp);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(0x22000000);
                ImageLoader.load(image, m.imageUrl);
                image.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ImageLoader.preview(ChatActivity.this, m.imageUrl);
                    }
                });
                if (mine) {
                    image.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showMessageMenu(m);
                            return true;
                        }
                    });
                }
                imageBox.addView(image);
                if (mine) {
                    imageBox.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showMessageMenu(m);
                            return true;
                        }
                    });
                }
                if (!m.content.isEmpty() && !"[图片]".equals(m.content)) {
                    TextView caption = Ui.tv(ChatActivity.this, m.content, 12.5f,
                            mine ? 0xFFFFFFFF : Ui.TEXT, false);
                    caption.setPadding(Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 4),
                            Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 2));
                    imageBox.addView(caption);
                }
                col.addView(imageBox);
            } else {
                if ("image".equals(m.contentType)) text = "🖼 图片";
                bubble.setText(text);
                bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
                bubble.setTextColor(Ui.bubbleText(mine));
                bubble.setPadding(Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 9),
                        Ui.dp(ChatActivity.this, 12), Ui.dp(ChatActivity.this, 9));
                bubble.setBackground(Ui.bubble(mine));
                int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.74);
                bubble.setMaxWidth(max);
                if (mine) {
                    bubble.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showMessageMenu(m);
                            return true;
                        }
                    });
                }
                col.addView(bubble);
            }

            TextView time = Ui.tv(ChatActivity.this, Ui.time(m.createdAt), 9.5f, Ui.MUTED2, false);
            time.setPadding(Ui.dp(ChatActivity.this, 6), Ui.dp(ChatActivity.this, 3),
                    Ui.dp(ChatActivity.this, 6), 0);
            col.addView(time);

            if (!mine) {
                String avatarValue = avatarFor(m);
                int avatarSize = m.senderFrame == null || m.senderFrame.isEmpty() ? 34 : 38;
                View avatar = m.senderFrame == null || m.senderFrame.isEmpty()
                        ? Ui.avatarCircle(ChatActivity.this, avatarValue, avatarSize)
                        : Ui.avatarCircleFramed(ChatActivity.this, avatarValue, m.senderFrame, avatarSize);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        Ui.dp(ChatActivity.this, avatarSize), Ui.dp(ChatActivity.this, avatarSize));
                alp.rightMargin = Ui.dp(ChatActivity.this, 8);
                holder.addView(avatar, alp);
            }
            holder.addView(col);
            if (mine) {
                int avatarSize = Ui.hasFrame() ? 44 : 34;
                View avatar = Ui.avatarCircleFramed(ChatActivity.this, Session.avatar(), avatarSize);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        Ui.dp(ChatActivity.this, avatarSize), Ui.dp(ChatActivity.this, avatarSize));
                alp.leftMargin = Ui.dp(ChatActivity.this, 8);
                holder.addView(avatar, alp);
            }
            root.addView(holder);
            return root;
        }
    }

    @Override
    public void onBackPressed() {
        if (emojiOpen) {
            setEmojiOpen(false, false);
            return;
        }
        if (voiceMode) {
            setVoiceMode(false);
            return;
        }
        if (toolbarOpen) {
            setToolbarOpen(false);
            return;
        }
        super.onBackPressed();
    }
}
