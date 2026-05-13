package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * GridLayoutManager that exposes a "scroll to this position on first
 * paint, and tell me when it has actually stuck" hook.
 *
 * <p>Background: a plain {@code scrollToPositionWithOffset(...)} sets
 * {@code mPendingScrollPosition} and requests a layout — but during the
 * tabs page's first paint the layout passes triggered by inset
 * dispatch, fragment-postpone release, view attach, etc. can consume or
 * discard that pending scroll before the row actually becomes
 * visible. Captured logs show {@code findFirstVisibleItemPosition()}
 * stuck at 0 for ~100 ms after the call, then jumping to the target —
 * a visible scroll the user reports as a bug.
 *
 * <p>This subclass turns the one-shot pending-scroll into a sticky
 * target: every {@code onLayoutCompleted} re-checks whether the target
 * row is actually the first visible. If not, it re-issues the scroll
 * for the next layout pass. Once {@code findFirstVisibleItemPosition()}
 * matches, the target clears and the {@code onReached} callback fires
 * — that's the cue to release a postponed enter transition.</p>
 *
 * <p>Self-clears on item-count mismatch ({@code state.getItemCount() <=
 * mInitialPosition}) and skips pre-layout passes (where pending scroll
 * is ignored by the superclass anyway).</p>
 */
public class TabsGridLayoutManager extends GridLayoutManager {

    private static final String DBG = "TabsScrollDbg";

    private int mInitialPosition = RecyclerView.NO_POSITION;
    @Nullable private Runnable mOnReached;

    public TabsGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    // ── Debug instrumentation ────────────────────────────────────────
    // Temporary: log every external scroll request and every
    // onLayoutChildren entry so we can find why firstVis shifts on
    // notifyItemRangeChanged for the active tab. Strip once root-cause
    // is identified.

    private void logCaller(String label, int position) {
        StringBuilder sb = new StringBuilder();
        sb.append("[LM] ").append(label).append(" pos=").append(position)
                .append(" firstVis=").append(findFirstVisibleItemPosition())
                .append(" lastVis=").append(findLastVisibleItemPosition())
                .append("\n  callers:");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // Skip the first frame (this method) — keep the next 8 so we
        // can see who called us without flooding logs.
        for (int i = 1; i < Math.min(stack.length, 9); i++) {
            StackTraceElement el = stack[i];
            sb.append("\n    ").append(el.getClassName())
                    .append('.').append(el.getMethodName())
                    .append('(').append(el.getFileName()).append(':')
                    .append(el.getLineNumber()).append(')');
        }
        Log.d(DBG, sb.toString());
    }

    @Override
    public void scrollToPosition(int position) {
        logCaller("scrollToPosition", position);
        super.scrollToPosition(position);
    }

    @Override
    public void scrollToPositionWithOffset(int position, int offset) {
        logCaller("scrollToPositionWithOffset(offset=" + offset + ")", position);
        super.scrollToPositionWithOffset(position, offset);
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView,
            RecyclerView.State state, int position) {
        logCaller("smoothScrollToPosition", position);
        super.smoothScrollToPosition(recyclerView, state, position);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        Log.d(DBG, "[LM] onLayoutChildren ENTER preLayout=" + state.isPreLayout()
                + " itemCount=" + state.getItemCount()
                + " willRunSimpleAnimations=" + state.willRunSimpleAnimations()
                + " willRunPredictiveAnimations=" + state.willRunPredictiveAnimations()
                + " firstVis(before)=" + findFirstVisibleItemPosition()
                + " uptime=" + SystemClock.uptimeMillis());

        // Preserve anchor across the predictive-animations pre/post-layout
        // dance. Background: at the end of every onLayoutChildren the
        // superclass calls mAnchorInfo.reset() (sets mValid=false). The
        // next call (post-layout step 2) therefore re-runs
        // updateAnchorInfoForLayout, which falls through to
        // updateAnchorFromChildren — and that method iterates
        // mChildHelper indices (NOT adapter positions). Predictive
        // animations scrap/reattach the changed view holder, which
        // shuffles the mChildHelper order, so the "first reference
        // child" returned isn't necessarily the position-4 child that
        // pre-layout settled on. Captured logs show firstVis sliding
        // from 4 to 2 on every notifyItemRangeChanged with payload.
        //
        // Force the anchor by pre-seeding mPendingScrollPosition with
        // the current firstVisible. updateAnchorFromPendingData runs
        // first in updateAnchorInfoForLayout and wins, so the broken
        // children-based pick is bypassed entirely. Skip during the
        // gated initial flow (mInitialPosition set) so setInitialPosition
        // still controls that pass, and skip during pre-layout because
        // updateAnchorFromPendingData ignores pending data when
        // state.isPreLayout() is true anyway.
        if (!state.isPreLayout() && mInitialPosition == RecyclerView.NO_POSITION) {
            int firstVis = findFirstVisibleItemPosition();
            if (firstVis != RecyclerView.NO_POSITION
                    && firstVis >= 0
                    && firstVis < state.getItemCount()) {
                View firstView = findViewByPosition(firstVis);
                int offset = firstView == null
                        ? 0
                        : firstView.getTop() - getPaddingTop();
                scrollToPositionWithOffset(firstVis, offset);
            }
        }

        super.onLayoutChildren(recycler, state);
        Log.d(DBG, "[LM] onLayoutChildren EXIT firstVis(after)="
                + findFirstVisibleItemPosition()
                + " lastVis=" + findLastVisibleItemPosition());
    }

    /**
     * Request that the LayoutManager scroll to {@code position} and keep
     * re-issuing the scroll on every post-layout pass until the row is
     * actually the first visible. Fires {@code onReached} the first time
     * that condition is observed.
     *
     * <p>Calling again with a different position cancels the previous
     * request.</p>
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

        // If the data set is now too small for the target, give up.
        if (state.getItemCount() <= mInitialPosition) {
            int reached = mInitialPosition;
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
