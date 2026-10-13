package com.solarized.firedown.p2pshare;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The ONE thing Firedown ever listens for on a LAN interface, and it is
 * deliberately tiny: two token-gated {@code POST}s of a ~1–2 KB signaling
 * code, body-capped, no file bytes (those ride the WebRTC DataChannel; the
 * loopback server stays 127.0.0.1-only). The token is 16 random bytes
 * carried only inside the offer code / the NSD TXT record the peer was
 * handed, and the server's lifetime is one share screen's.
 *
 * <ul>
 *   <li><b>{@code /answer} — the SENDER's listener.</b> What turns a share
 *       into a single-step flow: WebRTC needs an answer back (the receiver's
 *       candidates + fingerprint don't exist until Accept), and once the
 *       receiver has the offer it knows the sender's LAN address — the offer
 *       embeds {@code ans: http://<lan-ip>:<port>/answer?t=<token>} and the
 *       receiver POSTs its answer straight back. A body of {@code declined}
 *       is the receiver saying no, so the sender can offer elsewhere instead
 *       of waiting out the offer's lifetime.</li>
 *   <li><b>{@code /offer} — the RECEIVER's listener.</b> Advertised over NSD
 *       ({@link P2pNearbyAdvertiser}) while the Receive screen is open: a
 *       nearby sender taps this device in its list and POSTs the whole
 *       {@code FDS1.} offer here; the receiver previews it exactly as a
 *       scanned code and the answer returns over {@code /answer} above.</li>
 * </ul>
 *
 * <p>The {@link Handler} decides synchronously — {@code true} = 204 (taken),
 * {@code false} = 409 (a second answer after the first was applied; an offer
 * while another transfer is live). Only the route its owner serves is wired;
 * the other returns 404. Connections are handled on a small worker pool
 * ({@link #MAX_CONCURRENT}), so the handler can be called from several
 * threads at once and must decide atomically.
 *
 * <p><b>Every connection has an overall deadline</b> ({@link #CONNECTION_DEADLINE_MS})
 * on top of the per-read timeout: the head is read a byte at a time, so a
 * client trickling one byte every few seconds used to hold the one handling
 * thread for hours, blocking the receiver's {@code /offer} and the sender's
 * answer return for everyone else on the network. Connections beyond the
 * pool are closed at once.
 *
 * <p>Interface pick: prefers a wlan* site-local IPv4 so an active VPN's tun
 * address (unreachable by the peer) isn't advertised.
 */
public class P2pLanServer {

    private static final String TAG = "P2pLanServer";

    /** A signaling code is ~1-2 KB; anything bigger is a protocol violation. */
    private static final int MAX_BODY = 16 * 1024;

    /** The receiver's decline, POSTed to the sender's {@code /answer}. */
    public static final String DECLINED_BODY = "declined";

    /** Whole-connection budget: head + a 16 KB body from a phone on the LAN. */
    static final long CONNECTION_DEADLINE_MS = 10_000;

    /** Connections handled at once; more are closed (a phone or two is the real load). */
    static final int MAX_CONCURRENT = 4;

    /** Route decisions, on the accept thread. A null handler = route not served. */
    public interface Handler {
        /** Sender: an {@code FDR1.} answer, or {@link #DECLINED_BODY}. */
        boolean onAnswer(@NonNull String body);

        /** Receiver: an {@code FDS1.} offer from a nearby sender. */
        boolean onOffer(@NonNull String body);
    }

    private final Handler mHandler;
    private final boolean mServeAnswer;
    private final boolean mServeOffer;
    private final String mToken;

    private final byte[] mTokenBytes;

    private volatile ServerSocket mServerSocket;
    private volatile boolean mRunning;
    private ThreadPoolExecutor mWorkers;
    private String mBaseUrl;
    private int mPort;
    // The harness shortens it to test the deadline in milliseconds.
    long mDeadlineMs = CONNECTION_DEADLINE_MS;

    /**
     * @param serveAnswer wire {@code /answer} (the sender's side)
     * @param serveOffer  wire {@code /offer} (the receiver's side)
     */
    public P2pLanServer(@NonNull Handler handler, boolean serveAnswer, boolean serveOffer) {
        mHandler = handler;
        mServeAnswer = serveAnswer;
        mServeOffer = serveOffer;
        byte[] tokenBytes = new byte[16];
        new SecureRandom().nextBytes(tokenBytes);
        StringBuilder token = new StringBuilder(tokenBytes.length * 2);
        for (byte b : tokenBytes) {
            token.append(String.format(Locale.US, "%02x", b));
        }
        mToken = token.toString();
        mTokenBytes = mToken.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Bind to the device's LAN address. Returns false (and stays inert) when
     * there is no usable site-local IPv4 — the sender then simply omits the
     * answer-return URL; the receiver is not visible nearby.
     */
    public boolean start() {
        return start(findLanAddress());
    }

    /**
     * Bind to {@code lanAddress} (the harness's door — it binds the loopback;
     * production goes through {@link #start()} and the LAN pick).
     */
    boolean start(@Nullable InetAddress lanAddress) {
        if (lanAddress == null) {
            return false;
        }
        try {
            mServerSocket = new ServerSocket(0, 4, lanAddress);
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "bind failed: " + e.getMessage());
            }
            return false;
        }
        mPort = mServerSocket.getLocalPort();
        mBaseUrl = "http://" + lanAddress.getHostAddress() + ":" + mPort;
        mWorkers = new ThreadPoolExecutor(0, MAX_CONCURRENT, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "P2pLanWorker");
                    thread.setDaemon(true);
                    return thread;
                });
        mRunning = true;
        Thread acceptThread = new Thread(this::acceptLoop, "P2pLan");
        acceptThread.start();
        return true;
    }

    /** The sender's answer-return URL (embedded in the offer as {@code ans}). */
    @Nullable
    public String getAnswerUrl() {
        return mBaseUrl == null ? null : mBaseUrl + "/answer?t=" + mToken;
    }

    /** The per-session token the NSD TXT record carries for {@code /offer}. */
    @NonNull
    public String getToken() {
        return mToken;
    }

    public int getPort() {
        return mPort;
    }

    /**
     * The address to reach a nearby receiver on, from what NSD resolved: the
     * first IPv4, else null. The receiver binds a site-local IPv4 only, so an
     * IPv6-only result can never connect (and a scoped link-local one, with
     * its {@code %wlan0} zone, is not even a valid URL host); the device is
     * then not listed rather than shown as a row that cannot work.
     */
    @Nullable
    public static String reachableHost(@Nullable Iterable<InetAddress> addresses) {
        if (addresses == null) {
            return null;
        }
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
        }
        return null;
    }

    /** Build a peer's {@code /offer} URL from what NSD resolved. */
    @NonNull
    public static String offerUrl(@NonNull String host, int port, @NonNull String token) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + "/offer?t=" + token;
    }

    public void stop() {
        mRunning = false;
        ThreadPoolExecutor workers = mWorkers;
        if (workers != null) {
            workers.shutdownNow();
        }
        ServerSocket socket = mServerSocket;
        mServerSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Best-effort; the accept loop exits on the error.
            }
        }
    }

    private void acceptLoop() {
        while (mRunning) {
            Socket client;
            try {
                ServerSocket socket = mServerSocket;
                if (socket == null) {
                    return;
                }
                client = socket.accept();
            } catch (Exception e) {
                // Closed by stop(), or a teardown race — never crash the thread.
                return;
            }
            final Socket accepted = client;
            try {
                mWorkers.execute(() -> {
                    try {
                        handleConnection(accepted);
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "lan connection: " + e.getMessage());
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                // Pool full (or stopping): drop it rather than queue behind
                // whatever is holding the workers.
                closeQuietly(accepted);
            }
        }
    }

    private void handleConnection(Socket client) throws IOException {
        try {
            long deadline = System.currentTimeMillis() + mDeadlineMs;
            InputStream in = new DeadlineInputStream(client, deadline);
            OutputStream out = client.getOutputStream();

            P2pHttp.RequestHead head = P2pHttp.readHead(in);
            if (head == null) {
                P2pHttp.sendStatus(out, 400, "Bad Request");
                return;
            }
            if (!tokenMatches(head.query.get("t"))) {
                P2pHttp.sendStatus(out, 403, "Forbidden");
                return;
            }
            boolean answerRoute = mServeAnswer && "/answer".equals(head.path);
            boolean offerRoute = mServeOffer && "/offer".equals(head.path);
            if (!"POST".equals(head.method) || (!answerRoute && !offerRoute)) {
                P2pHttp.sendStatus(out, 404, "Not Found");
                return;
            }
            byte[] body = P2pHttp.readBody(in, P2pHttp.contentLength(head), MAX_BODY);
            if (body == null) {
                P2pHttp.sendStatus(out, 400, "Bad Request");
                return;
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            boolean taken = answerRoute ? mHandler.onAnswer(text) : mHandler.onOffer(text);
            if (taken) {
                P2pHttp.sendStatus(out, 204, "No Content");
            } else {
                P2pHttp.sendStatus(out, 409, "Conflict");
            }
            out.flush();
        } finally {
            closeQuietly(client);
        }
    }

    /** Constant-time compare (the token travels in TXT records and offers, but still). */
    private boolean tokenMatches(@Nullable String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(mTokenBytes, presented.getBytes(StandardCharsets.US_ASCII));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Best-effort.
        }
    }

    /**
     * Input stream with ONE deadline for the whole connection: before every
     * read the socket timeout is set to the time left, and a read past the
     * deadline fails. A per-read timeout alone resets on every byte.
     */
    static final class DeadlineInputStream extends FilterInputStream {
        private final Socket mSocket;
        private final long mDeadline;

        DeadlineInputStream(@NonNull Socket socket, long deadline) throws IOException {
            super(socket.getInputStream());
            mSocket = socket;
            mDeadline = deadline;
        }

        private void arm() throws IOException {
            long left = mDeadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new SocketTimeoutException("connection deadline");
            }
            mSocket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
        }

        @Override
        public int read() throws IOException {
            arm();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            arm();
            return super.read(buffer, offset, length);
        }
    }

    /**
     * The device's LAN IPv4. wlan* interfaces are preferred so an active VPN's
     * tun address (site-local too, but unreachable by the peer) isn't picked
     * when Wi-Fi is also up; otherwise the first up, non-loopback site-local
     * IPv4 wins. Null when the device has no LAN address at all.
     */
    @Nullable
    private static InetAddress findLanAddress() {
        try {
            List<NetworkInterface> interfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            InetAddress fallback = null;
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    boolean siteLocalV4 = address instanceof Inet4Address
                            && address.isSiteLocalAddress();
                    if (!siteLocalV4) {
                        continue;
                    }
                    String name = networkInterface.getName();
                    if (name != null && name.startsWith("wlan")) {
                        return address;
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
