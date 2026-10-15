package com.solarized.firedown.harness;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.capture.*;
import com.solarized.firedown.data.capture.CaptureSections.BatchSummary;
import com.solarized.firedown.data.entity.*;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ui.diffs.CaptureItemDiffCallback;
import com.solarized.firedown.utils.MessageHelper;
import java.util.*;

/** Drives the REAL CaptureSections / CaptureMark / CaptureItem /
 *  CaptureItemDiffCallback against a stand-in entity. */
public class CaptureHarness {
  static int pass, fail;
  static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }
  static BrowserDownloadEntity e(int uid, String mime, String url, String origin) { return new BrowserDownloadEntity(uid, mime, url, origin); }
  static String shape(List<CaptureItem> items) {
    StringBuilder b = new StringBuilder();
    for (CaptureItem i : items) {
      b.append(switch (i.type) { case CaptureItem.TYPE_ROW -> "R" + i.uid() + (i.showType ? "t" : "") + (i.showDomain ? "d" : "") + (i.captionCount > 0 ? "c" + i.captionCount : "");
        case CaptureItem.TYPE_TILE -> "T" + i.uid() + (i.showType ? "t" : "") + (i.showDomain ? "d" : "") + (i.captionCount > 0 ? "c" + i.captionCount : "");
        case CaptureItem.TYPE_COMPACT -> "C" + i.uid() + (i.showType ? "t" : "") + (i.showDomain ? "d" : "");
        case CaptureItem.TYPE_SQUARE -> "S" + i.uid() + (i.showType ? "t" : "");
        case CaptureItem.TYPE_HEADER -> "H" + i.group + ":" + i.count; default -> "|" + i.count; }).append(' ');
    }
    return b.toString().trim();
  }
  static CaptureDownloadState row(String url, int status, int progress, boolean live, int err) {
    CaptureDownloadState s = new CaptureDownloadState(); s.url = url; s.status = status; s.progress = progress; s.live = live; s.errorType = err; return s;
  }

  public static void main(String[] a) {
    // typeRank orders video, audio, subtitle, other, image
    check("rank", CaptureSections.typeRank(e(1,"video/mp4","u","o")) == 0
        && CaptureSections.typeRank(e(1,"audio/mp4","u","o")) == 1
        && CaptureSections.typeRank(e(1,"text/vtt","u","o")) == 2
        && CaptureSections.typeRank(e(1,"application/zip","u","o")) == 3
        && CaptureSections.typeRank(e(1,"image/png","u","o")) == 4);

    // a video is a tile; audio in a video container (the entity's audio
    // flag) is a row, ranked with the audio so the tiles stay one block
    BrowserDownloadEntity m4a = e(2, "video/mp4", "a.m4a", "o"); m4a.audio = true;
    check("video is a tile", CaptureSections.isTile(e(1,"video/mp4","u","o")));
    check("audio-flagged mp4 is a row", !CaptureSections.isTile(m4a) && CaptureSections.typeRank(m4a) == 1);
    check("hls master is a tile, an audio playlist is not", CaptureSections.isTile(e(1,"application/x-mpegurl","u","o"))
        && !CaptureSections.isTile(e(1,"audio/mpegurl","u","o")));
    check("subtitle is compact", CaptureSections.isCompact(e(1,"text/vtt","u","o")) && !CaptureSections.isCompact(e(1,"audio/mp4","u","o")));

    // current page: video, audio, sub, 3 images; earlier: video, 2 images
    String P = "https://clips.example/v", Q = "https://firedown.app/s";
    List<BrowserDownloadEntity> shown = List.of(
        e(1,"video/mp4","v1",P), e(2,"audio/mp4","a1",P), e(3,"text/vtt","s1",P),
        e(4,"image/gif","g1",P), e(5,"image/png","i1",P), e(6,"image/png","i2",P),
        e(7,"video/mp4","v2",Q), e(8,"image/png","i3",Q), e(9,"image/png","i4",Q));
    Set<Integer> current = Set.of(1,2,3,4,5,6);
    Map<String,Integer> cc = CaptureSections.captionCounts(shown);
    List<CaptureItem> items = CaptureSections.build(shown, x -> current.contains(x.getUid()), false, 3, 40, 43, Map.of(), cc);
    check("unfiltered shape: " + shape(items), shape(items).equals("T1c1 R2t C3t H0:3 S4t S5t S6t |43 T7d H1:40 S8t S9t"));

    // under a chip: no headers, no type labels, divider stays
    List<BrowserDownloadEntity> imgs = List.of(e(5,"image/png","i1",P), e(6,"image/png","i2",P), e(8,"image/png","i3",Q));
    items = CaptureSections.build(imgs, x -> current.contains(x.getUid()), true, 2, 1, 1, Map.of(), Map.of());
    check("filtered shape: " + shape(items), shape(items).equals("S5 S6 |1 S8"));

    // no current page (home): one group, no divider, domains shown
    items = CaptureSections.build(shown, x -> false, false, 0, 4, 9, Map.of(), Map.of());
    check("no anchor: " + shape(items), shape(items).equals("T1d R2td C3td H1:4 S4t S5t S6t T7d S8t S9t"));
    // the second video sits after the first group's squares because the
    // input was not re-sorted for "one group" here; the builder keeps input
    // order inside a group (the view model's sort puts rows first).

    // only the current page: no divider
    items = CaptureSections.build(shown.subList(0, 6), x -> true, false, 3, 0, 0, Map.of(), Map.of());
    check("current only: " + shape(items), shape(items).equals("T1 R2t C3t H0:3 S4t S5t S6t"));

    // under the Subtitle chip: compact rows lose the type label, keep the domain
    items = CaptureSections.build(List.of(e(3,"text/vtt","s1",P), e(10,"text/vtt","s2",Q)),
        x -> current.contains(x.getUid()), true, 0, 0, 1, Map.of(), Map.of());
    check("subtitle chip: " + shape(items), shape(items).equals("C3 |1 C10d"));

    // the CC count lands on the video tile only, never on the audio-flagged mp4
    BrowserDownloadEntity cm = e(11, "video/mp4", "m", P); cm.audio = true;
    items = CaptureSections.build(List.of(e(1,"video/mp4","v1",P), cm), x -> true, false, 0, 0, 0, Map.of(), Map.of(P, 2));
    check("cc on tile only: " + shape(items), shape(items).equals("T1c2 R11t"));

    // marks by URL: stronger wins, FILE_NOT_FOUND is no mark
    Map<String,CaptureMark> byUrl = CaptureSections.marksByUrl(List.of(
        row("v1", Download.ERROR, 0, false, MessageHelper.IOEXCEPTION),
        row("v1", Download.PROGRESS, 42, false, 0),
        row("a1", Download.FINISHED, 100, false, 0),
        row("s1", Download.ERROR, 0, false, MessageHelper.FILE_NOT_FOUND),
        row("q720", Download.QUEUED, 0, false, 0),
        row("i1", Download.PROGRESS, Download.PROCESSING_PROGRESS, false, 0),
        row("i2", Download.PROGRESS, 0, true, 0),
        row("i3", Download.ERROR, 0, false, MessageHelper.BAD_REQUEST)));
    check("progress beats error", byUrl.get("v1").state == CaptureMark.PROGRESS && byUrl.get("v1").percent == 42);
    check("finished is saved", byUrl.get("a1").state == CaptureMark.SAVED);
    check("file-not-found is no mark", !byUrl.containsKey("s1"));
    check("processing = finishing", byUrl.get("i1").finishing && byUrl.get("i1").indeterminate && byUrl.get("i1").percent == 100);
    check("live = indeterminate", byUrl.get("i2").indeterminate && !byUrl.get("i2").finishing);
    check("failed", byUrl.get("i3").state == CaptureMark.FAILED);

    // marks by uid: a variant's URL matches its capture
    BrowserDownloadEntity v = e(20, "video/mp4", "master.m3u8", P);
    v.streams.add(new FFmpegEntity("q1080")); v.streams.add(new FFmpegEntity("q720")); v.streams.add(new FFmpegEntity(null));
    BrowserDownloadEntity saved = e(21, "audio/mp4", "a1", P);
    BrowserDownloadEntity none = e(22, "image/png", "nothing", P);
    Map<Integer,CaptureMark> byUid = CaptureSections.marksByUid(List.of(v, saved, none), byUrl);
    check("variant url matches", byUid.get(20) != null && byUid.get(20).state == CaptureMark.PENDING);
    check("own url matches", byUid.get(21).state == CaptureMark.SAVED);
    check("unmatched has none", !byUid.containsKey(22));
    // the capture URL AND a variant both matched: the stronger mark wins
    BrowserDownloadEntity both = e(23, "video/mp4", "a1", P); both.streams.add(new FFmpegEntity("q720"));
    check("stronger across keys", CaptureSections.marksByUid(List.of(both), byUrl).get(23).state == CaptureMark.PENDING);

    // stronger ordering
    CaptureMark prog = CaptureMark.fromRow(Download.PROGRESS, 10, false, 0), pend = CaptureMark.fromRow(Download.QUEUED, 0, false, 0),
        sav = CaptureMark.fromRow(Download.FINISHED, 0, false, 0), fl = CaptureMark.fromRow(Download.ERROR, 0, false, 1);
    check("stronger", CaptureMark.stronger(sav, prog) == prog && CaptureMark.stronger(pend, sav) == pend
        && CaptureMark.stronger(fl, sav) == sav && CaptureMark.stronger(null, fl) == fl && CaptureMark.stronger(pend, null) == pend);
    check("mark equality", prog.equals(CaptureMark.fromRow(Download.PROGRESS, 10, false, 0)) && !prog.equals(CaptureMark.fromRow(Download.PROGRESS, 11, false, 0)));

    // batch summary: no row yet counts as pending
    Map<Integer,CaptureMark> bm = new HashMap<>();
    bm.put(1, sav); bm.put(2, prog); bm.put(3, pend); bm.put(4, fl);
    BatchSummary s = CaptureSections.summarize(List.of(1,2,3,4,5), bm);
    check("summary counts", s.total == 5 && s.saved == 1 && s.downloading == 1 && s.pending == 2 && s.failed == 1 && !s.isDone());
    check("summary percent", s.percent == (100 + 10 + 0 + 100 + 0) / 5);
    check("summary done", CaptureSections.summarize(List.of(1,4), bm).isDone());

    // origins: distinct, non-empty, capped
    List<BrowserDownloadEntity> many = new ArrayList<>();
    for (int i = 0; i < 1000; i++) { many.add(e(i, "image/png", "u" + i, "o" + i)); many.add(e(i, "image/png", "u", "o" + i)); }
    many.add(e(5000, "image/png", "u", null)); many.add(e(5001, "image/png", "u", ""));
    List<String> origins = CaptureSections.origins(many);
    check("origins capped", origins.size() == CaptureSections.MAX_ORIGINS && origins.get(0).equals("o0") && new HashSet<>(origins).size() == origins.size());

    // diff: a mark-only change is a payload; a presentation change is not
    CaptureItemDiffCallback diff = new CaptureItemDiffCallback();
    BrowserDownloadEntity x = e(30, "video/mp4", "v", P);
    CaptureItem a0 = CaptureItem.row(x, 0, true, false, 0, null);
    CaptureItem a1 = CaptureItem.row(x, 0, true, false, 0, prog);
    CaptureItem a2 = CaptureItem.row(x, 0, false, false, 0, null);
    check("same item", diff.areItemsTheSame(a0, a1) && diff.areItemsTheSame(a0, a2));
    check("mark change differs", !diff.areContentsTheSame(a0, a1) && diff.getChangePayload(a0, a1) == CaptureItemDiffCallback.PAYLOAD_MARK);
    check("presentation change no payload", !diff.areContentsTheSame(a0, a2) && diff.getChangePayload(a0, a2) == null);
    check("headers by group", diff.areItemsTheSame(CaptureItem.header(0, 3), CaptureItem.header(0, 4))
        && !diff.areItemsTheSame(CaptureItem.header(0, 3), CaptureItem.header(1, 3))
        && !diff.areContentsTheSame(CaptureItem.header(0, 3), CaptureItem.header(0, 4)));
    check("row vs square never same", !diff.areItemsTheSame(a0, CaptureItem.square(x, 0, true, null)));
    check("row vs tile never same", !diff.areItemsTheSame(a0, CaptureItem.tile(x, 0, false, 0, null)));
    CaptureItem t0 = CaptureItem.tile(x, 0, false, 1, null), t1 = CaptureItem.tile(x, 0, false, 1, prog);
    check("tile mark change is a payload", diff.areItemsTheSame(t0, t1) && diff.getChangePayload(t0, t1) == CaptureItemDiffCallback.PAYLOAD_MARK);
    check("divider same", diff.areContentsTheSame(CaptureItem.divider(3), CaptureItem.divider(3)));
    check("divider count change rebinds", diff.areItemsTheSame(CaptureItem.divider(3), CaptureItem.divider(4))
        && !diff.areContentsTheSame(CaptureItem.divider(3), CaptureItem.divider(4)));

    System.out.println(pass + " passed, " + fail + " failed");
    if (fail > 0) System.exit(1);
  }
}
