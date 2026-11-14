package com.nekocyte.companion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.json.JSONObject;

/** Subtle animated aurora background used by store themes. */
public class ThemeBackgroundView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase;

    public ThemeBackgroundView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{Ui.BG, withAlpha(Ui.ACCENT, 42), withAlpha(Ui.ACCENT2, 30), Ui.BG},
                new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        String pattern = pattern();
        float cx = w * (0.22f + 0.12f * (float) Math.sin(phase));
        float cy = h * (0.26f + 0.09f * (float) Math.cos(phase * 0.8f));
        float cx2 = w * (0.78f + 0.10f * (float) Math.cos(phase * 0.7f));
        float cy2 = h * (0.70f + 0.08f * (float) Math.sin(phase * 0.9f));
        if ("orange".equals(pattern)) {
            drawFruit(canvas, cx, cy, Math.min(w, h) * 0.30f, 0xFFF49A2D, 0xFF5C9B45);
            drawFruit(canvas, cx2, cy2, Math.min(w, h) * 0.22f, 0xFFFFB347, 0xFF4F8A3A);
        } else if ("lemon".equals(pattern)) {
            drawFruit(canvas, cx, cy, Math.min(w, h) * 0.29f, 0xFFF7D93B, 0xFF7FA244);
            drawFruit(canvas, cx2, cy2, Math.min(w, h) * 0.20f, 0xFFFFE875, 0xFF8CAF4E);
        } else if ("maple".equals(pattern)) {
            drawLeaf(canvas, cx, cy, Math.min(w, h) * 0.14f, 0xFFC93B32, phase);
            drawLeaf(canvas, cx2, cy2, Math.min(w, h) * 0.11f, 0xFFE25D45, phase + 1.4f);
            drawLeaf(canvas, w * 0.62f, h * 0.18f, Math.min(w, h) * 0.08f, 0xFFB92F32, phase + 2.1f);
        } else if ("sakura".equals(pattern)) {
            drawPetals(canvas, w, h, phase);
        } else {
            paint.setColor(withAlpha(Ui.ACCENT, 45));
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.46f, paint);
            paint.setColor(withAlpha(Ui.ACCENT2, 34));
            canvas.drawCircle(cx2, cy2, Math.min(w, h) * 0.52f, paint);
        }
    }

    private String pattern() {
        try {
            return new JSONObject(Session.storeTheme()).optString("pattern", "aurora");
        } catch (Exception e) {
            return "aurora";
        }
    }

    private void drawFruit(Canvas canvas, float cx, float cy, float r, int color, int leaf) {
        paint.setColor(color);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(0x33FFFFFF);
        canvas.drawCircle(cx, cy, r * 0.82f, paint);
        paint.setColor(0x55FFFFFF);
        paint.setStrokeWidth(dp(1.2f));
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            canvas.drawLine(cx, cy, cx + (float) Math.cos(a) * r * 0.8f,
                    cy + (float) Math.sin(a) * r * 0.8f, paint);
        }
        paint.setColor(leaf);
        RectF leafRect = new RectF(cx + r * 0.2f, cy - r, cx + r * 0.85f, cy - r * 0.55f);
        canvas.drawOval(leafRect, paint);
    }

    private void drawLeaf(Canvas canvas, float cx, float cy, float r, int color, float rotation) {
        canvas.save();
        canvas.rotate((float) Math.toDegrees(Math.sin(rotation) * 0.35), cx, cy);
        Path leaf = new Path();
        leaf.moveTo(cx, cy - r);
        leaf.lineTo(cx + r * 0.35f, cy - r * 0.3f);
        leaf.lineTo(cx + r, cy - r * 0.15f);
        leaf.lineTo(cx + r * 0.42f, cy + r * 0.2f);
        leaf.lineTo(cx + r * 0.55f, cy + r * 0.9f);
        leaf.lineTo(cx, cy + r * 0.45f);
        leaf.lineTo(cx - r * 0.55f, cy + r * 0.9f);
        leaf.lineTo(cx - r * 0.42f, cy + r * 0.2f);
        leaf.lineTo(cx - r, cy - r * 0.15f);
        leaf.lineTo(cx - r * 0.35f, cy - r * 0.3f);
        leaf.close();
        paint.setColor(withAlpha(color, 70));
        canvas.drawPath(leaf, paint);
        canvas.restore();
    }

    private void drawPetals(Canvas canvas, float w, float h, float phase) {
        paint.setColor(0x55F26A9A);
        for (int i = 0; i < 7; i++) {
            float x = w * ((i * 0.17f + phase * 0.012f) % 1.1f);
            float y = h * (0.15f + (i * 0.13f));
            canvas.save();
            canvas.rotate((phase * 18 + i * 37) % 360, x, y);
            RectF petal = new RectF(x - 10, y - 4, x + 10, y + 4);
            canvas.drawOval(petal, paint);
            canvas.restore();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(14000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Ui.themeActive()) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
