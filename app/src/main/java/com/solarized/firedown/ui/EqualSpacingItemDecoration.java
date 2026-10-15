package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.solarized.firedown.R;

/**
 * {@code spacing} around and between the items of a list or grid: half of it
 * on each side of every item, the full amount at the list's edges.
 *
 * <p>Two ways to get the edges. The default constructors work them out per
 * item from its position and span index (first row, first column, last
 * column…). That goes STALE in a grid whose items move: RecyclerView caches
 * an item's offsets until the item is rebound, and an insert above moves
 * every later item to another column (and the old first row down) through
 * an insert, not a rebind, so those items keep the old edge's offsets — a
 * tile flush with the screen on one side and a double gap on the other.
 * {@link #attachUniform} gives every item the same half on all four sides
 * and puts the other half on the RecyclerView's own padding, so nothing
 * depends on where an item sits and nothing can go stale. Same geometry.
 */
public class EqualSpacingItemDecoration extends RecyclerView.ItemDecoration {

    private static final int VERTICAL = OrientationHelper.VERTICAL;

    private int orientation = -1;
    private int spanCount = -1;
    private final int spacing;
    private final int halfSpacing;
    /** {@link #attachUniform}'s mode: half the spacing on every side of every
     *  item, no edge logic (the padding is the edge). */
    private final boolean uniform;

    public EqualSpacingItemDecoration(Context context, @DimenRes int spacingDimen) {
        this(context.getResources().getDimensionPixelSize(spacingDimen), false);
    }

    public EqualSpacingItemDecoration(int spacingPx) {
        this(spacingPx, false);
    }

    private EqualSpacingItemDecoration(int spacingPx, boolean uniform) {

        spacing = spacingPx;
        halfSpacing = spacing / 2;
        this.uniform = uniform;
    }

    /**
     * Adds the decoration in its position-independent mode and pads the
     * RecyclerView by the rest of the spacing on all four sides, on top of
     * whatever padding the view had the first time (remembered on the view,
     * so a list that re-attaches on every grid/list switch doesn't pile
     * padding up). The caller removes any previous decoration first, as
     * before. The list needs clipToPadding off to scroll through the top and
     * bottom padding, as the LCEE recycler has.
     */
    public static void attachUniform(@NonNull RecyclerView recyclerView, int spacingPx) {
        int[] base;
        Object tag = recyclerView.getTag(R.id.equal_spacing_base_padding);
        if (tag instanceof int[] stored) {
            base = stored;
        } else {
            base = new int[] {
                    recyclerView.getPaddingStart(), recyclerView.getPaddingTop(),
                    recyclerView.getPaddingEnd(), recyclerView.getPaddingBottom()
            };
            recyclerView.setTag(R.id.equal_spacing_base_padding, base);
        }
        int edge = spacingPx - spacingPx / 2;
        recyclerView.setPaddingRelative(base[0] + edge, base[1] + edge,
                base[2] + edge, base[3] + edge);
        // The scrollbar stays on the view's edge, outside the new padding.
        recyclerView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        recyclerView.addItemDecoration(new EqualSpacingItemDecoration(spacingPx, true));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {

        super.getItemOffsets(outRect, view, parent, state);

        if (uniform) {
            outRect.set(halfSpacing, halfSpacing, halfSpacing, halfSpacing);
            return;
        }

        // Re-read BOTH from the live LayoutManager on every pass — never
        // cache them across layouts. The old once-only cache
        // (if (spanCount == -1) …) went stale when the span changed on a
        // LIVE RecyclerView without recreating the decoration: the
        // images-filter dense mosaic flipped the old Captured grid 2 → 3 via
        // setSpanCount(), and the decoration kept computing column edges
        // for 2 columns — every other tile got the left-edge inset and
        // right-edge detection misfired, producing visibly uneven gutters.
        // (Downloads dodged it only because configureRecyclerView happens
        // to recreate its decoration on every density change.) The
        // re-read is two instanceof checks + a getter per child per
        // layout pass — negligible.
        orientation = getOrientation(parent);
        spanCount = getTotalSpan(parent);

        RecyclerView.LayoutManager layoutManager = parent.getLayoutManager();

        if(layoutManager == null)
            return;

        int childCount = layoutManager.getItemCount();
        int childIndex = parent.getChildAdapterPosition(view);

        int itemSpanSize = getItemSpanSize(parent, childIndex);
        int spanIndex = getItemSpanIndex(parent, childIndex);

        /* INVALID SPAN */
        if (spanCount < 1) return;

        setSpacings(outRect, parent, childCount, childIndex, itemSpanSize, spanIndex);
    }

    protected void setSpacings(Rect outRect, RecyclerView parent, int childCount, int childIndex, int itemSpanSize, int spanIndex) {

        outRect.top = halfSpacing;
        outRect.bottom = halfSpacing;
        outRect.left = halfSpacing;
        outRect.right = halfSpacing;

        if (isTopEdge(parent, childCount, childIndex, itemSpanSize, spanIndex)) {
            outRect.top = spacing;
        }

        if (isLeftEdge(parent, childCount, childIndex, itemSpanSize, spanIndex)) {
            outRect.left = spacing;
        }

        if (isRightEdge(parent, childCount, childIndex, itemSpanSize, spanIndex)) {
            outRect.right = spacing;
        }

        if (isBottomEdge(parent, childCount, childIndex, itemSpanSize, spanIndex)) {
            outRect.bottom = spacing;
        }

        // Note: previously we zeroed out left/right when itemSpanSize ==
        // spanCount as an exception for headers/banners that carried
        // their own list_spacing internally so they'd line up with
        // list-mode rows (which used CardViewListItemDecoration, no
        // horizontal gutter). The downloads RecyclerView now uses this
        // same decoration in list mode too — list rows are 1-span so
        // every item would have hit that exception and lost its
        // gutter. We rely on the header layout dropping its internal
        // horizontal padding so the decoration is the single source of
        // truth for horizontal spacing.
    }

    @SuppressWarnings("all")
    protected int getTotalSpan(RecyclerView parent) {

        RecyclerView.LayoutManager mgr = parent.getLayoutManager();
        if (mgr instanceof GridLayoutManager) {
            return ((GridLayoutManager) mgr).getSpanCount();
        } else if (mgr instanceof StaggeredGridLayoutManager) {
            return ((StaggeredGridLayoutManager) mgr).getSpanCount();
        } else if (mgr instanceof LinearLayoutManager) {
            return 1;
        }
        return -1;
    }

    @SuppressWarnings("all")
    protected int getItemSpanSize(RecyclerView parent, int childIndex) {

        RecyclerView.LayoutManager mgr = parent.getLayoutManager();
        if (mgr instanceof GridLayoutManager) {
            return ((GridLayoutManager) mgr).getSpanSizeLookup().getSpanSize(childIndex);
        } else if (mgr instanceof StaggeredGridLayoutManager) {
            return 1;
        } else if (mgr instanceof LinearLayoutManager) {
            return 1;
        }

        return -1;
    }


    protected int getItemSpanIndex(RecyclerView parent, int childIndex) {

        RecyclerView.LayoutManager mgr = parent.getLayoutManager();
        if (mgr instanceof GridLayoutManager) {
            return ((GridLayoutManager) mgr).getSpanSizeLookup().getSpanIndex(childIndex, spanCount);
        } else if (mgr instanceof StaggeredGridLayoutManager) {
            return childIndex % spanCount;
        } else if (mgr instanceof LinearLayoutManager) {
            return 0;
        }

        return -1;
    }


    protected int getOrientation(RecyclerView parent) {

        RecyclerView.LayoutManager mgr = parent.getLayoutManager();
        if (mgr instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) mgr).getOrientation();
        } else if (mgr instanceof GridLayoutManager) {
            return ((GridLayoutManager) mgr).getOrientation();
        } else if (mgr instanceof StaggeredGridLayoutManager) {
            return ((StaggeredGridLayoutManager) mgr).getOrientation();
        }

