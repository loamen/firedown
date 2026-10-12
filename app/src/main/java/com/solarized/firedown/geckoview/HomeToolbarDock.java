package com.solarized.firedown.geckoview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * Docks the home's {@link GeckoToolbar} over a slot in the page content at
 * rest, and raises it to the top of the screen while the URL field has focus.
 *
 * <p>The home is "the mark over the field": the flame, the wordmark and the
 * search pill sit together in the upper part of the screen, and when the
 * user taps the pill it rises into the position the browser's address bar
 * occupies, with the suggestions opening under it. The pill is the REAL
 * {@code GeckoToolbar} the whole time — Chrome's fakebox model, where the
 * omnibox is drawn over the NTP placeholder — so a tap on it is a tap on the
 * real {@code AutoCompleteEditText}: the keyboard opens natively, the focus
 * callbacks the two homes already route are the only hooks, the pill's own
 * colour lift and the search-engine glyph are untouched, and the commit to
 * the browser lands on a toolbar already sitting where the browser's will
 * be. A second "fake" pill would have needed a programmatic
 * {@code showSoftInput}, a theme and glyph kept in sync, and a duplicate
 * accessibility node.
 *
 * <p>Mechanism: the toolbar is a direct child of the home's
 * {@code CoordinatorLayout} laid out at the top (where the browser's is),
 * and this class translates it DOWN over a {@code Space} the content
 * reserves for it. The rest translation is resolved from window coordinates
 * — the slot's top minus the pill's untranslated top — whenever the slot,
 * the toolbar or the scroll view lays out, or the content scrolls, so it
 * follows the mark through a rotation (the activity handles
 * {@code configChanges} in place). Raising is a 0→1 fraction with the
 * translation computed from the CURRENT rest value on every frame.
 *
 * <p>The tween, second iteration (maintainer, 2026-10-12, against Chrome
 * on the device: "their animation is way smoother, also their bar is not at
 * the middle, is way upper but not at the top, so the animation does not
 * take that long path"). The first version slid a mid-screen pill ~300dp
 * in 240 ms while the IME resize re-centred the mark under it, so the slide
 * re-targeted mid-flight, and the suggestions overlay rode up at full
 * opacity like a second bar. Now: the mark group is anchored near the top
 * of the content ({@code home_mark_top}), so the trip is about half and the
 * slot does not move when the keyboard resizes the page; the duration and
 * curve are Chrome's omnibox-focus ones (225 ms, the standard
 * fast-out-slow-in) and the pill's own colour/corner animation runs on the
 * SAME clock ({@link GeckoToolbar#FOCUS_ANIMATION_MS} — it was 150 ms, so
 * the pill finished changing shape before it arrived); the mark fades out
 * with the fraction (Chrome fades its logo); and the overlay FADES IN under
 * the moving pill rather than riding
 * up opaque — on a hardware layer for the fade, because alpha on a deep
 * ViewGroup otherwise costs an offscreen pass per frame. The fragments show
 * the overlay right after calling {@link #setRaised}, so it appears at the
 * fraction's alpha and fades in from there.
 *
 * <p>Owned by the fragment: created in {@code onCreateView}, driven from
 * {@code onFocusChanged}, released in {@code onDestroyView}.
 */
public final class HomeToolbarDock {

    /** Chrome's URL-focus duration, shared with the pill's own colour/corner
     *  animation so the two run on one clock. */
    private static final long DURATION_MS = GeckoToolbar.FOCUS_ANIMATION_MS;

    private static final Interpolator INTERPOLATOR = new FastOutSlowInInterpolator();

    private final GeckoToolbar mToolbar;

    /** The pill inside the toolbar — the part that has to land on the slot. */
    private final View mPill;

    /** The content's placeholder, exactly the pill's height. */
    private final View mSlot;

    /** The content's scroll view; a scroll moves the slot on screen. */
    private final View mScroll;

    /** The mark group, faded out as the pill rises; may be null. */
    @Nullable
    private final View mFade;

    /** The suggestions overlay, faded in as the pill rises; may be null. */
    @Nullable
    private final View mOverlay;

    /** 0 = docked over the slot, 1 = at the top of the screen. */
    private float mFraction;

    /** The toolbar's translation at rest, in px; 0 until the first resolve. */
    private int mRest;

    private boolean mRaised;

    @Nullable
    private ValueAnimator mAnimator;

    private final View.OnLayoutChangeListener mOnLayout =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> resolve();

    private final View.OnScrollChangeListener mOnScroll =
            (v, x, y, oldX, oldY) -> resolve();

    public HomeToolbarDock(@NonNull GeckoToolbar toolbar, @NonNull View slot,
                           @NonNull View scroll, @Nullable View fade, @Nullable View overlay) {
        mToolbar = toolbar;
        mPill = toolbar.getAddressBarBackground();
        mSlot = slot;
        mScroll = scroll;
        mFade = fade;
        mOverlay = overlay;
        mSlot.addOnLayoutChangeListener(mOnLayout);
        mScroll.addOnLayoutChangeListener(mOnLayout);
        mToolbar.addOnLayoutChangeListener(mOnLayout);
        mScroll.setOnScrollChangeListener(mOnScroll);
        resolve();
    }

    /**
     * Raises the toolbar to the top (focus gained) or docks it back over the
     * slot (focus lost). Idempotent per state, so the callers' redundant
     * focus-lost calls (the back-press dismissal path clears focus after the
     * field already reported it) are no-ops; a reversal mid-flight continues
     * from the current fraction.
     */
    public void setRaised(boolean raised) {
        if (raised == mRaised) {
            return;
        }
        mRaised = raised;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        float target = raised ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(mFraction, target);
        animator.setDuration(Math.round(DURATION_MS * Math.abs(target - mFraction)));
        animator.setInterpolator(INTERPOLATOR);
        animator.addUpdateListener(va -> apply((float) va.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                setOverlayLayer(View.LAYER_TYPE_HARDWARE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                setOverlayLayer(View.LAYER_TYPE_NONE);
                if (mAnimator == animation) {
                    mAnimator = null;
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    /** Stops the animation and detaches from the views; the fragment's view
     *  is going away. */
    public void release() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        setOverlayLayer(View.LAYER_TYPE_NONE);
        if (mOverlay != null) {
            mOverlay.setAlpha(1f);
        }
        mSlot.removeOnLayoutChangeListener(mOnLayout);
        mScroll.removeOnLayoutChangeListener(mOnLayout);
        mToolbar.removeOnLayoutChangeListener(mOnLayout);
        mScroll.setOnScrollChangeListener(null);
    }

    /**
     * Re-derives the rest translation from where the slot and the pill are
     * right now, and re-applies the current fraction so a docked toolbar
     * follows the slot and a raised one stays put. Window coordinates on
     * purpose: they include the scroll offset and every ancestor's position,
     * and the pill's includes the toolbar's own translation, which is
     * subtracted back out.
     */
    private void resolve() {
        if (!mSlot.isLaidOut() || !mPill.isLaidOut()) {
            return;
        }
        int[] slotXy = new int[2];
        int[] pillXy = new int[2];
        mSlot.getLocationInWindow(slotXy);
        mPill.getLocationInWindow(pillXy);
        int pillTopAtRest = pillXy[1] - Math.round(mToolbar.getTranslationY());
        int rest = slotXy[1] - pillTopAtRest;
        if (rest != mRest) {
            mRest = rest;
            apply(mFraction);
        }
    }

    private void apply(float fraction) {
        mFraction = fraction;
        mToolbar.setTranslationY(mRest * (1f - fraction));
        if (mFade != null) {
            mFade.setAlpha(1f - fraction);
        }
        if (mOverlay != null) {
            mOverlay.setAlpha(fraction);
        }
    }

    private void setOverlayLayer(int type) {
        if (mOverlay != null && mOverlay.getLayerType() != type) {
            mOverlay.setLayerType(type, null);
        }
    }
}
