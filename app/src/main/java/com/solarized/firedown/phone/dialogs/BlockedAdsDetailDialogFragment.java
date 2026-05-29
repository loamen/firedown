package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.ui.adapters.BlockedTrackerDetailAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Drill-down sheet listing the uBlock-blocked hosts for the active
 * page. Opened from the SecurityStateSheet's "Ads blocked" stat card.
 *
 * <p>Sibling of {@link BlockedTrackersDetailDialogFragment} but the
 * two sheets serve different mechanisms: that one surfaces the ETP
 * (Enhanced Tracking Protection) per-category counts, this one
 * surfaces uBlock's filter-list blocks. We can't honestly merge them
 * — uBlock's static engine throws away filter-list origin at compile
 * time, so we can't classify a uBlock block as "ad" vs "tracker" the
 * way ETP can; the user-facing distinction is "what got blocked"
 * vs "what got fingerprinted/cross-site-tracked".</p>
 *
 * <p>Data flow: this sheet does NOT poll uBlock. It asks once on
 * open via {@link com.solarized.firedown.geckoview.GeckoRuntimeHelper#requestPageBlocks()},
 * and firedown.js responds with the active tab's hostname tally via
 * {@code pageBlocks} on the native port. The
 * {@link com.solarized.firedown.geckoview.GeckoUblockHelper}
 * LiveData is per-mode, so an incognito sheet only sees incognito
 * data and vice versa.</p>
 */
public class BlockedAdsDetailDialogFragment extends BaseBottomSheetDialogFragment {

    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BlockedTrackerDetailAdapter mAdapter;
    private TextView mSubtitle;
    private TextView mEmptyView;
    private RecyclerView mRecyclerView;

    /** Re-poll cadence while the sheet is visible. firedown.js only emits
     *  pageBlocks in response to requestPageBlocks(), so the list would
     *  otherwise freeze at the open-time snapshot; poll so blocks landing
     *  while the user reads the sheet keep it live. Started in onResume,
     *  stopped in onPause. */
    private static final long POLL_INTERVAL_MS = 1000L;
    private final android.os.Handler mPollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            mGeckoRuntimeHelper.requestPageBlocks();
            mPollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_blocked_ads_detail, container, false);

        mSubtitle = mView.findViewById(R.id.detail_subtitle);
        mEmptyView = mView.findViewById(R.id.detail_empty);
        mRecyclerView = mView.findViewById(R.id.detail_recycler);

        // Reuse the blocked-trackers detail adapter (host on the left,
        // ×N count on the right) so this sheet and its sibling
        // BlockedTrackersDetailDialogFragment read identically. We feed
        // it HostRow items only — no category Headers — since uBlock
        // blocks carry no category. The Home "Top trackers" card keeps
        // its own count-left leaderboard row (item_top_tracker); that's
        // a ranking, not a per-page host list.
        mAdapter = new BlockedTrackerDetailAdapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setHasFixedSize(false);

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Subscribe to the per-mode page-blocks stream — empty by
        // cold-start, populates after the onResume requestPageBlocks poll
        // round-trips through firedown.js.
        LiveData<List<GeckoUblockHelper.HostCount>> stream = mIsIncognito
                ? mIncognitoStateViewModel.getPageBlocks()
                : mGeckoStateViewModel.getPageBlocks();

        stream.observe(getViewLifecycleOwner(), this::render);
        // The first JS round-trip + the ongoing live polling are driven by
        // onResume/onPause so the list both populates on open and keeps
        // refreshing as new blocks land while the sheet stays open.
    }

    @Override
    public void onResume() {
        super.onResume();
        // Fire one request now (the previous stream value keeps painting
        // until it lands, so a quick reopen doesn't blank the list), then
        // keep polling on the interval.
        mPollHandler.removeCallbacks(mPollRunnable);
        mPollHandler.post(mPollRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mPollHandler.removeCallbacks(mPollRunnable);
    }

    @Override
    public void onDestroyView() {
        mPollHandler.removeCallbacks(mPollRunnable);
        super.onDestroyView();
    }


    private void render(@Nullable List<GeckoUblockHelper.HostCount> items) {
        if (items == null || items.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            mSubtitle.setText(getResources().getQuantityString(
                    R.plurals.blocked_ads_summary, 0, 0));
            return;
        }

        int total = 0;
        List<BlockedTrackerDetailAdapter.Item> rows = new ArrayList<>(items.size());
        for (GeckoUblockHelper.HostCount hc : items) {
            total += hc.count;
            rows.add(new BlockedTrackerDetailAdapter.HostRow(hc.host, (int) hc.count));
        }

        mEmptyView.setVisibility(View.GONE);
        mRecyclerView.setVisibility(View.VISIBLE);
        mAdapter.submitList(rows);

        mSubtitle.setText(getResources().getQuantityString(
                R.plurals.blocked_ads_summary, total, total));
    }
}
