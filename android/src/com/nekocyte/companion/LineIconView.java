package com.nekocyte.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 聊天底栏和工具栏使用的简约线条图标，不使用彩色 emoji。 */
public class LineIconView extends View {
    private String type = "plus";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = Color.GRAY;

    public LineIconView(Context context) {
        super(context);
        init();
    }

    public LineIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LineIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        color = Ui.MUTED;
        paint.setColor(color);
    }

    public void setType(String type) {
        this.type = type == null ? "plus" : type;
        invalidate();
    }

    public void setIconColor(int color) {
        this.color = color;
        paint.setColor(color);
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float s = Math.min(getWidth(), getHeight()) / 2f - dp(5);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.7f));

        if ("mic".equals(type)) {
            RectF body = new RectF(cx - s * 0.28f, cy - s, cx + s * 0.28f, cy + s * 0.25f);
            canvas.drawRoundRect(body, dp(8), dp(8), paint);
            RectF arc = new RectF(cx - s * 0.55f, cy - s * 0.35f, cx + s * 0.55f, cy + s * 0.55f);
            canvas.drawArc(arc, 0, 180, false, paint);
            canvas.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.85f, paint);
            canvas.drawLine(cx - s * 0.35f, cy + s * 0.85f, cx + s * 0.35f, cy + s * 0.85f, paint);
        } else if ("emoji".equals(type)) {
            canvas.drawCircle(cx, cy, s, paint);
            canvas.drawCircle(cx - s * 0.35f, cy - s * 0.25f, dp(1.2f), paint);
            canvas.drawCircle(cx + s * 0.35f, cy - s * 0.25f, dp(1.2f), paint);
            RectF smile = new RectF(cx - s * 0.45f, cy - s * 0.1f, cx + s * 0.45f, cy + s * 0.55f);
            canvas.drawArc(smile, 15, 150, false, paint);
        } else if ("search".equals(type)) {
            canvas.drawCircle(cx - s * 0.12f, cy - s * 0.12f, s * 0.55f, paint);
            canvas.drawLine(cx + s * 0.3f, cy + s * 0.3f, cx + s * 0.9f, cy + s * 0.9f, paint);
        } else if ("heart".equals(type)) {
            Path heart = new Path();
            heart.moveTo(cx, cy + s * 0.8f);
            heart.cubicTo(cx - s * 1.2f, cy - s * 0.05f, cx - s * 0.7f, cy - s * 0.85f, cx, cy - s * 0.3f);
            heart.cubicTo(cx + s * 0.7f, cy - s * 0.85f, cx + s * 1.2f, cy - s * 0.05f, cx, cy + s * 0.8f);
            canvas.drawPath(heart, paint);
        } else if ("keyboard".equals(type)) {
            RectF box = new RectF(cx - s, cy - s * 0.62f, cx + s, cy + s * 0.62f);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + i * s * 0.35f, cy - s * 0.18f, dp(1.2f), paint);
            }
            canvas.drawLine(cx - s * 0.45f, cy + s * 0.25f, cx + s * 0.45f, cy + s * 0.25f, paint);
        } else if ("plus".equals(type)) {
            canvas.drawCircle(cx, cy, s, paint);
            canvas.drawLine(cx - s * 0.45f, cy, cx + s * 0.45f, cy, paint);
            canvas.drawLine(cx, cy - s * 0.45f, cx, cy + s * 0.45f, paint);
        } else if ("cart".equals(type)) {
            canvas.save();
            canvas.translate(0, -s * 0.19f);
            Path basket = new Path();
            basket.moveTo(cx - s * 0.9f, cy - s * 0.62f);
            basket.lineTo(cx - s * 0.62f, cy - s * 0.3f);
            basket.lineTo(cx - s * 0.42f, cy + s * 0.48f);
            basket.lineTo(cx + s * 0.62f, cy + s * 0.48f);
            basket.lineTo(cx + s * 0.86f, cy - s * 0.18f);
            basket.lineTo(cx - s * 0.48f, cy - s * 0.18f);
            canvas.drawPath(basket, paint);
            canvas.drawCircle(cx - s * 0.25f, cy + s * 0.82f, dp(2.2f), paint);
            canvas.drawCircle(cx + s * 0.45f, cy + s * 0.82f, dp(2.2f), paint);
            canvas.restore();
        } else if ("calendar".equals(type)) {
            canvas.save();
            canvas.translate(0, s * 0.12f);
            RectF box = new RectF(cx - s * 0.78f, cy - s * 0.68f, cx + s * 0.78f, cy + s * 0.78f);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            canvas.drawLine(cx - s * 0.78f, cy - s * 0.28f, cx + s * 0.78f, cy - s * 0.28f, paint);
            canvas.drawLine(cx - s * 0.45f, cy - s * 1.02f, cx - s * 0.45f, cy - s * 0.56f, paint);
            canvas.drawLine(cx + s * 0.45f, cy - s * 1.02f, cx + s * 0.45f, cy - s * 0.56f, paint);
            canvas.drawCircle(cx - s * 0.35f, cy + s * 0.12f, dp(1.8f), paint);
            canvas.drawCircle(cx + s * 0.05f, cy + s * 0.12f, dp(1.8f), paint);
            canvas.drawCircle(cx + s * 0.45f, cy + s * 0.12f, dp(1.8f), paint);
            canvas.drawCircle(cx - s * 0.35f, cy + s * 0.52f, dp(1.8f), paint);
            canvas.drawCircle(cx + s * 0.05f, cy + s * 0.52f, dp(1.8f), paint);
            canvas.restore();
        } else if ("store".equals(type)) {
            RectF bag = new RectF(cx - s * 0.8f, cy - s * 0.35f, cx + s * 0.8f, cy + s * 0.8f);
            canvas.drawRoundRect(bag, dp(5), dp(5), paint);
            RectF handle = new RectF(cx - s * 0.35f, cy - s * 0.85f, cx + s * 0.35f, cy - s * 0.05f);
            canvas.drawArc(handle, 180, 180, false, paint);
            canvas.drawLine(cx - s * 0.8f, cy + s * 0.05f, cx + s * 0.8f, cy + s * 0.05f, paint);
        } else if ("transfer".equals(type)) {
            RectF box = new RectF(cx - s * 0.9f, cy - s * 0.65f, cx + s * 0.9f, cy + s * 0.65f);
            canvas.drawRoundRect(box, dp(8), dp(8), paint);
            canvas.drawLine(cx - s * 0.5f, cy, cx + s * 0.45f, cy, paint);
            canvas.drawLine(cx + s * 0.45f, cy, cx + s * 0.2f, cy - s * 0.25f, paint);
            canvas.drawLine(cx + s * 0.45f, cy, cx + s * 0.2f, cy + s * 0.25f, paint);
        } else if ("redpacket".equals(type)) {
            RectF box = new RectF(cx - s * 0.72f, cy - s * 0.8f, cx + s * 0.72f, cy + s * 0.8f);
            canvas.drawRoundRect(box, dp(5), dp(5), paint);
            canvas.drawLine(cx - s * 0.72f, cy - s * 0.25f, cx + s * 0.72f, cy - s * 0.25f, paint);
            canvas.drawCircle(cx, cy + s * 0.18f, s * 0.25f, paint);
        } else if ("image".equals(type)) {
            RectF box = new RectF(cx - s, cy - s * 0.75f, cx + s, cy + s * 0.75f);
            canvas.drawRoundRect(box, dp(3), dp(3), paint);
            Path mount = new Path();
            mount.moveTo(cx - s * 0.75f, cy + s * 0.55f);
            mount.lineTo(cx - s * 0.15f, cy - s * 0.1f);
            mount.lineTo(cx + s * 0.25f, cy + s * 0.3f);
            mount.lineTo(cx + s * 0.55f, cy - s * 0.05f);
            mount.lineTo(cx + s * 0.85f, cy + s * 0.55f);
            canvas.drawPath(mount, paint);
            canvas.drawCircle(cx + s * 0.45f, cy - s * 0.35f, dp(1.8f), paint);
        } else if ("camera".equals(type)) {
            RectF box = new RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.65f);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            RectF lens = new RectF(cx - s * 0.35f, cy - s * 0.2f, cx + s * 0.35f, cy + s * 0.5f);
            canvas.drawOval(lens, paint);
            canvas.drawLine(cx - s * 0.4f, cy - s * 0.6f, cx - s * 0.15f, cy - s * 0.95f, paint);
            canvas.drawLine(cx + s * 0.4f, cy - s * 0.6f, cx + s * 0.15f, cy - s * 0.95f, paint);
        } else if ("phone".equals(type)) {
            Path path = new Path();
            path.moveTo(cx - s * 0.65f, cy - s * 0.9f);
            path.cubicTo(cx - s * 0.2f, cy - s * 0.5f, cx + s * 0.2f, cy + s * 0.1f, cx + s * 0.75f, cy + s * 0.65f);
            path.lineTo(cx + s * 0.4f, cy + s * 0.9f);
            path.cubicTo(cx + s * 0.1f, cy + s * 0.6f, cx - s * 0.45f, cy + s * 0.1f, cx - s * 0.9f, cy - s * 0.4f);
            path.close();
            canvas.drawPath(path, paint);
        } else if ("video".equals(type)) {
            RectF box = new RectF(cx - s, cy - s * 0.55f, cx + s * 0.45f, cy + s * 0.55f);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            Path lens = new Path();
            lens.moveTo(cx + s * 0.5f, cy - s * 0.2f);
            lens.lineTo(cx + s, cy - s * 0.55f);
            lens.lineTo(cx + s, cy + s * 0.55f);
            lens.lineTo(cx + s * 0.5f, cy + s * 0.2f);
            lens.close();
            canvas.drawPath(lens, paint);
        } else if ("location".equals(type)) {
            Path pin = new Path();
            pin.moveTo(cx, cy + s);
            pin.cubicTo(cx - s * 0.85f, cy - s * 0.05f, cx - s * 0.65f, cy - s, cx, cy - s);
            pin.cubicTo(cx + s * 0.65f, cy - s, cx + s * 0.85f, cy - s * 0.05f, cx, cy + s);
            canvas.drawPath(pin, paint);
            canvas.drawCircle(cx, cy - s * 0.35f, s * 0.25f, paint);
        } else if ("file".equals(type)) {
            Path folder = new Path();
            folder.moveTo(cx - s, cy - s * 0.55f);
            folder.lineTo(cx - s * 0.2f, cy - s * 0.55f);
            folder.lineTo(cx, cy - s * 0.25f);
            folder.lineTo(cx + s, cy - s * 0.25f);
            folder.lineTo(cx + s, cy + s * 0.65f);
            folder.lineTo(cx - s, cy + s * 0.65f);
            folder.close();
            canvas.drawPath(folder, paint);
        } else if ("redpacket".equals(type)) {
            RectF box = new RectF(cx - s * 0.72f, cy - s, cx + s * 0.72f, cy + s);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            RectF flap = new RectF(cx - s * 0.72f, cy - s, cx + s * 0.72f, cy - s * 0.05f);
            canvas.drawArc(flap, 180, 180, false, paint);
            canvas.drawCircle(cx, cy + s * 0.35f, s * 0.18f, paint);
        } else if ("transfer".equals(type)) {
            canvas.drawLine(cx - s, cy - s * 0.35f, cx + s, cy - s * 0.35f, paint);
            canvas.drawLine(cx + s * 0.35f, cy - s * 0.7f, cx + s, cy - s * 0.35f, paint);
            canvas.drawLine(cx + s * 0.35f, cy, cx + s, cy - s * 0.35f, paint);
            canvas.drawLine(cx + s, cy + s * 0.55f, cx - s, cy + s * 0.55f, paint);
            canvas.drawLine(cx - s * 0.35f, cy + s * 0.2f, cx - s, cy + s * 0.55f, paint);
            canvas.drawLine(cx - s * 0.35f, cy + s * 0.9f, cx - s, cy + s * 0.55f, paint);
        } else {
            canvas.drawCircle(cx, cy, s, paint);
        }
    }
}
