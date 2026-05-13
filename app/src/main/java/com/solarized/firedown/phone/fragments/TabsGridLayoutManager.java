package com.solarized.firedown.phone.fragments;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * GridLayoutManager that exposes a "scroll to this position on first
 * paint, and tell me when it has actually stuck" hook, and disables
 * predictive item animations.
 *
 * <p><b>Why disable predictive animations:</b> The tabs grid doesn't
 * visually animate insertions or removals — cards just appear or
 * disappear. With predictive animations on,
 * {@code notifyItemRangeChanged} with a payload (e.g. an active tab's
 * thumbnail loading after the page is already on screen) triggers a
 * pre-layout pass followed by a real layout pass. Between the two
 * passes the LM resets {@code mAnchorInfo.mValid} to false, so the
 * post-layout pass re-runs {@code updateAnchorFromChildren} — which
 * iterates {@code mChildHelper} indices, not adapter positions. The
 * scrap/reattach of the changed view holder shuffles those indices,
 * so the "first reference child" picked during step 2 isn't the same
 * child step 1 anchored on. Result: the viewport drifts by one row.
 * Returning {@code false} from {@code supportsPredictiveItemAnimations}
 * tells RecyclerView to skip step 1 entirely — single layout pass,
 * no anchor drift, no need to fight the LM with a custom override.</p>
 *
 * <p><b>Sticky initial-position hook:</b> a plain
 * {@code scrollToPositionWithOffset} sets {@code mPendingScrollPosition}
 * and requests a layout, but during the tabs page's first paint a
 * layout pass triggered by inset dispatch or the postpone release can
 * discard that pending scroll before the row becomes visible. The
 * sticky version re-issues the scroll on each {@code onLayoutCompleted}
 * until {@code findFirstVisibleItemPosition} matches; once it does,
 * the {@code onReached} callback fires (the cue to release the
 * postponed enter transition).</p>
 *
 * <p>Self-clears on item-count mismatch and skips pre-layout passes.</p>
 */
public class TabsGridLayoutManager extends GridLayoutManager {

    private int mInitialPosition = RecyclerView.NO_POSITION;
    @Nullable private Runnable mOnReached;

    public TabsGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    /**
     * Request a one-shot scroll to {@code position}. The LM keeps
     * re-issuing the scroll on every post-layout pass until
     * {@code findFirstVisibleItemPosition()} reports the row is the
     * first visible (or gives up if the data set shrinks below the
     * target). Fires {@code onReached} the first time that condition
     * is observed.
     *
     * <p>Passing {@code NO_POSITION} cancels any pending request.</p>
     */
    public void setInitialPosition(int position, @Nullable Runnable onReached) {
        mInitialPosition = position;
        mOnReached = onReached;
        if (position != RecyclerView.NO_POSITION) {
            scrollToPositionWithOffset(position, 0);
        }
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (mInitialPosition == RecyclerView.NO_POSITION) return;
        if (state.isPreLayout()) return;

        if (state.getItemCount() <= mInitialPosition) {
            Runnable cb = mOnReached;
            mInitialPosition = RecyclerView.NO_POSITION;
            mOnReached = null;
            if (cb != null) cb.run();
            return;
        }

        if (findFirstVisibleItemPosition() == mInitialPosition) {
            Runnable cb = mOnReached;
            mInitialPosition = RecyclerView.NO_POSITION;
            mOnReached = null;
            if (cb != null) cb.run();
        } else {
            // Some intermediate layout pass (postpone release, view
            // attach, …) discarded mPendingScrollPosition. Re-issue it
            // — the next onLayoutCompleted will re-check.
            scrollToPositionWithOffset(mInitialPosition, 0);
        }
    }
}
