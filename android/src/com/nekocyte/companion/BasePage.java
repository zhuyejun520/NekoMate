package com.nekocyte.companion;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public abstract class BasePage extends ScrollView {
    protected final MainActivity host;
    protected final LinearLayout box;

    public BasePage(MainActivity host) {
        super(host);
        this.host = host;
        setBackgroundColor(Ui.themeActive() ? 0x00000000 : Ui.BG);
        setFillViewport(true);
        box = Ui.column(host);
        box.setPadding(Ui.dp(host, 16), Ui.dp(host, 10), Ui.dp(host, 16), Ui.dp(host, 30));
        addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    public abstract void refresh();

    public void onShow() {
    }

    public void onHide() {
    }

    protected void clear() {
        box.removeAllViews();
    }

    protected void section(String title, String sub) {
        LinearLayout row = Ui.row(host);
        row.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 8));
        TextView t = Ui.tv(host, title, 15, Ui.TEXT, true);
        row.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.tv(host, "  " + sub, 11.5f, Ui.MUTED2, false);
            row.addView(s);
        }
        box.addView(row);
    }

    protected void hint(String text) {
        LinearLayout card = Ui.card(host);
        TextView t = Ui.tv(host, text, 13, Ui.MUTED, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(host, 18), 0, Ui.dp(host, 18));
        card.addView(t);
        box.addView(card, marginBottom());
    }

    protected void loading(String text) {
        TextView t = Ui.tv(host, text, 12.5f, Ui.MUTED2, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(host, 24), 0, Ui.dp(host, 24));
        box.addView(t);
    }

    protected LinearLayout.LayoutParams marginBottom() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(host, 10);
        return lp;
    }

    protected LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    protected Context ctx() {
        return host;
    }
}
