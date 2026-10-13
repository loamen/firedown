package com.solarized.firedown.p2pshare;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.RestoredFileAccess;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback byte bridge between the p2pshare WebRTC engine (a page script in a
 * hidden GeckoSession — createOffer() hangs in a WebExtension background page,
 * so the engine runs in a real content document served from /engine here) and
 * the filesystem. File bytes must NOT cross the native-messaging bridge — every
 * chunk would be base64-in-JSON through the EventDispatcher — so instead the
 * engine speaks plain HTTP to this server on 127.0.0.1:
 *
 * <ul>
 *   <li>SEND: {@code GET /read?t=<token>} streams the shared file; the engine
 *       fetch()es it and pumps the response stream into the DataChannel.
 *       Optional {@code &from=<offset>} starts mid-file (a resumed transfer
 *       streams only the remainder) and {@code &len=<bytes>} caps the body
 *       (the engine's resume-tail verification reads one 64 KB window).</li>
 *   <li>RECEIVE: {@code POST /write?t=<token>&off=<offset>} appends a chunk
 *       batch to the target file. The engine serializes its POSTs and the
 *       offset is verified against the bytes already written, so ordering on
 *       disk is guaranteed even if a retry ever duplicated a request. A
 *       resumed session arms the target at the kept byte count; {@code off=0}
 *       against a non-empty target is the one sanctioned exception — the
 *       sender refused the resume (tail mismatch), so truncate and restart.</li>
 * </ul>
 *
 * Bound to the loopback address only and gated by a per-session random token,
 * so nothing off-device (and no other app reading logcat — the token is never
 * logged) can read the shared file or corrupt a transfer. Lifetime is exactly
 * one share session: {@link P2pShareController} starts it when a transfer is
 * prepared and stops it when the share screen closes, mirroring the old
 * LAN-share "nothing listens when the sheet isn't open" contract.
 */
public class P2pLoopbackServer {

    private static final String TAG = "P2pLoopbackServer";

    /**
     * Cap on a single write body (the engine flushes ~4 MiB batches; anything
     * bigger is a protocol violation, not a bigger batch).
     */
    private static final int MAX_WRITE_BODY = 16 * 1024 * 1024;

    private static final int STREAM_BUFFER = 64 * 1024;

    private final Context mContext;
    private final String mToken;

    private volatile ServerSocket mServerSocket;
    private volatile ExecutorService mHandlerPool;
    private Thread mAcceptThread;
    private volatile boolean mRunning;

    // SEND role — the file served on /read.
    private volatile String mReadPath;

    // RECEIVE role — the .part file /write appends to. mWriteLock serializes
    // the WRITES (offset check + append; the engine's POSTs are sequential by
    // contract). Closing does NOT take it: closeWriteTarget runs on the main
    // thread (teardown, finalize), and a write holds the lock while it reads
    // its body off the socket — up to the read timeout on a stuck POST — so a
    // locked close could block the UI for that long (ANR). It swaps the file
    // out atomically and closes it; a write caught mid-body fails with an
    // IOException, which only happens while the session is being torn down.
    private final Object mWriteLock = new Object();
    private final AtomicReference<RandomAccessFile> mWriteFile = new AtomicReference<>();
    private long mWritten;

    public P2pLoopbackServer(Context context) {
        mContext = context.getApplicationContext();
        byte[] tokenBytes = new byte[16];
        new SecureRandom().nextBytes(tokenBytes);
        StringBuilder token = new StringBuilder(tokenBytes.length * 2);
        for (byte b : tokenBytes) {
            token.append(String.format(Locale.US, "%02x", b));
        }
        mToken = token.toString();
    }

    public void start() throws IOException {
        // Ephemeral port on the IPv4 loopback only — never a LAN interface.
        //
        // MUST bind to 127.0.0.1 explicitly, NOT InetAddress.getLoopbackAddress():
        // on Android the latter returns the IPv6 loopback ::1 (AOSP's
        // loopbackAddresses() is {Inet6Address.LOOPBACK, Inet4Address.LOOPBACK}
        // and getLoopbackAddress() takes [0]). The engine URL (baseUrl()) is the
        // IPv4 literal http://127.0.0.1:<port>, so binding to ::1 left nothing
        // listening on IPv4 and the GeckoView content process's page load was
        // refused with NS_ERROR_CONNECTION_REFUSED (0x804B000D). Bind and connect
        // must be the same family.
        mServerSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        mHandlerPool = Executors.newFixedThreadPool(2);
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "P2pLoopback");
        mAcceptThread.start();
    }

    public void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException e) {
                // Closing is best-effort; the accept loop exits on the error.
            }
            mServerSocket = null;
        }
        if (mHandlerPool != null) {
            mHandlerPool.shutdownNow();
            mHandlerPool = null;
        }
        closeWriteTarget();
    }

    public void setReadFile(String absolutePath) {
        mReadPath = absolutePath;
    }

    /**
     * Arm the write side. The target is a .part file owned by the app (the
     * controller creates it); bytes are appended strictly in order.
     *
     * @param keepBytes bytes of an existing partial to keep for a RESUMED
     *                  transfer — the next write must land at exactly this
     *                  offset (or at 0, the sender-refused-resume restart).
     *                  0 = fresh transfer, any existing content is dropped.
     */
    public void setWriteTarget(File partFile, long keepBytes) throws IOException {
        synchronized (mWriteLock) {
            RandomAccessFile file = new RandomAccessFile(partFile, "rw");
            try {
                file.setLength(keepBytes);
                file.seek(keepBytes);
            } catch (IOException e) {
                closeQuietly(file);
                throw e;
            }
            mWritten = keepBytes;
            closeQuietly(mWriteFile.getAndSet(file));
        }
    }

    /**
     * Close the write file so the controller can verify + rename it. Safe to
     * call twice (stop() calls it as a backstop).
     */
    public void closeWriteTarget() {
        closeQuietly(mWriteFile.getAndSet(null));
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException e) {
            Log.e(TAG, "closeWriteTarget", e);
        }
    }

    public String getReadUrl() {
        return baseUrl() + "/read?t=" + mToken;
    }

    /**
     * The engine appends {@code &off=<offset>} per POST.
     */
    public String getWriteUrl() {
        return baseUrl() + "/write?t=" + mToken;
    }

    /** The URL the hidden GeckoSession loads to host the WebRTC engine. */
    public String getEnginePageUrl() {
        return baseUrl() + "/engine";
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + mServerSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (mRunning) {
            // stop() (main thread) can null the socket/pool the instant after
            // the mRunning check; snapshot into locals and catch EVERYTHING so
            // a teardown race (NPE on a nulled socket, RejectedExecution on a
            // shut-down pool) can never kill this bare thread → crash the app.
            Socket client;
            try {
                ServerSocket socket = mServerSocket;
                if (socket == null) {
                    return;
                }
                client = socket.accept();
            } catch (Exception e) {
                // Socket closed by stop(), or a transient/teardown error.
                if (mRunning && BuildConfig.DEBUG) {
                    Log.d(TAG, "accept ended: " + e.getMessage());
                }
                return;
            }
            ExecutorService pool = mHandlerPool;
            if (pool == null) {
                closeQuietly(client);
                return;
            }
            try {
                pool.execute(() -> handleConnection(client));
            } catch (RuntimeException e) {
                // Pool shut down between the null check and execute().
                closeQuietly(client);
                return;
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            P2pHttp.RequestHead head = P2pHttp.readHead(in);
            if (head == null) {
                P2pHttp.sendStatus(out, 400, "Bad Request");
                return;
            }

            // The engine page + its script are served UNGATED (they're just the
            // bundled engine code, not sensitive) so the hidden GeckoSession can
            // load them; the file endpoints stay token-gated.
            if ("GET".equals(head.method) && "/engine".equals(head.path)) {
                sendAsset(out, "engine.html", "text/html; charset=utf-8");
            } else if ("GET".equals(head.method) && "/engine-page.js".equals(head.path)) {
                sendAsset(out, "engine-page.js", "application/javascript; charset=utf-8");
            } else if (!mToken.equals(head.query.get("t"))) {
                P2pHttp.sendStatus(out, 403, "Forbidden");
            } else if ("GET".equals(head.method) && "/read".equals(head.path)) {
                handleRead(head, out);
            } else if ("POST".equals(head.method) && "/write".equals(head.path)) {
                handleWrite(head, in, out);
            } else {
                P2pHttp.sendStatus(out, 404, "Not Found");
            }
            out.flush();
        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "connection: " + e.getMessage());
            }
        } finally {
            closeQuietly(client);
        }
    }

    private void sendAsset(OutputStream out, String assetName, String contentType) throws IOException {
        byte[] body;
        try (InputStream asset = mContext.getAssets().open("p2pshare/" + assetName)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[STREAM_BUFFER];
            int read;
            while ((read = asset.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            body = buffer.toByteArray();
        }
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private void handleRead(P2pHttp.RequestHead head, OutputStream out) throws IOException {
        String path = mReadPath;
        if (path == null) {
            P2pHttp.sendStatus(out, 404, "Not Found");
            return;
        }
        long from;
        long lenCap;
        try {
            from = Long.parseLong(head.query.getOrDefault("from", "0"));
            lenCap = Long.parseLong(head.query.getOrDefault("len", "-1"));
        } catch (NumberFormatException e) {
            P2pHttp.sendStatus(out, 400, "Bad Request");
            return;
        }
        if (from < 0) {
            P2pHttp.sendStatus(out, 400, "Bad Request");
            return;
        }
        // RestoredFileAccess: a restored (foreign-owned) download opens via
        // the persisted SAF grant where a plain FileInputStream would EACCES.
        ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(mContext, path);
        if (pfd == null) {
            P2pHttp.sendStatus(out, 404, "Not Found");
            return;
        }
        try (ParcelFileDescriptor held = pfd;
             FileInputStream stream = new FileInputStream(held.getFileDescriptor())) {
            long total = held.getStatSize();
            if (from > total) {
                P2pHttp.sendStatus(out, 400, "Bad Request");
                return;
            }
            long length = total - from;
            if (lenCap >= 0 && lenCap < length) {
                length = lenCap;
            }
            if (!skipFully(stream, from)) {
                P2pHttp.sendStatus(out, 500, "Seek Failed");
                return;
            }
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            // Bounded copy: with a len cap the stream has more bytes than the
            // declared body — never write past Content-Length.
            byte[] buffer = new byte[STREAM_BUFFER];
            long remaining = length;
            while (mRunning && remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = stream.read(buffer, 0, want);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /**
     * InputStream.skip may skip fewer bytes than asked even mid-file; loop to
     * the exact offset (the ranged read's byte positions must be precise —
     * a short skip would silently serve the wrong bytes).
     */
    private static boolean skipFully(InputStream stream, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                return false;
            }
            remaining -= skipped;
        }
        return true;
    }

    private void handleWrite(P2pHttp.RequestHead head, InputStream in, OutputStream out) throws IOException {
        long offset;
        long declared;
        try {
            offset = Long.parseLong(head.query.getOrDefault("off", "-1"));
            declared = Long.parseLong(head.headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            P2pHttp.sendStatus(out, 400, "Bad Request");
            return;
        }
        if (offset < 0 || declared < 0 || declared > MAX_WRITE_BODY) {
            P2pHttp.sendStatus(out, 400, "Bad Request");
            return;
        }
        synchronized (mWriteLock) {
            RandomAccessFile file = mWriteFile.get();
            if (file == null) {
                P2pHttp.sendStatus(out, 409, "Conflict");
                return;
            }
            // The offset check makes writes idempotent-safe: a duplicated or
            // out-of-order request cannot silently corrupt the file.
            if (offset != mWritten) {
                if (offset == 0 && mWritten > 0) {
                    // The one sanctioned mismatch: the write side was armed to
                    // resume a partial, but the sender refused (tail hash
                    // mismatch = different file behind the same name) and is
                    // streaming from byte 0. Drop the kept bytes and restart.
                    // Safe against the duplicate-request worry the strict rule
                    // guards: the engine's POSTs are chained on one promise and
                    // never retried, so a mid-transfer off=0 cannot recur.
                    file.setLength(0);
                    file.seek(0);
                    mWritten = 0;
                } else {
                    P2pHttp.sendStatus(out, 409, "Conflict");
                    return;
                }
            }
            byte[] buffer = new byte[STREAM_BUFFER];
            long remaining = declared;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) {
                    throw new IOException("body truncated");
                }
                file.write(buffer, 0, read);
                remaining -= read;
            }
            mWritten += declared;
        }
        P2pHttp.sendStatus(out, 204, "No Content");
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Best-effort.
        }
    }
}
