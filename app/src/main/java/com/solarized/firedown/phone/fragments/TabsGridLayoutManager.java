package com.solarized.firedown.phone.fragments;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * GridLayoutManager for the tabs grid that:
 *
 * <ol>
 *   <li><b>Disables predictive item animations</b>. The tab list LiveData
 *       re-emits on any per-tab state change (thumb decoded, title
 *       updated, active flag flipped) — DiffUtil produces partial-bind
 *       {@code notifyItemChanged} events with payloads. With predictive
 *       animations on, RecyclerView runs a pre-layout pass with the
 *       <em>old</em> state followed by a real layout with the new
 *       state. Between the two passes the LM resets
 *       {@code mAnchorInfo.mValid}, so the post-layout pass re-runs
 *       {@code updateAnchorFromChildren} — which iterates
 *       {@code mChildHelper} indices, not adapter positions. The
 *       scrap/reattach of the changed view holder shuffles those
 *       indices, the "first reference child" picked during the second
 *       pass isn't the same one the first pass anchored on, and the
 *       viewport drifts by one row. Returning {@code false} from
 *       {@link #supportsPredictiveItemAnimations()} tells RecyclerView
 *       to skip the pre-layout pass entirely — single layout per
 *       notify, no anchor drift. Cost: insert / remove / move events
 *       no longer animate (changes are still cross-faded since that
 *       doesn't need predictive). The tabs grid doesn't visually
 *       need slide-in animations, and the original anchor-drift bug
 *       (PR #134) was exactly this — see logs in PR #155 confirming
 *       the second submitList commit causes {@code firstVisible} to
 *       drift from 9 to 7.</li>
 *
 *   <li><b>Trust-the-first-layout hook.</b>
 *       {@code scrollToPositionWithOffset} queued from
 *       {@link #setInitialPosition(int, Runnable)} arms a one-shot
 *       waiting flag; the very next non-pre {@code onLayoutCompleted}
 *       fires the {@code onReached} callback regardless of where the
 *       LM ended up placing the row. If the LM has to clamp (target
 *       near the end of a short list), clamping still leaves the
 *       active row in view, which is the only invariant the caller
 *       cares about.</li>
 * </ol>
 */
public class TabsGridLayoutManager extends GridLayoutManager {

    private int mScrollTarget = RecyclerView.NO_POSITION;
    @Nullable private Runnable mOnReached;
    private boolean mWaitingForFirstLayout = false;

    public TabsGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    /**
     * Request a one-shot scroll to {@code scrollTarget} and fire
     * {@code onReached} on the next non-pre-layout pass. The callback
     * fires regardless of whether the LM was able to honor the
     * request exactly — callers (the holder postpone-release in
     * BaseTabsFragment) just need to know "the LM has finished
     * positioning the list for the initial scroll".
     *
     * <p>Pass {@code scrollTarget == NO_POSITION} to cancel without
     * firing the callback (or use {@link #cancelInitialPosition()}).
     */
    public void setInitialPosition(int scrollTarget, @Nullable Runnable onReached) {
        mScrollTarget = scrollTarget;
        mOnReached = onReached;
        mWaitingForFirstLayout = scrollTarget != RecyclerView.NO_POSITION;
        if (mWaitingForFirstLayout) {
            scrollToPositionWithOffset(scrollTarget, 0);
        }
    }

    /** Cancel any pending request without firing the callback. */
    public void cancelInitialPosition() {
        mScrollTarget = RecyclerView.NO_POSITION;
        mOnReached = null;
        mWaitingForFirstLayout = false;
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        android.util.Log.d("TabsJump",
                "[TabsGridLayoutManager] onLayoutCompleted preLayout=" + state.isPreLayout()
                        + " itemCount=" + state.getItemCount()
                        + " waiting=" + mWaitingForFirstLayout
                        + " firstVisible=" + findFirstVisibleItemPosition()
                        + " firstCompletelyVisible=" + findFirstCompletelyVisibleItemPosition());
        if (!mWaitingForFirstLayout) return;
        if (state.isPreLayout()) return;

        // First non-pre-layout pass after the scroll request: whatever
        // the LM produced is the final placement (predictive animations
        // do run a second pass, but it's restricted to running the
        // animator on the already-positioned children — it can't shift
        // the anchor). Release the gate.
        mWaitingForFirstLayout = false;
        Runnable cb = mOnReached;
        mScrollTarget = RecyclerView.NO_POSITION;
        mOnReached = null;
        if (cb != null) cb.run();
    }
}
