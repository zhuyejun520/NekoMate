package com.nekocyte.companion;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** 原生液态玻璃 Dock：消息 / AI好友 / 动态。 */
public class LiquidDockView extends View {
    public interface OnTabSelectedListener {
        void onSelected(int index);
    }

    private static final String[] LABELS = {"消息", "AI好友", "动态"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final Handler backdropHandler = new Handler(Looper.getMainLooper());
    private Bitmap backdrop;
    private View content;
    private int selected = 0;
    private int committed = 0;
    private float sliderPos = 0f;
    private float squash = 0f;
    private float pressScale = 1f;
    private ValueAnimator sliderAnimator;
    private ValueAnimator squashAnimator;
    private ValueAnimator pressAnimator;
    private OnTabSelectedListener listener;
    private boolean touching = false;
    private final Runnable backdropTask = new Runnable() {
        @Override
        public void run() {
            captureBackdrop();
            if (isAttachedToWindow()) backdropHandler.postDelayed(this, 1400);
        }
    };

    public LiquidDockView(Context context) {
        super(context);
        init();
    }

    public LiquidDockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LiquidDockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelectedIndex(int index) {
        int target = Math.max(0, Math.min(2, index));
        committed = target;
        if (target == selected) {
            invalidate();
            return;
        }
        selected = target;
        animateSlider(target);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Math.round(dp(80)));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        content = getRootView().findViewById(R.id.content);
        backdropHandler.removeCallbacks(backdropTask);
        backdropHandler.postDelayed(backdropTask, 260);
    }

    @Override
    protected void onDetachedFromWindow() {
        backdropHandler.removeCallbacks(backdropTask);
        super.onDetachedFromWindow();
    }

    private void captureBackdrop() {
        if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return;
        int w = content.getWidth();
        int h = content.getHeight();
        int scale = 6;
        int bw = Math.max(1, w / scale);
        int bh = Math.max(1, h / scale);
        try {
            Bitmap raw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            c.scale(1f / scale, 1f / scale);
            content.draw(c);
            Bitmap blurred = blur(raw, 2);
            raw.recycle();
            if (backdrop != null && !backdrop.isRecycled()) backdrop.recycle();
            backdrop = blurred;
            invalidate();
        } catch (Exception ignored) {
        }
    }

