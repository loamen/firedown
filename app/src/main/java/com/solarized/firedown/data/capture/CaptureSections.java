package com.solarized.firedown.data.capture;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.CaptureDownloadState;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Captured sheet's section rules, as pure functions the view model calls
 * on every emission (see {@link CaptureItem} for the layout they produce).
 */
public final class CaptureSections {

    /**
     * Ceiling on the origins one status query binds. SQLite before 3.32 (every
     * Android below 11) refuses more than 999 bound variables, and a single
     * statement with more fails outright rather than degrading. Past this many
     * distinct pages in one tab's capture list the marks simply stop for the
     * oldest ones.
     */
    public static final int MAX_ORIGINS = 900;

    private CaptureSections() {
    }

    /** An image capture renders as a square. GIF and SVG are images here,
     *  as in the chip rail. */
    public static boolean isSquare(@NonNull BrowserDownloadEntity e) {
        return FileUriHelper.isImage(e.getMimeType());
    }

    /** A video renders as a tile (HLS/DASH manifests included: isVideo knows
     *  their mime types). Not an audio capture in a video container (the
     *  entity's audio flag, an m4a served as video/mp4) nor an audio
     *  playlist (audio/mpegurl, which isVideo also accepts): those are rows,
     *  and a tile would show them as a blank frame. */
    public static boolean isTile(@NonNull BrowserDownloadEntity e) {
        String mime = e.getMimeType();
        return !isAudioCapture(e) && FileUriHelper.isVideo(mime);
    }

    private static boolean isAudioCapture(@NonNull BrowserDownloadEntity e) {
        return e.isAudio() || FileUriHelper.isAudio(e.getMimeType());
    }

    /** A subtitle track renders as a compact row. */
    public static boolean isCompact(@NonNull BrowserDownloadEntity e) {
        return FileUriHelper.isSubtitle(e.getMimeType());
    }

    /**
     * Order inside a group: the video first, then its audio, its subtitles,
     * any other file, and the images last — the media are what a visit to the
     * sheet is usually for, and the images sit as one block below them instead
     * of interleaving. Rank 0 is exactly the tiles ({@link #isTile}), so they
     * stay one block and line up two to a line. Used for BOTH groups since the
     * redesign (it was the current page only, when every capture was the same
     * tile).
     */
    public static int typeRank(@NonNull BrowserDownloadEntity e) {
        String mime = e.getMimeType();
        if (isTile(e)) return 0;
        if (isAudioCapture(e)) return 1;
        if (FileUriHelper.isSubtitle(mime)) return 2;
        if (FileUriHelper.isImage(mime)) return 4;
        return 3;
    }

    /**
     * The sheet's items for an already sorted and limited capture list: the
     * current page's captures, then a divider and the earlier ones; inside
     * each group the video tiles, the rows (audio, subtitles, other files),
     * then an "Images N" header and the squares.
     *
     * @param shown          captures to show, sorted group-first then by
     *                       {@link #typeRank}, already cut to the list limit
     * @param isCurrent      whether a capture belongs to the page shown now
     * @param filtered       a filter chip is active: no headers (the chip
     *                       names the type) and no type labels
     * @param currentImages  images in the current group BEFORE the limit, so
     *                       the header's count doesn't grow as the user
     *                       scrolls more in
     * @param earlierImages  the same for the earlier group
     * @param earlierTotal   every capture of the earlier group before the
     *                       limit: the divider's count
     * @param marks          download marks by capture uid
     * @param captionCounts  subtitle tracks per page origin, over the tab's
     *                       unfiltered captures (see {@link #captionCounts})
     */
    @NonNull
    public static List<CaptureItem> build(@NonNull List<BrowserDownloadEntity> shown,
                                          @NonNull Predicate<BrowserDownloadEntity> isCurrent,
                                          boolean filtered,
                                          int currentImages,
                                          int earlierImages,
                                          int earlierTotal,
                                          @NonNull Map<Integer, CaptureMark> marks,
                                          @NonNull Map<String, Integer> captionCounts) {
        List<CaptureItem> out = new ArrayList<>(shown.size() + 3);
        int group = -1;
        boolean headerAdded = false;
        for (BrowserDownloadEntity e : shown) {
            if (e == null) continue;
            int g = isCurrent.test(e) ? CaptureItem.GROUP_CURRENT : CaptureItem.GROUP_EARLIER;
            if (g != group) {
                if (group == CaptureItem.GROUP_CURRENT && g == CaptureItem.GROUP_EARLIER) {
                    out.add(CaptureItem.divider(earlierTotal));
                }
                group = g;
                headerAdded = false;
            }
            CaptureMark mark = marks.get(e.getUid());
            boolean earlier = g == CaptureItem.GROUP_EARLIER;
            if (isTile(e)) {
                out.add(CaptureItem.tile(e, g, earlier, captionCountFor(e, captionCounts), mark));
            } else if (isCompact(e)) {
                out.add(CaptureItem.compact(e, g, !filtered, earlier, mark));
            } else if (isSquare(e)) {
                if (!filtered && !headerAdded) {
                    int count = g == CaptureItem.GROUP_CURRENT ? currentImages : earlierImages;
                    out.add(CaptureItem.header(g, count));
                    headerAdded = true;
                }
                out.add(CaptureItem.square(e, g, !filtered, mark));
            } else {
                out.add(CaptureItem.row(e, g, !filtered, earlier,
                        captionCountFor(e, captionCounts), mark));
            }
        }
        return out;
    }

