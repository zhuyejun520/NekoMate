package com.nekocyte.companion;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class UserMomentsActivity extends BaseActivity {
    private LinearLayout list;
    private String name;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int userId = getIntent().getIntExtra("user_id", 0);
        name = getIntent().getStringExtra("name");
        if (name == null) name = "好友";

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
        TextView title = Ui.tv(this, name + "的动态", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 30));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        list.addView(Ui.tv(this, "正在加载动态…", 13, Ui.MUTED2, false));

        Api.userMoments(userId, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                render(data.optJSONArray("moments"));
            }

            @Override
            public void fail(String message) {
                list.removeAllViews();
                list.addView(Ui.tv(UserMomentsActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render(JSONArray arr) {
        list.removeAllViews();
        if (arr == null || arr.length() == 0) {
            list.addView(Ui.tv(this, name + "还没有发布动态", 13, Ui.MUTED, false));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            LinearLayout card = Ui.card(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 10);
            card.setLayoutParams(lp);

            String raw = m.optString("content", "");
            if (raw.startsWith("<!--md-->")) raw = raw.substring("<!--md-->".length()).trim();
            VideoLink video = VideoLink.find(raw);
            if (video != null) {
                raw = VideoLink.strip(raw, video);
                LinearLayout videoCard = Ui.card(this);
                videoCard.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
                videoCard.addView(Ui.tv(this, "▶ " + video.platform + " 视频 · 点击播放", 13, Ui.ACCENT, true));
                final VideoLink target = video;
                videoCard.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        android.content.Intent intent = new android.content.Intent(UserMomentsActivity.this, VideoPlayerActivity.class);
                        intent.putExtra("url", target.playerUrl);
                        intent.putExtra("title", target.platform + " 视频");
                        startActivity(intent);
                    }
                });
                card.addView(videoCard);
            }
            TextView text = Ui.tv(this, "", 14, Ui.TEXT, false);
            text.setText(Markdown.render(raw));
            text.setMovementMethod(LinkMovementMethod.getInstance());
            text.setLinkTextColor(Ui.ACCENT);
            card.addView(text);
            card.addView(Ui.tv(this, m.optString("created_at", "").replace("T", " ").replace("Z", ""), 10.5f, Ui.MUTED2, false));

            JSONArray images = m.optJSONArray("images");
            if (images != null && images.length() > 0) {
                LinearLayout imageRow = Ui.row(this);
                for (int j = 0; j < images.length() && j < 3; j++) {
                    final String url = images.optString(j, "");
                    ImageView image = new ImageView(this);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                            Ui.dp(this, 92), Ui.dp(this, 92));
                    ilp.rightMargin = Ui.dp(this, 4);
                    image.setLayoutParams(ilp);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ImageLoader.load(image, url);
                    image.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ImageLoader.preview(UserMomentsActivity.this, url);
                        }
                    });
                    imageRow.addView(image);
                }
                if (images.length() > 3) imageRow.addView(Ui.tv(this, "+" + (images.length() - 3), 12, Ui.MUTED, false));
                card.addView(imageRow);
            }
            int likes = m.optInt("likes", 0);
            int comments = m.optJSONArray("comments") == null ? 0 : m.optJSONArray("comments").length();
            card.addView(Ui.tv(this, "赞 " + likes + " · 评论 " + comments, 11, Ui.MUTED2, false));
            list.addView(card);
        }
    }
}
