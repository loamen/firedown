package com.solarized.firedown.data.repository;

import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.data.dao.WasmAllowlistDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.WasmAllowlistEntity;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Persistent per-site allowlist for WebAssembly. WASM is disabled by
 * default for privacy; sites listed here are allowed to use it while
 * the user has them open. {@link com.solarized.firedown.geckoview.GeckoComponents}
 * consults {@link #contains(String)} on every navigation and flips
 * the global {@code javascript.options.wasm} pref accordingly — the
 * pref is global to the Gecko runtime so the behaviour is "WASM on
 * while an allowlisted host is the active tab".
 *
 * <p>{@link #getNeedsWasmLive()} fires whenever the content-script
 * bridge reports a page tried to use WASM and failed; the browser
 * fragment observes it to show the "Enable for {host}?" snackbar.</p>
 */
@Singleton
public class WasmAllowlistRepository {

    private final WasmAllowlistDao mDao;
    private final Executor mDiskExecutor;
    private final HashSet<Integer> mSyncEntities = new HashSet<>();
    private final MutableLiveData<String> mNeedsWasmEvent = new MutableLiveData<>();

    @Inject
    public WasmAllowlistRepository(WasmAllowlistDao dao, @Qualifiers.DiskIO Executor diskExecutor) {
        this.mDao = dao;
        this.mDiskExecutor = diskExecutor;
        mDiskExecutor.execute(() -> mSyncEntities.addAll(mDao.getAllIds()));
    }

    public void add(String url) {
        String domain = WebUtils.getDomainName(url);
        int id = domain.hashCode();

        WasmAllowlistEntity entity = new WasmAllowlistEntity();
        entity.setId(id);
        entity.setOrigin(domain);
        entity.setDate(System.currentTimeMillis());

        mSyncEntities.add(id);
        mDiskExecutor.execute(() -> mDao.insert(entity));
    }

    public void delete(String url) {
        String domain = WebUtils.getDomainName(url);
        int id = domain.hashCode();
        mSyncEntities.remove(id);
        mDiskExecutor.execute(() -> mDao.deleteById(id));
    }

    public boolean contains(String url) {
        if (url == null) return false;
        String domain = WebUtils.getDomainName(url);
        return mSyncEntities.contains(domain.hashCode());
    }

    /**
     * Fired by the content-script bridge when a page logged a WASM
     * unavailability error. Carries the page URL so the snackbar can
     * scope the "Enable for {host}?" action to the right origin.
     */
    public void postNeedsWasm(String url) {
        mNeedsWasmEvent.postValue(url);
    }

    public MutableLiveData<String> getNeedsWasmLive() {
        return mNeedsWasmEvent;
    }
}