    /**
     * Subtitle captures per page origin. The count lands on the VIDEO tile of
     * that page only ({@link #captionCountFor}): captions belong to a video,
     * and every other capture of the page (thumbnail sprites, a player-logo
     * SVG, posters, the audio) shares the origin and would otherwise inherit
     * it — the "CC 1 on a PNG" bug.
     */
    @NonNull
    public static Map<String, Integer> captionCounts(@Nullable Collection<BrowserDownloadEntity> captures) {
        if (captures == null || captures.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> counts = new HashMap<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null || !FileUriHelper.isSubtitle(e.getMimeType())) continue;
            String origin = e.getFileOrigin();
            if (!TextUtils.isEmpty(origin)) {
                counts.merge(origin, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int captionCountFor(@NonNull BrowserDownloadEntity e,
                                       @NonNull Map<String, Integer> counts) {
        if (!isTile(e)) return 0;
        String origin = e.getFileOrigin();
        if (TextUtils.isEmpty(origin)) return 0;
        Integer count = counts.get(origin);
        return count != null ? count : 0;
    }

    /** Distinct non-empty origins of the given captures, in order, capped at
     *  {@link #MAX_ORIGINS}: the status query's bound list. */
    @NonNull
    public static List<String> origins(@Nullable Collection<BrowserDownloadEntity> captures) {
        if (captures == null || captures.isEmpty()) return Collections.emptyList();
        Set<String> origins = new LinkedHashSet<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null) continue;
            String origin = e.getFileOrigin();
            if (!TextUtils.isEmpty(origin)) {
                origins.add(origin);
                if (origins.size() >= MAX_ORIGINS) break;
            }
        }
        return new ArrayList<>(origins);
    }

    /** The status rows as one mark per download URL; two rows for one URL
     *  (a retry beside the failed attempt) keep the stronger mark. */
    @NonNull
    public static Map<String, CaptureMark> marksByUrl(@Nullable List<CaptureDownloadState> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyMap();
        Map<String, CaptureMark> byUrl = new HashMap<>();
        for (CaptureDownloadState row : rows) {
            if (row == null || TextUtils.isEmpty(row.url)) continue;
            CaptureMark mark = CaptureMark.fromRow(row.status, row.progress, row.live, row.errorType);
            if (mark != null) {
                byUrl.put(row.url, CaptureMark.stronger(byUrl.get(row.url), mark));
            }
        }
        return byUrl;
    }

    /**
     * The mark of each capture, by uid. A download stores the URL it fetched,
     * which is the capture's own URL or, for a capture with quality variants,
     * the URL of the variant that was picked ({@code DownloadRequest.from}
     * swaps it in). So a capture matches on its URL plus every variant's; a
     * YouTube download keeps the capture URL (its variants carry no URL of
     * their own) and matches on the first.
     */
    @NonNull
    public static Map<Integer, CaptureMark> marksByUid(@Nullable Collection<BrowserDownloadEntity> captures,
                                                       @NonNull Map<String, CaptureMark> byUrl) {
        if (captures == null || captures.isEmpty() || byUrl.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, CaptureMark> byUid = new HashMap<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null) continue;
            CaptureMark mark = byUrl.get(e.getFileUrl());
            List<FFmpegEntity> streams = e.getStreams();
            if (streams != null) {
                for (FFmpegEntity stream : streams) {
                    String url = stream != null ? stream.getStreamUrl() : null;
                    if (!TextUtils.isEmpty(url)) {
                        mark = CaptureMark.stronger(mark, byUrl.get(url));
                    }
                }
            }
            if (mark != null) {
                byUid.put(e.getUid(), mark);
            }
        }
        return byUid;
    }

    /**
     * Counts for the batch summary card. A capture with no row yet is pending:
     * the engine inserts its row a moment after the dispatch.
     */
    @NonNull
    public static BatchSummary summarize(@NonNull Collection<Integer> uids,
                                         @NonNull Map<Integer, CaptureMark> marks) {
        int saved = 0;
        int downloading = 0;
        int pending = 0;
        int failed = 0;
        int percentSum = 0;
        for (Integer uid : uids) {
            CaptureMark mark = marks.get(uid);
            if (mark == null || mark.state == CaptureMark.PENDING) {
                pending++;
            } else if (mark.state == CaptureMark.PROGRESS) {
                downloading++;
                percentSum += mark.indeterminate ? 0 : mark.percent;
            } else if (mark.state == CaptureMark.SAVED) {
                saved++;
                percentSum += 100;
            } else {
                failed++;
                percentSum += 100;
            }
        }
        int total = uids.size();
        int percent = total > 0 ? percentSum / total : 0;
        return new BatchSummary(total, saved, downloading, pending, failed, percent);
    }

    /** What the batch card says: "Saved 3 of 20 · 4 downloading · 13 pending". */
    public static final class BatchSummary {
        public final int total;
        public final int saved;
        public final int downloading;
        public final int pending;
        public final int failed;
        /** Overall progress for the card's bar, 0–100: each finished item
         *  (saved or failed) counts whole, each running one by its percent. */
        public final int percent;

        BatchSummary(int total, int saved, int downloading, int pending, int failed, int percent) {
            this.total = total;
            this.saved = saved;
            this.downloading = downloading;
            this.pending = pending;
            this.failed = failed;
            this.percent = percent;
        }

        public boolean isDone() {
            return downloading == 0 && pending == 0;
        }
    }
}