    private static Bitmap blur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int[] temp = new int[pix.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int x = -radius; x <= radius; x++) {
                    int p = pix[y * w + Math.min(w - 1, Math.max(0, x))];
                    sa += (p >>> 24) & 0xff; sr += (p >>> 16) & 0xff;
                    sg += (p >>> 8) & 0xff; sb += p & 0xff;
                }
                int count = radius * 2 + 1;
                for (int x = 0; x < w; x++) {
                    temp[y * w + x] = ((sa / count) << 24) | ((sr / count) << 16)
                            | ((sg / count) << 8) | (sb / count);
                    int out = Math.max(0, x - radius);
                    int in = Math.min(w - 1, x + radius + 1);
                    int po = pix[y * w + out];
                    int pi = pix[y * w + in];
                    sa += ((pi >>> 24) & 0xff) - ((po >>> 24) & 0xff);
                    sr += ((pi >>> 16) & 0xff) - ((po >>> 16) & 0xff);
                    sg += ((pi >>> 8) & 0xff) - ((po >>> 8) & 0xff);
                    sb += (pi & 0xff) - (po & 0xff);
                }
            }
            for (int x = 0; x < w; x++) {
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int y = -radius; y <= radius; y++) {
                    int p = temp[Math.min(h - 1, Math.max(0, y)) * w + x];
                    sa += (p >>> 24) & 0xff; sr += (p >>> 16) & 0xff;
                    sg += (p >>> 8) & 0xff; sb += p & 0xff;
                }
                int count = radius * 2 + 1;
                for (int y = 0; y < h; y++) {
                    pix[y * w + x] = ((sa / count) << 24) | ((sr / count) << 16)
                            | ((sg / count) << 8) | (sb / count);
                    int out = Math.max(0, y - radius);
                    int in = Math.min(h - 1, y + radius + 1);
                    int po = temp[out * w + x];
                    int pi = temp[in * w + x];
                    sa += ((pi >>> 24) & 0xff) - ((po >>> 24) & 0xff);
                    sr += ((pi >>> 16) & 0xff) - ((po >>> 16) & 0xff);
                    sg += ((pi >>> 8) & 0xff) - ((po >>> 8) & 0xff);
                    sb += (pi & 0xff) - (po & 0xff);
                }
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(14);
        float height = dp(58);
        float bottom = getHeight() - dp(14);
        bar.set(inset, bottom - height, getWidth() - inset, bottom);
        float radius = dp(24);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0x220F3440);
        paint.setShadowLayer(dp(16), 0, dp(8), 0x33264C5C);
        canvas.drawRoundRect(bar, radius, radius, paint);
        paint.clearShadowLayer();

        Path clip = new Path();
        clip.addRoundRect(bar, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        // Flat frosted surface: no sampled backdrop, no diagonal sheen or edge reflection.
        paint.setShader(null);
        paint.setColor(0xC8F2F6F9);
        canvas.drawRect(bar, paint);
        canvas.restore();

        drawSlider(canvas);
        drawTabs(canvas);
    }

    private void drawEdgeReflection(Canvas canvas) {
        float proximity = Math.max(0f, 1f - Math.min(sliderPos, 2f - sliderPos));
        if (proximity <= 0.01f) return;
        paint.setStyle(Paint.Style.FILL);
        if (sliderPos < 1f) {
            int alpha = (int) (46 * proximity);
            paint.setShader(new LinearGradient(bar.left, 0, bar.left + dp(58), 0,
                    new int[]{(alpha << 24) | 0xFFFFFF, 0x00FFFFFF}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(bar.left, bar.top, bar.left + dp(58), bar.bottom, paint);
            paint.setShader(null);
            paint.setColor((alpha << 24) | 0xFFFFFF);
            canvas.drawRoundRect(new RectF(bar.left + dp(2), bar.top + dp(3),
                    bar.left + dp(7), bar.bottom - dp(3)), dp(4), dp(4), paint);
        } else {
            int alpha = (int) (46 * proximity);
            paint.setShader(new LinearGradient(bar.right, 0, bar.right - dp(58), 0,
                    new int[]{(alpha << 24) | 0xFFFFFF, 0x00FFFFFF}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(bar.right - dp(58), bar.top, bar.right, bar.bottom, paint);
            paint.setShader(null);
            paint.setColor((alpha << 24) | 0xFFFFFF);
            canvas.drawRoundRect(new RectF(bar.right - dp(7), bar.top + dp(3),
                    bar.right - dp(2), bar.bottom - dp(3)), dp(4), dp(4), paint);
        }
    }

    private float cellWidth() {
        return (bar.width() - dp(20)) / 3f;
    }

    private void drawSlider(Canvas canvas) {
        float cell = cellWidth();
        float baseW = cell * 1.02f;
        float baseH = bar.height() - dp(1);
        float w = baseW + dp(12) * squash;
        float h = baseH - dp(10) * squash;
        float cx = bar.left + dp(10) + (sliderPos + 0.5f) * cell;
        RectF slider = new RectF(cx - w / 2, bar.centerY() - h / 2,
                cx + w / 2, bar.centerY() + h / 2);
        canvas.save();
        canvas.scale(1f + (pressScale - 1f) * 0.18f, pressScale,
                slider.centerX(), slider.centerY());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x8CFFFFFF);
        canvas.drawRoundRect(slider, dp(22), dp(22), paint);
        canvas.restore();
    }

    private void drawTabs(Canvas canvas) {
        float left = bar.left + dp(10);
        float cell = cellWidth();
        for (int i = 0; i < 3; i++) {
            float cx = left + cell * i + cell / 2f;
            boolean active = i == selected;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(active ? 0xFF3388BB : 0xFF6F8792);
            drawIcon(canvas, i, cx, bar.top + dp(22));
            textPaint.setColor(active ? 0xFF3388BB : 0xFF6F8792);
            textPaint.setTextSize(dp(active ? 11f : 10f));
            textPaint.setFakeBoldText(active);
            canvas.drawText(LABELS[i], cx, bar.bottom - dp(10), textPaint);
            textPaint.setFakeBoldText(false);
        }
    }

    private void drawIcon(Canvas canvas, int index, float cx, float cy) {
        float s = dp(8.5f);
        if (index == 0) {
            RectF bubble = new RectF(cx - s, cy - dp(7), cx + s, cy + dp(5));
            canvas.drawRoundRect(bubble, dp(4), dp(4), paint);
            Path tail = new Path();
            tail.moveTo(cx - dp(4), cy + dp(4));
            tail.lineTo(cx - dp(4), cy + dp(9));
            tail.lineTo(cx + dp(3), cy + dp(4));
            canvas.drawPath(tail, paint);
        } else if (index == 1) {
            RectF head = new RectF(cx - s, cy - dp(7), cx + s, cy + dp(6));
            canvas.drawRoundRect(head, dp(4), dp(4), paint);
            canvas.drawLine(cx - dp(4), cy - dp(7), cx - dp(4), cy - dp(10), paint);
            canvas.drawLine(cx + dp(4), cy - dp(7), cx + dp(4), cy - dp(10), paint);
            canvas.drawCircle(cx - dp(3), cy - dp(1), dp(1.2f), paint);
            canvas.drawCircle(cx + dp(3), cy - dp(1), dp(1.2f), paint);
            canvas.drawLine(cx - dp(3), cy + dp(3), cx + dp(3), cy + dp(3), paint);
        } else {
            canvas.drawCircle(cx, cy, s, paint);
            Path needle = new Path();
            needle.moveTo(cx + dp(4), cy - dp(4));
            needle.lineTo(cx - dp(2), cy + dp(1));
            needle.lineTo(cx - dp(4), cy + dp(4));
            needle.lineTo(cx + dp(2), cy - dp(1));
            needle.close();
            canvas.drawPath(needle, paint);
        }
    }

    private void animateSlider(int index) {
        if (sliderAnimator != null) sliderAnimator.cancel();
        sliderAnimator = ValueAnimator.ofFloat(sliderPos, index);
        sliderAnimator.setDuration(520);
        sliderAnimator.setInterpolator(new OvershootInterpolator(2.1f));
        sliderAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                sliderPos = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        sliderAnimator.start();
        if (squashAnimator != null) squashAnimator.cancel();
        squashAnimator = ValueAnimator.ofFloat(0f, 1f, 0f);
        squashAnimator.setDuration(340);
        squashAnimator.setInterpolator(new OvershootInterpolator(1.7f));
        squashAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                squash = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        squashAnimator.start();
    }

    private void press(boolean down) {
        if (pressAnimator != null) pressAnimator.cancel();
        pressAnimator = ValueAnimator.ofFloat(pressScale, down ? 1.20f : 1f);
        pressAnimator.setDuration(down ? 200 : 340);
        pressAnimator.setInterpolator(new OvershootInterpolator(down ? 3.4f : 2.2f));
        pressAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pressScale = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pressAnimator.start();
    }

    private int indexAt(float x) {
        int idx = (int) ((x - bar.left - dp(10)) / cellWidth());
        return Math.max(0, Math.min(2, idx));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (event.getY() < bar.top - dp(6) || event.getY() > bar.bottom + dp(6)) return false;
            touching = true;
            press(true);
            int idx = indexAt(event.getX());
            if (idx != selected) {
                selected = idx;
                animateSlider(idx);
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && touching) {
            int idx = indexAt(event.getX());
            if (idx != selected) {
                selected = idx;
                animateSlider(idx);
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            touching = false;
            press(false);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                performClick();
                if (selected != committed) {
                    committed = selected;
                    if (listener != null) listener.onSelected(committed);
                }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
