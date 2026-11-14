package com.nekocyte.companion;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

public class MyActivity extends BaseActivity {
    private FrameLayout avatarBox;
    private TextView nameView;
    private TextView genderView;
    private TextView bioView;
    private LinearLayout tagBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        TextView title = Ui.tv(this, "我的", 18, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView settings = Ui.button(this, "⚙", false);
        settings.setTextSize(22);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MyActivity.this, SettingsActivity.class));
            }
        });
        bar.addView(settings);
        root.addView(bar);

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(this, 18);
        clp.rightMargin = Ui.dp(this, 18);
        clp.topMargin = Ui.dp(this, 16);
        card.setLayoutParams(clp);

        LinearLayout top = Ui.row(this);
        avatarBox = new FrameLayout(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92));
        avatarBox.setLayoutParams(alp);
        top.addView(avatarBox);
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ilp.leftMargin = Ui.dp(this, 16);
        info.setLayoutParams(ilp);
        nameView = Ui.tv(this, "", 20, Ui.TEXT, true);
        info.addView(nameView);
        genderView = Ui.tv(this, "", 12, Ui.MUTED, false);
        info.addView(genderView);
        top.addView(info);
        card.addView(top);

        bioView = Ui.tv(this, "", 13.5f, Ui.TEXT, false);
        bioView.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        card.addView(bioView);
        tagBox = Ui.row(this);
        tagBox.setPadding(0, 0, 0, Ui.dp(this, 4));
        card.addView(tagBox);
        root.addView(card);

        LinearLayout mine = Ui.card(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.dp(this, 18);
        mlp.rightMargin = Ui.dp(this, 18);
        mlp.topMargin = Ui.dp(this, 12);
        mine.setLayoutParams(mlp);
        LinearLayout mineRow = Ui.row(this);
        mineRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        mineRow.addView(Ui.tv(this, "我的动态", 15, Ui.TEXT, true));
        View mineSpacer = new View(this);
        mineRow.addView(mineSpacer, new LinearLayout.LayoutParams(0, 1, 1));
        mineRow.addView(Ui.tv(this, "查看 ›", 12, Ui.MUTED, false));
        mine.addView(mineRow);
        mine.addView(Ui.tv(this, "查看自己发布的全部动态", 11.5f, Ui.MUTED2, false));
        mine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MyActivity.this, UserMomentsActivity.class);
                intent.putExtra("user_id", Session.userId());
                intent.putExtra("name", Session.nickname());
                startActivity(intent);
            }
        });
        root.addView(mine);

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        if (avatarBox == null) return;
        avatarBox.removeAllViews();
        int avatarSize = Ui.hasFrame() ? 92 : 76;
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, avatarSize), Ui.dp(this, avatarSize));
        avatarBox.setLayoutParams(alp);
        avatarBox.addView(Ui.avatarCircleFramed(this, Session.avatar(), avatarSize),
                new FrameLayout.LayoutParams(Ui.dp(this, avatarSize), Ui.dp(this, avatarSize)));
        nameView.setText(Session.nickname());
        String gender = Session.gender();
        if ("male".equals(gender)) genderView.setText("男");
        else if ("female".equals(gender)) genderView.setText("女");
        else genderView.setText("保密");

        String bio = Session.bio();
        bioView.setText(bio.isEmpty() ? "还没有个性签名，去设置里写一句吧" : bio);
        bioView.setTextColor(bio.isEmpty() ? Ui.MUTED2 : Ui.TEXT);

        tagBox.removeAllViews();
        JSONArray tags = null;
        try {
            tags = new JSONArray(Session.tagsJson());
        } catch (Exception ignored) {
        }
        if (tags == null || tags.length() == 0) {
            tagBox.addView(Ui.tv(this, "还没有标签", 12, Ui.MUTED2, false));
        } else {
            for (int i = 0; i < tags.length(); i++) {
                String tag = tags.optString(i, "").trim();
                if (tag.isEmpty()) continue;
                TextView chip = Ui.badge(this, tag, Ui.ACCENT);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(this, 6);
                chip.setLayoutParams(lp);
                tagBox.addView(chip);
            }
        }
    }
}
