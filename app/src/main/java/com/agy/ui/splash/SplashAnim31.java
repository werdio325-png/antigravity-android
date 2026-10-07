package com.agy.ui.splash;

import android.app.Activity;
import android.view.View;

/**
 * Cross-fades the system splash icon out into the 3-dot overlay (API31+).
 *
 * The bundled android.jar exposes the Android 12 preview callback signature
 * (onSplashScreenExit(SplashScreenView)); production devices ship
 * onSplashScreenExit(SplashScreenViewProvider). A reflective proxy lets the
 * same build satisfy both, and every call is best-effort.
 *
 * Isolated so the API31 types are only loaded when invoked.
 */
public final class SplashAnim31 {

    private SplashAnim31() {
    }

    public static void install(final Activity activity, final View dotSplash) {
        try {
            android.window.SplashScreen screen = activity.getSplashScreen();
            if (screen == null) {
                return;
            }
            Class<?> listenerClass = Class.forName(
                    "android.window.SplashScreen$OnExitAnimationListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy,
                                             java.lang.reflect.Method method,
                                             Object[] args) {
                            if ("onSplashScreenExit".equals(method.getName())
                                    && args != null && args.length == 1) {
                                animateExit(args[0], dotSplash);
                            }
                            return null;
                        }
                    });
            screen.setOnExitAnimationListener(
                    (android.window.SplashScreen.OnExitAnimationListener) listener);
        } catch (Throwable ignored) {
            // SplashScreen is best-effort; never block startup on it.
        }
    }

    private static void animateExit(final Object provider, final View dotSplash) {
        try {
            View icon = null;
            if (provider != null) {
                icon = reflectView(provider, "getView");
                if (icon == null) {
                    icon = reflectView(provider, "getIconView");
                }
            }
            if (icon != null) {
                icon.animate()
                        .alpha(0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(280)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                reflectVoid(provider, "remove");
                            }
                        })
                        .start();
            } else {
                reflectVoid(provider, "remove");
            }
            if (dotSplash != null) {
                dotSplash.setAlpha(0f);
                dotSplash.animate().alpha(1f).setDuration(280).start();
            }
        } catch (Throwable ignored) {
        }
    }

    private static View reflectView(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return (View) target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void reflectVoid(Object target, String method) {
        if (target == null) {
            return;
        }
        try {
            target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
        }
    }
}
