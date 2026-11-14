package com.nekocyte.companion;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.Manifest;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/** 图片选择统一入口：优先系统相册，兼容 OPPO/ColorOS 的旧版相册。 */
public class MediaUtil {
    public static Intent pickFromGallery(Context context, boolean multiple, int max) {
        DebugLog.i("media", "custom gallery permission " + permissionSummary(context)
                + " multiple=" + multiple + " max=" + max);
        return GalleryPickerActivity.intent(context, multiple, max);
    }

    /**
     * ColorOS/OnePlus 相册返回 picker_get_content 虚拟 URI，open 时报 ENOENT。
     * 改用 DocumentsUI 打开 DCIM/Camera，实际文件 URI 可正常读取。
     */
    public static Intent pickFromOemAlbum(Context context, boolean multiple) {
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType("image/*");
        doc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        doc.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        doc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            doc.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:DCIM/Camera"));
        } catch (Exception ignored) {
        }
        Intent preferred = preferSystemDocuments(context, doc);
        DebugLog.i("media", "pickFromOemAlbum DocumentsUI pkg=" + preferred.getPackage() + " multiple=" + multiple);
        return preferred;
    }

    public static String permissionSummary(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean img = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean vid = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            String extra = "";
            if (Build.VERSION.SDK_INT >= 34) {
                boolean sel = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
                extra = " selected=" + sel;
            }
            return "images=" + img + " video=" + vid + extra;
        }
        return "storage=" + (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    /** 旧版相册：单张 ACTION_PICK，MediaStore URI；多张 ACTION_GET_CONTENT。 */
    public static Intent pickFromLegacyGallery(Context context, boolean multiple) {
        if (!multiple) {
            try {
                Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (pick.resolveActivity(context.getPackageManager()) != null) {
                    DebugLog.i("media", "pickFromLegacyGallery ACTION_PICK");
                    return pick;
                }
                DebugLog.e("media", "ACTION_PICK unresolved");
            } catch (Exception e) {
                DebugLog.e("media", "ACTION_PICK failed", e);
            }
        }
        Intent get = new Intent(Intent.ACTION_GET_CONTENT);
        get.setType("image/*");
        get.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        get.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        get.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        DebugLog.i("media", "pickFromLegacyGallery ACTION_GET_CONTENT multiple=" + multiple);
        return get;
    }

    public static Intent pickFromDocuments(Context context, boolean multiple) {
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType("image/*");
        doc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        // 只显示本地文件，避免云端相册返回尚未下载的 URI。
        doc.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        doc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Intent preferred = preferSystemDocuments(context, doc);
        DebugLog.i("media", "pickFromDocuments pkg=" + preferred.getPackage() + " multiple=" + multiple);
        return preferred;
    }

    /** 优先使用 AOSP/Google 系统文件选择器，绕开部分 OEM 文件管理器的失效 URI。 */
    private static Intent preferSystemDocuments(Context context, Intent doc) {
        String[] packages = {"com.android.documentsui", "com.google.android.documentsui"};
        for (String pkg : packages) {
            try {
                Intent candidate = new Intent(doc);
                candidate.setPackage(pkg);
                if (candidate.resolveActivity(context.getPackageManager()) != null) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return doc;
    }

    /** 兼容旧调用：默认走相册。 */
    public static Intent pickImages(Context context, boolean multiple, int max) {
        return pickFromGallery(context, multiple, max);
    }

    public static void persist(Activity activity, Uri uri) {
        if (uri == null) return;
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            DebugLog.i("media", "persist ok " + describe(uri));
        } catch (Exception e) {
            DebugLog.d("media", "persist skipped " + describe(uri) + " " + e.getClass().getSimpleName());
        }
    }

    public static InputStream open(Context context, Uri uri) throws Exception {
        if (uri == null) throw new FileNotFoundException("图片地址为空");
        DebugLog.i("media", "open " + describe(uri));
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new FileNotFoundException("图片路径为空");
            return new FileInputStream(Uri.decode(path));
        }
        ContentResolver resolver = context.getContentResolver();
        Exception last = null;
        try {
            InputStream in = resolver.openInputStream(uri);
            if (in != null) return in;
        } catch (Exception e) {
            last = e;
            DebugLog.e("media", "openInputStream failed " + describe(uri), e);
        }
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd != null) return new FileInputStream(pfd.getFileDescriptor());
        } catch (Exception e) {
            last = e;
            DebugLog.e("media", "openFileDescriptor failed " + describe(uri), e);
        }
        try {
            android.content.res.AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r");
            if (afd != null) return afd.createInputStream();
        } catch (Exception e) {
            last = e;
            DebugLog.e("media", "openAssetFileDescriptor failed " + describe(uri), e);
        }
        // ColorOS picker_get_content/.../media/<id> 可以尝试还原成 MediaStore 标准 URI。
        try {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.contains("picker_get_content")) {
                int slash = path.lastIndexOf('/');
                long id = Long.parseLong(path.substring(slash + 1));
                Uri media = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                InputStream in = resolver.openInputStream(media);
                if (in != null) {
                    DebugLog.i("media", "recovered ColorOS picker URI -> " + describe(media));
                    return in;
                }
            }
        } catch (Exception e) {
            DebugLog.e("media", "recover ColorOS picker URI failed", e);
        }
        throw new FileNotFoundException("图片已失效或文件管理未授予读取权限"
                + (last == null ? "" : "（" + last.getClass().getSimpleName() + "）"));
    }

    public static String describe(Uri uri) {
        if (uri == null) return "null";
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
    }

    public static String authority(Uri uri) {
        return uri == null || uri.getAuthority() == null ? "" : uri.getAuthority().toLowerCase();
    }
}
