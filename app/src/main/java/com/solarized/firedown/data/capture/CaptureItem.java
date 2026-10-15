package com.solarized.firedown.data.capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One row of the Captured sheet. The sheet is a mixed list since the 2026-10
 * redesign (canvas "Captured Content sheet": option A, then F after the device
 * test, then G after F's): every capture but an image is a TILE, the
 * Downloads grid tile itself (same layout file, title and "VIDEO · 2:01 · CC 1"
 * over the picture), images are SQUARES under an "Images / N files" HEADER,
 * and captures from earlier pages of the tab sit behind one FOLD row that
 * opens in place. The view model builds this list
 * ({@link CaptureSections#build}) so the section structure, the per-item
 * presentation flags and the download marks land in ONE submit: a chip tap
 * can never paint the old list in the new presentation, which is the class
 * of bug the old adapter-side "presentation commit" machinery existed for.
 */
public final class CaptureItem {

    public static final int TYPE_SQUARE = 1;
    public static final int TYPE_HEADER = 2;
    public static final int TYPE_FOLD = 3;
    public static final int TYPE_TILE = 4;

    /** Captures from the page the tab shows now. */
    public static final int GROUP_CURRENT = 0;
    /** Captures from earlier pages of the same tab (every capture, when the
     *  tab shows no page). */
    public static final int GROUP_EARLIER = 1;

    public final int type;
    public final int group;
    /** The capture; null for a header or the fold. */
    @Nullable public final BrowserDownloadEntity entity;
    /** Show the type label (VIDEO, AUDIO, GIF…). False while a filter chip is
     *  active: the chip already states the type. */
    public final boolean showType;
    /** End a tile's facts with the capture's domain: only on earlier pages,
     *  and only when they span more than one site (with one, the fold row
     *  names it). */
    public final boolean showDomain;
    /** A header's count, or the fold's: the earlier group's captures. */
    public final int count;
    /** Subtitle tracks captured for this video's page ("CC 2"), counted over
     *  the tab's whole capture list so a chip that hides the subtitles doesn't
     *  hide the count. 0 for everything but a video. */
    public final int captionCount;
    /** What the download table says about this capture, or null. */
    @Nullable public final CaptureMark mark;
    /** The fold is open: the earlier captures follow it. */
    public final boolean open;
    /** The fold's sites, in order of first appearance. */
    @NonNull public final List<String> domains;
    /** The fold's stacked thumbnails, at most three captures. */
    @NonNull public final List<BrowserDownloadEntity> preview;

    private CaptureItem(int type, int group, @Nullable BrowserDownloadEntity entity,
                        boolean showType, boolean showDomain, int count, int captionCount,
                        @Nullable CaptureMark mark, boolean open, @NonNull List<String> domains,
                        @NonNull List<BrowserDownloadEntity> preview) {
        this.type = type;
        this.group = group;
        this.entity = entity;
        this.showType = showType;
        this.showDomain = showDomain;
        this.count = count;
        this.captionCount = captionCount;
        this.mark = mark;
        this.open = open;
        this.domains = domains;
        this.preview = preview;
    }

    private static CaptureItem capture(int type, @NonNull BrowserDownloadEntity entity, int group,
                                       boolean showType, boolean showDomain, int captionCount,
                                       @Nullable CaptureMark mark) {
        return new CaptureItem(type, group, entity, showType, showDomain, 0, captionCount, mark,
                false, Collections.emptyList(), Collections.emptyList());
    }

    /** Anything but an image: a video, its audio, a subtitle track, a file. */
    public static CaptureItem tile(@NonNull BrowserDownloadEntity entity, int group,
                                   boolean showType, boolean showDomain, int captionCount,
                                   @Nullable CaptureMark mark) {
        return capture(TYPE_TILE, entity, group, showType, showDomain, captionCount, mark);
    }

    public static CaptureItem square(@NonNull BrowserDownloadEntity entity, int group,
                                     boolean showType, @Nullable CaptureMark mark) {
        return capture(TYPE_SQUARE, entity, group, showType, false, 0, mark);
    }

    public static CaptureItem header(int group, int count) {
        return new CaptureItem(TYPE_HEADER, group, null, false, false, count, 0, null,
                false, Collections.emptyList(), Collections.emptyList());
    }

    public static CaptureItem fold(int count, boolean open, @NonNull List<String> domains,
                                   @NonNull List<BrowserDownloadEntity> preview) {
        return new CaptureItem(TYPE_FOLD, GROUP_EARLIER, null, false, false, count, 0, null,
                open, domains, preview);
    }

    public boolean isCapture() {
        return entity != null;
    }

    /** The capture's uid, or -1 for a header or the fold. */
    public int uid() {
        return entity != null ? entity.getUid() : -1;
    }

    /** Identity for the differ: a capture is itself across emissions; a
     *  header is its group's header; there is one fold. */
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
                && group == other.group
                && open == other.open
                && domains.equals(other.domains)
                && samePreview(preview, other.preview);
    }

    public boolean sameMarkAs(@NonNull CaptureItem other) {
        return Objects.equals(mark, other.mark);
    }

    private static boolean samePreview(@NonNull List<BrowserDownloadEntity> a,
                                       @NonNull List<BrowserDownloadEntity> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getUid() != b.get(i).getUid()) return false;
        }
        return true;
    }
}
