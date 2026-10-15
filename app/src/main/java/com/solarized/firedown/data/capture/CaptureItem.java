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
 * presentation flags and the download marks land in ONE submit. There are no
 * filter chips: the list's own order (video, audio, other, then the images
 * under their header) does what they did, and selecting a whole section is
 * the Images header's check.
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
    /** End a tile's facts with the capture's domain: only on earlier pages,
     *  and only when they span more than one site (with one, the fold row
     *  names it). */
    public final boolean showDomain;
    /** The page shown now caught this one video and no other: on a
     *  two-column grid the tile spans the row (BrowserOptionAdapter#spanSize),
     *  so a single-video page doesn't leave half a row empty beside it. False
     *  for a page with several videos (none is "the" video), for an earlier
     *  page's video, and for every other kind of tile. */
    public final boolean onlyVideo;
    /** A header's count, or the fold's: the earlier group's captures. */
    public final int count;
    /** Subtitle tracks captured for this video's page ("CC 2"), counted over
     *  the tab's whole capture list, the tracks the sheet leaves to the video
     *  included. 0 for everything but a video. */
    public final int captionCount;
    /** What the download table says about this capture, or null. */
    @Nullable public final CaptureMark mark;
    /** The fold is open: the earlier captures follow it. */
    public final boolean open;
    /** The fold can be closed: something of the page shown now sits above
     *  it. Without that the earlier captures are all the list holds, and the
     *  row is their heading, always open (see {@link CaptureSections#earlierOpen}). */
    public final boolean collapsible;
    /** The fold's sites, in order of first appearance. */
    @NonNull public final List<String> domains;
    /** The fold's stacked thumbnails, at most three captures. */
    @NonNull public final List<BrowserDownloadEntity> preview;

    private CaptureItem(int type, int group, @Nullable BrowserDownloadEntity entity,
                        boolean showDomain, boolean onlyVideo,
                        int count, int captionCount,
                        @Nullable CaptureMark mark, boolean open, boolean collapsible,
                        @NonNull List<String> domains,
                        @NonNull List<BrowserDownloadEntity> preview) {
        this.type = type;
        this.group = group;
        this.entity = entity;
        this.showDomain = showDomain;
        this.onlyVideo = onlyVideo;
        this.count = count;
        this.captionCount = captionCount;
        this.mark = mark;
        this.open = open;
        this.collapsible = collapsible;
        this.domains = domains;
        this.preview = preview;
    }

    private static CaptureItem capture(int type, @NonNull BrowserDownloadEntity entity, int group,
                                       boolean showDomain, boolean onlyVideo,
                                       int captionCount, @Nullable CaptureMark mark) {
        return new CaptureItem(type, group, entity, showDomain, onlyVideo, 0,
                captionCount, mark, false, false, Collections.emptyList(), Collections.emptyList());
    }

    /** Anything but an image: a video, its audio, a subtitle track, a file. */
    public static CaptureItem tile(@NonNull BrowserDownloadEntity entity, int group,
                                   boolean showDomain, boolean onlyVideo,
                                   int captionCount, @Nullable CaptureMark mark) {
        return capture(TYPE_TILE, entity, group, showDomain, onlyVideo, captionCount, mark);
    }

    public static CaptureItem square(@NonNull BrowserDownloadEntity entity, int group,
                                     @Nullable CaptureMark mark) {
        return capture(TYPE_SQUARE, entity, group, false, false, 0, mark);
    }

    public static CaptureItem header(int group, int count) {
        return new CaptureItem(TYPE_HEADER, group, null, false, false, count, 0, null,
                false, false, Collections.emptyList(), Collections.emptyList());
    }

    public static CaptureItem fold(int count, boolean open, boolean collapsible,
                                   @NonNull List<String> domains,
                                   @NonNull List<BrowserDownloadEntity> preview) {
        return new CaptureItem(TYPE_FOLD, GROUP_EARLIER, null, false, false, count, 0,
                null, open, collapsible, domains, preview);
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
        return showDomain == other.showDomain
                && onlyVideo == other.onlyVideo
                && count == other.count
                && captionCount == other.captionCount
                && group == other.group
                && open == other.open
                && collapsible == other.collapsible
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
