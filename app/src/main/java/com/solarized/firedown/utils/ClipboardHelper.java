package com.solarized.firedown.utils;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One correct clipboard read, shared by every "paste" affordance.
 *
 * <p>Encodes the two lessons {@code AutoCompleteView.showClipboard} documented
 * the hard way:
 * <ul>
 *   <li><b>One {@code getPrimaryClip()} call.</b> A multi-call chain fires
 *       Android 13's "App pasted from your clipboard" toast once per call.</li>
 *   <li><b>{@code coerceToText}, not {@code getText}.</b> Browsers (Brave/
 *       Chromium) put copied text under {@code text/uri-list}, where
 *       {@code getText()} returns null and the paste looks empty; coerceToText
 *       handles every representation.</li>
 * </ul>
 *
 * <p>The read is a binder call into system_server's clipboard service and must
 * never be fatal: a dying service throws {@code RuntimeException}
 * (DeadSystemException), some OEM services throw {@code SecurityException} on
 * background reads — both return empty here rather than crashing.
 */
public final class ClipboardHelper {

    private static final String TAG = "ClipboardHelper";

    private ClipboardHelper() {
    }

    /**
     * @return the primary clip coerced to trimmed text, or empty string when
     * the clipboard is empty/unreadable. Never null.
     */
    @NonNull
    public static String readTrimmedText(@Nullable Context context) {
        if (context == null) {
            return "";
        }
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = null;
        try {
            if (clipboard != null) {
                clip = clipboard.getPrimaryClip();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "clipboard read failed", e);
            return "";
        }
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence raw = clip.getItemAt(0).coerceToText(context);
        return raw == null ? "" : raw.toString().trim();
    }

    /**
     * When the current primary clip was copied, in wall-clock milliseconds,
     * or 0 when unknown (empty clipboard, unreadable, or a value that is not
     * a wall-clock time). Reads only the clip's DESCRIPTION, which does not
     * fire Android's paste toast.
     */
    public static long primaryClipCopiedAt(@Nullable Context context) {
        if (context == null) {
            return 0;
        }
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            ClipDescription description = clipboard == null ? null : clipboard.getPrimaryClipDescription();
            long stamp = description == null ? 0 : description.getTimestamp();
            // A wall-clock stamp is far past 2001; anything smaller is not
            // one (an uptime-based value would make every clip look old).
            return stamp > WALL_CLOCK_FLOOR_MS ? stamp : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 2001-09-09 — below this a timestamp is not a wall-clock time. */
    private static final long WALL_CLOCK_FLOOR_MS = 1_000_000_000_000L;
}
