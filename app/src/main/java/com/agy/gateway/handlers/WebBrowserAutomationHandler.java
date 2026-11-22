package com.agy.gateway.handlers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.agy.util.PathSafety;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolated headless offscreen WebView automation handler for the System Gateway
 * ({@code command: "browser_auto"}).
 *
 * Security model:
 * - Zero {@code @JavascriptInterface} bindings (no AndroidBridge).
 * - File/content/geolocation/popup access disabled.
 * - Strict {@code http://} and {@code https://} URL allowlist enforced on both
 *   initial navigation and in-page redirects.
 */
public final class WebBrowserAutomationHandler {

    private static final String TAG = "WebBrowserAuto";

    private static final int DEFAULT_WIDTH = 1080;
    private static final int DEFAULT_HEIGHT = 1920;
    private static final int MIN_DIM = 320;
    private static final int MAX_WIDTH = 2160;
    private static final int MAX_HEIGHT = 3840;
    private static final long DEFAULT_NAV_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_EVAL_TIMEOUT_MS = 10_000L;
    private static final long MAX_TIMEOUT_MS = 25_000L;
    private static final int DEFAULT_MAX_TEXT_CHARS = 16_000;
    private static final int MAX_CONSOLE_LOGS = 200;

    private final Context appContext;
    private final Handler mainHandler;
    private final Object opLock = new Object();
    private final Object consoleLock = new Object();
    private final Deque<JSONObject> consoleBuffer = new ArrayDeque<>();

    // Owned and touched strictly on the Android main thread
    private WebView webView;
    private int viewportWidth = DEFAULT_WIDTH;
    private int viewportHeight = DEFAULT_HEIGHT;

    private final AtomicLong navCounter = new AtomicLong(0L);
    private volatile long activeNavId = 0L;
    private volatile CountDownLatch activeNavLatch = null;
    private volatile int lastHttpStatus = 200;
    private volatile String lastLoadError = null;
    private volatile String lastLoadedUrl = "";

    public WebBrowserAutomationHandler(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public JSONObject handle(JSONObject payload) {
        if (payload == null) {
            payload = new JSONObject();
        }
        String action = payload.optString("action", "").trim().toLowerCase();
        synchronized (opLock) {
            switch (action) {
                case "navigate":
                    return handleNavigate(payload);
                case "eval":
                    return handleEval(payload);
                case "click":
                    return handleClick(payload);
                case "type":
                    return handleType(payload);
                case "get_text":
                    return handleGetText(payload);
                case "screenshot":
                    return handleScreenshot(payload);
                case "console_logs":
                    return handleConsoleLogs(payload);
                case "close":
                    return handleClose();
                default:
                    return errorResponse("unknown browser_auto action: " + action);
            }
        }
    }

    /** Releases the offscreen WebView if initialized. Safe to call from any thread. */
    public void close() {
        synchronized (opLock) {
            destroyWebViewOnMainThread();
        }
    }

    private JSONObject handleNavigate(JSONObject payload) {
        String url = payload.optString("url", "").trim();
        if (!isAllowedUrl(url)) {
            return errorResponse("invalid or disallowed url (only http/https allowed)");
        }
        long timeoutMs = clampLong(payload.optLong("timeout_ms", DEFAULT_NAV_TIMEOUT_MS), 1000L, MAX_TIMEOUT_MS);
        final int width = clampInt(payload.optInt("width", viewportWidth), MIN_DIM, MAX_WIDTH);
        final int height = clampInt(payload.optInt("height", viewportHeight), MIN_DIM, MAX_HEIGHT);

        String initErr = ensureWebView(width, height);
        if (initErr != null) {
            return errorResponse(initErr);
        }

        final long myNavId = navCounter.incrementAndGet();
        final CountDownLatch pageLatch = new CountDownLatch(1);
        activeNavId = myNavId;
        activeNavLatch = pageLatch;
        lastHttpStatus = 200;
        lastLoadError = null;
        lastLoadedUrl = url;

        final String targetUrl = url;
        final AtomicReference<String> startErr = new AtomicReference<>();
        final CountDownLatch startedLatch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView == null) {
                        startErr.set("WebView unavailable");
                    } else {
                        resizeWebViewLocked(webView, width, height);
                        webView.stopLoading();
                        webView.loadUrl(targetUrl);
                    }
                } catch (Throwable t) {
                    startErr.set("loadUrl failed: " + t.getMessage());
                } finally {
                    startedLatch.countDown();
                }
            }
        });

        awaitQuietly(startedLatch, 3000L);
        if (startErr.get() != null) {
            activeNavLatch = null;
            return errorResponse(startErr.get());
        }

        boolean finished = awaitQuietly(pageLatch, timeoutMs);
        activeNavLatch = null;

        final AtomicReference<String> finalUrl = new AtomicReference<>(lastLoadedUrl);
        final AtomicReference<String> finalTitle = new AtomicReference<>("");
        final CountDownLatch metaLatch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null) {
                        String u = webView.getUrl();
                        String t = webView.getTitle();
                        if (u != null) {
                            finalUrl.set(u);
                        }
                        if (t != null) {
                            finalTitle.set(t);
                        }
                        // Ensure layout reflects current content after navigation
                        resizeWebViewLocked(webView, viewportWidth, viewportHeight);
                    }
                } catch (Throwable ignored) {
                } finally {
                    metaLatch.countDown();
                }
            }
        });
        awaitQuietly(metaLatch, 2000L);

        JSONObject resp = new JSONObject();
        try {
            boolean ok = finished && lastLoadError == null;
            resp.put("ok", ok);
            resp.put("url", finalUrl.get());
            resp.put("title", finalTitle.get());
            resp.put("status", lastHttpStatus);
            if (!finished) {
                resp.put("timed_out", true);
                resp.put("error", "navigation timed out after " + timeoutMs + "ms");
            } else if (lastLoadError != null) {
                resp.put("error", lastLoadError);
            }
        } catch (Exception ignored) {
        }
        return resp;
    }

    private JSONObject handleEval(JSONObject payload) {
        String script = payload.optString("script", "");
        if (script.trim().isEmpty()) {
            return errorResponse("missing 'script'");
        }
        if (webView == null) {
            return errorResponse("no active browser session; call navigate first");
        }
        long timeoutMs = clampLong(payload.optLong("timeout_ms", DEFAULT_EVAL_TIMEOUT_MS), 500L, MAX_TIMEOUT_MS);
        EvalResult res = evaluateJs(script, timeoutMs);
        JSONObject resp = new JSONObject();
        try {
            if (!res.ok) {
                resp.put("ok", false);
                resp.put("error", res.error != null ? res.error : "eval failed");
            } else {
                resp.put("ok", true);
                resp.put("result", res.parsedValue != null ? res.parsedValue : JSONObject.NULL);
            }
        } catch (Exception ignored) {
        }
        return resp;
    }

    private JSONObject handleClick(JSONObject payload) {
        String selector = payload.optString("selector", "").trim();
        if (selector.isEmpty()) {
            return errorResponse("missing 'selector'");
        }
        if (webView == null) {
            return errorResponse("no active browser session; call navigate first");
        }
        String script = "(function(sel){"
                + "try{"
                + "var el=document.querySelector(sel);"
                + "if(!el){return {clicked:false,error:'element not found'};}"
                + "try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){}"
                + "if(typeof el.focus==='function'){try{el.focus();}catch(e){}}"
                + "el.click();"
                + "return {clicked:true,tag:(el.tagName||'')};"
                + "}catch(err){return {clicked:false,error:String(err)};}"
                + "})(" + JSONObject.quote(selector) + ");";

        EvalResult res = evaluateJs(script, DEFAULT_EVAL_TIMEOUT_MS);
        if (!res.ok) {
            return errorResponse(res.error != null ? res.error : "click failed");
        }
        JSONObject resp = new JSONObject();
        try {
            if (res.parsedValue instanceof JSONObject) {
                JSONObject obj = (JSONObject) res.parsedValue;
                boolean clicked = obj.optBoolean("clicked", false);
                resp.put("ok", clicked);
                resp.put("clicked", clicked);
                if (obj.has("tag")) {
                    resp.put("tag", obj.optString("tag", ""));
                }
                if (!clicked && obj.has("error")) {
                    resp.put("error", obj.optString("error", "click failed"));
                }
            } else {
                resp.put("ok", false);
                resp.put("clicked", false);
                resp.put("error", "unexpected click result");
            }
        } catch (Exception ignored) {
        }
        return resp;
    }

    private JSONObject handleType(JSONObject payload) {
        String selector = payload.optString("selector", "").trim();
        if (selector.isEmpty()) {
            return errorResponse("missing 'selector'");
        }
        if (!payload.has("text")) {
            return errorResponse("missing 'text'");
        }
        if (webView == null) {
            return errorResponse("no active browser session; call navigate first");
        }
        String text = payload.optString("text", "");
        boolean clear = payload.optBoolean("clear", true);

        String script = "(function(sel,txt,clr){"
                + "try{"
                + "var el=document.querySelector(sel);"
                + "if(!el){return {typed:false,error:'element not found'};}"
                + "try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){}"
                + "if(typeof el.focus==='function'){try{el.focus();}catch(e){}}"
                + "var tag=(el.tagName||'').toUpperCase();"
                + "if(tag==='INPUT'||tag==='TEXTAREA'){"
                + "var proto=tag==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;"
                + "var desc=Object.getOwnPropertyDescriptor(proto,'value');"
                + "var nextVal=clr?txt:(String(el.value||'')+txt);"
                + "if(desc&&desc.set){desc.set.call(el,nextVal);}else{el.value=nextVal;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return {typed:true,length:nextVal.length};"
                + "}else if(el.isContentEditable){"
                + "var nextTxt=clr?txt:(String(el.textContent||'')+txt);"
                + "el.textContent=nextTxt;"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "return {typed:true,length:nextTxt.length};"
                + "}else{"
                + "return {typed:false,error:'element is not editable: '+tag};"
                + "}"
                + "}catch(err){return {typed:false,error:String(err)};}"
                + "})(" + JSONObject.quote(selector) + "," + JSONObject.quote(text) + "," + (clear ? "true" : "false") + ");";

        EvalResult res = evaluateJs(script, DEFAULT_EVAL_TIMEOUT_MS);
        if (!res.ok) {
            return errorResponse(res.error != null ? res.error : "type failed");
        }
        JSONObject resp = new JSONObject();
        try {
            if (res.parsedValue instanceof JSONObject) {
                JSONObject obj = (JSONObject) res.parsedValue;
                boolean typed = obj.optBoolean("typed", false);
                resp.put("ok", typed);
                resp.put("typed", typed);
                if (obj.has("length")) {
                    resp.put("length", obj.optInt("length", 0));
                }
                if (!typed && obj.has("error")) {
                    resp.put("error", obj.optString("error", "type failed"));
                }
            } else {
                resp.put("ok", false);
                resp.put("typed", false);
                resp.put("error", "unexpected type result");
            }
        } catch (Exception ignored) {
        }
        return resp;
    }

    private JSONObject handleGetText(JSONObject payload) {
        if (webView == null) {
            return errorResponse("no active browser session; call navigate first");
        }
        String selector = payload.optString("selector", "").trim();
        int maxChars = clampInt(payload.optInt("max_chars", DEFAULT_MAX_TEXT_CHARS), 100, 200_000);

        String script = "(function(sel,maxC){"
                + "try{"
                + "var root=sel?document.querySelector(sel):(document.body||document.documentElement);"
                + "if(!root){return {ok:false,error:'element not found',url:location.href,title:document.title||''};}"
                + "var raw=(root.innerText!==undefined?root.innerText:root.textContent)||'';"
                + "var trimmed=raw.replace(/\\r\\n/g,'\\n').trim();"
                + "var trunc=trimmed.length>maxC;"
                + "if(trunc){trimmed=trimmed.slice(0,maxC);}"
                + "return {ok:true,text:trimmed,truncated:trunc,url:location.href,title:document.title||''};"
                + "}catch(err){return {ok:false,error:String(err),url:location.href,title:document.title||''};}"
                + "})(" + JSONObject.quote(selector) + "," + maxChars + ");";

        EvalResult res = evaluateJs(script, DEFAULT_EVAL_TIMEOUT_MS);
        if (!res.ok) {
            return errorResponse(res.error != null ? res.error : "get_text failed");
        }
        if (res.parsedValue instanceof JSONObject) {
            return (JSONObject) res.parsedValue;
        }
        return errorResponse("unexpected get_text result");
    }

    private JSONObject handleScreenshot(JSONObject payload) {
        if (webView == null) {
            return errorResponse("no active browser session; call navigate first");
        }
        String requestedPath = payload.optString("path", "").trim();
        File outFile;
        try {
            outFile = resolveScreenshotFile(requestedPath);
        } catch (IOException e) {
            return errorResponse(e.getMessage());
        }

        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
        final AtomicReference<String> drawErr = new AtomicReference<>();
        final int[] dims = new int[]{viewportWidth, viewportHeight};
        final CountDownLatch drawLatch = new CountDownLatch(1);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView == null) {
                        drawErr.set("WebView unavailable");
                        return;
                    }
                    int w = webView.getWidth() > 0 ? webView.getWidth() : viewportWidth;
                    int h = webView.getHeight() > 0 ? webView.getHeight() : viewportHeight;
                    if (webView.getWidth() != w || webView.getHeight() != h) {
                        resizeWebViewLocked(webView, w, h);
                    }
                    dims[0] = w;
                    dims[1] = h;
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(0xFFFFFFFF);
                    webView.draw(canvas);
                    bitmapRef.set(bmp);
                } catch (Throwable t) {
                    drawErr.set("screenshot draw failed: " + t.getMessage());
                } finally {
                    drawLatch.countDown();
                }
            }
        });

        if (!awaitQuietly(drawLatch, 10_000L)) {
            return errorResponse("screenshot timed out on main thread");
        }
        if (drawErr.get() != null) {
            return errorResponse(drawErr.get());
        }
        Bitmap bmp = bitmapRef.get();
        if (bmp == null) {
            return errorResponse("failed to capture bitmap");
        }

        try {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return errorResponse("cannot create screenshot directory: " + parent);
            }
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)) {
                    return errorResponse("failed encoding PNG");
                }
                fos.flush();
            }
            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("path", outFile.getAbsolutePath());
            resp.put("width", dims[0]);
            resp.put("height", dims[1]);
            resp.put("bytes", outFile.length());
            return resp;
        } catch (Exception e) {
            return errorResponse("screenshot save failed: " + e.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    private JSONObject handleConsoleLogs(JSONObject payload) {
        boolean clear = payload.optBoolean("clear", false);
        JSONArray arr = new JSONArray();
        synchronized (consoleLock) {
            for (JSONObject entry : consoleBuffer) {
                arr.put(entry);
            }
            if (clear) {
                consoleBuffer.clear();
            }
        }
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("logs", arr);
        } catch (Exception ignored) {
        }
        return resp;
    }

    private JSONObject handleClose() {
        destroyWebViewOnMainThread();
        synchronized (consoleLock) {
            consoleBuffer.clear();
        }
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("closed", true);
        } catch (Exception ignored) {
        }
        return resp;
    }

    private String ensureWebView(final int width, final int height) {
        if (webView != null) {
            return null;
        }
        final AtomicReference<String> err = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView == null) {
                        WebView wv = new WebView(appContext);
                        // Software layer guarantees offscreen Canvas capture works without a Window
                        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                        WebSettings s = wv.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setAllowFileAccess(false);
                        s.setAllowContentAccess(false);
                        s.setAllowFileAccessFromFileURLs(false);
                        s.setAllowUniversalAccessFromFileURLs(false);
                        s.setGeolocationEnabled(false);
                        s.setSaveFormData(false);
                        s.setJavaScriptCanOpenWindowsAutomatically(false);
                        s.setSupportMultipleWindows(false);
                        s.setDatabaseEnabled(false);
                        s.setUseWideViewPort(true);
                        s.setLoadWithOverviewMode(true);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                        }

                        wv.setWebViewClient(createWebViewClient());
                        wv.setWebChromeClient(createWebChromeClient());
                        resizeWebViewLocked(wv, width, height);
                        webView = wv;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "WebView init failed: " + t.getMessage());
                    err.set("WebView initialization failed: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!awaitQuietly(latch, 10_000L)) {
            return "WebView initialization timed out";
        }
        return err.get();
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                String url = uri != null ? uri.toString() : "";
                if (!isAllowedUrl(url)) {
                    Log.w(TAG, "blocked disallowed redirect url scheme");
                    return true;
                }
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (!isAllowedUrl(url)) {
                    Log.w(TAG, "blocked disallowed redirect url scheme");
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url == null || "about:blank".equals(url)) {
                    return;
                }
                lastLoadedUrl = url;
                CountDownLatch latch = activeNavLatch;
                if (latch != null && activeNavId == navCounter.get()) {
                    latch.countDown();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null) {
                    lastHttpStatus = errorResponse.getStatusCode();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    String desc = error != null && error.getDescription() != null
                            ? error.getDescription().toString()
                            : "page load error";
                    lastLoadError = desc;
                    CountDownLatch latch = activeNavLatch;
                    if (latch != null && activeNavId == navCounter.get()) {
                        latch.countDown();
                    }
                }
            }
        };
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("level", cm.messageLevel() != null ? cm.messageLevel().name().toLowerCase() : "log");
                        entry.put("message", cm.message() != null ? cm.message() : "");
                        entry.put("line", cm.lineNumber());
                        entry.put("source", cm.sourceId() != null ? cm.sourceId() : "");
                        entry.put("timestamp", System.currentTimeMillis());
                    } catch (Exception ignored) {
                    }
                    synchronized (consoleLock) {
                        consoleBuffer.addLast(entry);
                        while (consoleBuffer.size() > MAX_CONSOLE_LOGS) {
                            consoleBuffer.removeFirst();
                        }
                    }
                }
                return true;
            }
        };
    }

    private void resizeWebViewLocked(WebView wv, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        wv.measure(wSpec, hSpec);
        wv.layout(0, 0, width, height);
    }

    private void destroyWebViewOnMainThread() {
        if (webView == null) {
            return;
        }
        activeNavLatch = null;
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null) {
                        webView.stopLoading();
                        webView.setWebChromeClient(null);
                        webView.setWebViewClient(new WebViewClient());
                        webView.loadUrl("about:blank");
                        webView.clearHistory();
                        webView.removeAllViews();
                        webView.destroy();
                        webView = null;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "WebView destroy error: " + t.getMessage());
                    webView = null;
                } finally {
                    latch.countDown();
                }
            }
        });
        awaitQuietly(latch, 3000L);
    }

    private static final class EvalResult {
        final boolean ok;
        final Object parsedValue;
        final String error;

        EvalResult(boolean ok, Object parsedValue, String error) {
            this.ok = ok;
            this.parsedValue = parsedValue;
            this.error = error;
        }
    }

    private EvalResult evaluateJs(final String script, long timeoutMs) {
        final AtomicReference<String> rawRef = new AtomicReference<>();
        final AtomicReference<String> errRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView == null) {
                        errRef.set("WebView unavailable");
                        latch.countDown();
                        return;
                    }
                    webView.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            rawRef.set(value);
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    errRef.set("evaluateJavascript failed: " + t.getMessage());
                    latch.countDown();
                }
            }
        });

        if (!awaitQuietly(latch, timeoutMs)) {
            return new EvalResult(false, null, "script evaluation timed out after " + timeoutMs + "ms");
        }
        if (errRef.get() != null) {
            return new EvalResult(false, null, errRef.get());
        }
        String raw = rawRef.get();
        if (raw == null || "null".equals(raw) || "undefined".equals(raw)) {
            return new EvalResult(true, null, null);
        }
        try {
            Object parsed = new JSONTokener(raw).nextValue();
            return new EvalResult(true, parsed, null);
        } catch (Exception e) {
            return new EvalResult(true, raw, null);
        }
    }

    private File resolveScreenshotFile(String requestedPath) throws IOException {
        if (requestedPath == null || requestedPath.isEmpty()) {
            File dir = new File(appContext.getCacheDir(), "browser_auto");
            return new File(dir, System.currentTimeMillis() + ".png");
        }
        File target = new File(requestedPath);
        if (!target.isAbsolute()) {
            target = new File(new File(appContext.getCacheDir(), "browser_auto"), requestedPath);
        }
        File[] allowedRoots = new File[]{
                appContext.getCacheDir(),
                appContext.getFilesDir(),
                appContext.getExternalCacheDir(),
                appContext.getExternalFilesDir(null),
                Environment.getExternalStorageDirectory()
        };
        boolean allowed = false;
        for (File root : allowedRoots) {
            if (root != null && PathSafety.isInside(root, target)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IOException("screenshot path outside allowed app/external storage directories");
        }
        return target;
    }

    static boolean isAllowedUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.trim().isEmpty()) {
                return false;
            }
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject errorResponse(String message) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", false);
            resp.put("error", message != null ? message : "unknown error");
        } catch (Exception ignored) {
        }
        return resp;
    }

    private static boolean awaitQuietly(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static long clampLong(long val, long min, long max) {
        return Math.max(min, Math.min(max, val));
    }
}
