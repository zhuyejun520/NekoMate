package com.nekocyte.companion;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 商店-我的：按分类展示已拥有的主题、字体、气泡、头像框和表情包。 */
public class MyStoreActivity extends BaseActivity {
    private static final String[] KINDS = {"theme", "font", "bubble", "avatar_frame", "sticker_pack"};
    private static final String[] LABELS = {"主题", "字体", "气泡", "头像框", "表情包"};

    private LinearLayout content;
    private JSONArray items = new JSONArray();
    private JSONArray stickerPacks = new JSONArray();

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
        TextView title = Ui.tv(this, "我的", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(this, 10);
        title.setLayoutParams(tlp);
        bar.addView(title);
        root.addView(bar);

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
        content.addView(Ui.tv(this, "正在加载…", 12.5f, Ui.MUTED2, false));
        Api.storeInventory(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                items = data.optJSONArray("items");
                if (items == null) items = new JSONArray();
                stickerPacks = data.optJSONArray("sticker_packs");
                if (stickerPacks == null) stickerPacks = new JSONArray();
                render();
            }

            @Override
            public void fail(String message) {
                content.removeAllViews();
                content.addView(Ui.tv(MyStoreActivity.this, message, 13, Ui.RED, false));
            }
        });
    }

    private void render() {
        content.removeAllViews();
        for (int k = 0; k < KINDS.length; k++) {
            String kind = KINDS[k];
            boolean any = false;
            LinearLayout section = Ui.column(this);
            section.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
            section.addView(Ui.tv(this, LABELS[k], 16, Ui.TEXT, true));
            if ("sticker_pack".equals(kind)) {
                for (int i = 0; i < stickerPacks.length(); i++) {
                    JSONObject pack = stickerPacks.optJSONObject(i);
                    if (pack == null) continue;
                    renderPack(section, pack);
                    any = true;
                }
            } else {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || !kind.equals(item.optString("kind"))) continue;
                    renderItem(section, item);
                    any = true;
                }
            }
            if (!any) {
                section.addView(Ui.tv(this, "暂无", 12, Ui.MUTED2, false));
            }
            content.addView(section);
        }
    }

    private void renderItem(LinearLayout parent, final JSONObject item) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        LinearLayout row = Ui.row(this);
        addCover(row, item.optString("cover_url", "✦"));
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ilp.leftMargin = Ui.dp(this, 10);
        info.setLayoutParams(ilp);
        info.addView(Ui.tv(this, item.optString("name", "物品"), 14.5f, Ui.TEXT, true));
        info.addView(Ui.tv(this, item.optString("description", ""), 11.5f, Ui.MUTED, false));
        row.addView(info);
        TextView use = Ui.button(this, "使用", false);
        use.setTextSize(11.5f);
        use.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply(item);
            }
        });
        row.addView(use);
        card.addView(row);
        parent.addView(card);
    }

    private void renderPack(LinearLayout parent, JSONObject pack) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(lp);
        LinearLayout row = Ui.row(this);
        addCover(row, pack.optString("logo", "✦"));
        LinearLayout info = Ui.column(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ilp.leftMargin = Ui.dp(this, 10);
        info.setLayoutParams(ilp);
        info.addView(Ui.tv(this, pack.optString("name", "表情包"), 14.5f, Ui.TEXT, true));
        int count = pack.optJSONArray("items") == null ? 0 : pack.optJSONArray("items").length();
        info.addView(Ui.tv(this, "已获取 · " + count + " 张", 11.5f, Ui.MUTED, false));
        row.addView(info);
        card.addView(row);
        parent.addView(card);
    }

    private void addCover(LinearLayout row, String cover) {
        if (cover.startsWith("http")) {
            StickerImageView image = new StickerImageView(this);
            image.load(cover);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
            image.setLayoutParams(ilp);
            row.addView(image);
        } else {
            TextView icon = Ui.tv(this, cover.isEmpty() ? "✦" : cover, 24, Ui.ACCENT, false);
            icon.setGravity(Gravity.CENTER);
            icon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
            row.addView(icon);
        }
    }

    private void apply(JSONObject item) {
        String kind = item.optString("kind");
        JSONObject data = item.optJSONObject("data");
        if (data == null) data = new JSONObject();
        if ("theme".equals(kind)) Session.setStoreTheme(data.toString());
        else if ("font".equals(kind)) Session.setStoreFont(data.toString());
        else if ("bubble".equals(kind)) Session.setStoreBubble(data.toString());
        else if ("avatar_frame".equals(kind)) {
            Session.setStoreFrame(data.toString());
            try {
                JSONObject body = new JSONObject();
                body.put("avatar_frame", data.toString());
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
        else return;
        Ui.init(this);
        Ui.toast(this, "已应用，正在刷新");
        recreate();
    }
}
