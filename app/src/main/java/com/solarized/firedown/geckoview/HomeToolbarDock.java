package com.solarized.firedown.geckoview;

import android.view.View;

import androidx.annotation.NonNull;

/**
 * Docks the home's {@link GeckoToolbar} over a slot in the page content at
 * rest, and puts it at the top of the screen while the URL field has focus.
 *
 * <p>The home is "the mark over the field": the flame, the wordmark and the
 * search pill sit together in the middle of the screen, and when the user
 * taps the pill it takes the position the browser's address bar occupies,
 * with the suggestions opening under it. The pill is the REAL
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
 * follows the centred mark through an IME resize ({@code adjustResize} moves
 * the mark while the keyboard opens and closes) and a rotation (the activity
 * handles {@code configChanges} in place).
 *
 * <p>The move is a CUT, not a tween (maintainer, 2026-10-12: "is the
 * animation really necessary … it feels weak"). It shipped first as a 240 ms
 * slide with the mark fading under it, and a lone short slide of one bar —
 * the keyboard on its own clock, the suggestions panel simply appearing —
 * read as a wobble rather than a transition; making it read as one would
 * have meant choreographing the panel and the IME for a quarter second
 * nobody waits for. Firefox for Android cuts straight to its search screen
 * the same way. So on focus the pill is at the top on the next frame with
 * the panel under it, and on blur it is back over the slot; the overlay
 * covers the mark the moment it opens, so nothing needs fading either.
 *
 * <p>Owned by the fragment: created in {@code onCreateView}, driven from
 * {@code onFocusChanged}, released in {@code onDestroyView}.
 */
public final class HomeToolbarDock {

    private final GeckoToolbar mToolbar;

    /** The pill inside the toolbar — the part that has to land on the slot. */
    private final View mPill;

    /** The content's placeholder, exactly the pill's height. */
    private final View mSlot;

    /** The content's scroll view; a scroll moves the slot on screen. */
    private final View mScroll;

    /** The toolbar's translation at rest, in px; 0 until the first resolve. */
    private int mRest;

    private boolean mRaised;

    private final View.OnLayoutChangeListener mOnLayout =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> resolve();

    private final View.OnScrollChangeListener mOnScroll =
            (v, x, y, oldX, oldY) -> resolve();

    public HomeToolbarDock(@NonNull GeckoToolbar toolbar, @NonNull View slot,
                           @NonNull View scroll) {
        mToolbar = toolbar;
        mPill = toolbar.getAddressBarBackground();
        mSlot = slot;
        mScroll = scroll;
        mSlot.addOnLayoutChangeListener(mOnLayout);
        mScroll.addOnLayoutChangeListener(mOnLayout);
        mToolbar.addOnLayoutChangeListener(mOnLayout);
        mScroll.setOnScrollChangeListener(mOnScroll);
        resolve();
    }

    /**
     * Puts the toolbar at the top (focus gained) or back over the slot (focus
     * lost). Idempotent per state, so the callers' redundant focus-lost calls
     * (the back-press dismissal path clears focus after the field already
     * reported it) are no-ops.
     */
    public void setRaised(boolean raised) {
        if (raised == mRaised) {
            return;
        }
        mRaised = raised;
        apply();
    }

    /** Detaches from the views; the fragment's view is going away. */
    public void release() {
        mSlot.removeOnLayoutChangeListener(mOnLayout);
        mScroll.removeOnLayoutChangeListener(mOnLayout);
        mToolbar.removeOnLayoutChangeListener(mOnLayout);
        mScroll.setOnScrollChangeListener(null);
    }

    /**
     * Re-derives the rest translation from where the slot and the pill are
     * right now, and re-applies the current state so a docked toolbar
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
            apply();
        }
    }

    private void apply() {
        mToolbar.setTranslationY(mRaised ? 0f : mRest);
    }
}
