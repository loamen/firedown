package com.solarized.firedown.utils;

/** Harness stub: the visit code logs VisitTrace lines; keep them visible. */
public final class DebugLog {
    public static boolean verbose = false;

    private DebugLog() {}

    public static void d(String tag, String message) {
        if (verbose) System.out.println("  [" + tag + "] " + message);
    }

    public static String preview(CharSequence text) {
        return text == null ? "null" : text.toString();
    }
}
