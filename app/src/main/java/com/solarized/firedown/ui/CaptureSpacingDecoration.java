package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.data.capture.CaptureItem;

/**
 * Spacing for the Captured sheet's mixed list: full-width rows, headers and
 * the divider inset from the sheet edges, and squares three to a line with
 * equal gaps AND equal widths.
 *
 * <p>Why not {@link EqualSpacingItemDecoration}: it gives every item half the
 * gap on each side and the edge items the full gutter, so in a grid the edge
 * columns lose more width to offsets than the middle one and the squares come
 * out different widths. Here every column gives up the same total
 * ({@code (2·gutter + (n−1)·gap) / n}) and only how it splits between left and
 * right moves with the column.
 */
public class CaptureSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int mGutter;
    private final int mRowGap;
    private final int mSquareGap;

    public CaptureSpacingDecoration(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        mGutter = Math.round(16 * density);
        mRowGap = Math.round(6 * density);
        mSquareGap = Math.round(4 * density);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.ViewHolder holder = parent.getChildViewHolder(view);
        int type = holder != null ? holder.getItemViewType() : RecyclerView.INVALID_TYPE;
        if (type != CaptureItem.TYPE_SQUARE) {
            outRect.left = mGutter;
            outRect.right = mGutter;
            int vertical = type == CaptureItem.TYPE_ROW ? mRowGap / 2 : 0;
            outRect.top = vertical;
            outRect.bottom = vertical;
            return;
        }
        int spanCount = 1;
        RecyclerView.LayoutManager manager = parent.getLayoutManager();
        if (manager instanceof GridLayoutManager grid) {
            spanCount = grid.getSpanCount();
        }
        int column = 0;
        if (view.getLayoutParams() instanceof GridLayoutManager.LayoutParams params) {
            column = Math.max(0, params.getSpanIndex());
        }
        // Each column's share of the offsets, then where in it this column's
        // left edge falls: the first column starts at the gutter, each next
        // one a gap minus a share further in.
        float share = (2f * mGutter + (spanCount - 1) * mSquareGap) / spanCount;
        int left = Math.round(mGutter + column * (mSquareGap - share));
        outRect.left = left;
        outRect.right = Math.round(share) - left;
        outRect.top = 0;
        outRect.bottom = mSquareGap;
    }
}
