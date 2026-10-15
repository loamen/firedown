package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.SheetGroups;

import java.util.List;

/**
 * The player's Subtitles sheet: pick a subtitle track, turn subtitles off,
 * or add a subtitle file. A plain {@link BottomSheetDialogFragment}, not
 * {@link BaseBottomSheetDialogFragment}: the player activity is not a
 * BaseActivity and has no NavHostFragment. It is shown in the
 * MediaViewerFragment's child fragment manager and asks its parent, the
 * {@link Host}, for the choices every time its view is built, so a sheet
 * recreated by a rotation lists the player's tracks as they are then.
 */
public class SubtitleSheetDialogFragment extends BottomSheetDialogFragment {

    public static final String TAG = "subtitle-sheet";

    /** One row of the sheet. {@code detail} null hides the second line. */
    public static final class Option {
        public final String label;
        @Nullable
        public final String detail;
        public final boolean selected;
        /** Index into the player's text track list; -1 is "Off". */
        public final int index;

        public Option(String label, @Nullable String detail, boolean selected, int index) {
            this.label = label;
            this.detail = detail;
            this.selected = selected;
            this.index = index;
        }
    }

    /** The fragment that shows the sheet. */
    public interface Host {
        /** "Off" first, then every subtitle track the player has. */
        List<Option> getSubtitleOptions();

        void onSubtitleChosen(int index);

        /** False for a file that is not a download: there is no row to link to. */
        boolean canAddSubtitles();

        void onAddSubtitleRequested();
    }

    @Override
    public int getTheme() {
        return R.style.Theme_FireDown_BottomSheetDialogTheme;
    }

    @Nullable
    private Host host() {
        Fragment parent = getParentFragment();
        return parent instanceof Host ? (Host) parent : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dialog_player_subtitles, container, false);
        Host host = host();
        if (host == null) {
            // Restored without its player (process death): nothing to list.
            dismissAllowingStateLoss();
            return view;
        }
        LinearLayout tracks = view.findViewById(R.id.subtitles_tracks);
        for (Option option : host.getSubtitleOptions()) {
            View row = inflater.inflate(R.layout.item_player_subtitle, tracks, false);
            TextView label = row.findViewById(R.id.subtitle_label);
            TextView detail = row.findViewById(R.id.subtitle_detail);
            MaterialRadioButton radio = row.findViewById(R.id.subtitle_radio);
            label.setText(option.label);
            if (TextUtils.isEmpty(option.detail)) {
                detail.setVisibility(View.GONE);
            } else {
                detail.setText(option.detail);
            }
            radio.setChecked(option.selected);
            row.setOnClickListener(v -> {
                Host current = host();
                if (current != null) {
                    current.onSubtitleChosen(option.index);
                }
                dismissAllowingStateLoss();
            });
            tracks.addView(row);
        }
        SheetGroups.applyCorners(tracks);
        ViewGroup actions = view.findViewById(R.id.subtitles_actions);
        actions.setVisibility(host.canAddSubtitles() ? View.VISIBLE : View.GONE);
        SheetGroups.applyCorners(actions);
        view.findViewById(R.id.subtitles_add_card).setOnClickListener(v -> {
            Host current = host();
            dismissAllowingStateLoss();
            if (current != null) {
                current.onAddSubtitleRequested();
            }
        });
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // The bottom system-bar inset as padding, BaseBottomSheetDialogFragment's
        // rule for every sheet: the last row must clear the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, 0, 0, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        View view = getView();
        if (view == null || !(view.getParent() instanceof View)) {
            return;
        }
        // Open fully, and skip the half state on the way down — a landscape
        // player leaves a short viewport and a peeking sheet is a sliver.
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) view.getParent());
        // The width cap every other sheet takes (centred in landscape).
        int maxWidthPx = getResources().getDimensionPixelSize(R.dimen.bottom_sheet_max_width);
        behavior.setMaxWidth(maxWidthPx > 0 ? maxWidthPx : -1);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
