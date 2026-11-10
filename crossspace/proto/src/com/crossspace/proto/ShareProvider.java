package com.crossspace.proto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/**
 * 只读的"图片中转"provider。
 *
 * 为什么需要它：跨空间回放 ACTION_SEND 时，原始 content://media/... URI 的读权限
 * 没办法可靠地转授给另一个 user 里的目标应用（uid 1000 去 grant 会被
 * UriGrantsManagerService 静默忽略；目标应用把它再转发出去时直接 SecurityException 崩）。
 * 所以这里把 URI 换成"自己包里的 exported 只读 provider"，任何应用都能读，
 * 目标应用再转发这个 URI 也不会被权限检查打死。
 *
 * 本进程（com.crossspace.proto）通过 pm grant 拿到 READ_MEDIA_IMAGES，
 * openFile 里再用原始 URI 去 MediaProvider 取 fd；query 里把原图的
 * _data / _display_name / _size 报出去，供只认 MediaStore 字段的接收方（微信）使用。
 *
 * 注意：Android 11+ 包可见性下这套包装对微信失效（微信查不到本 provider 直接关窗），
 * wrap() 现已是恒等返回，provider 仅保留做兼容；详见 README 4.1。
 */
public class ShareProvider extends ContentProvider {
    static final String AUTH = "com.crossspace.proto.share";

    static Uri wrap(Uri src, String type) {
        if (src == null || !"content".equals(src.getScheme())) return src;
        String q = "u=" + Uri.encode(src.toString());
        if (type != null) q += "&t=" + Uri.encode(type);
        return Uri.parse("content://" + AUTH + "/f?" + q);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String inner = uri.getQueryParameter("u");
        if (inner == null) throw new java.io.FileNotFoundException("no u param: " + uri);
        try {
            return getContext().getContentResolver()
                    .openFileDescriptor(Uri.parse(inner), "r");
        } catch (Throwable t) {
            android.util.Log.w("[CrossSpaceProto]", "provider open fail " + inner, t);
            throw new java.io.FileNotFoundException("open fail " + inner + " : " + t);
        }
    }

    @Override
    public String getType(Uri uri) {
        String t = uri.getQueryParameter("t");
        return t != null ? t : "image/*";
    }

    /**
     * 很多接收方（微信的图片分享）不是直接 openFile，而是先按 MediaStore 的
     * 那几列查一遍拿 _data 绝对路径 / 文件名 / 大小。这里把内部 URI 的信息
     * 透出来，免得对方查到空 cursor 就当成"图没了"直接 finish。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String inner = uri.getQueryParameter("u");
        String type = uri.getQueryParameter("t");
        String path = null, name = null, mime = type;
        long size = -1;
        if (inner != null) {
            Cursor c = null;
            try {
                c = getContext().getContentResolver().query(Uri.parse(inner),
                        new String[]{"_data", "_display_name", "_size", "mime_type"}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    path = c.getString(0);
                    name = c.getString(1);
                    size = c.getLong(2);
                    String m = c.getString(3);
                    if (m != null) mime = m;
                }
            } catch (Throwable t) {
                android.util.Log.w("[CrossSpaceProto]", "provider query fail " + inner, t);
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
            }
        }
        MatrixCursor mc = new MatrixCursor(new String[]{
                "_id", "_data", "_display_name", "_size", "mime_type", "width", "height"}, 1);
        mc.addRow(new Object[]{Long.valueOf(1L), path, name, Long.valueOf(size), mime, Integer.valueOf(0), Integer.valueOf(0)});
        android.util.Log.i("[CrossSpaceProto]", "provider query " + inner + " -> path=" + path + " size=" + size);
        return mc;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