        return VERTICAL;
    }

    protected boolean isLeftEdge(RecyclerView parent, int childCount, int childIndex, int itemSpanSize, int spanIndex) {

        if (orientation == VERTICAL) {

            if(childIndex == RecyclerView.NO_POSITION)
                return false;

            return spanIndex == 0;

        } else {

            return (childIndex == 0) || isFirstItemEdgeValid((childIndex < spanCount), parent, childIndex);
        }
    }

    protected boolean isRightEdge(RecyclerView parent, int childCount, int childIndex, int itemSpanSize, int spanIndex) {

        if (orientation == VERTICAL) {

            if(childIndex == RecyclerView.NO_POSITION)
                return false;

            return (spanIndex + itemSpanSize) == spanCount;

        } else {

            return isLastItemEdgeValid((childIndex >= childCount - spanCount), parent, childCount, childIndex, spanIndex);
        }
    }

    protected boolean isTopEdge(RecyclerView parent, int childCount, int childIndex, int itemSpanSize, int spanIndex) {

        if (orientation == VERTICAL) {

            if(childIndex == RecyclerView.NO_POSITION)
                return false;

            return (childIndex == 0) || isFirstItemEdgeValid((childIndex < spanCount), parent, childIndex);

        } else {

            return spanIndex == 0;
        }
    }

    protected boolean isBottomEdge(RecyclerView parent, int childCount, int childIndex, int itemSpanSize, int spanIndex) {

        if (orientation == VERTICAL) {

            if(childIndex == RecyclerView.NO_POSITION)
                return false;

            return isLastItemEdgeValid((childIndex >= childCount - spanCount), parent, childCount, childIndex, spanIndex);

        } else {

            return (spanIndex + itemSpanSize) == spanCount;
        }
    }

    protected boolean isFirstItemEdgeValid(boolean isOneOfFirstItems, RecyclerView parent, int childIndex) {

        int totalSpanArea = 0;
        if (isOneOfFirstItems) {
            for (int i = childIndex; i >= 0; i--) {
                totalSpanArea = totalSpanArea + getItemSpanSize(parent, i);
            }
        }

        return isOneOfFirstItems && totalSpanArea <= spanCount;
    }

    protected boolean isLastItemEdgeValid(boolean isOneOfLastItems, RecyclerView parent, int childCount, int childIndex, int spanIndex) {

        int totalSpanRemaining = 0;
        if (isOneOfLastItems) {
            for (int i = childIndex; i < childCount; i++) {
                totalSpanRemaining = totalSpanRemaining + getItemSpanSize(parent, i);
            }
        }

        return isOneOfLastItems && (totalSpanRemaining <= spanCount - spanIndex);
    }
}