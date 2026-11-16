import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collections;

public class AgyClickableNotify {
    private static Bitmap cropScaleWhite(Bitmap src, int outSize, float fillRatio) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (pixels[y * w + x] >>> 24) & 0xff;
                if (alpha > 10) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return src;
        }
        Bitmap out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        int target = Math.round(outSize * fillRatio);
        float scale = Math.min((float) target / cropW, (float) target / cropH);
        int dstW = Math.round(cropW * scale);
        int dstH = Math.round(cropH * scale);
        int left = (outSize - dstW) / 2;
        int top = (outSize - dstH) / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, new Rect(minX, minY, maxX + 1, maxY + 1), new Rect(left, top, left + dstW, top + dstH), p);
        return out;
    }

    public static void main(String[] args) {
        try {
            String title = args.length > 0 ? args[0] : "Antigravity";
            String message = args.length > 1 ? args[1] : "";
            String tag = args.length > 2 ? args[2] : "agy_turn_done";

            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method sysMain = atClass.getDeclaredMethod("systemMain");
            sysMain.setAccessible(true);
            Object at = sysMain.invoke(null);

            Method getSysCtx = atClass.getDeclaredMethod("getSystemContext");
            getSysCtx.setAccessible(true);
            Context sysCtx = (Context) getSysCtx.invoke(at);
            Context baseShellCtx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);

            Context shellCtx = new ContextWrapper(baseShellCtx) {
                @Override
                public String getPackageName() {
                    return "com.android.shell";
                }
                @Override
                public String getOpPackageName() {
                    return "com.android.shell";
                }
            };

            Intent launchIntent = sysCtx.getPackageManager().getLaunchIntentForPackage("com.agy");
            if (launchIntent == null) {
                launchIntent = new Intent(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                launchIntent.setComponent(new ComponentName("com.agy", "com.agy.ui.launcher.LauncherDarkActivity"));
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(shellCtx, 0, launchIntent, piFlags);

            Method getService = NotificationManager.class.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object inm = getService.invoke(null);

            NotificationChannel ch = new NotificationChannel("agy_alerts", "Antigravity Alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("High-priority alerts and task completions");

            for (Method m : inm.getClass().getMethods()) {
                if (m.getName().equals("createNotificationChannels")) {
                    Class<?> plsClass = Class.forName("android.content.pm.ParceledListSlice");
                    Object slice = plsClass.getConstructor(java.util.List.class).newInstance(Collections.singletonList(ch));
                    if (m.getParameterCount() == 2) {
                        m.invoke(inm, "com.android.shell", slice);
                    } else if (m.getParameterCount() == 3) {
                        m.invoke(inm, "com.android.shell", "com.android.shell", slice);
                    }
                    break;
                }
            }

            Notification.Builder b = new Notification.Builder(shellCtx, "agy_alerts")
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setColor(0xFF5F6368)
                .setContentIntent(pi)
                .setAutoCancel(true);

            Bundle extras = new Bundle();
            extras.putString("android.substName", "Antigravity");
            b.addExtras(extras);

            File smallFile = new File("/data/local/tmp/agy_ic_notification.png");
            if (smallFile.isFile()) {
                byte[] smallBytes = Files.readAllBytes(smallFile.toPath());
                Bitmap raw = BitmapFactory.decodeByteArray(smallBytes, 0, smallBytes.length);
                if (raw != null) {
                    b.setSmallIcon(Icon.createWithBitmap(cropScaleWhite(raw, 144, 0.56f)));
                } else {
                    b.setSmallIcon(Icon.createWithData(smallBytes, 0, smallBytes.length));
                }
            } else {
                b.setSmallIcon(android.R.drawable.stat_notify_chat);
            }

            Notification n = b.build();

            for (Method m : inm.getClass().getMethods()) {
                if (m.getName().equals("enqueueNotificationWithTag")) {
                    m.invoke(inm, "com.android.shell", "com.android.shell", tag, 2001, n, 0);
                    break;
                }
            }
            System.out.println("OK: Posted neutral-gray monochrome A notification [" + tag + "]");
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
