package com.nudroidlabs.nubrowse;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public class NuBrowseApp extends Application {
    private static final String CRASH_PREFS = "nubrowse_runtime_diagnose";
    private static final String KEY_LAST_CRASH = "last_crash";
    private static final String KEY_PHASE = "startup_phase";
    private static volatile boolean handlingCrash = false;

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (!handlingCrash) {
                handlingCrash = true;
                try {
                    saveCrash(thread, throwable);
                } catch (Throwable ignored) {
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void saveCrash(Thread thread, Throwable throwable) {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        String phase = prefs.getString(KEY_PHASE, "unknown");
        StringBuilder out = new StringBuilder();
        out.append("NUBROWSE RUNTIME DIAGNOSE\n");
        out.append("=========================\n");
        out.append("APP: NuBrowse M4\n");
        out.append("PHASE: ").append(phase).append('\n');
        out.append("THREAD: ").append(thread == null ? "unknown" : thread.getName()).append('\n');
        out.append("EXCEPTION: ").append(throwable == null ? "unknown" : throwable.getClass().getName()).append('\n');
        if (throwable != null && throwable.getMessage() != null) {
            out.append("MESSAGE: ").append(clean(throwable.getMessage())).append('\n');
        }
        out.append("ANDROID: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("DEVICE: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        appendFrames(out, throwable, "STACK");
        Throwable cause = throwable == null ? null : throwable.getCause();
        if (cause != null && cause != throwable) {
            out.append("CAUSE: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) out.append(": ").append(clean(cause.getMessage()));
            out.append('\n');
            appendFrames(out, cause, "CAUSE STACK");
        }
        prefs.edit().putString(KEY_LAST_CRASH, out.toString()).commit();
    }

    private static void appendFrames(StringBuilder out, Throwable throwable, String label) {
        if (throwable == null) return;
        out.append(label).append(":\n");
        StackTraceElement[] frames = throwable.getStackTrace();
        int added = 0;
        for (StackTraceElement frame : frames) {
            if (frame == null) continue;
            String className = frame.getClassName();
            if (className.startsWith("com.nudroidlabs.nubrowse") || added < 3) {
                out.append("  at ").append(frame.toString()).append('\n');
                added++;
            }
            if (added >= 10) break;
        }
    }

    private static String clean(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static void markPhase(Context context, String phase) {
        context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit().putString(KEY_PHASE, phase).apply();
    }

    static String getLastCrash(Context context) {
        return context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).getString(KEY_LAST_CRASH, null);
    }

    static void clearLastCrash(Context context) {
        context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_CRASH).putString(KEY_PHASE, "retry").commit();
        handlingCrash = false;
    }
}
