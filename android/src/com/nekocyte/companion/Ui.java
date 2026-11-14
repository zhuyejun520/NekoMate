package com.nekocyte.companion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

public class Ui {
    public static int BG = 0xFFF5F4FA;
    public static int PANEL = 0xFFFFFFFF;
    public static int PANEL2 = 0xFFF0EDF9;
    public static int TEXT = 0xFF1D1A2B;
    public static int MUTED = 0xFF6C6784;
    public static int MUTED2 = 0xFF9892AC;
    public static int ACCENT = 0xFF7A5CFF;
    public static int ACCENT2 = 0xFFE85D9E;
    public static int GREEN = 0xFF12996B;
    public static int RED = 0xFFD64560;
    public static int BORDER = 0xFFE4E0F0;
    private static Typeface customTypeface;

    public static void init(Context c) {
        BG = c.getColor(R.color.bg);
        PANEL = c.getColor(R.color.panel);
        PANEL2 = c.getColor(R.color.panel2);
        TEXT = c.getColor(R.color.text);
        MUTED = c.getColor(R.color.muted);
        MUTED2 = c.getColor(R.color.muted2);
        ACCENT = c.getColor(R.color.accent);
        ACCENT2 = c.getColor(R.color.accent2);
        GREEN = c.getColor(R.color.green);
        RED = c.getColor(R.color.red);
        BORDER = c.getColor(R.color.border);
        applyStoreTheme();
        applyStoreFont();
    }

