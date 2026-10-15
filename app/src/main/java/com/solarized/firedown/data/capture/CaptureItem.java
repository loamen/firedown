package com.solarized.firedown.data.capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;

import java.util.Objects;

/**
 * One row of the Captured sheet. The sheet is a mixed list since the 2026-10
 * redesign (canvas "Captured Content sheet", option A, then board F after the
 * device test): videos are 16:9 TILES two to a line (the picture leads, since
 * an embedded player's capture is often named "src" or a hash), images are
 * SQUARES three to a line under an "Images N" HEADER, subtitles are COMPACT
 * rows, audio and other files are full-width ROWS, and captures from earlier
 * pages of the tab sit below an "Earlier in this tab" DIVIDER. The view
 * model builds this list
 * ({@link CaptureSections#build}) so the section structure, the per-item
 * presentation flags and the download marks land in ONE submit: a chip tap
 * can never paint the old list in the new presentation, which is the class
 * of bug the old adapter-side "presentation commit" machinery existed for.
 */
public final class CaptureItem {

    public static final int TYPE_ROW = 0;
    public static final int TYPE_SQUARE = 1;
    public static final int TYPE_HEADER = 2;
    public static final int TYPE_DIVIDER = 3;
    public static final int TYPE_TILE = 4;
    public static final int TYPE_COMPACT = 5;

    /** Captures from the page the tab shows now (or every capture, when the
     *  tab has no current page). */
    public static final int GROUP_CURRENT = 0;
    /** Captures from earlier pages of the same tab. */
    public static final int GROUP_EARLIER = 1;

    public final int type;
    public final int group;
    /** The capture; null for a header or the divider. */
    @Nullable public final BrowserDownloadEntity entity;
    /** Lead the facts with the type label (VIDEO, AUDIO, GIF…). False while a
     *  filter chip is active: the chip already states the type. */
    public final boolean showType;
    /** End a row's facts with the capture's domain. Only on earlier-page
     *  rows: on the current page every capture shares the address bar's. */
    public final boolean showDomain;
    /** A header's count, or the divider's: the earlier group's captures. */
    public final int count;
    /** Subtitle tracks captured for this video's page ("CC 2"), counted over
     *  the tab's whole capture list so a chip that hides the subtitles doesn't
     *  hide the count. 0 for everything but a video row. */
    public final int captionCount;
    /** What the download table says about this capture, or null. */
    @Nullable public final CaptureMark mark;

    private CaptureItem(int type, int group, @Nullable BrowserDownloadEntity entity,
                        boolean showType, boolean showDomain, int count, int captionCount,
                        @Nullable CaptureMark mark) {
        this.type = type;
        this.group = group;
        this.entity = entity;
        this.showType = showType;
        this.showDomain = showDomain;
        this.count = count;
        this.captionCount = captionCount;
        this.mark = mark;
    }

    public static CaptureItem row(@NonNull BrowserDownloadEntity entity, int group,
                                  boolean showType, boolean showDomain, int captionCount,
                                  @Nullable CaptureMark mark) {
        return new CaptureItem(TYPE_ROW, group, entity, showType, showDomain, 0,
                captionCount, mark);
    }

    public static CaptureItem square(@NonNull BrowserDownloadEntity entity, int group,
                                     boolean showType, @Nullable CaptureMark mark) {
        return new CaptureItem(TYPE_SQUARE, group, entity, showType, false, 0, 0, mark);
    }

    /** A video. No type label ever: the tile's shape says video. */
    public static CaptureItem tile(@NonNull BrowserDownloadEntity entity, int group,
                                   boolean showDomain, int captionCount,
                                   @Nullable CaptureMark mark) {
        return new CaptureItem(TYPE_TILE, group, entity, false, showDomain, 0,
                captionCount, mark);
    }

    /** A subtitle track: a short row named by its language. */
    public static CaptureItem compact(@NonNull BrowserDownloadEntity entity, int group,
                                      boolean showType, boolean showDomain,
                                      @Nullable CaptureMark mark) {
        return new CaptureItem(TYPE_COMPACT, group, entity, showType, showDomain, 0, 0, mark);
    }

    public static CaptureItem header(int group, int count) {
        return new CaptureItem(TYPE_HEADER, group, null, false, false, count, 0, null);
    }

    public static CaptureItem divider(int count) {
        return new CaptureItem(TYPE_DIVIDER, GROUP_EARLIER, null, false, false, count, 0, null);
    }

    public boolean isCapture() {
        return entity != null;
    }

    /** The capture's uid, or -1 for a header or the divider. */
    public int uid() {
        return entity != null ? entity.getUid() : -1;
    }

    /** Identity for the differ: a capture is itself across emissions; a
     *  header is its group's header; there is one divider. */
    public boolean sameItemAs(@NonNull CaptureItem other) {
        if (type != other.type) return false;
        if (entity != null && other.entity != null) {
            return entity.getUid() == other.entity.getUid();
        }
        return group == other.group;
    }

    /** Everything but the entity's own fields (the entity differ compares
     *  those) and the mark. */
    public boolean samePresentationAs(@NonNull CaptureItem other) {
        return showType == other.showType
                && showDomain == other.showDomain
                && count == other.count
                && captionCount == other.captionCount
                && group == other.group;
    }

    public boolean sameMarkAs(@NonNull CaptureItem other) {
        return Objects.equals(mark, other.mark);
    }
}
