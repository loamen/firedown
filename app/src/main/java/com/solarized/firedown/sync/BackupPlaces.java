package com.solarized.firedown.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.utils.BuildUtils;

/**
 * The one place the Backup screen's folder chooser can name on its own: a
 * mounted removable volume (an SD card, a USB drive). Everything else is the
 * system folder picker, opened plain, with one hint: cloud apps are under its
 * ☰ drawer.
 *
 * <p><b>Why there are no per-app cloud rows.</b> They shipped for two commits
 * (2026-10-13) and went for two reasons. (1) Nothing an app can read tells a
 * cloud from a provider of local content, or a provider that takes a folder
 * pick from one that does not: Termux, System Tracing, a file manager and
 * Proton Drive answer the same {@code DOCUMENTS_PROVIDER} intent under the
 * same {@code MANAGE_DOCUMENTS} permission as Google Drive, and the two facts
 * that differ ({@code FLAG_LOCAL_ONLY}, {@code FLAG_SUPPORTS_IS_CHILD}) sit
 * in the provider's roots, which only the system may query. Plain detection
 * listed all of them as backup places; an allowlist of verified packages
 * fixed that, at the cost of a list to keep. (2) A row could not do anything
 * the plain picker does not: there is no documented way to open the picker
 * inside a given app, so a "Google Drive" row launched the same picker as
 * "Somewhere else", plus a toast naming the app. A list kept up by hand to
 * buy an icon and a name was not worth keeping. What protects a pick now is
 * {@link FolderStore#checkWritable}, which tests what a backup actually
 * needs, after the choice, with no list.
 *
 * <p>A removable volume is the exception because it IS a capability:
 * {@link StorageVolume#createOpenDocumentTreeIntent()} (API 29+) opens the
 * picker right on the card.
 */
public final class BackupPlaces {

    /** A mounted removable volume the chooser can offer. */
    public static final class Volume {
        /** The volume's own description ("SD card"), or "" when it has none. */
        public final String label;
        /** On API 29+ the picker opened on the volume; null = the plain
         *  picker, where the row's subtitle says to pick the volume. */
        @Nullable
        public final Intent intent;

        Volume(String label, @Nullable Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    private BackupPlaces() {
    }

    /** The first mounted removable volume, or null when there is none. */
    @Nullable
    public static Volume removable(@NonNull Context context) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            return null;
        }
        try {
            for (StorageVolume volume : sm.getStorageVolumes()) {
                if (volume.isPrimary() || !volume.isRemovable()) {
                    continue;
                }
                if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                    continue;
                }
                Intent intent = BuildUtils.hasAndroidQ()
                        ? volume.createOpenDocumentTreeIntent() : null;
                String label = volume.getDescription(context);
                return new Volume(TextUtils.isEmpty(label) ? "" : label, intent);
            }
        } catch (RuntimeException ignored) {
            // A volume listing that throws is a volume we cannot offer.
        }
        return null;
    }
}