    private static int parseStoreColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void applyStoreTheme() {
        String raw = Session.storeTheme();
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject t = new JSONObject(raw);
            BG = parseStoreColor(t.optString("bg", ""), BG);
            PANEL = parseStoreColor(t.optString("panel", ""), PANEL);
            PANEL2 = parseStoreColor(t.optString("panel2", ""), PANEL2);
            TEXT = parseStoreColor(t.optString("text", ""), TEXT);
            MUTED = parseStoreColor(t.optString("muted", ""), MUTED);
            MUTED2 = parseStoreColor(t.optString("muted2", ""), MUTED2);
            ACCENT = parseStoreColor(t.optString("accent", ""), ACCENT);
            ACCENT2 = parseStoreColor(t.optString("accent2", ""), ACCENT2);
            BORDER = parseStoreColor(t.optString("border", ""), BORDER);
        } catch (Exception ignored) {
        }
    }

    private static void applyStoreFont() {
        String raw = Session.storeFont();
        String style = "sans";
        if (raw != null && !raw.isEmpty()) {
            try {
                style = new JSONObject(raw).optString("font", "sans");
            } catch (Exception ignored) {
            }
        }
        if ("serif".equals(style)) customTypeface = Typeface.SERIF;
        else if ("mono".equals(style)) customTypeface = Typeface.MONOSPACE;
        else customTypeface = Typeface.SANS_SERIF;
    }

    public static GradientDrawable bubble(boolean mine) {
        int fill = mine ? ACCENT : PANEL;
        int radius = 18;
        String raw = Session.storeBubble();
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONObject b = new JSONObject(raw);
                fill = parseStoreColor(b.optString(mine ? "mine" : "other", ""), fill);
                radius = b.optInt("radius", radius);
            } catch (Exception ignored) {
            }
        }
        GradientDrawable d = round(fill, radius);
        return d;
    }

    private static int frameColor() {
        return frameColor(Session.storeFrame());
    }

    private static int frameColor(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            String value = new JSONObject(raw).optString("color", "");
            return value.isEmpty() ? 0 : Color.parseColor(value);
        } catch (Exception e) {
            return 0;
        }
    }

    public static String frameImage() {
        return frameImage(Session.storeFrame());
    }

    private static String frameImage(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            String value = new JSONObject(raw).optString("image", "");
            return value == null || "null".equals(value) ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasImageFrame() {
        return !frameImage().isEmpty();
    }

    public static boolean hasFrame() {
        return frameColor() != 0 || !frameImage().isEmpty();
    }

    public static int bubbleText(boolean mine) {
        int fallback = mine ? Color.WHITE : TEXT;
        String raw = Session.storeBubble();
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            JSONObject b = new JSONObject(raw);
            return parseStoreColor(b.optString(mine ? "text_mine" : "text_other", ""), fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean themeActive() {
        String raw = Session.storeTheme();
        return raw != null && !raw.isEmpty();
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * (App.I == null ? 2 : App.I.getResources().getDisplayMetrics().density));
        return d;
    }

    public static GradientDrawable roundStroke(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable d = round(fill, radiusDp);
        d.setStroke(dp(App.I, strokeDp), stroke);
        return d;
    }

    public static GradientDrawable gradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, 0xFFB16CFF, ACCENT2});
        d.setCornerRadius(dp(App.I, 12));
        return d;
    }

    public static TextView tv(Context c, CharSequence text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (customTypeface != null) t.setTypeface(customTypeface, bold ? Typeface.BOLD : Typeface.NORMAL);
        else if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(0f, 1.18f);
        return t;
    }

    public static TextView label(Context c, String text) {
        return tv(c, text, 12, MUTED, false);
    }

    public static TextView button(Context c, String text, boolean primary) {
        TextView t = tv(c, text, 13.5f, primary ? Color.WHITE : TEXT, primary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setBackground(primary ? gradient() : roundStroke(PANEL2, BORDER, 12, 1));
        t.setClickable(true);
        return t;
    }

    public static TextView badge(Context c, String text, int color) {
        TextView t = tv(c, text, 11, color, false);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setBackground(round(0x22111111 | (color & 0x00FFFFFF), 8));
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(roundStroke(PANEL, BORDER, 16, 1));
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        return l;
    }

    public static void margin(View v, int l, int t, int r, int b) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.setMargins(dp(App.I, l), dp(App.I, t), dp(App.I, r), dp(App.I, b));
        v.setLayoutParams(lp);
    }

    public static void params(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(w, h);
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    public static View avatar(Context c, String value, int sizeDp) {
        return avatarView(c, value, sizeDp, false, false);
    }

    public static View avatarFramed(Context c, String value, int sizeDp) {
        return avatarView(c, value, sizeDp, false, true);
    }

    public static View avatarCircle(Context c, String value, int sizeDp) {
        return avatarView(c, value, sizeDp, true, false);
    }

    public static View avatarCircleFramed(Context c, String value, int sizeDp) {
        return avatarCircleFramed(c, value, Session.storeFrame(), sizeDp);
    }

    public static View avatarCircleFramed(Context c, String value, String frameJson, int sizeDp) {
        int frame = frameColor(frameJson);
        String frameUrl = frameImage(frameJson);
        if (frame == 0 && frameUrl.isEmpty()) return avatarCircle(c, value, sizeDp);
        FrameLayout box = new FrameLayout(c);
        int size = dp(c, sizeDp);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        int innerSize = frameUrl.isEmpty() ? size : Math.round(size * 0.80f);
        View inner = avatarView(c, value,
                frameUrl.isEmpty() ? sizeDp : Math.max(1, Math.round(sizeDp * 0.80f)), true, false);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(innerSize, innerSize);
        innerLp.gravity = Gravity.CENTER;
        box.addView(inner, innerLp);
        if (!frameUrl.isEmpty()) {
            ImageView overlay = new ImageView(c);
            overlay.setScaleType(ImageView.ScaleType.FIT_XY);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            AvatarCache.load(overlay, frameUrl);
            box.addView(overlay, new FrameLayout.LayoutParams(size, size));
        }
        if (frame != 0) {
            View ring = new View(c);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0x00000000);
            shape.setStroke(dp(c, 3), frame);
            ring.setBackground(shape);
            ring.setClickable(false);
            ring.setFocusable(false);
            box.addView(ring, new FrameLayout.LayoutParams(size, size));
        }
        return box;
    }

    private static View avatarView(Context c, String value, int sizeDp, boolean circle, boolean framed) {
        String text = value == null ? "" : value.trim();
        if (text.equalsIgnoreCase("null") || text.equalsIgnoreCase("undefined") || text.equalsIgnoreCase("none")) {
            text = "";
        }
        int frame = framed ? frameColor() : 0;
        if (text.startsWith("http") || text.startsWith("/")) {
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription("头像");
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{ACCENT, ACCENT2});
            bg.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(c, circle ? sizeDp : 14));
            if (frame != 0) bg.setStroke(dp(c, 3), frame);
            image.setBackground(bg);
            image.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
            image.setLayoutParams(lp);
            AvatarCache.load(image, text);
            return image;
        }
        if (text.isEmpty()) text = "🙂";
        TextView t = tv(c, text, sizeDp * 0.42f, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT, ACCENT2});
        g.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, circle ? sizeDp : 14));
        if (frame != 0) g.setStroke(dp(c, 3), frame);
        t.setBackground(g);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        t.setLayoutParams(lp);
        return t;
    }

    public static View smallAvatar(Context c, String value, int sizeDp) {
        return avatar(c, value, sizeDp);
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    public static void toast(Context c, String msg) {
        if (c != null && msg != null) {
            Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
        }
    }

    public static void hideKeyboard(Activity a) {
        try {
            InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null && a.getCurrentFocus() != null) {
                im.hideSoftInputFromWindow(a.getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    public static String time(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            OffsetDateTime odt = OffsetDateTime.parse(iso);
            Date d = Date.from(odt.toInstant());
            Date now = new Date();
            SimpleDateFormat same = new SimpleDateFormat("HH:mm", Locale.CHINA);
            SimpleDateFormat other = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            SimpleDateFormat y = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(d);
            String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(now);
            if (day.equals(today)) return same.format(d);
            String year = new SimpleDateFormat("yyyy", Locale.CHINA).format(d);
            String nowYear = new SimpleDateFormat("yyyy", Locale.CHINA).format(now);
            if (year.equals(nowYear)) return other.format(d);
            return y.format(d);
        } catch (Exception e) {
            return iso.length() > 16 ? iso.substring(5, 16).replace('T', ' ') : iso;
        }
    }

    public static String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
    }

    public static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase(Locale.CHINA);
    }

    public static int color(String hex, int fallback) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String str(JSONObject o, String key, String def) {
        if (o == null || o.isNull(key)) return def;
        String v = o.optString(key, def);
        return v == null || "null".equals(v) ? def : v;
    }
}
