package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureItem;

/**
 * Spacing for the Captured sheet's mixed list: the tiles and the squares on
 * one 8dp gap, the Downloads grid's own ({@code list_spacing}), everything
 * lined up on the sheet's 16dp gutter, and the fold set apart from the grid
 * above and below it.
 *
 * <p><b>No offset depends on the column.</b> Every item gets half the gap on
 * each side, and the other part of the gutter is the RecyclerView's own
 * horizontal padding ({@link #attach}): 16dp to every edge, 8dp between
 * columns, every column the same width, for tiles and squares alike. The
 * first version split the offsets by column (the first column flush with
 * the gutter, the last one too), and on-device that broke as captures
 * arrived: RecyclerView caches an item's offsets until the item is rebound,
 * and a capture landing ABOVE moves every tile after it into the other
 * column without rebinding it, so tiles kept their old column's offsets and
 * sat 12dp off on one side, hugging the screen edge on the other. Offsets
 * that are the same in every column cannot go stale.
 */
public class CaptureSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int mHalfGap;
    private final int mFoldGap;

    private CaptureSpacingDecoration(@NonNull Context context) {
        mHalfGap = context.getResources().getDimensionPixelSize(R.dimen.list_spacing) / 2;
        mFoldGap = Math.round(12 * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Pads the list to the gutter less half a gap and adds the decoration:
     * the two halves of one rule, so they're set together or not at all.
     * The scrollbar stays on the sheet's edge, outside the padding.
     */
    public static void attach(@NonNull RecyclerView recyclerView) {
        Context context = recyclerView.getContext();
        CaptureSpacingDecoration decoration = new CaptureSpacingDecoration(context);
        int gutter = Math.round(16 * context.getResources().getDisplayMetrics().density);
        int side = Math.max(0, gutter - decoration.mHalfGap);
        // The EXTRA part of the padding (RecyclerPadding), so an inset
        // listener on the list keeps it.
        RecyclerPadding.setExtra(recyclerView, side, 0);
        recyclerView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        recyclerView.addItemDecoration(decoration);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.ViewHolder holder = parent.getChildViewHolder(view);
        int type = holder != null ? holder.getItemViewType() : RecyclerView.INVALID_TYPE;
        outRect.left = mHalfGap;
        outRect.right = mHalfGap;
        if (type == CaptureItem.TYPE_SQUARE || type == CaptureItem.TYPE_TILE) {
            outRect.top = 0;
            outRect.bottom = 2 * mHalfGap;
        } else if (type == CaptureItem.TYPE_FOLD) {
            outRect.top = mFoldGap;
            outRect.bottom = mFoldGap;
        } else {
            outRect.top = 0;
            outRect.bottom = 0;
        }
    }
}
