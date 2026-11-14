package com.nekocyte.companion;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 商店：主题、字体、气泡、头像框和表情包。 */
public class StoreActivity extends BaseActivity {
    private static final String[] KINDS = {"theme", "font", "bubble", "avatar_frame", "sticker_pack"};
    private static final String[] LABELS = {"主题", "字体", "气泡", "头像框", "表情包"};

    private LinearLayout content;
    private LinearLayout tabRow;
    private TextView coinView;
    private JSONArray items = new JSONArray();
    private String kind = "theme";

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
        TextView title = Ui.tv(this, "商店", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tlp);
        bar.addView(title);
        coinView = Ui.tv(this, "金币 0", 12, Ui.MUTED, false);
        bar.addView(coinView);
        if (Session.isAdmin()) {
            TextView frames = Ui.button(this, "头像框", false);
            frames.setTextSize(11.5f);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.leftMargin = Ui.dp(this, 8);
            frames.setLayoutParams(flp);
            frames.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(StoreActivity.this, AvatarFrameAdminActivity.class));
                }
            });
            bar.addView(frames);
            TextView admin = Ui.button(this, "表情包管理", false);
            admin.setTextSize(11.5f);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = Ui.dp(this, 8);
            admin.setLayoutParams(alp);
            admin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(StoreActivity.this, StickerAdminActivity.class));
                }
            });
            bar.addView(admin);
        }
        TextView mine = Ui.button(this, "我的", false);
        mine.setTextSize(11.5f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = Ui.dp(this, 8);
        mine.setLayoutParams(mlp);
        mine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(StoreActivity.this, MyStoreActivity.class));
            }
        });
        bar.addView(mine);
        root.addView(bar);

        tabRow = Ui.row(this);
        tabRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        for (int i = 0; i < KINDS.length; i++) {
            final int index = i;
            TextView tab = Ui.tv(this, LABELS[i], 12.5f, i == 0 ? Ui.ACCENT : Ui.MUTED, i == 0);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    kind = KINDS[index];
                    for (int j = 0; j < tabRow.getChildCount(); j++) {
                        TextView t = (TextView) tabRow.getChildAt(j);
                        t.setTextColor(j == index ? Ui.ACCENT : Ui.MUTED);
                        t.setTypeface(null, j == index ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    }
                    render();
                }
            });
            tabRow.addView(tab);
        }
        root.addView(tabRow);

        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        int pad = Ui.dp(this, 12);
        content.setPadding(pad, pad, pad, Ui.dp(this, 28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        content.removeAllViews();
        content.addView(Ui.tv(this, "正在加载商店…", 12.5f, Ui.MUTED2, false));
        Api.storeItems(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                items = data.optJSONArray("items");
                if (items == null) items = new JSONArray();
                coinView.setText("金币 " + data.optInt("coins", 0));
                render();
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(StoreActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render() {
        content.removeAllViews();
        boolean any = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !kind.equals(item.optString("kind"))) continue;
            any = true;
            renderItem(item);
        }
        if (!any) {
            content.addView(Ui.tv(this, "这个分类还没有商品。", 13, Ui.MUTED, false));
        }
    }

    private void renderItem(final JSONObject item) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);

        LinearLayout head = Ui.row(this);
        String cover = item.optString("cover_url", "");
        if (cover.startsWith("http")) {
            StickerImageView image = new StickerImageView(this);
            image.load(cover);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
            image.setLayoutParams(ilp);
            head.addView(image);
        } else {
            TextView icon = Ui.tv(this, cover.isEmpty() ? "✦" : cover, 25, Ui.ACCENT, false);
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
            icon.setLayoutParams(ilp);
            head.addView(icon);
        }
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.leftMargin = Ui.dp(this, 10);
        info.setLayoutParams(infoLp);
        info.addView(Ui.tv(this, item.optString("name", "商品"), 15, Ui.TEXT, true));
        info.addView(Ui.tv(this, item.optString("description", ""), 11.5f, Ui.MUTED, false));
        head.addView(info);
        card.addView(head);

        final boolean owned = item.optBoolean("owned", false);
        int price = item.optInt("price", 0);
        TextView action = Ui.button(this, owned ? "使用" : "购买  ·  " + price + " 金币", !owned);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 10);
        action.setLayoutParams(alp);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (owned) apply(item);
                else buy(item);
            }
        });
        card.addView(action);
        content.addView(card);
    }

    private void buy(final JSONObject item) {
        Api.purchaseStoreItem(item.optInt("id"), new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                Ui.toast(StoreActivity.this, "已购买");
                load();
            }

            @Override
            public void fail(String message) {
                Ui.toast(StoreActivity.this, message);
            }
        });
    }

    private void apply(JSONObject item) {
        String k = item.optString("kind");
        JSONObject data = item.optJSONObject("data");
        if (data == null) data = new JSONObject();
        if ("theme".equals(k)) Session.setStoreTheme(data.toString());
        else if ("font".equals(k)) Session.setStoreFont(data.toString());
        else if ("bubble".equals(k)) Session.setStoreBubble(data.toString());
        else if ("avatar_frame".equals(k)) {
            Session.setStoreFrame(data.toString());
            syncFrame(data);
        }
        else if ("sticker_pack".equals(k)) {
            Ui.toast(this, "已添加到表情面板");
            return;
        }
        Ui.init(this);
        Ui.toast(this, "已应用，正在刷新");
        recreate();
    }

    private void syncFrame(JSONObject data) {
        try {
            JSONObject body = new JSONObject();
            body.put("avatar_frame", data == null ? "{}" : data.toString());
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
    }
}
