package com.solarized.firedown.phone.fragments;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;

/**
 * GridLayoutManager for the tabs grid. Two responsibilities:
 *
 * <ol>
 *   <li><b>Disable predictive item animations</b>. The tab list LiveData
 *       re-emits on any per-tab state change (thumb decoded, title
 *       updated, active flag flipped). DiffUtil produces partial-bind
 *       {@code notifyItemChanged} events with payloads. With predictive
 *       animations on, RecyclerView runs a pre-layout pass with the
 *       <em>old</em> state followed by a real layout pass with the new
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
 *       notify, no anchor drift.</li>
 *
 *   <li><b>Be a stock GridLayoutManager otherwise.</b> Initial scroll
 *       to the active tab is handled by {@code BaseTabsFragment} —
 *       it calls {@link #scrollToPositionWithOffset(int, int)}
 *       <em>before</em> the first layout pass (between {@code setAdapter}
 *       and the natural RecyclerView measure), so the LM uses the
 *       pending position as its initial anchor on the very first
 *       {@code onLayoutChildren}. No custom hook needed; this mirrors
 *       Chromium's tab switcher pattern.</li>
 * </ol>
 */
public class TabsGridLayoutManager extends GridLayoutManager {

    public TabsGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }
}
