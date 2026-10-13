package com.solarized.firedown.sync;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.utils.BuildUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the Backup screen's "Where to back up" chooser can offer, read off the
 * phone rather than told in a paragraph: the installed CLOUD apps that ship a
 * DocumentsProvider (what the system folder picker lists behind its ☰ drawer)
 * and a mounted removable volume (SD card / USB drive). Everything else is
 * the plain picker.
 *
 * <p>What this can and cannot know, so the chooser's copy stays honest:
 * <ul>
 *   <li>It CAN list the apps — {@link PackageManager#queryIntentContentProviders}
 *       on {@link DocumentsContract#PROVIDER_INTERFACE}, with the manifest's
 *       {@code <queries>} entry for Android 11+ package visibility — with
 *       their names and icons. It CANNOT tell a cloud from a provider of
 *       LOCAL content by any manifest fact: Termux (its home directory),
 *       System Tracing (its trace files) and a file manager all answer the
 *       same intent and are protected the same way, and the first build
 *       listed all of them as places to back up (the maintainer's phone,
 *       2026-10-13). The only distinguishing fact — a root's
 *       {@code FLAG_LOCAL_ONLY} — lives in the roots, which are
 *       unqueryable (below). So a row shows only for an app that is BOTH
 *       installed AND in {@link #KNOWN_CLOUD_PACKAGES}; an unknown provider
 *       is never offered as a backup place and degrades to "Somewhere else"
 *       plus the picker's own ☰, where it is still one tap away.</li>
 *   <li>It CANNOT read a provider's roots: {@code queryRoots} is behind
 *       {@code MANAGE_DOCUMENTS}, a system-only permission. So it cannot tell
 *       whether the app is signed in (Drive with no account lists nothing in
 *       the picker) or whether it supports folder picks at all
 *       ({@code Root.FLAG_SUPPORTS_IS_CHILD} — Proton Drive does not, see
 *       CLAUDE.md), and it cannot open the picker INSIDE a chosen app (no
 *       documented root id to point {@code EXTRA_INITIAL_URI} at). A cloud row
 *       therefore launches the plain picker and says what to tap there.</li>
 *   <li>A removable volume is the exception: {@link
 *       StorageVolume#createOpenDocumentTreeIntent()} (API 29+) opens the
 *       picker straight on the card.</li>
 * </ul>
 */
public final class BackupPlaces {

    public enum Kind { CLOUD, REMOVABLE }

    /** One row of the chooser. */
    public static final class Place {
        public final Kind kind;
        /** The app's label, or the volume's description ("SD card"). */
        public final String label;
        /** The app's own icon for a CLOUD row; null = the chooser's glyph. */
        @Nullable
        public final Drawable icon;
        /** A REMOVABLE row on API 29+: the picker opened on the volume;
         *  null = launch the plain picker. */
        @Nullable
        public final Intent intent;

        Place(Kind kind, String label, @Nullable Drawable icon, @Nullable Intent intent) {
            this.kind = kind;
            this.label = label;
            this.icon = icon;
            this.intent = intent;
        }
    }

    /** The system's own providers: the phone's storage, Downloads, the media
     *  store, MTP devices and the shell's bug reports — none is a cloud, and
     *  the first is what the "Somewhere else" row already means. */
    private static final Set<String> SYSTEM_AUTHORITIES = new HashSet<>(Arrays.asList(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            "com.android.mtp.documents",
            "com.android.shell.documents"));

    private static final String MANAGE_DOCUMENTS = "android.permission.MANAGE_DOCUMENTS";

    /**
     * Cloud apps whose DocumentsProvider is KNOWN to take a folder pick, keyed
     * by PACKAGE (the public, stable id; provider authorities are internal and
     * have been renamed). It is AND-ed with detection, so an entry that is not
     * installed never produces a row. Every entry needs EVIDENCE, because a
     * wrong one is not harmless: the row would send the user to a picker ☰
     * the app is absent from, which is the Proton Drive failure (installed,
     * ships a provider, no {@code FLAG_SUPPORTS_IS_CHILD}; see CLAUDE.md).
     * Evidence per entry, checked 2026-10-13:
     * <ul>
     *   <li>Google Drive: on the maintainer's phone, listed in the folder
     *       picker once signed in, and backups completed into it.</li>
     *   <li>Nextcloud: its RootCursor sets FLAG_SUPPORTS_IS_CHILD and
     *       DocumentsStorageProvider implements isChildDocument.</li>
     *   <li>Seafile: SeafileProvider sets FLAG_SUPPORTS_IS_CHILD and
     *       implements isChildDocument.</li>
     *   <li>Infomaniak kDrive: CloudStorageProvider sets FLAG_SUPPORTS_IS_CHILD
     *       and implements isChildDocument.</li>
     * </ul>
     * Checked and left OUT: MEGA (root flags 0), ownCloud (SEARCH | CREATE,
     * no IS_CHILD), Cryptomator and Filen (no DocumentsProvider in their
     * source), Proton Drive (above). Closed-source apps with no device test
     * (Dropbox, OneDrive, Box, pCloud…) are out until someone sees them in
     * the folder picker; they still work, if they can, through "Somewhere
     * else". Adding one is a one-line change once it has been seen there.
     */
    private static final Set<String> KNOWN_CLOUD_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.apps.docs",      // Google Drive
            "com.nextcloud.client",              // Nextcloud
            "com.seafile.seadroid2",             // Seafile
            "com.infomaniak.drive"));            // Infomaniak kDrive

    private BackupPlaces() {
    }

    /**
     * Every installed KNOWN cloud app that ships a DocumentsProvider
     * ({@link #KNOWN_CLOUD_PACKAGES}), one row per app (an app may declare
     * several providers), sorted by label. Empty when the phone has none the
     * list recognises — the chooser then says so and points at "Somewhere
     * else" for any other, which the paragraph this replaced could never do.
     */
    @NonNull
    public static List<Place> cloudApps(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> providers;
        try {
            providers = pm.queryIntentContentProviders(
                    new Intent(DocumentsContract.PROVIDER_INTERFACE), 0);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (providers == null) {
            return Collections.emptyList();
        }
        String self = context.getPackageName();
        Set<String> seen = new HashSet<>();
        List<Place> out = new ArrayList<>();
        for (ResolveInfo ri : providers) {
            ProviderInfo info = ri.providerInfo;
            if (info == null || !info.exported || info.authority == null) {
                continue;
            }
            if (self.equals(info.packageName) || isSystemAuthority(info.authority)) {
                continue;
            }
            if (!KNOWN_CLOUD_PACKAGES.contains(info.packageName)) {
                continue; // a local-content provider, or a cloud this list does not know
            }
            // A real DocumentsProvider is protected by MANAGE_DOCUMENTS (the
            // framework refuses to attach one that isn't); anything else that
            // answers the intent filter is not something the picker lists.
            if (!MANAGE_DOCUMENTS.equals(info.readPermission)) {
                continue;
            }
            ApplicationInfo app = info.applicationInfo;
            if (app == null || !seen.add(info.packageName)) {
                continue;
            }
            CharSequence label = app.loadLabel(pm);
            if (TextUtils.isEmpty(label)) {
                continue;
            }
            Drawable icon;
            try {
                icon = app.loadIcon(pm);
            } catch (RuntimeException e) {
                icon = null;
            }
            out.add(new Place(Kind.CLOUD, label.toString(), icon, null));
        }
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private static boolean isSystemAuthority(@NonNull String authorities) {
        for (String authority : authorities.split(";")) {
            if (SYSTEM_AUTHORITIES.contains(authority.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first mounted removable volume (an SD card, a USB drive), or null.
     * On API 29+ the row carries the volume's own picker intent, which opens
     * the picker right on it; below, the plain picker with the volume named.
     */
    @Nullable
    public static Place removable(@NonNull Context context) {
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
                return new Place(Kind.REMOVABLE, TextUtils.isEmpty(label) ? "" : label,
                        null, intent);
            }
        } catch (RuntimeException ignored) {
            // A volume listing that throws is a volume we cannot offer.
        }
        return null;
    }
}
