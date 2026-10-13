package com.solarized.firedown.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;

import com.solarized.firedown.R;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;
import com.solarized.firedown.sync.BackupPlaces;
import com.solarized.firedown.utils.SheetGroups;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Where to back up": one card per KIND of place (cloud storage, a WebDAV
 * server, the SD card / USB drive while one is in, "Somewhere else") and a
 * footnote that a cloud app shows under the picker's ☰ only once signed in.
 * The sheet only CHOOSES: the folder pickers are registered on
 * {@link SyncSettingsFragment}, so the pick goes back to it through its
 * back-stack entry's saved-state handle ({@link #RESULT}, an int
 * {@code PLACE_*}) and the screen opens the picker or the sign-in page.
 *
 * <p>It was a centred MaterialAlertDialog with a Cancel button, the one menu
 * in the app that was not a sheet (reported on-device beside the Downloads
 * ⋮ sheet). Swipe and tap outside dismiss it now, as they do every sheet.
 */
@AndroidEntryPoint
public class BackupPlacesSheetDialogFragment extends BaseBottomSheetDialogFragment {

    /** Saved-state key {@link SyncSettingsFragment} observes; value is a PLACE_*. */
    public static final String RESULT = "backup_place";
    public static final int PLACE_CLOUD = 1;
    public static final int PLACE_WEBDAV = 2;
    public static final int PLACE_VOLUME = 3;
    public static final int PLACE_OTHER = 4;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_backup_places, container, false);
        Context context = inflater.getContext();

        mView.findViewById(R.id.place_cloud).setOnClickListener(v -> choose(PLACE_CLOUD));
        mView.findViewById(R.id.place_webdav).setOnClickListener(v -> choose(PLACE_WEBDAV));
        mView.findViewById(R.id.place_other).setOnClickListener(v -> choose(PLACE_OTHER));

        // Asked when the sheet opens and again when the pick lands (the screen
        // reads the volume's own picker intent then): a card pulled in between
        // just falls back to the plain picker.
        BackupPlaces.Volume volume = BackupPlaces.removable(context);
        View volumeCard = mView.findViewById(R.id.place_volume);
        if (volume != null) {
            String label = TextUtils.isEmpty(volume.label)
                    ? getString(R.string.folder_backup_provider_sd) : volume.label;
            ((TextView) mView.findViewById(R.id.place_volume_title)).setText(label);
            ((TextView) mView.findViewById(R.id.place_volume_sub)).setText(volume.intent != null
                    ? getString(R.string.folder_backup_place_removable_sub)
                    : getString(R.string.folder_backup_place_volume_sub, label));
            volumeCard.setOnClickListener(v -> choose(PLACE_VOLUME));
            volumeCard.setVisibility(View.VISIBLE);
        }
        SheetGroups.applyCorners(mView.findViewById(R.id.places_group));
        return mView;
    }

    /**
     * Pops FIRST, then hands the pick to the screen: the screen may navigate
     * on it (the WebDAV sign-in page is an action of settings_sync), which
     * only resolves once settings_sync is the current destination again.
     */
    private void choose(int place) {
        if (mNavController == null) {
            dismissAllowingStateLoss();
            return;
        }
        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        mNavController.popBackStack();
        if (previous != null) {
            previous.getSavedStateHandle().set(RESULT, place);
        }
    }
}
