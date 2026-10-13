package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

/**
 * The minimal HTTP/1.1 request-head parser + status writer shared by the two
 * tiny servers of the P2P share: {@link P2pLoopbackServer} (127.0.0.1, the
 * engine page + the file byte endpoints) and {@link P2pLanServer} (the LAN
 * signaling POSTs). Request line + headers, query split; no chunked bodies,
 * no keep-alive — the only clients are our own extension fetch() calls and
 * a peer phone's one POST. Pure Java on purpose, so
 * {@code scripts/p2p-harness} can drive the real thing on a plain JVM.
 */
final class P2pHttp {

    private P2pHttp() {
    }

    /** Cap the request head so a malformed client can't grow it unbounded. */
    static final int MAX_HEAD_BYTES = 16 * 1024;

    static final class RequestHead {
        String method;
        String path;
        final HashMap<String, String> query = new HashMap<>();
        final HashMap<String, String> headers = new HashMap<>();
    }

    /**
     * <b>Reads raw bytes up to and INCLUDING the terminating {@code \r\n\r\n},
     * and no further</b>, so {@code in} is left positioned EXACTLY at the first
     * body byte. This is load-bearing: a {@code BufferedReader}/{@code readLine}
     * approach leaves the blank line's final {@code \n} unconsumed (readLine
     * consumes the {@code \r}, sets an internal {@code skipLF}, and returns
     * before eating the {@code \n}), so a subsequent raw body read starts one
     * byte early — it takes the stray {@code \n} as {@code body[0]} and DROPS
     * the last real byte. For a deflate-compressed answer/chunk that one lost
     * byte corrupts the whole stream ("operation aborted" on decode). Parsing
     * the head as bytes and stopping at the exact terminator avoids it.
     * Returns null on an empty, oversized or malformed head.
     */
    @Nullable
    static RequestHead readHead(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // Match the CRLFCRLF terminator; `match` is the index into it.
        final byte[] term = {'\r', '\n', '\r', '\n'};
        int match = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            if (b == term[match]) {
                match++;
                if (match == term.length) {
                    break;
                }
            } else {
                // A stray '\r' can still begin a fresh terminator.
                match = (b == '\r') ? 1 : 0;
            }
            if (buffer.size() > MAX_HEAD_BYTES) {
                return null;
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        String[] lines = new String(buffer.toByteArray(), StandardCharsets.US_ASCII)
                .split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return null;
        }
        RequestHead head = new RequestHead();
        head.method = parts[0];
        String target = parts[1];
        int queryStart = target.indexOf('?');
        if (queryStart >= 0) {
            head.path = target.substring(0, queryStart);
            for (String pair : target.substring(queryStart + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    head.query.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        } else {
            head.path = target;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                head.headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return head;
    }

    /** A bodiless status line with {@code Connection: close}. */
    static void sendStatus(@NonNull OutputStream out, int code, @NonNull String reason)
            throws IOException {
        String response = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Read exactly {@code declared} body bytes (the request head's
     * Content-Length) — null when the caller's cap is exceeded or the length
     * is missing; throws when the connection ends short.
     */
    @Nullable
    static byte[] readBody(@NonNull InputStream in, long declared, int maxBytes)
            throws IOException {
        if (declared <= 0 || declared > maxBytes) {
            return null;
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
        return body;
    }

    /** The declared Content-Length, or -1 when absent/malformed. */
    static long contentLength(@NonNull RequestHead head) {
        try {
            return Long.parseLong(head.headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
