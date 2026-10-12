package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.SyncManager;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Delete backup data" — the sub-screen behind the ONE erasure door on the
 * Backup screen, holding the two SCOPED deletions as rows: bookmarks (the
 * server-side document; turns sync off too) and backed-up files (the account
 * directory in the backup FOLDER; keeps the code and the folder grant — see
 * {@link CloudBackupManager#deleteAllData}).
 * Each row confirms in its own dialog; the dialogs moved here verbatim from
 * {@code SyncSettingsFragment}, which used to offer them from two root rows and
 * then, briefly, from a chooser dialog. A destination was preferred over that
 * chooser so each row can state its consequence in place (see
 * {@code settings_cloud_delete.xml}).
 *
 * <p>Row visibility follows what applies RIGHT NOW — bookmark sync on, backup
 * set up — re-read on every resume and after each deletion completes, so a
 * finished erasure retires its own row. The Backup screen re-evaluates the door
 * itself when the user returns (its {@code onResume} → {@code updateState}).
 *
 * <p>{@code @AndroidEntryPoint} is required because the base fragment declares
 * an {@code @Inject} field — Hilt members-injection runs through the concrete
 * leaf's generated injector.
 */
@AndroidEntryPoint
public class CloudDeleteFragment extends BasePreferenceFragment
        implements Preference.OnPreferenceClickListener {

    @Inject
    SyncManager mSyncManager;
    @Inject
    CloudBackupManager mCloudBackup;

    private Preference mBookmarks;
    private Preference mBackups;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_cloud_delete, rootKey);

        mBookmarks = findPreference(Preferences.SETTINGS_CLOUD_DELETE_BOOKMARKS);
        mBackups = findPreference(Preferences.SETTINGS_CLOUD_DELETE_BACKUPS);
        if (mBookmarks != null) {
            mBookmarks.setOnPreferenceClickListener(this);
        }
        if (mBackups != null) {
            mBackups.setOnPreferenceClickListener(this);
        }
        tintIcons();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyVisibility();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (Preferences.SETTINGS_CLOUD_DELETE_BOOKMARKS.equals(key)) {
            showDeleteBookmarksDialog();
            return true;
        }
        if (Preferences.SETTINGS_CLOUD_DELETE_BACKUPS.equals(key)) {
            showDeleteBackupsDialog();
            return true;
        }
        return false;
    }

    /** A row shows only while its deletion applies: bookmarks while sync is on
     *  (the erasure also turns sync off), backups while an account is set up. */
    private void applyVisibility() {
        if (mBookmarks != null) {
            mBookmarks.setVisible(mSyncManager.isEnabled());
        }
        if (mBackups != null) {
            mBackups.setVisible(mCloudBackup.isSetUp());
        }
    }

    /**
     * Confirms and runs the bookmark server-side erasure (right-to-erasure) —
     * distinct from turning sync off; on success it also turns sync off locally.
     * SCOPED: bookmarks only.
     */
    private void showDeleteBookmarksDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_delete_title)
                .setMessage(R.string.settings_sync_delete_message)
                .setPositiveButton(R.string.settings_sync_delete_action, (dialog, which) -> {
                    snackbar(getString(R.string.settings_sync_delete_started));
                    mSyncManager.deleteServerData(ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        applyVisibility();
                        snackbar(getString(ok
                                ? R.string.settings_sync_delete_done
                                : R.string.settings_sync_delete_failed));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Confirms and runs the backed-up-files erasure: the account directory
     * under "Firedown Backup" in the chosen folder is deleted, object files
     * and sidecars alike. The recovery code and the folder grant are kept —
     * the same code and folder back the next file up, and the code still
     * keys bookmark sync.
     */
    private void showDeleteBackupsDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_delete_title)
                .setMessage(R.string.settings_cloud_backup_delete_message)
                .setPositiveButton(R.string.settings_cloud_backup_delete_action, (dialog, which) -> {
                    snackbar(getString(R.string.settings_cloud_backup_delete_started));
                    mCloudBackup.deleteAllData(ok -> {
                        if (!isAdded()) {
                            return;
                        }
                        applyVisibility();
                        snackbar(getString(ok
                                ? R.string.settings_cloud_backup_delete_done
                                : R.string.settings_cloud_backup_delete_failed));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}
