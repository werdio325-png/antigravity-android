package com.agy.ui.webview;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ContentInfo;
import android.view.OnReceiveContentListener;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.webkit.WebView;

import com.agy.ui.extra.ClipboardImageReader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WebView subclass advertising rich image/* MIME types to Android IMEs
 * (Gboard clipboard screenshots, stickers, etc.) via commitContent (API 25+)
 * and View.setOnReceiveContentListener (API 31+), delivering pasted images to
 * JS via window.__agDeliverPastedImage(base64, mime, name).
 */
public final class AgyWebView extends WebView {

    private static final String TAG = "AgyWebView";
    private static final String[] IMAGE_MIME_TYPES = new String[]{
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/*"
    };

    public AgyWebView(Context context) {
        super(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31ReceiveContentHelper.install(this);
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            return Api25InputConnectionHelper.wrap(this, base, outAttrs);
        }
        return base;
    }

    private void dispatchImagesAsync(final List<ClipboardImageReader.ImageRef> refs) {
        final Context appCtx = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (ClipboardImageReader.ImageRef ref : refs) {
                    try {
                        ClipboardImageReader.ImagePayload payload =
                                ClipboardImageReader.readImageFromUri(appCtx, ref.uri, ref.mime);
                        if (payload != null) {
                            ClipboardImageReader.deliverPastedImageToWebView(AgyWebView.this, payload);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "OnReceiveContent image read failed: " + t.getMessage());
                    }
                }
            }
        }, "agy-receive-image").start();
    }

    private static String[] mergeMimeTypes(String[] existing, String[] extra) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            for (String s : existing) {
                if (s != null && !s.isEmpty()) {
                    merged.add(s);
                }
            }
        }
        if (extra != null) {
            for (String s : extra) {
                if (s != null && !s.isEmpty()) {
                    merged.add(s);
                }
            }
        }
        return merged.toArray(new String[0]);
    }

    /** Isolated API 25+ class so ART on API 24 never resolves InputContentInfo. */
    private static final class Api25InputConnectionHelper {
        static InputConnection wrap(final AgyWebView webView, InputConnection base, EditorInfo outAttrs) {
            if (outAttrs != null) {
                outAttrs.contentMimeTypes = mergeMimeTypes(outAttrs.contentMimeTypes, IMAGE_MIME_TYPES);
            }
            if (base == null) {
                return null;
            }
            return new InputConnectionWrapper(base, false) {
                @Override
                public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
                    if (inputContentInfo != null
                            && ClipboardImageReader.detectImageMime(
                                    webView.getContext(),
                                    inputContentInfo.getContentUri(),
                                    inputContentInfo.getDescription()) != null) {
                        if (handleCommitImageContent(webView, inputContentInfo, flags)) {
                            return true;
                        }
                    }
                    return super.commitContent(inputContentInfo, flags, opts);
                }
            };
        }

        private static boolean handleCommitImageContent(final AgyWebView webView,
                                                        final InputContentInfo info,
                                                        int flags) {
            final boolean needsGrant = (flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0;
            if (needsGrant) {
                try {
                    info.requestPermission();
                } catch (Throwable t) {
                    Log.w(TAG, "InputContentInfo.requestPermission failed: " + t.getMessage());
                    return false;
                }
            }
            final Context appCtx = webView.getContext().getApplicationContext();
            final Uri uri = info.getContentUri();
            final String mimeHint = ClipboardImageReader.detectImageMime(appCtx, uri, info.getDescription());

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardImageReader.ImagePayload payload =
                                ClipboardImageReader.readImageFromUri(appCtx, uri, mimeHint);
                        if (payload != null) {
                            ClipboardImageReader.deliverPastedImageToWebView(webView, payload);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "commitContent image read failed: " + t.getMessage());
                    } finally {
                        if (needsGrant) {
                            try {
                                info.releasePermission();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }, "agy-ime-image").start();
            return true;
        }
    }

    /** Isolated API 31+ class so ART on API 24-30 never resolves OnReceiveContentListener/ContentInfo. */
    private static final class Api31ReceiveContentHelper {
        static void install(final AgyWebView webView) {
            webView.setOnReceiveContentListener(IMAGE_MIME_TYPES, new OnReceiveContentListener() {
                @Override
                public ContentInfo onReceiveContent(View view, ContentInfo payload) {
                    if (payload == null) {
                        return null;
                    }
                    final ClipData clip = payload.getClip();
                    if (clip == null || clip.getItemCount() == 0) {
                        return payload;
                    }
                    final ClipDescription desc = clip.getDescription();
                    final List<ClipboardImageReader.ImageRef> refs = new ArrayList<>();
                    final List<ClipData.Item> remainingItems = new ArrayList<>();
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        ClipData.Item item = clip.getItemAt(i);
                        if (item == null) {
                            continue;
                        }
                        String mime = item.getUri() != null
                                ? ClipboardImageReader.detectImageMime(webView.getContext(), item.getUri(), desc)
                                : null;
                        if (mime != null) {
                            refs.add(new ClipboardImageReader.ImageRef(item.getUri(), mime));
                        } else {
                            remainingItems.add(item);
                        }
                    }
                    if (refs.isEmpty()) {
                        return payload;
                    }
                    webView.dispatchImagesAsync(refs);
                    if (remainingItems.isEmpty()) {
                        return null;
                    }
                    ClipData remainingClip = new ClipData(new ClipDescription(desc), remainingItems.get(0));
                    for (int i = 1; i < remainingItems.size(); i++) {
                        remainingClip.addItem(remainingItems.get(i));
                    }
                    return new ContentInfo.Builder(payload).setClip(remainingClip).build();
                }
            });
        }
    }
}
