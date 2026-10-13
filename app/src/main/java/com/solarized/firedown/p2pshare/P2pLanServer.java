package com.solarized.firedown.p2pshare;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
 * <p>The {@link Handler} decides on the server's accept thread and answers
 * synchronously — {@code true} = 204 (taken), {@code false} = 409 (a second
 * answer after the first was applied; an offer while another transfer is
 * live). Only the route its owner serves is wired; the other returns 404.
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

    private volatile ServerSocket mServerSocket;
    private volatile boolean mRunning;
    private String mBaseUrl;
    private int mPort;

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
    }

    /**
     * Bind to the device's LAN address. Returns false (and stays inert) when
     * there is no usable site-local IPv4 — the sender then simply omits the
     * answer-return URL; the receiver is not visible nearby.
     */
    public boolean start() {
        InetAddress lanAddress = findLanAddress();
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

    /** Build a peer's {@code /offer} URL from what NSD resolved. */
    @NonNull
    public static String offerUrl(@NonNull String host, int port, @NonNull String token) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + "/offer?t=" + token;
    }

    public void stop() {
        mRunning = false;
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
            // Handled inline on this thread: bodies are ~1-2 KB and the
            // legitimate clients are one or two phones, so no handler pool.
            try {
                handleConnection(client);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "lan connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) throws IOException {
        try {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            P2pLoopbackServer.RequestHead head = P2pLoopbackServer.readHead(in);
            if (head == null) {
                P2pLoopbackServer.sendStatus(out, 400, "Bad Request");
                return;
            }
            if (!mToken.equals(head.query.get("t"))) {
                P2pLoopbackServer.sendStatus(out, 403, "Forbidden");
                return;
            }
            boolean answerRoute = mServeAnswer && "/answer".equals(head.path);
            boolean offerRoute = mServeOffer && "/offer".equals(head.path);
            if (!"POST".equals(head.method) || (!answerRoute && !offerRoute)) {
                P2pLoopbackServer.sendStatus(out, 404, "Not Found");
                return;
            }
            long declared;
            try {
                declared = Long.parseLong(head.headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                declared = -1;
            }
            if (declared <= 0 || declared > MAX_BODY) {
                P2pLoopbackServer.sendStatus(out, 400, "Bad Request");
                return;
            }
            byte[] body = new byte[(int) declared];
            int have = 0;
            while (have < body.length) {
                int read = in.read(body, have, body.length - have);
                if (read < 0) {
                    throw new IOException("body truncated");
                }
                have += read;
            }
            String text = new String(body, StandardCharsets.UTF_8).trim();
            boolean taken = answerRoute ? mHandler.onAnswer(text) : mHandler.onOffer(text);
            if (taken) {
                P2pLoopbackServer.sendStatus(out, 204, "No Content");
            } else {
                P2pLoopbackServer.sendStatus(out, 409, "Conflict");
            }
            out.flush();
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // Best-effort.
            }
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
