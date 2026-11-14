package com.nekocyte.companion;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 聊天底部表情面板：搜索、黄脸 emoji、常用图片表情三个无文字标签页。 */
public class EmojiPanel extends LinearLayout {
    public interface Listener {
        void onEmoji(String emoji);
        void onSticker(String url, String words);
    }

    private static final String[] EMOJIS = {
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉",
            "😍", "🥰", "😘", "😗", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭",
            "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨",
            "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🤧",
            "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥺", "😢", "😭", "😤", "😠",
            "😡", "🤬", "😳", "🥹", "😱", "😨", "😰", "😥", "😓", "🫶", "👍", "👎",
            "👏", "🙌", "🙏", "💪", "🤝", "❤️", "💔", "💖", "✨", "🔥", "🎉", "🌙",
            "☀️", "🌸", "🍀", "🐱", "🐶", "🐼", "🍰", "🍜", "☕", "🎵", "😺", "😸"
    };

    private static final String[] KEYWORDS = {
            "开心 笑 笑脸", "开心 笑", "大笑 开心", "笑 嘻嘻", "大笑 露齿", "汗 笑 尴尬", "笑哭 大笑", "笑 打滚",
            "微笑 温柔", "天使 善良", "微笑", "眨眼 调皮", "爱心眼 喜欢", "喜欢 爱心", "亲亲 爱你", "亲吻",
            "好吃 馋", "吐舌 调皮", "眨眼 坏笑", "疯狂 搞笑", "吐舌 坏笑", "钱 发财", "抱抱 拥抱", "偷笑 捂嘴",
            "嘘 安静", "思考 疑问", "闭嘴 沉默", "挑眉 疑惑", "无语 平静", "无语 无奈", "沉默", "得意 坏笑",
            "不屑 无语", "翻白眼 无语", "尴尬 龇牙", "叹气 无奈", "说谎 匹诺曹", "放松 微笑", "难过 失落",
            "困 睡觉", "流口水 馋", "睡觉 晚安", "生病 口罩", "发烧 生病", "受伤 疼", "恶心", "呕吐",
            "打喷嚏 感冒", "热 流汗", "冷 发抖", "晕 醉", "晕 震惊", "爆炸 震惊", "牛仔 得意", "庆祝 派对",
            "委屈 可怜", "哭 伤心", "大哭 伤心", "生气 哼", "生气 愤怒", "愤怒 生气", "骂 愤怒", "脸红 害羞",
            "感动 快哭", "害怕 惊恐", "害怕", "冷汗 紧张", "担心 伤心", "流汗 紧张", "比心 爱你", "赞 点赞",
            "踩 不赞同", "鼓掌 棒", "举手 开心", "感谢 拜托", "加油 努力", "握手 合作", "爱心 喜欢", "心碎 失恋",
            "爱心 喜欢", "闪亮 开心", "火 热门", "庆祝 礼花", "月亮 晚安", "太阳 早安", "花 喜欢", "四叶草 幸运",
            "猫 可爱", "狗 可爱", "熊猫 可爱", "蛋糕 甜", "面 吃饭", "咖啡 喝", "音乐 唱歌", "猫 开心", "猫 笑"
    };

    private final Activity activity;
    private final Listener listener;
    private final LineIconView[] tabs = new LineIconView[3];
    private final ScrollView scroll;
    private final LinearLayout content;
    private final HorizontalScrollView packScroll;
    private final LinearLayout packRow;
    private JSONArray packs = new JSONArray();
    private int activePack = 0;
    private int current = 1;

    public EmojiPanel(Activity activity, Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.themeActive() ? 0x00000000 : Ui.BG);

