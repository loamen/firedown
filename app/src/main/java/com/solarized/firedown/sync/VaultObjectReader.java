package com.solarized.firedown.sync;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.crypto.VaultCrypto;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Random-access, decrypt-on-read view over ONE backed-up object in the folder —
 * the enabler for STREAMING a backed-up file without first restoring it to disk.
 *
 * <p>It works because the object's layout is chunked and every chunk is
 * INDEPENDENTLY sealed: the plaintext is split into fixed
 * {@link VaultEngine#CHUNK_SIZE} (8 MiB) blocks, each AES-256-GCM'd under the
 * per-file DEK with its own IV ({@link VaultCrypto#encryptChunk}) and written
 * back to back. So a plaintext byte offset maps to exactly one chunk
 * ({@code offset / CHUNK_SIZE}) at a byte range the sidecar fixes
 * ({@link VaultEngine#chunkOffset}, {@link VaultEngine#chunkCiphertextLength}),
 * read with one positional read and decrypted on its own — a player seeking
 * anywhere pays for the ~8 MiB chunk(s) covering that range, not the file.</p>
 *
 * <p>The DEK is unwrapped once in the constructor from the sidecar's wrapped
 * blob under the account's storage master key. The object is located (one
 * directory listing) and opened in the constructor too, so construction is IO
 * and belongs off the main thread; the descriptor stays open for the reader's
 * life. A tiny LRU of decrypted chunks avoids re-reading the same chunk on
 * adjacent reads (a demuxer reads a chunk in many small {@code readAt} calls).</p>
 *
 * <p>Not thread-safe by field, so {@link #readAt} is {@code synchronized}.</p>
 */
public final class VaultObjectReader {

    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    /** Decrypted-chunk LRU size. Two covers a read straddling a chunk boundary
     *  plus a small seek-back without a re-read; larger would hold tens of MiB
     *  of plaintext in memory for no real gain. */
    private static final int CACHE_CHUNKS = 2;

    private final FolderStore store;
    private final String objectIdHex;
    private final long length;      // plaintext bytes (the file size)
    private final int chunkCount;
    private final byte[] dek;
    private final Uri object;
    private final ParcelFileDescriptor pfd;

    /** Decrypted-chunk cache, access-ordered so the eldest entry is the LRU
     *  victim. Bounded to {@link #CACHE_CHUNKS}. */
    private final Map<Integer, byte[]> cache = new LinkedHashMap<Integer, byte[]>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > CACHE_CHUNKS;
        }
    };

    private boolean closed;

    public VaultObjectReader(FolderStore store, SyncIdentity identity, VaultEntry entry)
            throws GeneralSecurityException, IOException {
        this.store = store;
        this.objectIdHex = entry.objectId;
        this.length = entry.size;
        this.chunkCount = Math.max(1, entry.chunkCount);
        this.dek = VaultCrypto.unwrapDek(Base64.decode(entry.wrappedDek, B64),
                identity.storageMasterKey());
        Uri doc;
        ParcelFileDescriptor opened;
        try {
            doc = store.requireObject(identity.accountBase32(), entry.objectId);
            opened = store.openRead(doc);
        } catch (IOException | RuntimeException e) {
            Arrays.fill(dek, (byte) 0);
            throw e;
        }
        this.object = doc;
        this.pfd = opened;
    }

    /** Total plaintext length of the file. */
    public long length() {
        return length;
    }

    /**
     * Reads up to {@code len} plaintext bytes starting at {@code pos} into
     * {@code buf} at {@code off}. Returns the number of bytes read (≥ 1) or
     * {@code -1} at end of file. A request straddling a chunk boundary returns
     * only up to that boundary (the caller loops), the standard
     * {@code InputStream}/{@code DataSource} contract.
     */
    public synchronized int readAt(long pos, byte[] buf, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("reader closed");
        }
        if (pos >= length || len <= 0) {
            return -1;
        }
        int chunkIndex = (int) (pos / VaultEngine.CHUNK_SIZE);
        int within = (int) (pos % VaultEngine.CHUNK_SIZE);
        byte[] chunk = decryptedChunk(chunkIndex);
        if (within >= chunk.length) {
            return -1;
        }
        int available = chunk.length - within;
        int n = Math.min(len, available);
        long remainingInFile = length - pos;
        if (n > remainingInFile) {
            n = (int) remainingInFile;
        }
        System.arraycopy(chunk, within, buf, off, n);
        return n;
    }

    /**
     * Reads the WHOLE object into memory (for images, which media3 can't stream
     * and which are small). Refuses an object larger than {@code maxBytes} with
     * an {@link IOException} so a mislabeled huge file can't OOM the app.
     */
    public synchronized byte[] readAll(long maxBytes) throws IOException {
        if (length > maxBytes) {
            throw new IOException("object too large to load in memory: " + length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(length, Integer.MAX_VALUE));
        byte[] buf = new byte[64 * 1024];
        long pos = 0;
        while (pos < length) {
            int n = readAt(pos, buf, 0, buf.length);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            pos += n;
        }
        return out.toByteArray();
    }

    /** Reads + decrypts chunk {@code i}, serving from the LRU when warm. */
    private byte[] decryptedChunk(int i) throws IOException {
        byte[] cached = cache.get(i);
        if (cached != null) {
            return cached;
        }
        if (i < 0 || i >= chunkCount) {
            throw new IOException("chunk " + i + " out of range");
        }
        int cipherLen = VaultEngine.chunkCiphertextLength(length, i);
        byte[] enc = store.readRange(object, pfd, VaultEngine.chunkOffset(i), cipherLen);
        if (enc.length != cipherLen) {
            throw new IOException("object truncated at chunk " + i
                    + " (" + enc.length + " of " + cipherLen + " bytes)");
        }
        byte[] plain;
        try {
            // The reader ALREADY knows the index, so the FDVC2 AAD check comes
            // free: bytes that are not chunk i of this object fail here instead
            // of the player rendering spliced media.
            plain = VaultCrypto.decryptChunk(enc, dek, objectIdHex, i);
        } catch (GeneralSecurityException e) {
            throw new IOException("chunk " + i + " decrypt failed", e);
        }
        cache.put(i, plain);
        return plain;
    }

    /** Number of chunks (from the sidecar). */
    public int chunkCount() {
        return chunkCount;
    }

    /** Wipes the unwrapped DEK, drops the plaintext cache and closes the
     *  object. Idempotent. */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Arrays.fill(dek, (byte) 0);
        cache.clear();
        try {
            pfd.close();
        } catch (IOException ignored) {
            // nothing further to do with a descriptor that will not close
        }
    }
}
