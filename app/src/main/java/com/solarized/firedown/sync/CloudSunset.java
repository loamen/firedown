package com.solarized.firedown.sync;

import android.content.Context;
import android.text.format.DateUtils;

import java.util.concurrent.TimeUnit;

/**
 * The Cloud Backup SUNSET — one compile-time switch that turns every WRITE
 * door of the hosted service off while the READ doors keep working, so a user
 * can bring their files home before the server is deleted.
 *
 * <p>Why a switch and not a deletion: the files are on a server that is being
 * shut down (firedown {@code docs/SERVER-SUNSET-PLAN.md}). The release that
 * carries this flag has to go out FIRST and stay restorable for weeks — new
 * backups and purchases stop at once, restore / stream / delete keep working
 * until {@link #DELETE_AT_MS}, and only a later release removes the code.
 * Removing it now would strand every file still on the server.
 *
 * <p>What {@link #ACTIVE} gates (every one re-checks it at the moment of the
 * action, because an older surface — a finished-download notification, a
 * queued WorkManager job — can outlive the screen that offered it):
 * <ul>
 *   <li>the download sheet's "Back up to cloud" row, the multi-select backup
 *       action, the finished-notification action and its receiver;</li>
 *   <li>{@code VaultBackupWorker} itself (a job queued before the update
 *       fails with the {@code closed} slug instead of uploading);</li>
 *   <li>buying credit (the Cloud screen CTA, the Backups overflow, the buy
 *       screen), pairing a browser, the Storage screen's upsell and
 *       "Free up space" cards — the latter deletes phone copies trusting the
 *       cloud, which is exactly what must not happen now;</li>
 *   <li>the Downloads banner and the account watch, which switch to the ONE
 *       message that matters: restore by the date.</li>
 * </ul>
 * What it does NOT touch: the Backups list (restore, restore all, stream,
 * delete), the recovery code (it still keys bookmark sync, which moves to the
 * www host and outlives the storage service), bookmark sync itself, and
 * {@code CreditSettleWorker} (a purchase paid before the update still settles
 * for the on-chain late window).
 *
 * <p>{@link #DELETE_AT_MS} is a constant on purpose: the date is a promise
 * made in copy, in 17 languages, and a value fetched from the very server
 * that is closing would have no fallback worth trusting. It must be set to
 * the real date before the release ships (the plan's checklist item), and it
 * must agree with the date the server operator reaps on.
 */
public final class CloudSunset {

    /** Every write door consults this. {@code false} restores the pre-sunset app. */
    public static final boolean ACTIVE = true;

    /**
     * The instant the server deletes what is left (UTC). SET BEFORE RELEASE —
     * this placeholder is deliberately far enough out that an unedited build
     * cannot promise a date that has already passed.
     */
    public static final long DELETE_AT_MS = 1_798_675_200_000L; // 2026-12-31T00:00:00Z

    private CloudSunset() {
    }

    /** True once the deletion instant has passed: nothing is left to restore. */
    public static boolean isPast() {
        return System.currentTimeMillis() >= DELETE_AT_MS;
    }

    /** Whole days until {@link #DELETE_AT_MS}, 0 once it has passed. */
    public static long daysLeft() {
        long ms = DELETE_AT_MS - System.currentTimeMillis();
        return Math.max(0, TimeUnit.MILLISECONDS.toDays(ms));
    }

    /** The deletion date in the device locale, date only ("31 December 2026"). */
    public static String deleteDate(Context context) {
        return DateUtils.formatDateTime(context, DELETE_AT_MS,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                        | DateUtils.FORMAT_UTC);
    }
}
