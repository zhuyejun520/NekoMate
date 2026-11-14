package com.nekocyte.companion;

import android.widget.ImageView;

/** 兼容旧调用点：图片加载统一走 ImageCache 内存 + 磁盘缓存。 */
public class ImageLoader {
    public static void load(final ImageView view, String url) {
        ImageCache.load(view, url);
    }

    public static void preview(android.content.Context context, String url) {
        if (url == null || url.isEmpty()) return;
        android.content.Intent intent = new android.content.Intent(context, ImagePreviewActivity.class);
        intent.putExtra("url", url);
        context.startActivity(intent);
    }
}
