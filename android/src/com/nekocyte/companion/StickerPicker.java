package com.nekocyte.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 表情包搜索与常用表情包面板。 */
public class StickerPicker {
    public interface Listener {
        void onSticker(String url, String words);
    }

    private final Activity activity;
    private final Listener listener;
    private AlertDialog dialog;
    private LinearLayout results;
    private TextView status;
    private EditText search;

    public StickerPicker(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show() {
        LinearLayout root = Ui.column(activity);
        int pad = Ui.dp(activity, 14);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout row = Ui.row(activity);
        search = new EditText(activity);
        search.setHint("搜索表情，例如 大笑 / 无语 / 猫");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED2);
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
        search.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1));
        row.addView(search);
        TextView find = Ui.button(activity, "搜索", true);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 44));
        flp.leftMargin = Ui.dp(activity, 8);
        find.setLayoutParams(flp);
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
        row.addView(find);
        root.addView(row);

        TextView favTitle = Ui.tv(activity, "常用表情包（长按聊天里的表情可收藏）", 12, Ui.MUTED, true);
        favTitle.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
        root.addView(favTitle);
        LinearLayout favorites = Ui.column(activity);
        root.addView(favorites);
        renderFavorites(favorites);

        status = Ui.tv(activity, "输入关键词搜索，留空随机来一组", 11.5f, Ui.MUTED2, false);
        status.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 4));
        root.addView(status);
        results = Ui.column(activity);
        root.addView(results);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        dialog = new AlertDialog.Builder(activity)
                .setTitle("表情包")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void doSearch() {
        final String keyword = search.getText().toString().trim();
        status.setText("正在搜索…");
        results.removeAllViews();
        Api.searchStickers(keyword, 1, 20, new Api.Cb() {
            @Override
            public void ok(JSONObject data) {
                status.setText(keyword.isEmpty() ? "随机表情" : "「" + keyword + "」的搜索结果");
                renderResults(data.optJSONArray("res"), data.optString("words", keyword.isEmpty() ? "随机表情" : keyword));
            }

            @Override
            public void fail(String message) {
                status.setText(message);
            }
        });
    }

    private void renderFavorites(LinearLayout box) {
        box.removeAllViews();
        JSONArray arr = Session.favorites();
        if (arr.length() == 0) {
            box.addView(Ui.tv(activity, "还没有收藏，长按别人或自己发的表情包即可保存", 11.5f, Ui.MUTED2, false));
            return;
        }
        addGrid(box, arr, false);
    }

    private void renderResults(JSONArray arr, String words) {
        results.removeAllViews();
        if (arr == null || arr.length() == 0) {
            results.addView(Ui.tv(activity, "没有搜到表情包", 12, Ui.MUTED, false));
            return;
        }
        JSONArray items = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", arr.optString(i));
                o.put("words", words);
                items.put(o);
            } catch (Exception ignored) {
            }
        }
        addGrid(results, items, true);
    }

    private void addGrid(LinearLayout parent, JSONArray items, final boolean searched) {
        LinearLayout row = null;
        for (int i = 0; i < items.length(); i++) {
            if (i % 4 == 0) {
                row = Ui.row(activity);
                row.setPadding(0, Ui.dp(activity, 3), 0, Ui.dp(activity, 3));
                parent.addView(row);
            }
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            final String url = item.optString("url", "");
            final String words = item.optString("words", "表情");
            StickerImageView sticker = new StickerImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(activity, 78), Ui.dp(activity, 78));
            lp.rightMargin = Ui.dp(activity, 6);
            sticker.setLayoutParams(lp);
            sticker.load(url);
            sticker.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dialog != null) dialog.dismiss();
                    listener.onSticker(url, words);
                }
            });
            sticker.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Session.addFavorite(url, words);
                    Ui.toast(activity, "已收藏到常用表情包");
                    return true;
                }
            });
            row.addView(sticker);
        }
    }
}
