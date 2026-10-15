package com.solarized.firedown.data.models;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.capture.CaptureItem;
import com.solarized.firedown.data.capture.CaptureMark;
import com.solarized.firedown.data.capture.CaptureSections;
import com.solarized.firedown.data.capture.CaptureSections.BatchSummary;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.PriorityTaskThreadPoolExecutor;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class BrowserDownloadViewModel extends ViewModel {

    private final BrowserDownloadRepository mBrowserDownloadRepository;
    private final GeckoRuntimeHelper mGeckoRuntimeHelper;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final Sorting mSorting;
    private final PriorityTaskThreadPoolExecutor mPriorityExecutor;
    private final MutableLiveData<Integer> mObservableBrowserType = new MutableLiveData<>();

    /** The sheet's items: the capture list built into sections, with each
     *  capture's download mark. Rebuilt when either input moves. */
    private final MediatorLiveData<List<CaptureItem>> mItems = new MediatorLiveData<>();
    /** The status query's input; re-posted only when its value changes, since
     *  every post starts a new Room query. */
    private final MutableLiveData<MarksQuery> mMarksQuery = new MutableLiveData<>();
    private final MutableLiveData<BatchSummary> mBatchSummary = new MutableLiveData<>();

    @Nullable private Snapshot mSnapshot;
    /** Whether the fold is open: the user's last tap, or the sheet's own
     *  choice. Null until either, which is closed. While the page shown now
     *  lists nothing (no page, nothing caught yet, nothing of the chip's
     *  type) the fold is open whatever this says, and can't be closed: the
     *  earlier captures are all there is (CaptureSections.earlierOpen). That
     *  opening LATCHES here (rebuild), so a capture landing on the page a
     *  moment later doesn't snap the list shut under the user. The view model
     *  is the sheet's own, so every opening of the sheet starts over, and a
     *  rotation keeps the state. */
    @Nullable private Boolean mEarlierOpen;
    @NonNull private Map<String, CaptureMark> mMarksByUrl = Collections.emptyMap();

    /** The captures of the batch this sheet dispatched, by uid, in dispatch
     *  order. Held as entities, not uids: a chip or the list limit can take a
     *  batch member out of the shown list, and its mark must still count. */
    private final LinkedHashMap<Integer, BrowserDownloadEntity> mBatch = new LinkedHashMap<>();
    /** Batch members whose download row has been seen. One that was seen and
     *  is gone was deleted from Downloads, and leaves the batch — otherwise
     *  the card would count it as pending forever. */
    private final Set<Integer> mBatchSeen = new HashSet<>();

    /** Sites the fold row lists at most; the line ellipsizes long before. */
    private static final int MAX_FOLD_DOMAINS = 8;

    @Inject
    public BrowserDownloadViewModel(
            BrowserDownloadRepository repository,
            DownloadDataRepository downloadDataRepository,
            GeckoRuntimeHelper geckoRuntimeHelper,
            GeckoStateDataRepository geckoStateDataRepository,
            PriorityTaskThreadPoolExecutor priorityExecutor,
            Sorting sorting) {

        this.mBrowserDownloadRepository = repository;
        this.mGeckoRuntimeHelper = geckoRuntimeHelper;
        this.mGeckoStateDataRepository = geckoStateDataRepository;
        this.mPriorityExecutor = priorityExecutor;
        this.mSorting = sorting;

        // Use Transformations.switchMap to react to limit changes
        LiveData<Snapshot> snapshots = Transformations.switchMap(mObservableBrowserType, limit ->
                Transformations.map(mBrowserDownloadRepository.getData(), entities ->
                        snapshot(entities, limit)
                )
        );
        LiveData<Map<String, CaptureMark>> marks = Transformations.switchMap(mMarksQuery, query -> {
            if (query.origins.isEmpty()) {
                return new MutableLiveData<>(Collections.emptyMap());
            }
            return Transformations.map(
                    downloadDataRepository.getCaptureStatesLive(query.safe, query.origins),
                    CaptureSections::marksByUrl);
        });
        mItems.addSource(snapshots, snapshot -> {
            mSnapshot = snapshot;
            updateMarksQuery();
            rebuild();
        });
        mItems.addSource(marks, byUrl -> {
            mMarksByUrl = byUrl != null ? byUrl : Collections.emptyMap();
            rebuild();
        });
    }

    public LiveData<List<CaptureItem>> getCaptureItems(int limit) {
        mObservableBrowserType.postValue(limit);
        return mItems;
    }

    /** The batch card's counts, or null while no batch is in flight. */
    public LiveData<BatchSummary> getBatchSummary() {
        return mBatchSummary;
    }

    /**
     * Remember the captures the sheet just sent to Downloads as one batch, so
     * the card above the list can count them down. A single download needs no
     * card (its row says it), so fewer than two captures clear the batch.
     */
    public void startBatch(@NonNull Collection<BrowserDownloadEntity> captures) {
        mBatch.clear();
        mBatchSeen.clear();
        if (captures.size() >= 2) {
            for (BrowserDownloadEntity e : captures) {
                if (e != null) {
                    mBatch.put(e.getUid(), e);
                }
            }
        }
        updateMarksQuery();
        rebuild();
    }

    /** Open the fold if it's closed, close it if it's open. */
    public void toggleEarlier() {
        Snapshot snapshot = mSnapshot;
        // Nothing of the page above the fold: it is a heading, not a toggle.
        if (snapshot == null || snapshot.currentTotal == 0) {
            return;
        }
        mEarlierOpen = !isEarlierOpen(snapshot);
        rebuild();
    }

    private boolean isEarlierOpen(@NonNull Snapshot snapshot) {
        return CaptureSections.earlierOpen(mEarlierOpen, snapshot.currentTotal);
    }

    private void updateMarksQuery() {
        List<BrowserDownloadEntity> watched = new ArrayList<>();
        boolean safe = false;
        if (mSnapshot != null) {
            watched.addAll(mSnapshot.shown);
            safe = mSnapshot.incognito;
        }
        watched.addAll(mBatch.values());
        MarksQuery query = new MarksQuery(safe, CaptureSections.origins(watched));
        if (!query.equals(mMarksQuery.getValue())) {
            mMarksQuery.setValue(query);
        }
    }

    private void rebuild() {
        Snapshot snapshot = mSnapshot;
        if (snapshot == null) {
            return;
        }
        // Not only from null: a fold the user closed under another chip
        // opens here too, or the first capture of the page would close the
        // earlier ones the user is looking at.
        if (snapshot.currentTotal == 0 && snapshot.earlierTotal > 0) {
            mEarlierOpen = true;
        }
        Map<Integer, CaptureMark> marks = CaptureSections.marksByUid(snapshot.shown, mMarksByUrl);
        final Set<BrowserDownloadEntity> current = snapshot.current;
        CaptureSections.Earlier earlier = snapshot.earlierTotal == 0
                ? CaptureSections.Earlier.NONE
                : new CaptureSections.Earlier(snapshot.earlierTotal, isEarlierOpen(snapshot),
                        snapshot.currentTotal > 0, snapshot.earlierDomains,
                        snapshot.earlierPreview);
        mItems.setValue(CaptureSections.build(snapshot.shown,
                current::contains,
                snapshot.filtered, snapshot.currentImages, snapshot.earlierImages,
                earlier, marks, snapshot.captionCounts));
        updateBatchSummary();
    }

    private void updateBatchSummary() {
        if (mBatch.isEmpty()) {
            if (mBatchSummary.getValue() != null) {
                mBatchSummary.setValue(null);
            }
            return;
        }
        Map<Integer, CaptureMark> marks = CaptureSections.marksByUid(mBatch.values(), mMarksByUrl);
        // A member with no row yet is pending (the engine inserts it a moment
        // after the dispatch); one whose row was there and is gone was deleted
        // from Downloads and stops counting.
        mBatch.keySet().removeIf(uid -> !marks.containsKey(uid) && mBatchSeen.contains(uid));
        mBatchSeen.addAll(marks.keySet());
        if (mBatch.isEmpty()) {
            mBatchSummary.setValue(null);
            return;
        }
        mBatchSummary.setValue(CaptureSections.summarize(mBatch.keySet(), marks));
    }

    /**
     * In-flight inspect-task count (queued + running). The capture sheet uses
     * this to show a "scanning…" indicator while a capture is still being
     * processed (e.g. an HLS-master fetch that takes a few seconds).
     */
    public LiveData<Integer> getInflight() {
        return mPriorityExecutor.getInFlight();
    }

    @Nullable
    private Snapshot snapshot(List<BrowserDownloadEntity> entities, int limit) {
        if (entities == null) return null;

        // Note: Using the injected mGeckoRuntimeHelper instead of static call
        int currentTabId = mGeckoRuntimeHelper.getTabId();
        GeckoState state = mGeckoStateDataRepository.peekCurrentGeckoState();
        boolean onPage = state != null && !state.isHome();
        final int currentVisitId = onPage ? state.getVisitId() : 0;
        final String currentPageKey = onPage ? state.getPageIdentityKey() : null;

        // Sort BEFORE limiting so the current page's media always floats to
        // the top (and never gets truncated by the limit). Order:
        //   1. captures from the page-visit you're on now, first
        //   2. within each group, by type: video, audio, subtitle, other,
        //      images (CaptureSections.typeRank) — the tiles first, the video
        //      leading them, then the images as one block of squares under
        //      their header
        //   3. then most-recent first (descending creationTime)
        // Step 1 is the session anchor. Within one tab the repo accumulates
        // captures across navigations; each is stamped with the navigation
        // visit id that was active when it was captured (GeckoState#getVisitId),
        // so "this page" is identified by a browser navigation boundary rather
        // than by matching origin URL strings — which different extensions
        // spell inconsistently (m./www., feed vs deep-link). The origin is a
        // SECOND test on top, never the anchor: a capture whose own page is
        // the page on screen counts too (currentPage, the feed-then-open
        // case), compared by page identity key so m./www., a trailing slash
        // and tracking params can't tell them apart.
        // Step 2 surfaces the actual video + captions above the page's
        // thumbnails (which are captured later, so recency alone would bury the
        // video). Since the mixed layout it applies to BOTH groups: the
        // section builder puts each group's images under one header, which
        // needs them contiguous.
        List<BrowserDownloadEntity> tab = entities.stream()
                .filter(entity -> entity != null && entity.getTabId() == currentTabId)
                .collect(Collectors.toList());
        final Set<BrowserDownloadEntity> current = currentPage(tab, currentVisitId, currentPageKey);
        Comparator<BrowserDownloadEntity> order = Comparator
                .comparingInt((BrowserDownloadEntity e) -> current.contains(e) ? 0 : 1)
                .thenComparingInt(CaptureSections::typeRank)
                .thenComparing(Comparator.reverseOrder());
        // A subtitle whose video's ⋮ already offers it is not an item
        // (CaptureSections.ridesWithVideo): the video counts it and its
        // picker lists it, and the sheet has no Subtitle chip that could
        // list it again. Dropped before anything is counted, so the fold's
        // "N files" and the empty-page rule count what is shown.
        boolean filtered = mSorting.isBrowserFiltered();
        Set<String> captionDoors = CaptureSections.captionDoors(tab);
        List<BrowserDownloadEntity> all = tab.stream()
                .filter(mSorting::getPredicateBrowser)
                .filter(e -> !CaptureSections.ridesWithVideo(e, captionDoors))
                .sorted(order)
                .collect(Collectors.toList());

        // Header counts come from the whole group, before the limit cuts it,
        // so "40 files" doesn't read 15, then 25, as the user scrolls more in.
        // The same for the fold: its count, its sites and its thumbnails.
        int currentImages = 0;
        int earlierImages = 0;
        List<BrowserDownloadEntity> earlier = new ArrayList<>();
        Set<String> earlierOrigins = new LinkedHashSet<>();
        for (BrowserDownloadEntity e : all) {
            boolean onCurrent = current.contains(e);
            if (!onCurrent) {
                earlier.add(e);
                String origin = e.getFileOrigin();
                earlierOrigins.add(origin == null || origin.isEmpty() ? e.getFileUrl() : origin);
            }
            if (CaptureSections.isSquare(e)) {
                if (onCurrent) {
                    currentImages++;
                } else {
                    earlierImages++;
                }
            }
        }

        // Parsed per distinct page, not per capture: a page's captures share
        // its origin, and this runs on every emission.
        Set<String> earlierDomains = new LinkedHashSet<>();
        for (String origin : earlierOrigins) {
            if (earlierDomains.size() >= MAX_FOLD_DOMAINS) break;
            String domain = origin != null ? WebUtils.getDomainName(origin) : null;
            if (domain != null && !domain.isEmpty()) {
                earlierDomains.add(domain);
            }
        }

        List<BrowserDownloadEntity> shown = limit > 0 && all.size() > limit
                ? new ArrayList<>(all.subList(0, limit))
                : all;
        boolean incognito = !shown.isEmpty() && shown.get(0).isIncognito();
        logVisitTrace(currentTabId, currentVisitId, current, shown);
        return new Snapshot(shown, current, filtered,
                currentImages, earlierImages, all.size() - earlier.size(), earlier.size(),
                new ArrayList<>(earlierDomains), CaptureSections.preview(earlier), incognito,
                CaptureSections.captionCounts(tab));
    }

    /**
     * VisitTrace: the READ side of the Captured pin — the tab's current anchor
     * plus each shown entity's stamped visit id, in render order. Pairs with
     * GeckoState.updateVisit's id-move lines and the capture stamps
     * (GeckoRuntimeHelper), so one repro of "the video isn't pinned first"
     * names the cause in logcat: entities whose {@code v=} trails the anchor
     * were stamped under a page key the tab has since respelled (e.g. a
     * YouTube Mix's list=/index= params). {@code adb logcat -s VisitTrace:*}.
     * Debug builds only (DebugLog no-ops in release); names are previews per
     * the truncate-user-text logging rule.
     */
    private static void logVisitTrace(int currentTabId, int currentVisitId,
                                      Set<BrowserDownloadEntity> current,
                                      List<BrowserDownloadEntity> result) {
        if (!BuildConfig.DEBUG) {
            return; // skip the string-building work entirely in release
        }
        StringBuilder sb = new StringBuilder("sheet tab=").append(currentTabId)
                .append(" anchor=").append(currentVisitId);
        // " *" = this page by visit id, " o" = this page by origin (captured
        // earlier, for this page), "  " = an earlier page.
        for (BrowserDownloadEntity e : result) {
            String mark = e.getVisitId() == currentVisitId && currentVisitId > 0 ? " *"
                    : current.contains(e) ? " o" : "  ";
            sb.append("\n  v=").append(e.getVisitId()).append(mark)
                    .append(' ').append(DebugLog.preview(e.getFileName()));
        }
        DebugLog.d("VisitTrace", sb.toString());
    }

    /**
     * The captures of {@code tab} that belong to the page currently shown in
     * the active tab: stamped with its visit id, or made FOR it — their
     * origin names this page ({@link GeckoState#originNamesPage}). A feed or
     * a profile captures its items before the user opens one; opening it
     * moves the visit, and without the second test the page's own video sat
     * behind "Earlier in this tab". {@code visitId == 0} means no anchor
     * (home / unavailable): nothing is "this page" and the list is pure
     * recency. An identity set, so the sections, the counts and the fold all
     * read the same answer for the same snapshot.
     */
    private static Set<BrowserDownloadEntity> currentPage(List<BrowserDownloadEntity> tab,
                                                          int visitId,
                                                          @Nullable String pageKey) {
        Set<BrowserDownloadEntity> current = Collections.newSetFromMap(new IdentityHashMap<>());
        if (visitId <= 0) {
            return current;
        }
        // Parsed once per distinct origin: a page's captures share it, and
        // this runs on every emission.
        Map<String, Boolean> named = new HashMap<>();
        for (BrowserDownloadEntity e : tab) {
            if (e.getVisitId() == visitId) {
                current.add(e);
                continue;
            }
            String origin = e.getFileOrigin();
            if (pageKey == null || origin == null || origin.isEmpty()) {
                continue;
            }
            Boolean hit = named.get(origin);
            if (hit == null) {
                hit = GeckoState.originNamesPage(origin, pageKey);
                named.put(origin, hit);
            }
            if (hit) {
                current.add(e);
            }
        }
        return current;
    }

    /**
     * Subtitle entities captured for the given video origin, current tab only.
     * Used by the variant picker to render its captions multi-select section.
     * Returns an empty list if the origin is unknown or has no captions.
     */
    public List<BrowserDownloadEntity> subtitlesForOrigin(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) return Collections.emptyList();
        List<BrowserDownloadEntity> entities = mBrowserDownloadRepository.getData().getValue();
        if (entities == null) return Collections.emptyList();
        int currentTabId = mGeckoRuntimeHelper.getTabId();
        return entities.stream()
                .filter(e -> e != null
                        && e.getTabId() == currentTabId
                        && FileUriHelper.isSubtitle(e.getMimeType())
                        && origin.equals(e.getFileOrigin()))
                .collect(Collectors.toList());
    }

    public int getCurrentSortBrowserId(){
        return mSorting.getCurrentSortBrowserId();
    }

    public String getCurrentSortForIds(int selectedIds){
        return mSorting.getCurrentSortForIds(selectedIds);
    }

    public void setCurrentSortBrowser(int type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void setCurrentSortBrowser(String type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void clearBrowserDownloads() {
        mBrowserDownloadRepository.postClear();
    }

    public void sortBrowserDownloads(String sorting) {
        mSorting.setCurrentSortBrowser(sorting);
        mBrowserDownloadRepository.postComplete();
    }

    public void update() {
        mBrowserDownloadRepository.postComplete();
    }

    public void loadMore(int limit) {
        mObservableBrowserType.postValue(limit);
    }

    /** One filtered, sorted, limited capture list plus what the section
     *  builder needs to know about it, taken together so they can't disagree. */
    private static final class Snapshot {
        final List<BrowserDownloadEntity> shown;
        /** The captures that are "this page" (see currentPage). */
        final Set<BrowserDownloadEntity> current;
        final boolean filtered;
        final int currentImages;
        final int earlierImages;
        final int currentTotal;
        final int earlierTotal;
        final List<String> earlierDomains;
        final List<BrowserDownloadEntity> earlierPreview;
        final boolean incognito;
        final Map<String, Integer> captionCounts;

        Snapshot(List<BrowserDownloadEntity> shown, Set<BrowserDownloadEntity> current,
                 boolean filtered,
                 int currentImages, int earlierImages, int currentTotal, int earlierTotal,
                 List<String> earlierDomains, List<BrowserDownloadEntity> earlierPreview,
                 boolean incognito, Map<String, Integer> captionCounts) {
            this.shown = shown;
            this.current = current;
            this.filtered = filtered;
            this.currentImages = currentImages;
            this.earlierImages = earlierImages;
            this.currentTotal = currentTotal;
            this.earlierTotal = earlierTotal;
            this.earlierDomains = earlierDomains;
            this.earlierPreview = earlierPreview;
            this.incognito = incognito;
            this.captionCounts = captionCounts;
        }
    }

    /** The status query's arguments. A private tab's downloads go to the Safe
     *  Folder, so its captures are matched against vault rows only. */
    private static final class MarksQuery {
        final boolean safe;
        final List<String> origins;

        MarksQuery(boolean safe, List<String> origins) {
            this.safe = safe;
            this.origins = origins;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MarksQuery other)) return false;
            return safe == other.safe && origins.equals(other.origins);
        }

        @Override
        public int hashCode() {
            return Objects.hash(safe, origins);
        }
    }
}