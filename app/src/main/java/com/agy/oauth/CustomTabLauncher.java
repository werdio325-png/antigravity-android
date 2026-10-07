package com.agy.oauth;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.agy.util.PackageInspector;

import java.util.HashMap;
import java.util.Map;

/**
 * Opens OAuth URLs as a real Chrome Custom Tab, with no androidx.browser
 * dependency.
 *
 * A plain Chrome tab is opened first because it is synchronous and reliable;
 * this matches the original working behavior. A branded Custom Tab (a normal
 * ACTION_VIEW carrying a SESSION binder from Chrome's CustomTabsService) is
 * only attempted if the plain launch fails, since the service bind is async
 * and a missing/invalid session binder makes Chrome drop the tab. The final
 * fallback is any browser.
 */
public final class CustomTabLauncher {

    private static final String TAG = OAuthConstants.TAG;

    private static final String SERVICE_ACTION =
            "android.support.customtabs.action.CustomTabsService";
    private static final String SERVICE_TOKEN =
            "android.support.customtabs.ICustomTabsService";

    // AIDL transaction codes for ICustomTabsService (FIRST_CALL_TRANSACTION = 1):
    // warmup=1, newSession=2, mayLaunchUrl=3 (see ICustomTabsService.aidl).
    private static final int TX_WARMUP = 1;
    private static final int TX_NEW_SESSION = 2;

    private static final String[] CHROME_PACKAGES = {
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
    };

    /** Session binder per browser package, established on first open. */
    private static final Map<String, IBinder> SESSIONS = new HashMap<>();

    public boolean openInBrowser(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            Log.e(TAG, "bad url: " + url, e);
            return false;
        }
        for (String pkg : CHROME_PACKAGES) {
            if (!PackageInspector.isPackageInstalled(context.getPackageManager(), pkg)) {
                continue;
            }
            // Reliable path first: a plain Chrome tab. Chrome 100+ drops a
            // Custom Tab whose SESSION binder is missing/invalid, and the
            // service bind is async, so launching the custom tab first can
            // swallow the navigation entirely. The plain tab opens every time;
            // the custom tab is only a fallback if that start fails.
            if (startPlainChrome(context, pkg, uri)) {
                return true;
            }
            if (openCustomTab(context, pkg, uri)) {
                return true;
            }
        }
        return openDefault(context, uri);
    }

    /** Opens as a Custom Tab in {@code pkg}; falls back to a plain tab there. */
    private boolean openCustomTab(Context context, String pkg, Uri uri) {
        IBinder session = SESSIONS.get(pkg);
        if (session != null) {
            return startView(context, pkg, uri, session);
        }
        Intent service = new Intent(SERVICE_ACTION).setPackage(pkg);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                IBinder s = newSession(binder);
                if (s != null) {
                    synchronized (SESSIONS) {
                        SESSIONS.put(pkg, s);
                    }
                    startView(context, pkg, uri, s);
                } else {
                    startPlainChrome(context, pkg, uri);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (SESSIONS) {
                    SESSIONS.remove(pkg);
                }
            }
        };
        try {
            boolean bound = context.bindService(service, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind CustomTabsService pkg=" + pkg + " bound=" + bound);
            if (!bound) {
                return startPlainChrome(context, pkg, uri);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "bind CustomTabsService failed pkg=" + pkg, e);
            return false;
        }
    }

    /** warmup + newSession over raw binder; returns the session binder or null. */
    private static IBinder newSession(IBinder service) {
        try {
            Parcel warm = Parcel.obtain();
            Parcel warmReply = Parcel.obtain();
            try {
                warm.writeInterfaceToken(SERVICE_TOKEN);
                warm.writeLong(0L);
                service.transact(TX_WARMUP, warm, warmReply, 0);
                warmReply.readInt();
            } finally {
                warm.recycle();
                warmReply.recycle();
            }
            Binder callback = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    // Opaque session handle: acknowledge every callback.
                    return true;
                }
            };
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SERVICE_TOKEN);
                data.writeStrongBinder(callback);
                service.transact(TX_NEW_SESSION, data, reply, 0);
                reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
            return callback;
        } catch (Exception e) {
            Log.w(TAG, "newSession failed", e);
            return null;
        }
    }

    private boolean startView(Context context, String pkg, Uri uri, IBinder session) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        Bundle extras = new Bundle();
        extras.putBinder(OAuthConstants.EXTRA_SESSION, session);
        extras.putInt(OAuthConstants.EXTRA_TOOLBAR_COLOR, OAuthConstants.TOOLBAR_COLOR_DARK);
        view.putExtras(extras);
        view.putExtra(OAuthConstants.EXTRA_APP_ID, context.getPackageName());
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        view.setPackage(pkg);
        try {
            context.startActivity(view);
            Log.i(TAG, "opened Custom Tab pkg=" + pkg + " url=" + uri);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Custom Tab start failed pkg=" + pkg, e);
            return false;
        }
    }

    private boolean startPlainChrome(Context context, String pkg, Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        view.setPackage(pkg);
        try {
            context.startActivity(view);
            Log.i(TAG, "opened plain Chrome tab pkg=" + pkg + " url=" + uri);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "plain Chrome tab failed pkg=" + pkg, e);
            return false;
        }
    }

    private boolean openDefault(Context context, Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(view);
            Log.i(TAG, "opened default browser url=" + uri);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "no browser available for url: " + uri, e);
            return false;
        }
    }
}
