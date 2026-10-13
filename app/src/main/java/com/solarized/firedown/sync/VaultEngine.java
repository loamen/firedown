package com.solarized.firedown.sync;

import android.text.TextUtils;
import android.util.Base64;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.crypto.VaultCrypto;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * The backup engine: encrypts a local download and writes it into the backup
 * folder as one object (chunk → AES-256-GCM → appended to {@code <id>.fdvc}),
 * then writes the encrypted sidecar that commits it; restores by the reverse
 * path. Runs on a worker thread. A {@link BackupStore} is the only thing it
 * talks to: a folder the user picked ({@link FolderStore}) or a WebDAV server
 * ({@link WebDavStore}); there is no Firedown server either way.
 *
 * <p>All encryption is client-side ({@link VaultCrypto}); the sidecar reuses the
 * bookmark blob framing under the storage master key. The on-disk chunk format
 * is the one the hosted service used — 8 MiB plaintext per FDVC2 chunk, each
 * sealed with (objectId, index) as GCM AAD, 34 bytes of overhead — written back
 * to back into ONE file. That keeps {@code VaultCryptoTest} and the shared
 * vectors meaningful, and lets {@link VaultObjectReader} map a plaintext offset
 * to a byte range of the object without an index: chunk {@code i} starts at
 * {@code i × (CHUNK_SIZE + CHUNK_OVERHEAD)}.</p>
 */
public final class VaultEngine {

    /** 8 MiB plaintext per chunk. Public so the streaming reader can map a
     *  plaintext file offset to its chunk index without re-deriving it. */
    public static final int CHUNK_SIZE = 8 * 1024 * 1024;
    /** Per-chunk ciphertext overhead: 5 magic + 1 ver + 12 IV + 16 GCM tag.
     *  Unchanged by the FDVC2 position-binding AAD (AAD is authenticated, not
     *  stored). With CHUNK_SIZE it fixes every chunk's byte range inside the
     *  object file, which is what makes random-access reads possible. */
    public static final int CHUNK_OVERHEAD = 34;
    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    /** Per-chunk progress (plaintext bytes), so the UI can show a determinate
     *  per-item bar like the Downloads list. */
    public interface ProgressListener {
        void onProgress(long bytesDone, long bytesTotal);
    }

    /**
     * Opens the plaintext of the file being backed up. A plain FileInputStream
     * for an owned file; the worker passes a SAF-grant stream for a RESTORED
     * foreign-owned file — on Android 11+ a reinstalled app doesn't own its old
     * public files, so a direct File open EACCES-es (see
     * {@code RestoredFileAccess}). Opened exactly once per attempt.
     */
    public interface StreamSource {
        InputStream open() throws IOException;
    }

    private final BackupStore store;
    private final String account;
    private final byte[] storageKey;
    private volatile boolean cancelled;

    public VaultEngine(BackupStore store, SyncIdentity identity) {
        this.store = store;
        this.account = identity.accountBase32();
        this.storageKey = identity.storageMasterKey();
    }

    /**
     * Asks a running backup/restore to stop at its next chunk. WorkManager does
     * not interrupt a Worker's thread on cancel, so without this a cancelled
     * backup kept writing a multi-GB object to the end.
     */
    public void cancel() {
        cancelled = true;
    }

