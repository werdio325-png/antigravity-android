package dev.continuum.antigravity.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;

/**
 * Resolves Storage Access Framework (SAF) tree URIs to real filesystem paths.
 */
public final class DocumentsContractHelper {

    private static final String TAG = "DocumentsContractHelper";

    private DocumentsContractHelper() {
    }

    public static String resolveTreeUriToPath(Context context, Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId == null) {
                return null;
            }
            String baseStorage = Environment.getExternalStorageDirectory().getAbsolutePath();

            // Format is often "primary:Documents/my-project" or "<volume-uuid>:folder"
            if (documentId.startsWith("primary:")) {
                String subPath = documentId.substring("primary:".length());
                if (subPath.startsWith("/")) {
                    subPath = subPath.substring(1);
                }
                return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
            }

            String[] parts = documentId.split(":");
            if (parts.length > 1) {
                String volume = parts[0];
                String subPath = parts[1];
                if (subPath.startsWith("/")) {
                    subPath = subPath.substring(1);
                }
                if ("primary".equalsIgnoreCase(volume)) {
                    return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
                }
                File external = new File("/storage/" + volume + "/" + subPath);
                if (external.exists()) {
                    return external.getAbsolutePath();
                }
                return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
            }

            String cleanDocId = documentId.startsWith("/") ? documentId.substring(1) : documentId;
            return cleanDocId.isEmpty() ? baseStorage : new File(baseStorage, cleanDocId).getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving tree uri to path: " + treeUri, t);
            return null;
        }
    }
}
