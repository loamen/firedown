package com.solarized.firedown.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * {@code spacing} around and between the items of a list or grid: half of it
 * on every side of every item, and the other half on the RecyclerView's own
 * padding, so the list's edges get the full amount and two neighbours get
 * one gap between them. The same geometry in every column and every row.
 *
 * <p>No offset depends on where an item sits, and that is the point. This
 * class used to work the edges out per item, from its position and span
 * index (a full edge for the first row, the first column, the last…).
 * RecyclerView caches an item's offsets until the item is REBOUND, and an
 * insert, remove or move changes which item is first, last or in which
 * column without rebinding the ones it shifts, so they kept the offsets of
 * the place they left: in a grid a tile flush with the screen edge with a
 * double gap on its other side (seen in the Captured sheet first, then in
 * Downloads, the tab grid and Backups), in a list an uneven gap around the
 * first or last row. Every list moved to this mode and the per-position
 * one was deleted; don't bring it back.
 *
 * <p>The padding half goes through {@link RecyclerPadding} as its EXTRA, so
 * an inset listener on the same list sets the BASE through it too and
 * neither erases the other. The list needs clipToPadding off to scroll
 * through the top and bottom padding, as the LCEE recycler and the
 * settings list have.
 */
public final class EqualSpacingItemDecoration extends RecyclerView.ItemDecoration {

    private final int halfSpacing;

    private EqualSpacingItemDecoration(int spacingPx) {
        halfSpacing = spacingPx / 2;
    }

    /**
     * Adds the decoration and pads the RecyclerView by the rest of the
     * spacing on all four sides, as the EXTRA part of its padding: the base,
     * what an inset listener reserves for the system bars, is kept whichever
     * of the two runs first, and re-attaching (the Downloads grid/list
     * switch) never piles the edge up. An inset listener on such a list sets
     * its padding through {@link RecyclerPadding#setBase} or
     * {@link RecyclerPadding#setBaseBottom}, never {@code setPadding}. The
     * caller removes any previous decoration first.
     */
    public static void attachUniform(@NonNull RecyclerView recyclerView, int spacingPx) {
        int edge = spacingPx - spacingPx / 2;
        RecyclerPadding.setExtra(recyclerView, edge, edge);
        // The scrollbar stays on the view's edge, outside the new padding.
        recyclerView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        recyclerView.addItemDecoration(new EqualSpacingItemDecoration(spacingPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        outRect.set(halfSpacing, halfSpacing, halfSpacing, halfSpacing);
    }
}