        LinearLayout top = Ui.row(activity);
        top.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        top.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8), Ui.dp(activity, 4));
        String[] types = {"search", "emoji", "heart"};
        for (int i = 0; i < types.length; i++) {
            final int index = i;
            LineIconView icon = new LineIconView(activity);
            icon.setType(types[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 38), Ui.dp(activity, 38));
            lp.rightMargin = Ui.dp(activity, 4);
            icon.setLayoutParams(lp);
            icon.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    activePack = 0;
                    showTab(index);
                }
            });
            tabs[i] = icon;
            top.addView(icon);
        }
        addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44)));

        packScroll = new HorizontalScrollView(activity);
        packScroll.setHorizontalScrollBarEnabled(false);
        packScroll.setVisibility(GONE);
        packRow = Ui.row(activity);
        packRow.setPadding(Ui.dp(activity, 2), 0, Ui.dp(activity, 8), 0);
        packScroll.addView(packRow);
        top.addView(packScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        content = Ui.column(activity);
        content.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 2), Ui.dp(activity, 8), Ui.dp(activity, 12));
        scroll.addView(content);
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        showTab(1);
        loadPacks();
    }

    public void openDefault() {
        showTab(1);
    }

    private void loadPacks() {
        Api.storeInventory(new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                packs = data.optJSONArray("sticker_packs");
                if (packs == null) packs = new JSONArray();
                renderPackBar();
            }

            @Override
            public void fail(String message) {
                packScroll.setVisibility(GONE);
            }
        });
    }

    private void renderPackBar() {
        packRow.removeAllViews();
        if (packs.length() == 0) {
            packScroll.setVisibility(GONE);
            return;
        }
        packScroll.setVisibility(VISIBLE);
        for (int i = 0; i < packs.length(); i++) {
            final int index = i + 1;
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) continue;
            String logo = pack.optString("logo", "📦");
            View icon = Ui.avatar(activity, logo, 36);
            icon.setBackground(activePack == index ? Ui.round(0x337A5CFF, 10) : null);
            icon.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 2),
                    Ui.dp(activity, 2), Ui.dp(activity, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 40), Ui.dp(activity, 40));
            lp.rightMargin = Ui.dp(activity, 6);
            icon.setLayoutParams(lp);
            icon.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    activePack = index;
                    showTab(1);
                }
            });
            packRow.addView(icon);
        }
    }

    private void showTab(int index) {
        current = index;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setIconColor(i == current ? Ui.ACCENT : Ui.MUTED);
            tabs[i].setBackground(i == current ? Ui.round(0x167A5CFF, 9) : null);
        }
        content.removeAllViews();
        if (index == 0) showSearch();
        else if (index == 2) showFavorites();
        else showEmoji();
        renderPackBar();
    }

    private void showEmoji() {
        content.removeAllViews();
        if (activePack > packs.length()) activePack = 0;
        if (activePack > 0 && packs.length() >= activePack) {
            JSONObject pack = packs.optJSONObject(activePack - 1);
            JSONArray items = pack == null ? null : pack.optJSONArray("items");
            if (items != null && items.length() > 0) {
                content.addView(Ui.tv(activity, pack.optString("name", "表情包"),
                        11.5f, Ui.MUTED, true));
                addStickerGrid(content, items);
                return;
            }
        }
        addEmojiGrid(content, EMOJIS, KEYWORDS);
    }

    private void addEmojiGrid(LinearLayout parent, String[] emojis, String[] keywords) {
        LinearLayout row = null;
        int visible = 0;
        for (int i = 0; i < emojis.length; i++) {
            if (visible % 7 == 0) {
                row = Ui.row(activity);
                row.setGravity(Gravity.CENTER);
                row.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
                parent.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String emoji = emojis[i];
            TextView cell = new TextView(activity);
            cell.setText(emoji);
            cell.setTextSize(24);
            cell.setGravity(Gravity.CENTER);
            cell.setTextColor(Color.BLACK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Ui.dp(activity, 38), 1f);
            cell.setLayoutParams(lp);
            cell.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onEmoji(emoji);
                }
            });
            if (row != null) row.addView(cell);
            visible++;
        }
    }

    private void showFavorites() {
        content.removeAllViews();
        JSONArray arr = Session.favorites();
        if (arr.length() == 0) {
            TextView empty = Ui.tv(activity, "还没有常用图片表情\n长按聊天里的表情包即可收藏", 12, Ui.MUTED2, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(activity, 24), 0, Ui.dp(activity, 24));
            content.addView(empty);
            return;
        }
        addStickerGrid(content, arr);
    }

    private void addStickerGrid(LinearLayout parent, JSONArray arr) {
        LinearLayout row = null;
        for (int i = 0; i < arr.length(); i++) {
            if (i % 4 == 0) {
                row = Ui.row(activity);
                row.setGravity(Gravity.CENTER);
                row.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
                parent.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            final String url = item.optString("url", "");
            final String words = item.optString("words", "表情");
            FrameLayout cell = new FrameLayout(activity);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(activity, 80), 1f));
            StickerImageView sticker = new StickerImageView(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Ui.dp(activity, 72), Ui.dp(activity, 72), Gravity.CENTER);
            sticker.setLayoutParams(lp);
            sticker.load(url);
            sticker.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onSticker(url, words);
                }
            });
            cell.addView(sticker);
            if (row != null) row.addView(cell);
        }
    }

    private void showSearch() {
        content.removeAllViews();
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText search = new EditText(activity);
        search.setHint("搜索表情或贴纸");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED2);
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 38), 1);
        row.addView(search, inputLp);
        LineIconView find = new LineIconView(activity);
        find.setType("search");
        find.setIconColor(Ui.ACCENT);
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(
                Ui.dp(activity, 38), Ui.dp(activity, 38));
        findLp.leftMargin = Ui.dp(activity, 6);
        row.addView(find, findLp);
        content.addView(row);
        final LinearLayout results = Ui.column(activity);
        content.addView(results);
        View.OnClickListener action = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(search.getText().toString().trim(), results);
            }
        };
        find.setOnClickListener(action);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(v.getText().toString().trim(), results);
                    return true;
                }
                return false;
            }
        });
        TextView hint = Ui.tv(activity, "支持黄脸 emoji 和已收藏的图片表情", 11.5f, Ui.MUTED2, false);
        hint.setPadding(0, Ui.dp(activity, 8), 0, 0);
        results.addView(hint);
    }

    private void doSearch(String query, LinearLayout results) {
        results.removeAllViews();
        if (query.isEmpty()) {
            results.addView(Ui.tv(activity, "输入关键词搜索表情", 12, Ui.MUTED2, false));
            return;
        }
        String q = query.toLowerCase();
        String[] matched = new String[EMOJIS.length];
        String[] matchedKeys = new String[EMOJIS.length];
        int count = 0;
        for (int i = 0; i < EMOJIS.length; i++) {
            if (KEYWORDS[i].toLowerCase().contains(q) || EMOJIS[i].contains(query)) {
                matched[count] = EMOJIS[i];
                matchedKeys[count] = KEYWORDS[i];
                count++;
            }
        }
        if (count > 0) {
            String[] emojis = new String[count];
            String[] keys = new String[count];
            System.arraycopy(matched, 0, emojis, 0, count);
            System.arraycopy(matchedKeys, 0, keys, 0, count);
            results.addView(Ui.tv(activity, "表情", 11.5f, Ui.MUTED, true));
            addEmojiGrid(results, emojis, keys);
        }
        Api.searchStickers(query, 1, 20, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                JSONArray arr = data.optJSONArray("res");
                if (arr == null || arr.length() == 0) return;
                JSONArray items = new JSONArray();
                String words = data.optString("words", query);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        JSONObject item = new JSONObject();
                        item.put("url", arr.optString(i));
                        item.put("words", words);
                        items.put(item);
                    } catch (Exception ignored) {
                    }
                }
                results.addView(Ui.tv(activity, "图片表情", 11.5f, Ui.MUTED, true));
                addStickerGrid(results, items);
            }

            @Override
            public void fail(String message) {
            }
        });
    }
}
