package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.FlatPreference;

/**
 * The Backup screen's morphing CTA row, with STATE-DEPENDENT emphasis: the
 * FILLED button ({@code preference_cloud_buy_button}) while something is
 * genuinely needed — no recovery code yet ("Create recovery code"), no folder
 * chosen ("Choose backup folder"), or a folder that went unreachable ("Choose
 * the folder again") — and the OUTLINED variant
 * ({@code preference_cloud_buy_button_plain}) once the backup is healthy, where
 * the row only offers "Change folder": a backup that needs nothing isn't stared
 * down by a permanent filled button (the calm-by-default ethos, same reasoning
 * as the home pill's GONE-when-idle). The hero's lost state re-escalates the
 * one moment that matters. The class and layout names keep the historical
 * "buy" — the row was the hosted service's top-up door; nothing is bought here.
 *
 * <p>Swapping {@link #setLayoutResource} yields a new RecyclerView view type,
 * and {@code notifyChanged()} rebinds through it — the standard
 * PreferenceGroupAdapter path (it registers unseen layouts on demand in
 * getItemViewType), no adapter surgery needed. The label keeps riding the
 * Preference TITLE in both layouts (the button carries {@code @android:id/title}).
 */
public class CloudBuyButtonPreference extends Preference implements FlatPreference {

    /** Matches the XML default layout (the filled variant). */
    private boolean mEmphasized = true;

    public CloudBuyButtonPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Filled (true) vs outlined (false) — no-op when unchanged, so the
     *  every-updateState caller never causes a rebind churn. */
    public void setEmphasized(boolean emphasized) {
        if (mEmphasized == emphasized) {
            return;
        }
        mEmphasized = emphasized;
        setLayoutResource(emphasized
                ? R.layout.preference_cloud_buy_button
                : R.layout.preference_cloud_buy_button_plain);
        notifyChanged();
    }
}
