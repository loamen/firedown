package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Makes this phone visible to nearby senders while the Receive screen is
 * open: one DNS-SD registration of {@link #SERVICE_TYPE} through the
 * system's {@link NsdManager}, naming the {@link P2pLanServer}'s port and
 * carrying the per-session token the {@code /offer} route is gated on.
 *
 * <p>Why NSD and not our own multicast: the system responder does the mDNS
 * (no MulticastLock, no {@code CHANGE_WIFI_MULTICAST_STATE}, and NSD is not
 * in the {@code NEARBY_WIFI_DEVICES} set — Wi-Fi Aware/Direct are), so this
 * adds no permission. Android may RENAME the service on a name conflict;
 * the name actually registered is what the sender's list shows, so it is
 * reported back through {@code onVisible} rather than assumed.
 *
 * <p>Lifetime = the Receive screen's idle state. Nothing is advertised while
 * a transfer runs (the screen stops listening), and nothing survives the
 * screen — a phone is never discoverable without a screen saying so.
 */
final class P2pNearbyAdvertiser {

    private static final String TAG = "P2pNearbyAdvertiser";

    /** DNS-SD service type; must match {@link P2pNearbyDiscovery}. */
    static final String SERVICE_TYPE = "_firedown._tcp";

    /** TXT keys: the /offer token, the human device name, the record version. */
    static final String TXT_TOKEN = "t";
    static final String TXT_DEVICE = "d";
    static final String TXT_VERSION = "v";
    static final String VERSION = "1";

    /** DNS-SD instance names are capped at 63 bytes; keep the model readable. */
    private static final int MAX_NAME_CHARS = 40;

    private final NsdManager mNsdManager;
    private final NsdServiceInfo mInfo;
    private final Consumer<String> mOnVisible;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mRegistered;

    private final NsdManager.RegistrationListener mListener = new NsdManager.RegistrationListener() {
        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "registration failed: " + errorCode);
            }
            mRegistered = false;
            mMainHandler.post(() -> mOnVisible.accept(null));
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "unregistration failed: " + errorCode);
            }
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            // The system may have appended " (2)" to dodge a conflict — show
            // what the LAN actually sees.
            final String name = serviceInfo.getServiceName();
            mMainHandler.post(() -> mOnVisible.accept(name));
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            // Nothing to do; stop() already reported invisibility.
        }
    };

    /**
     * @param onVisible main thread; the registered name, or null when the
     *                  phone could not be made visible (no Wi-Fi, NSD error)
     */
    P2pNearbyAdvertiser(@NonNull Context context, @NonNull String deviceName,
                        int port, @NonNull String token,
                        @NonNull Consumer<String> onVisible) {
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mOnVisible = onVisible;
        String name = deviceName.trim();
        if (name.isEmpty()) {
            name = "Firedown";
        }
        if (name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS);
        }
        mInfo = new NsdServiceInfo();
        mInfo.setServiceName(name);
        mInfo.setServiceType(SERVICE_TYPE);
        mInfo.setPort(port);
        mInfo.setAttribute(TXT_TOKEN, token);
        mInfo.setAttribute(TXT_DEVICE, name);
        mInfo.setAttribute(TXT_VERSION, VERSION);
    }

    void start() {
        if (mNsdManager == null || mRegistered) {
            mMainHandler.post(() -> mOnVisible.accept(null));
            return;
        }
        try {
            mRegistered = true;
            mNsdManager.registerService(mInfo, NsdManager.PROTOCOL_DNS_SD, mListener);
        } catch (RuntimeException e) {
            // A listener already in use, or a system without NSD — never a
            // crash for a convenience affordance.
            mRegistered = false;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "registerService threw: " + e.getMessage());
            }
            mMainHandler.post(() -> mOnVisible.accept(null));
        }
    }

    void stop() {
        if (mNsdManager != null && mRegistered) {
            mRegistered = false;
            try {
                mNsdManager.unregisterService(mListener);
            } catch (RuntimeException e) {
                // "listener not registered" when registration had failed
                // between our flag flips — nothing to undo.
            }
        }
    }

    /** The attribute bytes NSD hands back, decoded (null when absent). */
    @Nullable
    static String attribute(@NonNull NsdServiceInfo info, @NonNull String key) {
        byte[] value = info.getAttributes() == null ? null : info.getAttributes().get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
