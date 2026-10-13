package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sender's side of nearby: lists the phones on this network that have
 * their Receive screen open (each advertises {@link P2pNearbyAdvertiser#SERVICE_TYPE}).
 * Owned by the Send screen, started with its view and stopped with it.
 *
 * <p>Discovery hands back names only; each found service is RESOLVED to a
 * host + port + TXT (the /offer token). Resolves are SERIALIZED: Android's
 * {@code resolveService} refuses a second concurrent resolve with
 * {@code FAILURE_ALREADY_ACTIVE}, so found services queue and resolve one
 * at a time. IPv4 is preferred — the receiver's LAN server binds a
 * site-local IPv4 — and a service with no IPv4 is listed as unreachable
 * rather than shown as a tappable dead row.
 *
 * <p>Everything reaches the {@link Listener} on the main thread as a fresh
 * list, in first-seen order.
 */
public final class P2pNearbyDiscovery {

    private static final String TAG = "P2pNearbyDiscovery";

    /** One nearby phone, as resolved. */
    public static final class Device {
        /** The DNS-SD instance name (unique on the LAN; what the list shows). */
        @NonNull public final String name;
        @NonNull public final String host;
        public final int port;
        @NonNull public final String token;

        Device(@NonNull String name, @NonNull String host, int port, @NonNull String token) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.token = token;
        }

        /** The receiver's {@code /offer} URL. */
        @NonNull
        public String offerUrl() {
            return P2pLanServer.offerUrl(host, port, token);
        }
    }

    public interface Listener {
        /** Main thread. The full current list, first-seen first. */
        void onDevicesChanged(@NonNull List<Device> devices);
    }

    private final NsdManager mNsdManager;
    private final Listener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    // Keyed by service name. LinkedHashMap keeps first-seen order stable
    // across updates, so the list never reshuffles under a finger.
    private final Map<String, Device> mDevices = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> mResolveQueue = new ArrayDeque<>();
    private boolean mResolving;
    private boolean mDiscovering;

    private final NsdManager.DiscoveryListener mDiscoveryListener = new NsdManager.DiscoveryListener() {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "discovery start failed: " + errorCode);
            }
            mDiscovering = false;
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "discovery stop failed: " + errorCode);
            }
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            if (!isOurType(serviceInfo)) {
                return;
            }
            mMainHandler.post(() -> enqueueResolve(serviceInfo));
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            final String name = serviceInfo.getServiceName();
            mMainHandler.post(() -> {
                if (mDevices.remove(name) != null) {
                    publish();
                }
            });
        }
    };

    public P2pNearbyDiscovery(@NonNull Context context, @NonNull Listener listener) {
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        mListener = listener;
    }

    public void start() {
        if (mNsdManager == null || mDiscovering) {
            return;
        }
        try {
            mDiscovering = true;
            mNsdManager.discoverServices(P2pNearbyAdvertiser.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        } catch (RuntimeException e) {
            mDiscovering = false;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "discoverServices threw: " + e.getMessage());
            }
        }
    }

    public void stop() {
        if (mNsdManager != null && mDiscovering) {
            mDiscovering = false;
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (RuntimeException e) {
                // Not active — nothing to stop.
            }
        }
        mResolveQueue.clear();
        mDevices.clear();
    }

    private static boolean isOurType(NsdServiceInfo info) {
        String type = info.getServiceType();
        // Android reports the type with or without a trailing dot depending
        // on the version; compare the meaningful part only.
        return type != null && type.startsWith(P2pNearbyAdvertiser.SERVICE_TYPE);
    }

    private void enqueueResolve(NsdServiceInfo info) {
        if (!mDiscovering) {
            return;
        }
        mResolveQueue.add(info);
        resolveNext();
    }

    private void resolveNext() {
        if (mResolving || !mDiscovering) {
            return;
        }
        NsdServiceInfo next = mResolveQueue.poll();
        if (next == null) {
            return;
        }
        mResolving = true;
        try {
            mNsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    mMainHandler.post(() -> {
                        mResolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    mMainHandler.post(() -> {
                        mResolving = false;
                        onResolved(serviceInfo);
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            mResolving = false;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "resolveService threw: " + e.getMessage());
            }
        }
    }

    private void onResolved(NsdServiceInfo info) {
        if (!mDiscovering) {
            return;
        }
        String token = P2pNearbyAdvertiser.attribute(info, P2pNearbyAdvertiser.TXT_TOKEN);
        String host = preferredHost(info);
        if (token == null || token.isEmpty() || host == null || info.getPort() <= 0) {
            return;
        }
        String name = info.getServiceName();
        if (name == null || name.isEmpty()) {
            name = P2pNearbyAdvertiser.attribute(info, P2pNearbyAdvertiser.TXT_DEVICE);
        }
        if (name == null || name.isEmpty()) {
            return;
        }
        mDevices.put(name, new Device(name, host, info.getPort(), token));
        publish();
    }

    /**
     * The address to reach the service on. API 34 resolves to a LIST and an
     * IPv4 is picked from it; before that a single host is reported and is
     * used as-is (the system resolver reports the IPv4 when one exists).
     */
    @Nullable
    private static String preferredHost(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            List<InetAddress> addresses = info.getHostAddresses();
            InetAddress any = null;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) {
                    return address.getHostAddress();
                }
                if (any == null) {
                    any = address;
                }
            }
            return any == null ? null : any.getHostAddress();
        }
        InetAddress host = info.getHost();
        return host == null ? null : host.getHostAddress();
    }

    private void publish() {
        mListener.onDevicesChanged(new ArrayList<>(mDevices.values()));
    }
}
