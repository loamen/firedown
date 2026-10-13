package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;

import com.solarized.firedown.data.di.NetworkModule;

/**
 * Which {@link BackupStore} is THE backup place: a saved WebDAV server when
 * there is one ({@link WebDavSettings}), else the picked folder
 * ({@link FolderStore}, which may itself be unconfigured). Choosing one
 * forgets the other ({@code CloudBackupManager.adoptFolder} /
 * {@code adoptWebDav}), so there is never a question of which wins.
 *
 * <p>The WebDAV store is cached for the process and rebuilt only when the
 * saved sign-in changes: it owns the client (connections, the trusted
 * certificate) and the decoded-sidecar cache, which every worker, the
 * Backups list and the stream player then share.
 */
public final class BackupStores {

    private static WebDavStore sWebDav;
    private static int sGeneration = -1;

    private BackupStores() {
    }

    /** The active place. Cheap after the first call (no keystore). */
    @NonNull
    public static BackupStore open(@NonNull Context context, @NonNull SharedPreferences prefs) {
        return open(context, new FolderStore(context, prefs));
    }

    /** {@link #open(Context, SharedPreferences)} with a folder store the
     *  caller already holds. */
    @NonNull
    static BackupStore open(@NonNull Context context, @NonNull FolderStore folders) {
        WebDavSettings settings = new WebDavSettings(context);
        if (!settings.isConfigured()) {
            return folders;
        }
        synchronized (BackupStores.class) {
            int generation = WebDavSettings.generation();
            if (sWebDav != null && generation == sGeneration) {
                return sWebDav;
            }
            WebDavConfig config = settings.load();
            if (config == null) {
                // Saved but unreadable (the keystore key is gone): no place
                // that can be used, and the folder (if any) was forgotten when
                // the server was chosen. Reads as not set up.
                return folders;
            }
            sWebDav = new WebDavStore(config, NetworkModule.globalClient);
            sGeneration = generation;
            return sWebDav;
        }
    }

    /** Whether the active place is reached over the network. */
    public static boolean usesNetwork(@NonNull Context context) {
        return new WebDavSettings(context).isConfigured();
    }

    /**
     * The WorkManager constraints of a backup or restore: a connection when
     * the place is a server; none for a folder, which may be on the phone or
     * a card (a provider that needs the network answers with an error the
     * worker retries).
     */
    @NonNull
    public static Constraints transferConstraints(@NonNull Context context) {
        Constraints.Builder b = new Constraints.Builder();
        if (usesNetwork(context)) {
            b.setRequiredNetworkType(NetworkType.CONNECTED);
        }
        return b.build();
    }
}
