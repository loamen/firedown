package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureItem;

/**
 * Spacing for the Captured sheet's mixed list: the tiles and the squares on
 * one 8dp gap, the Downloads grid's own (EqualSpacingItemDecoration with
 * list_spacing), the Images header and the fold row inset from the sheet
 * edges to the tiles' edge, and the fold set apart from the grid above and
 * below it.
 *
 * <p>Why not {@link EqualSpacingItemDecoration} itself: it gives every item
 * half the gap on each side and the edge items the full gutter, so in a grid
 * the edge columns lose more width to offsets than the middle one and the
 * squares come out different widths. Here every column gives up the same
 * total ({@code (2·gutter + (n−1)·gap) / n}) and only how it splits between
 * left and right moves with the column. The column count comes from the
 * item's own span size, so one grid holds tiles and squares at different
 * counts.
 */
public class CaptureSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int mGutter;
    private final int mGap;
    private final int mFoldGap;

    public CaptureSpacingDecoration(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mGutter = Math.round(16 * density);
        mGap = context.getResources().getDimensionPixelSize(R.dimen.list_spacing);
        mFoldGap = Math.round(12 * density);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.ViewHolder holder = parent.getChildViewHolder(view);
        int type = holder != null ? holder.getItemViewType() : RecyclerView.INVALID_TYPE;
        if (type == CaptureItem.TYPE_SQUARE || type == CaptureItem.TYPE_TILE) {
            gridOffsets(outRect, view, parent);
            return;
        }
        outRect.left = mGutter;
        outRect.right = mGutter;
        boolean fold = type == CaptureItem.TYPE_FOLD;
        outRect.top = fold ? mFoldGap : 0;
        outRect.bottom = fold ? mFoldGap : 0;
    }

    private void gridOffsets(@NonNull Rect outRect, @NonNull View view,
                             @NonNull RecyclerView parent) {
        int spanCount = 1;
        RecyclerView.LayoutManager manager = parent.getLayoutManager();
        if (manager instanceof GridLayoutManager grid) {
            spanCount = grid.getSpanCount();
        }
        int columns = 1;
        int column = 0;
        if (view.getLayoutParams() instanceof GridLayoutManager.LayoutParams params) {
            int span = Math.max(1, params.getSpanSize());
            columns = Math.max(1, spanCount / span);
            column = Math.max(0, params.getSpanIndex()) / span;
        }
        // Each column's share of the offsets, then where in it this column's
        // left edge falls: the first column starts at the gutter, each next
        // one a gap minus a share further in.
        float share = (2f * mGutter + (columns - 1) * mGap) / columns;
        int left = Math.round(mGutter + column * (mGap - share));
        outRect.left = left;
        outRect.right = Math.round(share) - left;
        outRect.top = 0;
        outRect.bottom = mGap;
    }
}
