package com.solarized.firedown.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A WebDAV server small enough to read, with a switch for every failure the
 * client has to classify: wrong password, read-only, full, a dropped upload,
 * a server that ignores Range or answers the wrong range, a 500, a collection
 * that vanished, a site root that is not WebDAV, a redirect. Answers the way
 * Nextcloud does where servers differ (a 404 propstat for the props a
 * collection lacks, quoted ETags, RFC 1123 dates).
 */
final class FakeDav implements HttpHandler {

    static final class Node {
        final boolean dir;
        byte[] data;
        long modified;
        int version;

        Node(boolean dir, byte[] data, long modified) {
            this.dir = dir;
            this.data = data;
            this.modified = modified;
        }
    }

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    final Map<String, Node> nodes = Collections.synchronizedMap(new TreeMap<>());
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final List<String> expectHeaders = Collections.synchronizedList(new ArrayList<>());
    final Set<String> propfindRefused = ConcurrentHashMap.newKeySet();
    final Map<String, String> redirects = new ConcurrentHashMap<>();

    volatile String user = "alex";
    volatile String password = "secret";
    volatile boolean readOnly;
    volatile boolean full;
    volatile boolean ignoreRange;
    volatile boolean wrongContentRange;
    volatile boolean absoluteHrefs;
    volatile int forcePropfindStatus;
    /** Cut a PUT whose path ends with this suffix after this many bytes. */
    volatile long dropPutAfter = -1;
    volatile String dropPutSuffix = ".fdvc";
    volatile int port;
    volatile String scheme = "http";

    FakeDav() {
        nodes.put("/", new Node(true, null, now()));
    }

    static long now() {
        return System.currentTimeMillis() / 1000 * 1000;
    }

    /** Makes every collection along {@code path}. */
    void mkdirs(String path) {
        String p = norm(path);
        List<String> chain = new ArrayList<>();
        while (!p.equals("/")) {
            chain.add(0, p);
            p = parent(p);
        }
        for (String c : chain) {
            nodes.putIfAbsent(c, new Node(true, null, now()));
        }
    }

    void putFile(String path, byte[] data, long modified) {
        Node n = new Node(false, data, modified);
        nodes.put(norm(path), n);
    }

    Node get(String path) {
        return nodes.get(norm(path));
    }

    int count(String method, String suffix) {
        int n = 0;
        synchronized (log) {
            for (String line : log) {
                String[] parts = line.split("\t", 3);
                if (parts[0].equals(method) && parts[1].endsWith(suffix)) {
                    n++;
                }
            }
        }
        return n;
    }

