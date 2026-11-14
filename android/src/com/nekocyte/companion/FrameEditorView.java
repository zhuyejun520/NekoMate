package com.nekocyte.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 头像框本地编辑器：默认头像只是不可修改的预览底图，
 * 透明 PNG 是独立图层，导出时只输出 PNG 图层。
 */
public class FrameEditorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap frame;
    private float scale = 1f;
    private float tx = 0f;
    private float ty = 0f;
    private float initialScale = 1f;
    private float lastX, lastY;
    private float lastDistance = 0f;
    private float lastMidX, lastMidY;
    private boolean twoFinger = false;

    public FrameEditorView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    public void setFrame(Bitmap bitmap) {
        frame = bitmap;
        reset();
    }

    public void reset() {
        scale = 1f;
        tx = 0f;
        ty = 0f;
        initialScale = 1f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(w, h);
        if (size <= 0) size = w;
        setMeasuredDimension(size, size);
    }

    private float size() {
        return Math.min(getWidth(), getHeight());
    }

    private float centerX() {
        return getWidth() / 2f + tx;
    }

    private float centerY() {
        return getHeight() / 2f + ty;
    }

    private float fitScale() {
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return 1f;
        return size() * 0.84f / Math.max(frame.getWidth(), frame.getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = size();
        if (s <= 0) return;
        drawBaseAvatar(canvas, s);
        if (frame == null) return;
        if (initialScale <= 0f) initialScale = fitScale();
        float f = fitScale() * scale;
        canvas.save();
        canvas.translate(centerX(), centerY());
        canvas.scale(f, f);
        canvas.drawBitmap(frame, -frame.getWidth() / 2f, -frame.getHeight() / 2f, paint);
        canvas.restore();
    }

    private void drawBaseAvatar(Canvas canvas, float s) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = s * 0.36f;
        basePaint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFF8B7CFF, 0xFFE85D9E}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, basePaint);
        basePaint.setShader(null);
        basePaint.setColor(0xFF1D1A2B);
        basePaint.setStyle(Paint.Style.FILL);
        float eyeY = cy - r * 0.12f;
        canvas.drawCircle(cx - r * 0.30f, eyeY, r * 0.055f, basePaint);
        canvas.drawCircle(cx + r * 0.30f, eyeY, r * 0.055f, basePaint);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(Math.max(2f, r * 0.035f));
        basePaint.setStrokeCap(Paint.Cap.ROUND);
        RectF mouth = new RectF(cx - r * 0.20f, cy + r * 0.10f, cx + r * 0.20f, cy + r * 0.35f);
        canvas.drawArc(mouth, 15, 150, false, basePaint);
        basePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (frame == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                twoFinger = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    twoFinger = true;
                    lastDistance = distance(event);
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f;
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoFinger && event.getPointerCount() >= 2) {
                    float dist = distance(event);
                    float midX = (event.getX(0) + event.getX(1)) / 2f;
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    if (lastDistance > 0) {
                        scale *= dist / lastDistance;
                        scale = Math.max(0.25f, Math.min(4f, scale));
                    }
                    tx += midX - lastMidX;
                    ty += midY - lastMidY;
                    lastDistance = dist;
                    lastMidX = midX;
                    lastMidY = midY;
                    invalidate();
                } else {
                    tx += event.getX() - lastX;
                    ty += event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() <= 2) {
                    twoFinger = false;
                    lastDistance = 0f;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                twoFinger = false;
                lastDistance = 0f;
                break;
        }
        return true;
    }

    private float distance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public Bitmap exportFrame(int outSize) {
        if (frame == null) return null;
        Bitmap out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float viewSize = size();
        if (viewSize <= 0) viewSize = 1f;
        float f = fitScale() * scale * outSize / viewSize;
        float cx = outSize / 2f + tx * outSize / viewSize;
        float cy = outSize / 2f + ty * outSize / viewSize;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(f, f);
        canvas.drawBitmap(frame, -frame.getWidth() / 2f, -frame.getHeight() / 2f, paint);
        canvas.restore();
        return out;
    }
}
