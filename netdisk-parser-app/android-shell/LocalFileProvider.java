package com.netdisk.parser;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 FileProvider（无 androidx 依赖）：
 * 通过 content://com.netdisk.parser.fileprovider/f/<id> 向其他应用授权读取本 App 下载目录中的文件。
 * URI 中不暴露真实文件路径（用 id 映射），防止外部应用（如 WPS）从 URI 里解析出路径后
 * 绕过 ContentResolver 直接按路径访问，触发 Android 分区存储的 SAF 授权弹窗。
 */
public class LocalFileProvider extends ContentProvider {

    /** id -> 真实文件路径映射（由 MainActivity.openFile 写入） */
    private static final Map<String, String> ID_TO_PATH = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong(1);

    /** 由 MainActivity 调用：注册一个待授权文件，返回 URI id */
    public static String registerFile(String path) {
        String id = Long.toString(SEQ.getAndIncrement(), 36);
        ID_TO_PATH.put(id, path);
        return id;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        // URI 格式: content://authority/f/<id>
        String pathSeg = uri.getPath(); // /f/<id>
        if (pathSeg == null) throw new FileNotFoundException("no path");
        String[] parts = pathSeg.split("/");
        String id = null;
        for (int i = 0; i < parts.length; i++) {
            if ("f".equals(parts[i]) && i + 1 < parts.length) {
                id = parts[i + 1];
                break;
            }
        }
        if (id == null) throw new FileNotFoundException("no id");
        String path = ID_TO_PATH.get(id);
        if (path == null) throw new FileNotFoundException("id not found: " + id);
        File f = new File(path);
        if (!f.exists()) throw new FileNotFoundException("file not exists: " + path);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveFile(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            File f = resolveFile(uri);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < name.length()) {
                String ext = name.substring(dot + 1).toLowerCase();
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) return mime;
            }
        } catch (Exception e) { /* ignore */ }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolveFile(uri);
            if (projection == null || projection.length == 0) {
                projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            }
            MatrixCursor cursor = new MatrixCursor(projection, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : projection) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    row.add(f.getName());
                } else if (OpenableColumns.SIZE.equals(col)) {
                    row.add(f.length());
                } else {
                    row.add(null);
                }
            }
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
