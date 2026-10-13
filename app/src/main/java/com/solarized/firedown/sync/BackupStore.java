package com.solarized.firedown.sync;

import com.solarized.firedown.sync.model.VaultEntry;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Where backups live: the operations {@link VaultEngine} and
 * {@link VaultObjectReader} need, with the layout and the commit rules of
 * {@link BackupLayout} behind every implementation. Two today:
 * {@link FolderStore} (a folder the user picked through the system picker)
 * and {@link WebDavStore} (a WebDAV server the user signed in to). Both hold
 * the same files under the same names, so a backup made through one can be
 * read through the other once both point at the same place.
 *
 * <p>Every method that touches the place can throw
 * {@link UnreachableException}: the place cannot be used until the user does
 * something (the grant was revoked, the server refused the sign-in, its
 * certificate changed, the folder is gone). A plain {@link IOException} is a
 * failure a retry can fix (a timeout, a write the server rejected for now).
 * The backup worker names the first and retries the second.
 */
public interface BackupStore {

    /** The place cannot be used right now and a retry will not change that:
     *  grant gone, sign-in refused, certificate changed, folder gone. */
    final class UnreachableException extends IOException {
        public UnreachableException(String message) {
            super(message);
        }

        public UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Positional reads of one object, for streaming a backup without
     *  restoring it. Not thread-safe; {@link VaultObjectReader} serialises. */
    interface RangeReader extends Closeable {
        /** Reads {@code length} bytes at {@code offset}; fewer at the end of
         *  the object, none past it. */
        byte[] read(long offset, int length) throws IOException;

        @Override
        void close();
    }

    /** Whether a place has been chosen (not whether it answers now). */
    boolean isConfigured();

    /** Whether the place answers right now. Off the main thread only. */
    boolean isReachable();

    /** Who holds the place, in the user's words ("Google Drive",
     *  "cloud.example.com"); "" when none is chosen. Cheap. */
    String providerLabel();

    /** Where exactly ("Google Drive › Backups"); the provider alone when
     *  nothing more is known. Cheap. */
    String locationLabel();

    /** Every committed entry of {@code account}; sweeps orphans
     *  ({@link BackupLayout#collectEntries}). Empty when nothing was
     *  backed up yet. */
    List<VaultEntry> listEntries(String account, byte[] storageKey) throws IOException;

    /** Writes (or rewrites) the sidecar of {@code entry}: the commit marker. */
    void writeEntry(String account, VaultEntry entry, byte[] storageKey)
            throws IOException, GeneralSecurityException;

    /**
     * Opens a new object for writing. {@code length} is the exact number of
     * bytes the caller will write; a store that sends the object over the
     * network declares it up front. {@link OutputStream#close()} completes
     * the write: when it throws, the object was not stored. A stream closed
     * before {@code length} bytes were written stores nothing either.
     */
    OutputStream openNewObject(String account, String objectId, long length) throws IOException;

    /** Opens an object for one sequential read (restore).
     *  {@link java.io.FileNotFoundException} when it is not there. */
    InputStream openObject(String account, String objectId) throws IOException;

    /** Opens an object for positional reads (streaming).
     *  {@link java.io.FileNotFoundException} when it is not there. */
    RangeReader openObjectRanges(String account, String objectId) throws IOException;

    /** Deletes one backup: the sidecar first, then the object, best-effort. */
    void deleteObject(String account, String objectId) throws IOException;

    /** Deletes everything {@code account} holds, and its directory. */
    void deleteAccount(String account) throws IOException;
}