    private void checkCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("cancelled");
        }
    }

    /** The folder's index: every committed entry (the sidecars, decrypted). */
    public List<VaultEntry> loadManifest() throws IOException {
        return store.listEntries(account, storageKey);
    }

    /**
     * Encrypts {@code file} and writes it into the folder as a new object, then
     * commits it with its sidecar. {@code thumb} is a tiny base64 preview kept
     * in the (encrypted) sidecar, or null; {@code origin} the download's origin
     * URL (nullable) for a restored row's {@code MIME · domain};
     * {@code downloadedAt} the download's own date (0 = unknown).
     *
     * <p><b>Dedup:</b> if this file is ALREADY backed up (same name + size), no
     * new object is created — the existing entry is returned, with a missing
     * preview or origin backfilled into its sidecar. So tapping "Back up" again
     * on the same download is a no-op, not a duplicate.
     */
    public VaultEntry backupFile(File file, String mime, String thumb,
                                 ProgressListener progress, String origin, long downloadedAt)
            throws IOException, GeneralSecurityException {
        return backupStream(file.getName(), file.length(),
                () -> new FileInputStream(file), mime, thumb, progress, origin, downloadedAt);
    }

    /** The access-agnostic backup core: {@code name}/{@code size} identify the
     *  content (the dedup key), {@code source} supplies the plaintext bytes. */
    public VaultEntry backupStream(String name, long size, StreamSource source,
                                   String mime, String thumb, ProgressListener progress,
                                   String origin, long downloadedAt)
            throws IOException, GeneralSecurityException {
        VaultEntry existing = findExisting(name, size);
        if (existing != null) {
            // Already backed up — don't duplicate the object. An entry written
            // by an older build can be MISSING fields this one knows (the
            // preview, the origin); backfill whatever is absent straight into
            // the sidecar — cheap, no re-upload. Carries the original
            // backedUpAt: a repair is not a new backup and must not jump to
            // the top of the Backups list.
            String mergedThumb = existing.thumb != null ? existing.thumb : thumb;
            String mergedOrigin = !TextUtils.isEmpty(existing.origin) ? existing.origin : origin;
            boolean gainedThumb = existing.thumb == null && mergedThumb != null;
            boolean gainedOrigin = TextUtils.isEmpty(existing.origin)
                    && !TextUtils.isEmpty(mergedOrigin);
            if (gainedThumb || gainedOrigin) {
                VaultEntry repaired = new VaultEntry(existing.objectId, existing.wrappedDek,
                        existing.name, existing.size, existing.mime, existing.downloadedAt,
                        existing.chunkCount, mergedThumb, mergedOrigin, existing.backedUpAt);
                store.writeEntry(account, repaired, storageKey);
                return repaired;
            }
            return existing;
        }
        int chunkCount = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (chunkCount == 0) {
            chunkCount = 1; // an empty file still writes one (empty) chunk
        }
        String objectId = BackupLayout.newObjectId();
        byte[] dek = VaultCrypto.generateDek();
        boolean attempted = false;
        boolean committed = false;
        try {
            try (InputStream in = source.open()) {
                // From here a store may hold part of an object, so a failure
                // below deletes it. The exact length is declared up front: a
                // store that sends the object over the network needs it, and
                // refuses a short or long write (a file that changed size
                // under the backup).
                attempted = true;
                try (OutputStream out = store.openNewObject(account, objectId,
                        objectLength(size, chunkCount))) {
                    byte[] buf = new byte[CHUNK_SIZE];
                    long written = 0;
                    if (progress != null) {
                        progress.onProgress(0, size); // 0% up front (determinate)
                    }
                    for (int i = 0; i < chunkCount; i++) {
                        checkCancelled();
                        int n = readFully(in, buf);
                        byte[] plain = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                        // FDVC2: sealed with (objectId, i) as GCM AAD, so a
                        // restore proves the bytes are chunk i of THIS object.
                        byte[] enc = VaultCrypto.encryptChunk(plain, dek, objectId, i);
                        out.write(enc);
                        written += n;
                        if (progress != null) {
                            progress.onProgress(written, size);
                        }
                    }
                    out.flush();
                }
            }
            checkCancelled();
            String wrappedDek = Base64.encodeToString(VaultCrypto.wrapDek(dek, storageKey), B64);
            // downloadedAt is the DOWNLOAD's own date, not "now": a restored row
            // must land in the same date section the original sat in. backedUpAt
            // is the OTHER date — what the Backups list sorts on.
            long stamp = downloadedAt > 0 ? downloadedAt : System.currentTimeMillis();
            VaultEntry entry = new VaultEntry(objectId, wrappedDek, name,
                    size, mime, stamp, chunkCount, thumb, origin,
                    System.currentTimeMillis());
            // The sidecar is the commit marker — written LAST, after every
            // chunk is in the object. An object this never reaches is an
            // orphan the next listing sweeps after a day.
            store.writeEntry(account, entry, storageKey);
            committed = true;
            return entry;
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (!committed && attempted) {
                // A failed or cancelled upload: free the half-written object
                // now rather than leave it to the age sweep — a retry writes a
                // fresh id, so nothing refers to this one.
                try {
                    store.deleteObject(account, objectId);
                } catch (IOException ignored) {
                    // best-effort; the orphan sweep is the backstop
                }
            }
        }
    }

    /** The existing entry for a file (matched by name + size), or null. */
    private VaultEntry findExisting(String name, long size) throws IOException {
        for (VaultEntry e : loadManifest()) {
            if (size == e.size && name != null && name.equals(e.name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Restores an entry to {@code dest}: reads the object chunk by chunk,
     * decrypts, reassembles. The per-file DEK is unwrapped from the sidecar
     * under the storage master key. {@code progress} may be null.
     */
    public void restoreFile(VaultEntry entry, File dest, ProgressListener progress)
            throws IOException, GeneralSecurityException {
        byte[] dek = VaultCrypto.unwrapDek(Base64.decode(entry.wrappedDek, B64), storageKey);
        try {
            long done = 0;
            try (InputStream in = store.openObject(account, entry.objectId);
                 OutputStream out = new FileOutputStream(dest)) {
                for (int i = 0; i < entry.chunkCount; i++) {
                    checkCancelled();
                    int cipherLen = chunkCiphertextLength(entry.size, i);
                    byte[] enc = new byte[cipherLen];
                    int got = readFully(in, enc);
                    if (got != cipherLen) {
                        throw new IOException("object truncated at chunk " + i
                                + " (" + got + " of " + cipherLen + " bytes)");
                    }
                    // The index rides into decryptChunk as GCM AAD (FDVC2), so a
                    // chunk that is not chunk i of this object fails to open.
                    byte[] plain = VaultCrypto.decryptChunk(enc, dek, entry.objectId, i);
                    out.write(plain);
                    done += plain.length;
                    if (progress != null) {
                        progress.onProgress(done, entry.size);
                    }
                }
            }
            if (entry.size > 0 && done != entry.size) {
                throw new IOException("restored " + done + " bytes, sidecar says " + entry.size);
            }
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * The ciphertext length of chunk {@code i} of an object whose plaintext is
     * {@code size} bytes — every chunk but the last is a full {@link #CHUNK_SIZE}
     * plus the overhead; the last carries the remainder (an empty file's one
     * chunk is the overhead alone). Derived from the SIDECAR (authenticated under the storage
     * key), never from what the provider reports as the file's size.
     */
    public static int chunkCiphertextLength(long size, int i) {
        long start = (long) i * CHUNK_SIZE;
        long plainLen = Math.max(0, Math.min(CHUNK_SIZE, size - start));
        return (int) (plainLen + CHUNK_OVERHEAD);
    }

    /** The exact byte length of an object holding {@code size} plaintext
     *  bytes in {@code chunkCount} chunks: every chunk carries the overhead.
     *  What {@link BackupStore#openNewObject} is told to expect. */
    public static long objectLength(long size, int chunkCount) {
        return size + (long) chunkCount * CHUNK_OVERHEAD;
    }

    /** The byte offset of chunk {@code i} inside the object file. */
    public static long chunkOffset(int i) {
        return (long) i * ((long) CHUNK_SIZE + CHUNK_OVERHEAD);
    }

    /** Removes one entry: the sidecar first (unreference), then the object. */
    public void deleteEntry(VaultEntry entry) throws IOException {
        store.deleteObject(account, entry.objectId);
    }

    /** Removes several entries, each the same way as {@link #deleteEntry}. */
    public void deleteEntries(List<VaultEntry> toDelete) throws IOException {
        if (toDelete == null || toDelete.isEmpty()) {
            return;
        }
        IOException first = null;
        for (VaultEntry e : toDelete) {
            try {
                store.deleteObject(account, e.objectId);
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Removes everything this account holds in the folder. */
    public void deleteAll() throws IOException {
        store.deleteAccount(account);
    }

    /** Reads up to buf.length bytes, tolerating short reads; returns the count. */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
