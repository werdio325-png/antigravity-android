package dev.continuum.antigravity.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * Universal dispatcher for opening web links, documents, local files, and folders.
 */
public final class UriDispatcher {

    private static final String TAG = "UriDispatcher";

    private UriDispatcher() {
    }

    public static boolean open(Context context, String uriOrPath) {
        if (context == null || uriOrPath == null || uriOrPath.trim().isEmpty()) {
            return false;
        }
        uriOrPath = uriOrPath.trim();

        // 1. Web links: http://, https://
        if (uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://")) {
            // Never open internal Google API endpoints
            if (uriOrPath.contains("googleapis.com/oauth2/v2/userinfo")
                    || uriOrPath.contains("googleapis.com/auth")
                    || uriOrPath.contains("daily-cloudcode-pa.googleapis.com")) {
                return false;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriOrPath));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
                context.startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Cannot open web uri: " + uriOrPath, e);
                return false;
            }
        }

        // 2. Local files & directories: file:// or /storage/...
        File targetFile = null;
        if (uriOrPath.startsWith("file://")) {
            try {
                targetFile = new File(Uri.parse(uriOrPath).getPath());
            } catch (Exception ignored) {
            }
        } else if (uriOrPath.startsWith("/")) {
            targetFile = new File(uriOrPath);
        }

        if (targetFile != null) {
            if (targetFile.isDirectory()) {
                return openDirectory(context, targetFile);
            } else if (targetFile.isFile()) {
                return openFile(context, targetFile);
            }
        }

        // 3. Generic URIs (content://, mailto:, etc.)
        try {
            Uri uri = Uri.parse(uriOrPath);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open generic uri: " + uriOrPath, e);
            return false;
        }
    }

    public static boolean openDirectory(Context context, File dir) {
        try {
            Uri contentUri = AppFileProvider.getUriForFile(context, dir);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Exception e1) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW);
                fallback.setDataAndType(Uri.fromFile(dir), "resource/folder");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                return true;
            } catch (Exception e2) {
                Log.w(TAG, "Cannot open directory: " + dir, e2);
                return false;
            }
        }
    }

    public static boolean openFile(Context context, File file) {
        try {
            Uri contentUri = AppFileProvider.getUriForFile(context, file);
            String mime = context.getContentResolver().getType(contentUri);
            if (mime == null) {
                mime = "*/*";
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot open file: " + file, e);
            return false;
        }
    }
}
