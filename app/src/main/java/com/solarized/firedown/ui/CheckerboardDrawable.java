package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.R;

/**
 * The ground behind a Captured-sheet image square: two mid greys in a
 * checkerboard, the universal "this image is transparent" signal. An opaque
 * picture covers it completely; it shows only through transparent pixels,
 * which is the point — a page's SVG icons are often black with no fill
 * colour of their own (YouTube's player icons), and on the sheet's dark tile
 * they were black on near-black. The two greys are chosen so a black and a
 * white icon both read on them (see {@code capture_checker_light}).
 *
 * <p>The square's card clips it to the rounded corners, so this draws plain
 * cells.
 */
public final class CheckerboardDrawable extends Drawable {

    private final Paint mLight = new Paint();
    private final Paint mDark = new Paint();
    private final int mCell;

    public CheckerboardDrawable(@NonNull Context context) {
        mLight.setColor(ContextCompat.getColor(context, R.color.capture_checker_light));
        mDark.setColor(ContextCompat.getColor(context, R.color.capture_checker_dark));
        mCell = Math.max(1, context.getResources().getDimensionPixelSize(R.dimen.capture_checker_cell));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawRect(bounds, mLight);
        for (int top = bounds.top, row = 0; top < bounds.bottom; top += mCell, row++) {
            int bottom = Math.min(top + mCell, bounds.bottom);
            for (int left = bounds.left + (row % 2) * mCell; left < bounds.right; left += 2 * mCell) {
                canvas.drawRect(left, top, Math.min(left + mCell, bounds.right), bottom, mDark);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mLight.setAlpha(alpha);
        mDark.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mLight.setColorFilter(colorFilter);
        mDark.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
