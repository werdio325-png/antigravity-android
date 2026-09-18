package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

public class FileChooserHandler {
    public static final int FILE_CHOOSER_REQUEST_CODE = 1002;
    private ValueCallback<Uri[]> fileChooserCallback = null;

    public boolean onShowFileChooser(Activity activity, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fcp) {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        fileChooserCallback = filePathCallback;

        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            if (fcp != null && fcp.getAcceptTypes() != null && fcp.getAcceptTypes().length > 0) {
                String[] types = fcp.getAcceptTypes();
                if (types.length == 1 && types[0] != null && !types[0].trim().isEmpty()) {
                    intent.setType(types[0]);
                } else if (types.length > 1) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                }
            }

            if (fcp != null && fcp.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            activity.startActivityForResult(Intent.createChooser(intent, "Выберите файлы или изображения"), FILE_CHOOSER_REQUEST_CODE);
            return true;
        } catch (Exception e) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = null;
            }
            return false;
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileChooserCallback == null) return true;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
            return true;
        }
        return false;
    }

    public void reset() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
    }
}
