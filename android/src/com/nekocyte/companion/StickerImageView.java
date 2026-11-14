package com.nekocyte.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;
import android.widget.ImageView;

import java.nio.ByteBuffer;

/** 支持 GIF（Android 9+）和静态图的贴纸图片，使用统一离线缓存。 */
public class StickerImageView extends ImageView {
    private static final LruCache<String, Drawable> DRAWABLES = new LruCache<String, Drawable>(96) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            return 1;
        }
    };

    public StickerImageView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
    }

    public void load(final String url) {
        if (url == null || url.isEmpty()) return;
        final String key = url.startsWith("/") ? Session.server() + url : url;
        setTag(key);
        Drawable cached = DRAWABLES.get(key);
        if (cached != null) {
            setImageDrawable(cached);
            startAnimated(cached);
            return;
        }
        ImageCache.bytes(url, new ImageCache.BytesCallback() {
            @Override
            public void ok(byte[] data) {
                if (data == null || data.length == 0 || !key.equals(getTag())) return;
                Drawable animated = decodeAnimated(data);
                Drawable drawable = animated;
                if (drawable == null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp == null) return;
                    drawable = new BitmapDrawable(getResources(), bmp);
                }
                DRAWABLES.put(key, drawable);
                setImageDrawable(drawable);
                startAnimated(drawable);
            }
        });
    }

    private void startAnimated(Drawable drawable) {
        if (Build.VERSION.SDK_INT >= 28 && drawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) drawable).start();
        }
    }

    private Drawable decodeAnimated(byte[] data) {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            android.graphics.ImageDecoder.Source source =
                    android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(data));
            Drawable drawable = android.graphics.ImageDecoder.decodeDrawable(source);
            return drawable instanceof AnimatedImageDrawable ? drawable : null;
        } catch (Exception e) {
            return null;
        }
    }
}
