package dev.continuum.antigravity.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Lightweight, zero-dependency ContentProvider for sharing files securely
 * with external applications via content:// URIs.
 */
public class AppFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }
        int fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equalsIgnoreCase(mode) || "wt".equalsIgnoreCase(mode)) {
            fileMode = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE;
        } else if ("rw".equalsIgnoreCase(mode) || "rwt".equalsIgnoreCase(mode)) {
            fileMode = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        }
        return ParcelFileDescriptor.open(file, fileMode);
    }

    @Override
    public String getType(Uri uri) {
        File file = getFileForUri(uri);
        if (file.isDirectory()) {
            return "vnd.android.document/directory";
        }
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String extension = name.substring(lastDot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
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

    public static Uri getUriForFile(Context context, File file) {
        String authority = context.getPackageName() + ".fileprovider";
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .path(file.getAbsolutePath())
                .build();
    }

    private File getFileForUri(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("Invalid URI: " + uri);
        }
        File file = new File(path);
        try {
            String canonical = file.getCanonicalPath();
            Context context = getContext();
            if (context != null) {
                File filesDir = context.getFilesDir();
                File cacheDir = context.getCacheDir();
                File appDataDir = context.getApplicationInfo() != null && context.getApplicationInfo().dataDir != null
                        ? new File(context.getApplicationInfo().dataDir) : null;
                File extStorage = Environment.getExternalStorageDirectory();
                File extFiles = context.getExternalFilesDir(null);
                File extCache = context.getExternalCacheDir();

                File[] allowedDirs = new File[] {
                    filesDir,
                    cacheDir,
                    appDataDir,
                    extStorage,
                    extFiles,
                    extCache
                };

                boolean isAllowed = false;
                for (File dir : allowedDirs) {
                    if (dir != null) {
                        String allowedDir = dir.getCanonicalPath();
                        if (canonical.equals(allowedDir) || canonical.startsWith(allowedDir + File.separator)) {
                            isAllowed = true;
                            break;
                        }
                    }
                }
                if (!isAllowed) {
                    throw new SecurityException("Path traversal attempt or unauthorized path: " + canonical);
                }
            }
            return new File(canonical);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve canonical path: " + path, e);
        }
    }
}
