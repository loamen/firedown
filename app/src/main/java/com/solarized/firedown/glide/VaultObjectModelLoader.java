package com.solarized.firedown.glide;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import com.solarized.firedown.sync.FolderStore;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.VaultObjectInputStream;
import com.solarized.firedown.sync.VaultObjectReader;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.IOException;
import java.io.InputStream;

/**
 * Glide {@link ModelLoader} that loads a {@link VaultObjectModel} as a
 * decrypt-on-read {@link InputStream} over its object in the backup folder.
 * With it registered, {@code Glide.with(...).load(model)} pulls a preview
 * straight from the encrypted file — the recovery code is loaded on Glide's
 * own background thread, the DEK unwrapped on-device, and each 8&nbsp;MiB chunk
 * decrypted as the decoder reads. Built-in stream decoders then handle images.
 */
public final class VaultObjectModelLoader implements ModelLoader<VaultObjectModel, InputStream> {

    private final Context appContext;

    public VaultObjectModelLoader(Context appContext) {
        this.appContext = appContext;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull VaultObjectModel model, int width,
                                               int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(model.cacheKey()), new Fetcher(appContext, model));
    }

    @Override
    public boolean handles(@NonNull VaultObjectModel model) {
        return model.objectId != null && model.wrappedDek != null;
    }

    /** Opens the decrypt-on-read stream on Glide's source thread. The reader's
     *  construction locates and opens the object (a folder listing + an open),
     *  which Glide does on the same background thread. */
    private static final class Fetcher implements DataFetcher<InputStream> {
        private final Context appContext;
        private final VaultObjectModel model;
        private VaultObjectInputStream stream;

        Fetcher(Context appContext, VaultObjectModel model) {
            this.appContext = appContext;
            this.model = model;
        }

        @Override
        public void loadData(@NonNull Priority priority,
                             @NonNull DataCallback<? super InputStream> callback) {
            byte[] code = new SyncSecrets(appContext).load();
            if (code == null) {
                callback.onLoadFailed(new IOException("no recovery code"));
                return;
            }
            try {
                SyncIdentity identity = SyncIdentity.fromCode(code);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
                FolderStore store = new FolderStore(appContext, prefs);
                VaultEntry entry = new VaultEntry(model.objectId, model.wrappedDek, null,
                        model.size, null, 0, model.chunkCount, null, null); // thumb + origin unused for decode
                VaultObjectReader reader = new VaultObjectReader(store, identity, entry);
                stream = new VaultObjectInputStream(reader);
                callback.onDataReady(stream);
            } catch (Exception e) {
                callback.onLoadFailed(e);
            } finally {
                SyncSecrets.wipe(code);
            }
        }

        @Override
        public void cleanup() {
            if (stream != null) {
                stream.close(); // closes the reader → wipes the DEK, closes the object
                stream = null;
            }
        }

        @Override
        public void cancel() {
            // Best-effort — closing the stream aborts an in-flight read. Glide
            // still calls cleanup() afterward (idempotent).
            if (stream != null) {
                stream.close();
            }
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

    /** Registered in {@code GlideModule}. Holds the app context for every
     *  folder load. */
    public static final class Factory implements ModelLoaderFactory<VaultObjectModel, InputStream> {
        private final Context appContext;

        public Factory(Context context) {
            this.appContext = context.getApplicationContext();
        }

        @NonNull
        @Override
        public ModelLoader<VaultObjectModel, InputStream> build(@NonNull MultiModelLoaderFactory factory) {
            return new VaultObjectModelLoader(appContext);
        }

        @Override
        public void teardown() {
            // no-op
        }
    }
}