    List<String> childrenOf(String path) {
        String p = norm(path);
        List<String> out = new ArrayList<>();
        synchronized (nodes) {
            for (String k : nodes.keySet()) {
                if (!k.equals("/") && parent(k).equals(p)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    static String norm(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            serve(ex);
        } finally {
            ex.close();
        }
    }

    private void serve(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();
        String path = norm(ex.getRequestURI().getPath());
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        // Tab-separated: the paths hold a space ("Firedown Backup").
        log.add(method + "\t" + path + (auth != null ? "\tauth" : "\tnoauth"));
        expectHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Expect")));

        String target = redirects.get(rawPath);
        if (target != null) {
            drain(ex);
            ex.getResponseHeaders().add("Location", target);
            ex.sendResponseHeaders(301, -1);
            return;
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(auth)) {
            drain(ex);
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fake\"");
            ex.sendResponseHeaders(401, -1);
            return;
        }
        switch (method) {
            case "PROPFIND":
                propfind(ex, path);
                break;
            case "MKCOL":
                mkcol(ex, path);
                break;
            case "PUT":
                put(ex, path);
                break;
            case "GET":
            case "HEAD":
                get(ex, path, method.equals("HEAD"));
                break;
            case "DELETE":
                delete(ex, path);
                break;
            default:
                drain(ex);
                ex.sendResponseHeaders(405, -1);
        }
    }

    private void propfind(HttpExchange ex, String path) throws IOException {
        drain(ex);
        if (forcePropfindStatus != 0) {
            ex.sendResponseHeaders(forcePropfindStatus, -1);
            return;
        }
        if (propfindRefused.contains(path)) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        Node n = nodes.get(path);
        if (n == null) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        String depth = ex.getRequestHeaders().getFirst("Depth");
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\"?>\n<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">");
        response(xml, path, n);
        if ("1".equals(depth) && n.dir) {
            for (String child : childrenOf(path)) {
                response(xml, child, nodes.get(child));
            }
        }
        xml.append("</d:multistatus>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
        ex.sendResponseHeaders(207, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void response(StringBuilder xml, String path, Node n) {
        if (n == null) {
            return;
        }
        String href = encode(path) + (n.dir && !path.equals("/") ? "/" : "");
        if (absoluteHrefs) {
            href = scheme + "://127.0.0.1:" + port + href;
        }
        xml.append("<d:response><d:href>").append(href).append("</d:href>");
        xml.append("<d:propstat><d:prop>");
        if (n.dir) {
            xml.append("<d:resourcetype><d:collection/></d:resourcetype>");
        } else {
            xml.append("<d:resourcetype/>");
            xml.append("<d:getcontentlength>").append(n.data.length).append("</d:getcontentlength>");
            xml.append("<d:getetag>&quot;").append(n.version).append('-').append(n.data.length)
                    .append("&quot;</d:getetag>");
        }
        xml.append("<d:getlastmodified>").append(HTTP_DATE.format(Instant.ofEpochMilli(n.modified)))
                .append("</d:getlastmodified>");
        xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>");
        // What Nextcloud does for the props a collection lacks: a 404
        // propstat listing them EMPTY, which must not blank anything.
        xml.append("<d:propstat><d:prop>");
        if (n.dir) {
            xml.append("<d:getcontentlength/><d:getetag/>");
        }
        xml.append("<oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>");
        xml.append("</d:response>");
    }

    private static String encode(String path) {
        try {
            return new URI(null, null, path, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private void mkcol(HttpExchange ex, String path) throws IOException {
        drain(ex);
        if (readOnly) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        if (nodes.containsKey(path)) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        Node parent = nodes.get(parent(path));
        if (parent == null || !parent.dir) {
            ex.sendResponseHeaders(409, -1);
            return;
        }
        nodes.put(path, new Node(true, null, now()));
        ex.sendResponseHeaders(201, -1);
    }

    private void put(HttpExchange ex, String path) throws IOException {
        long declared = -1;
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            declared = Long.parseLong(cl);
        }
        if (dropPutAfter >= 0 && path.endsWith(dropPutSuffix)) {
            readUpTo(ex.getRequestBody(), dropPutAfter);
            // Hang up with no answer: what a proxy timeout or a lost link
            // looks like from the phone.
            throw new IOException("fake: dropped the upload");
        }
        if (readOnly) {
            drain(ex);
            ex.sendResponseHeaders(403, -1);
            return;
        }
        if (full) {
            drain(ex);
            ex.sendResponseHeaders(507, -1);
            return;
        }
        Node parent = nodes.get(parent(path));
        if (parent == null || !parent.dir) {
            drain(ex);
            ex.sendResponseHeaders(409, -1);
            return;
        }
        byte[] body = readUpTo(ex.getRequestBody(), Long.MAX_VALUE);
        if (declared >= 0 && body.length != declared) {
            // The client went away mid-body: store nothing, like a server
            // writing to a temporary name would.
            return;
        }
        Node old = nodes.get(path);
        Node n = new Node(false, body, now());
        n.version = old == null ? 0 : old.version + 1;
        nodes.put(path, n);
        ex.sendResponseHeaders(old == null ? 201 : 204, -1);
    }

    private void get(HttpExchange ex, String path, boolean head) throws IOException {
        drain(ex);
        Node n = nodes.get(path);
        if (n == null) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        if (n.dir) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        byte[] data = n.data;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (head) {
            ex.getResponseHeaders().add("Content-Length", Integer.toString(data.length));
            ex.sendResponseHeaders(200, -1);
            return;
        }
        if (range == null || ignoreRange) {
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
            return;
        }
        String spec = range.substring("bytes=".length());
        int dash = spec.indexOf('-');
        long start = Long.parseLong(spec.substring(0, dash));
        long end = dash + 1 < spec.length() ? Long.parseLong(spec.substring(dash + 1)) : data.length - 1;
        if (start >= data.length) {
            ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
            ex.sendResponseHeaders(416, -1);
            return;
        }
        end = Math.min(end, data.length - 1);
        long shownStart = wrongContentRange ? start + 1 : start;
        ex.getResponseHeaders().add("Content-Range",
                "bytes " + shownStart + "-" + end + "/" + data.length);
        int len = (int) (end - start + 1);
        ex.sendResponseHeaders(206, len);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data, (int) start, len);
        }
    }

    private void delete(HttpExchange ex, String path) throws IOException {
        drain(ex);
        if (readOnly) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        if (!nodes.containsKey(path)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        synchronized (nodes) {
            nodes.keySet().removeIf(k -> k.equals(path) || k.startsWith(path + "/"));
        }
        ex.sendResponseHeaders(204, -1);
    }

    private static void drain(HttpExchange ex) throws IOException {
        readUpTo(ex.getRequestBody(), Long.MAX_VALUE);
    }

    private static byte[] readUpTo(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (total < max) {
            int n;
            try {
                n = in.read(buf, 0, (int) Math.min(buf.length, max - total));
            } catch (IOException e) {
                break; // the client hung up: whatever arrived is all there is
            }
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }
}
