package com.nekocyte.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MomentsPage extends BasePage {
    private final Handler poller = new Handler(Looper.getMainLooper());
    private Runnable pollTask;
    private boolean visible;
    private String lastMomentsJson = "";
    private LinearLayout momentList;

    public MomentsPage(MainActivity host) {
        super(host);
    }

    @Override
    public void onShow() {
        visible = true;
        startPolling();
    }

    @Override
    public void onHide() {
        visible = false;
        if (pollTask != null) poller.removeCallbacks(pollTask);
        pollTask = null;
    }

    private void startPolling() {
        if (pollTask != null) poller.removeCallbacks(pollTask);
        pollTask = new Runnable() {
            @Override
            public void run() {
                if (!visible || momentList == null) return;
                Api.moments(new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        JSONArray arr = data.optJSONArray("moments");
                        if (arr != null && !arr.toString().equals(lastMomentsJson)) {
                            LocalStore.saveMoments(host, arr);
                            renderMoments(momentList, arr);
                        }
                    }

                    @Override
                    public void fail(String message) {
                    }
                });
                poller.postDelayed(this, 8000);
            }
        };
        poller.postDelayed(pollTask, 8000);
    }

    @Override
    public void refresh() {
        clear();
        LinearLayout top = Ui.row(host);
        TextView post = Ui.button(host, "＋ 发布动态", true);
        top.addView(post);
        box.addView(top, full());
        post.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.postMomentDialog();
            }
        });

        final LinearLayout list = Ui.column(host);
        momentList = list;
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = Ui.dp(host, 10);
        box.addView(list, lp);
        JSONArray cached = LocalStore.loadMoments(host);
        if (cached.length() > 0) {
            renderMoments(list, cached);
        } else {
            list.addView(Ui.tv(host, "正在加载动态…", 12.5f, Ui.MUTED2, false));
        }

        Api.moments(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("moments");
                LocalStore.saveMoments(host, arr);
                renderMoments(list, arr);
            }

            @Override
            public void fail(String message) {
                if (list.getChildCount() == 0 || (list.getChildCount() == 1
                        && list.getChildAt(0) instanceof TextView)) {
                    list.removeAllViews();
                    list.addView(Ui.tv(host, message, 13, Ui.RED, false));
                }
            }
        });
    }

    private void renderMoments(LinearLayout list, JSONArray arr) {
        lastMomentsJson = arr == null ? "[]" : arr.toString();
        list.removeAllViews();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                JSONArray images = m.optJSONArray("images");
                if (images == null) continue;
                for (int j = 0; j < images.length(); j++) {
                    String url = images.optString(j, "");
                    if (!url.isEmpty()) ImageCache.prefetch(url);
                }
            }
        }
        if (arr == null || arr.length() == 0) {
            LinearLayout empty = Ui.card(host);
            TextView t = Ui.tv(host, "还没有动态\n发一条动态，让朋友们看到", 13, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(host, 18), 0, Ui.dp(host, 12));
            empty.addView(t);
            TextView b = Ui.button(host, "发布第一条", true);
            empty.addView(b);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.postMomentDialog();
                }
            });
            list.addView(empty, full());
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) render(list, m);
        }
    }

    private void addVideoCard(LinearLayout parent, final VideoLink video) {
        LinearLayout box = Ui.column(host);
        box.setPadding(Ui.dp(host, 14), Ui.dp(host, 12), Ui.dp(host, 14), Ui.dp(host, 12));
        box.setBackground(Ui.roundStroke(Ui.PANEL2, Ui.BORDER, 12, 1));
        LinearLayout row = Ui.row(host);
        TextView play = Ui.tv(host, "▶", 22, Ui.ACCENT, true);
        row.addView(play);
        LinearLayout info = Ui.column(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(host, 12);
        info.setLayoutParams(lp);
        info.addView(Ui.tv(host, video.platform + " 视频", 14, Ui.TEXT, true));
        info.addView(Ui.tv(host, "点击播放 · 默认暂停", 11.5f, Ui.MUTED2, false));
        row.addView(info);
        box.addView(row);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(host, VideoPlayerActivity.class);
                i.putExtra("url", video.playerUrl);
                i.putExtra("title", video.platform + " 视频");
                host.startActivity(i);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(host, 8);
        parent.addView(box, blp);
    }

    private void loadImage(final ImageView view, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setDoInput(true);
                    InputStream in = conn.getInputStream();
                    final Bitmap bitmap = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bitmap != null) {
                        host.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                view.setImageBitmap(bitmap);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void render(LinearLayout parent, final JSONObject m) {
        final int id = m.optInt("id");
        JSONObject author = m.optJSONObject("author");
        String name = Ui.str(author, "nickname", "好友");
        String avatar = Ui.str(author, "avatar", "");

        LinearLayout card = Ui.card(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(host, 10);
        card.setLayoutParams(lp);

        LinearLayout head = Ui.row(host);
        head.addView(Ui.avatar(host, avatar, 38));
        LinearLayout meta = Ui.column(host);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mlp.leftMargin = Ui.dp(host, 10);
        meta.setLayoutParams(mlp);
        meta.addView(Ui.tv(host, name, 13.5f, Ui.TEXT, true));
        meta.addView(Ui.tv(host, Ui.time(m.optString("created_at")), 11, Ui.MUTED2, false));
        head.addView(meta);
        String personaName = m.isNull("ai_persona_name") ? "" : m.optString("ai_persona_name", "");
        if (!personaName.isEmpty() && !"null".equals(personaName)) head.addView(Ui.badge(host, "AI · " + personaName, Ui.ACCENT));
        card.addView(head);

        String rawContent = m.optString("content", "");
        if (rawContent.startsWith("<!--md-->")) {
            rawContent = rawContent.substring("<!--md-->".length()).trim();
        }
        VideoLink video = VideoLink.find(rawContent);
        String displayContent = video == null ? rawContent : VideoLink.strip(rawContent, video);
        if (video != null) addVideoCard(card, video);

        TextView content = Ui.tv(host, "", 14, Ui.TEXT, false);
        content.setText(Markdown.render(displayContent));
        content.setMovementMethod(LinkMovementMethod.getInstance());
        content.setLinkTextColor(Ui.ACCENT);
        content.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 6));
        card.addView(content);

        JSONArray images = m.optJSONArray("images");
        if (images != null && images.length() > 0) {
            LinearLayout grid = Ui.column(host);
            grid.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 4));
            int shown = Math.min(images.length(), 9);
            LinearLayout row = null;
            int visible = 0;
            for (int i = 0; i < shown; i++) {
                String url = images.optString(i, "");
                if (url == null || url.isEmpty()) continue;
                if (visible % 3 == 0) {
                    row = Ui.row(host);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = Ui.dp(host, 4);
                    row.setLayoutParams(rlp);
                    grid.addView(row);
                }
                if (url.startsWith("/")) url = Session.server() + url;
                ImageView iv = new ImageView(host);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        Ui.dp(host, 96), Ui.dp(host, 96));
                ilp.rightMargin = Ui.dp(host, 6);
                iv.setLayoutParams(ilp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.round(Ui.PANEL2, 10));
                if (row != null) row.addView(iv);
                final String imageUrl = url;
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ImageLoader.preview(host, imageUrl);
                    }
                });
                ImageLoader.load(iv, url);
                visible++;
            }
            card.addView(grid);
        }

        JSONArray comments = m.optJSONArray("comments");
        if (comments != null) {
            for (int i = 0; i < comments.length() && i < 10; i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c == null) continue;
                JSONObject cu = c.optJSONObject("user");
                String cn = cu == null ? "好友" : cu.optString("nickname", "好友");
                if (c.optBoolean("is_ai", false)) cn = "AI · " + cn;
                TextView line = Ui.tv(host, cn + "：" + c.optString("content"), 12, Ui.MUTED, false);
                line.setPadding(0, Ui.dp(host, 3), 0, 0);
                card.addView(line);
            }
        }

        LinearLayout actions = Ui.row(host);
        actions.setPadding(0, Ui.dp(host, 10), 0, 0);
        final boolean liked = m.optBoolean("liked", false);
        int likeCount = m.optInt("likes", 0);
        TextView like = Ui.button(host, (liked ? "♥ 已赞 " : "♡ 赞 ") + likeCount, false);
        TextView comment = Ui.button(host, "💬 评论", false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(host, 8);
        comment.setLayoutParams(clp);
        like.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Api.likeMoment(id, new Api.Cb() {
                    @Override
                    public void ok(JSONObject data) {
                        refresh();
                    }

                    @Override
                    public void fail(String message) {
                        Ui.toast(host, message);
                    }
                });
            }
        });
        comment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(host);
                input.setHint("写点什么…");
                input.setBackgroundResource(R.drawable.bg_input);
                input.setTextColor(Ui.TEXT);
                input.setHintTextColor(Ui.MUTED2);
                input.setPadding(Ui.dp(host, 12), Ui.dp(host, 10), Ui.dp(host, 12), Ui.dp(host, 10));
                new android.app.AlertDialog.Builder(host)
                        .setTitle("评论")
                        .setView(input)
                        .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String text = input.getText().toString().trim();
                                if (text.isEmpty()) return;
                                Api.commentMoment(id, text, new Api.Cb() {
                                    @Override
                                    public void ok(JSONObject data) {
                                        refresh();
                                    }

                                    @Override
                                    public void fail(String message) {
                                        Ui.toast(host, message);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        actions.addView(like);
        actions.addView(comment);
        if (m.optInt("user_id") == Session.userId() || m.optInt("author_id") == Session.userId()) {
            TextView delete = Ui.button(host, "删除", false);
            delete.setTextColor(Ui.RED);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = Ui.dp(host, 8);
            delete.setLayoutParams(dlp);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new android.app.AlertDialog.Builder(host)
                            .setTitle("删除动态")
                            .setMessage("删除后不可恢复，确定删除这条动态？")
                            .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    Api.deleteMoment(id, new Api.Cb() {
                                        @Override
                                        public void ok(JSONObject data) {
                                            Ui.toast(host, "已删除");
                                            refresh();
                                        }

                                        @Override
                                        public void fail(String message) {
                                            Ui.toast(host, message);
                                        }
                                    });
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
            actions.addView(delete);
        }
        card.addView(actions);
        parent.addView(card);
    }
}
