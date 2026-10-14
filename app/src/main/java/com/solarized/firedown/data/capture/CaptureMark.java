package com.solarized.firedown.data.capture;

import androidx.annotation.Nullable;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.utils.MessageHelper;

import java.util.Objects;

/**
 * What the download table says about one capture in the Captured sheet: is
 * it saved, still downloading, waiting, or did it fail. Built from the rows
 * {@code DownloadDao.getCaptureStatesLive} returns, matched to a capture by
 * URL (see {@link CaptureSections#marksByUid}).
 *
 * <p>Immutable, with value equality, because it rides inside a
 * {@link CaptureItem} and the sheet's differ compares items by content: a
 * progress tick must read as a change, an unchanged mark must not.
 */
public final class CaptureMark {

    /** The file is in Downloads (or the Safe Folder for a private tab). */
    public static final int SAVED = 1;
    /** The last attempt ended in an error the user can retry from Downloads. */
    public static final int FAILED = 2;
    /** Queued behind other downloads. */
    public static final int PENDING = 3;
    /** Bytes are moving (or the post-download mux is running). */
    public static final int PROGRESS = 4;

    public final int state;
    /** 0–100, meaningful only for a determinate {@link #PROGRESS}. */
    public final int percent;
    /** True for a live stream (no length) and for {@link #finishing}. */
    public final boolean indeterminate;
    /** Bytes are all in and the mux pass is running ("Finishing…"). */
    public final boolean finishing;

    private CaptureMark(int state, int percent, boolean indeterminate, boolean finishing) {
        this.state = state;
        this.percent = percent;
        this.indeterminate = indeterminate;
        this.finishing = finishing;
    }

    /**
     * The mark for one download row, or null when the row says nothing worth
     * showing. An ERROR row whose type is {@link MessageHelper#FILE_NOT_FOUND}
     * is a finished download whose file has since left the device (the
     * missing-file sweep marks those) — calling that "couldn't download"
     * would be wrong, so it gets no mark.
     */
    @Nullable
    public static CaptureMark fromRow(int status, int progress, boolean live, int errorType) {
        if (status == Download.PROGRESS) {
            boolean finishing = progress == Download.PROCESSING_PROGRESS;
            int percent = Math.max(0, Math.min(100, progress));
            return new CaptureMark(PROGRESS, percent, live || finishing, finishing);
        }
        if (status == Download.QUEUED) {
            return new CaptureMark(PENDING, 0, false, false);
        }
        if (status == Download.FINISHED) {
            return new CaptureMark(SAVED, 0, false, false);
        }
        if (status == Download.ERROR && errorType != MessageHelper.FILE_NOT_FOUND) {
            return new CaptureMark(FAILED, 0, false, false);
        }
        return null;
    }

    /**
     * Which of two marks for the same capture wins: a download in flight says
     * more than a queued one, a queued one more than an old saved copy, and
     * any of them more than an earlier failed attempt (the user retried).
     */
    @Nullable
    public static CaptureMark stronger(@Nullable CaptureMark a, @Nullable CaptureMark b) {
        if (a == null) return b;
        if (b == null) return a;
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(CaptureMark mark) {
        return switch (mark.state) {
            case PROGRESS -> 4;
            case PENDING -> 3;
            case SAVED -> 2;
            default -> 1;
        };
    }

    public boolean isActive() {
        return state == PROGRESS || state == PENDING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CaptureMark other)) return false;
        return state == other.state
                && percent == other.percent
                && indeterminate == other.indeterminate
                && finishing == other.finishing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, percent, indeterminate, finishing);
    }
}
