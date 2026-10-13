import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * A small WebDAV server over a directory, to test Firedown's WebDAV backup
 * against without a Nextcloud: run it on a laptop, point the phone at it.
 * JDK only (11 or later, the one Android Studio ships works), one file, no
 * build:
 *
 * <pre>
 *   java scripts/webdav-server/DavServer.java --root ~/dav-test
 *   java scripts/webdav-server/DavServer.java --root ~/dav-test --tls
 *   java scripts/webdav-server/DavServer.java --root ~/dav-test --layout nextcloud
 * </pre>
 *
 * Options: {@code --port N} (8080; 0 = any free port), {@code --host ADDR}
 * (0.0.0.0, so a phone on the same Wi-Fi reaches it), {@code --user U
 * --password P} (alex / secret), {@code --no-auth}, {@code --layout
 * plain|nextcloud}, {@code --tls} with {@code --keystore FILE} (made with a
 * self-signed certificate on first use; its SHA-256 fingerprint is printed,
 * to compare with the one the phone shows), {@code --read-only},
 * {@code --quota BYTES}.
 *
 * <p>What it serves is WebDAV class 1: OPTIONS, PROPFIND (Depth 0 and 1;
 * named props it does not have come back in a 404 propstat, as Nextcloud
 * does), GET and HEAD with single byte ranges, PUT (written to a hidden
 * temporary file and renamed into place, so an interrupted upload never
 * leaves half a file), MKCOL, DELETE, COPY and MOVE. No locking and no
 * PROPPATCH. HTTP Basic sign-in over the whole share. In the {@code
 * nextcloud} layout the directory is served where Nextcloud serves a user's
 * files ({@code /remote.php/dav/files/<user>/} and {@code
 * /remote.php/webdav/}) and the site root is not WebDAV, so typing only the
 * host on the phone exercises the address discovery.
 *
 * <p>It is a test server: one fixed keystore password, no rate limits, the
 * sign-in compared in constant time and nothing more. Keep it on a home
 * network. Every request is logged on one line.
 */
public final class DavServer {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final String TMP_PREFIX = ".davput-";
    private static final String KEYSTORE_PASSWORD = "changeit";
    private static final long MAX_XML_BYTES = 1024 * 1024;

    // ---- options ----

    static final class Options {
        Path root;
        String host = "0.0.0.0";
        int port = 8080;
        String user = "alex";
        String password = "secret";
        boolean auth = true;
        boolean nextcloud;
        boolean tls;
        Path keystore;
        boolean readOnly;
        long quota = -1;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--root":
                        o.root = Paths.get(expand(args[++i]));
                        break;
                    case "--host":
                        o.host = args[++i];
                        break;
                    case "--port":
                        o.port = Integer.parseInt(args[++i]);
                        break;
                    case "--user":
                        o.user = args[++i];
                        break;
                    case "--password":
                        o.password = args[++i];
                        break;
                    case "--no-auth":
                        o.auth = false;
                        break;
                    case "--layout":
                        String layout = args[++i];
                        if (!layout.equals("plain") && !layout.equals("nextcloud")) {
                            throw new IllegalArgumentException("--layout plain|nextcloud");
                        }
                        o.nextcloud = layout.equals("nextcloud");
                        break;
                    case "--tls":
                        o.tls = true;
                        break;
                    case "--keystore":
                        o.keystore = Paths.get(expand(args[++i]));
                        break;
                    case "--read-only":
                        o.readOnly = true;
                        break;
                    case "--quota":
                        o.quota = Long.parseLong(args[++i]);
                        break;
                    case "--help":
                    case "-h":
                        System.out.println("usage: java DavServer.java --root DIR [--port 8080] "
                                + "[--host 0.0.0.0] [--user alex --password secret | --no-auth] "
                                + "[--layout plain|nextcloud] [--tls [--keystore FILE]] "
                                + "[--read-only] [--quota BYTES]");
                        System.exit(0);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option " + a);
                }
            }
            if (o.root == null) {
                throw new IllegalArgumentException("--root DIR is required");
            }
            if (o.keystore == null) {
                o.keystore = Paths.get("davserver-tls.p12");
            }
            return o;
        }

        private static String expand(String p) {
            return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
        }
    }

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = Options.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        Files.createDirectories(o.root);
        Path root = o.root.toRealPath();
        List<String> lan = lanAddresses();
        HttpServer server;
        String fingerprint = null;
        if (o.tls) {
            KeyStore ks = loadOrMakeKeystore(o.keystore, lan);
            X509Certificate cert = (X509Certificate) ks.getCertificate("davserver");
            fingerprint = toHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(o.host, o.port), 64);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx));
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress(o.host, o.port), 64);
        }
        server.createContext("/", new Dav(o, root));
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dav");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        int port = server.getAddress().getPort();

        String scheme = o.tls ? "https" : "http";
        System.out.println("Firedown test WebDAV server");
        System.out.println("  root:      " + root);
        List<String> hosts = new ArrayList<>();
        if (o.host.equals("0.0.0.0")) {
            hosts.addAll(lan);
            hosts.add("127.0.0.1");
        } else {
            hosts.add(o.host);
        }
        for (String h : hosts) {
            String base = scheme + "://" + h + ":" + port;
            if (o.nextcloud) {
                System.out.println("  address:   " + base + "   (type just this: the phone finds "
                        + "/remote.php/dav/files/" + o.user + "/)");
            } else {
                System.out.println("  address:   " + base + "/");
            }
        }
        System.out.println("  sign-in:   " + (o.auth ? o.user + " / " + o.password : "none"));
        if (!"UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"))) {
            System.out.println("  note:      file names are limited to ASCII under this locale "
                    + "(LC_ALL=C.UTF-8 lifts it); Firedown's own names are ASCII");
        }
        if (o.readOnly) {
            System.out.println("  read-only: every write is refused (403)");
        }
        if (o.quota >= 0) {
            System.out.println("  quota:     " + o.quota + " bytes (507 past it)");
        }
        if (fingerprint != null) {
            System.out.println("  certificate SHA-256 (compare with the phone):");
            System.out.println("    " + display(fingerprint));
            System.out.println("FINGERPRINT " + fingerprint);
        }
        System.out.println("LISTENING " + port);
        System.out.flush();
        Thread.currentThread().join();
    }

    // ---- the handler ----

    private static final class Dav implements HttpHandler {
        private final Options o;
        private final Path root;
        private final List<String> mounts = new ArrayList<>();
        private final String expectedAuth;

        Dav(Options o, Path root) {
            this.o = o;
            this.root = root;
            if (o.nextcloud) {
                mounts.add("/remote.php/dav/files/" + encodeSegment(o.user) + "/");
                mounts.add("/remote.php/webdav/");
            } else {
                mounts.add("/");
            }
            this.expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                    (o.user + ":" + o.password).getBytes(StandardCharsets.UTF_8));
        }

        /** A request path resolved: which mount, the decoded segments under
         *  it, and the file. Null target = outside every mount. */
        private final class Target {
            final String mount;
            final List<String> segments;
            final Path file;

            Target(String mount, List<String> segments) {
                this.mount = mount;
                this.segments = segments;
                Path p = root;
                for (String s : segments) {
                    p = p.resolve(s);
                }
                this.file = p;
            }

            boolean isMountRoot() {
                return segments.isEmpty();
            }

            Target child(String name) {
                List<String> s = new ArrayList<>(segments);
                s.add(name);
                return new Target(mount, s);
            }

            String href(boolean dir) {
                StringBuilder sb = new StringBuilder(mount);
                for (int i = 0; i < segments.size(); i++) {
                    if (i > 0) {
                        sb.append('/');
                    }
                    sb.append(encodeSegment(segments.get(i)));
                }
                if (dir && !segments.isEmpty()) {
                    sb.append('/');
                }
                return sb.toString();
            }
        }

        /** Thrown for a path that cannot be served (traversal, an encoded
         *  slash, a symlink out of the root). */
        private final class BadPath extends Exception {
            BadPath(String m) {
                super(m);
            }
        }

        private Target resolve(String rawPath) throws BadPath {
            String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
            for (String mount : mounts) {
                String bare = mount.substring(0, mount.length() - 1);
                String rest;
                if (path.startsWith(mount)) {
                    rest = path.substring(mount.length());
                } else if (path.equals(bare) && !bare.isEmpty()) {
                    rest = "";
                } else {
                    continue;
                }
                List<String> segments = new ArrayList<>();
                for (String raw : rest.split("/")) {
                    if (raw.isEmpty()) {
                        continue;
                    }
                    String s = percentDecode(raw);
                    if (s == null || s.equals(".") || s.equals("..") || s.indexOf('/') >= 0
                            || s.indexOf('\\') >= 0 || s.indexOf('\0') >= 0) {
                        throw new BadPath("bad segment " + raw);
                    }
                    segments.add(s);
                }
                Target t;
                try {
                    t = new Target(mount, segments);
                } catch (InvalidPathException e) {
                    throw new BadPath("name not representable here (start the JVM with a "
                            + "UTF-8 locale, e.g. LC_ALL=C.UTF-8)");
                }
                if (!t.file.normalize().startsWith(root)) {
                    throw new BadPath("outside the root");
                }
                // No following a symlink out of the share.
                Path probe = t.file;
                while (probe != null && !probe.equals(root)) {
                    if (Files.isSymbolicLink(probe)) {
                        throw new BadPath("symlink");
                    }
                    probe = probe.getParent();
                }
                return t;
            }
            return null;
        }

        @Override
        public void handle(HttpExchange ex) {
            long start = System.nanoTime();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            String rawPath = ex.getRequestURI().getRawPath();
            Reply reply = new Reply(ex);
            try {
                serve(ex, method, rawPath, reply);
            } catch (ClientGone e) {
                reply.note = "client went away";
            } catch (Exception e) {
                reply.note = e.toString();
                if (reply.status == 0) {
                    try {
                        reply.empty(500);
                    } catch (IOException ignored) {
                        // the connection is gone too
                    }
                }
            } finally {
                ex.close();
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                System.out.println(String.format(Locale.ROOT, "%s %s %s %s %dms%s",
                        HTTP_DATE.format(Instant.now()).substring(17, 25), method,
                        rawPath, reply.status == 0 ? "-" : Integer.toString(reply.status), ms,
                        reply.note == null ? "" : "  (" + reply.note + ")"));
                System.out.flush();
            }
        }

        private void serve(HttpExchange ex, String method, String rawPath, Reply reply)
                throws Exception {
            if (ex.getRequestURI().getRawFragment() != null) {
                // A fragment never belongs on the wire; acting on the path
                // without it could delete the collection it was cut from.
                drain(ex);
                reply.note = "fragment in the request";
                reply.empty(400);
                return;
            }
            Target t;
            try {
                t = resolve(rawPath);
            } catch (BadPath e) {
                drain(ex);
                reply.note = e.getMessage();
                reply.empty(400);
                return;
            }
            if (t == null) {
                // Outside the share: what a site root looks like (Nextcloud
                // layout), no sign-in needed to be told so.
                drain(ex);
                if (method.equals("GET") || method.equals("HEAD")) {
                    reply.text(200, "text/html; charset=utf-8",
                            "<!doctype html><title>WebDAV test server</title>"
                                    + "<p>WebDAV is served at " + escapeXml(String.join(", ", mounts)),
                            method.equals("HEAD"));
                } else {
                    reply.empty(method.equals("PROPFIND") ? 405 : 404);
                }
                return;
            }
            if (o.auth && !authorized(ex)) {
                drain(ex);
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Firedown test\", charset=\"UTF-8\"");
                reply.empty(401);
                return;
            }
            switch (method) {
                case "OPTIONS":
                    drain(ex);
                    options(ex, reply);
                    break;
                case "PROPFIND":
                    propfind(ex, t, reply);
                    break;
                case "GET":
                    get(ex, t, reply, false);
                    break;
                case "HEAD":
                    get(ex, t, reply, true);
                    break;
                case "PUT":
                    put(ex, t, reply);
                    break;
                case "MKCOL":
                    mkcol(ex, t, reply);
                    break;
                case "DELETE":
                    drain(ex);
                    delete(t, reply);
                    break;
                case "COPY":
                case "MOVE":
                    drain(ex);
                    copyOrMove(ex, t, reply, method.equals("MOVE"));
                    break;
                default:
                    drain(ex);
                    ex.getResponseHeaders().add("Allow", ALLOW);
                    reply.empty(405);
            }
        }

        private static final String ALLOW =
                "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, COPY, MOVE";

        private boolean authorized(HttpExchange ex) {
            String got = ex.getRequestHeaders().getFirst("Authorization");
            if (got == null) {
                return false;
            }
            return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                    expectedAuth.getBytes(StandardCharsets.UTF_8));
        }

        private void options(HttpExchange ex, Reply reply) throws IOException {
            Headers h = ex.getResponseHeaders();
            h.add("DAV", "1");
            h.add("Allow", ALLOW);
            h.add("MS-Author-Via", "DAV");
            reply.empty(200);
        }

        // ---- PROPFIND ----

        /** What allprop and propname answer with, as {@code ns|local}. */
        private static final String[] LIVE_PROPS = {
                "DAV:|displayname", "DAV:|resourcetype", "DAV:|getlastmodified",
                "DAV:|creationdate", "DAV:|getcontentlength", "DAV:|getcontenttype",
                "DAV:|getetag",
        };

        private void propfind(HttpExchange ex, Target t, Reply reply) throws Exception {
            byte[] body = readBody(ex, MAX_XML_BYTES);
            PropRequest request;
            try {
                request = PropRequest.parse(body);
            } catch (IOException e) {
                reply.note = "bad PROPFIND body: " + e.getMessage();
                reply.empty(400);
                return;
            }
            String depth = ex.getRequestHeaders().getFirst("Depth");
            if (depth == null || depth.equalsIgnoreCase("infinity")) {
                reply.xml(403, "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<d:error xmlns:d=\"DAV:\"><d:propfind-finite-depth/></d:error>");
                return;
            }
            if (!depth.equals("0") && !depth.equals("1")) {
                reply.empty(400);
                return;
            }
            if (!Files.exists(t.file)) {
                reply.empty(404);
                return;
            }
            StringBuilder xml = new StringBuilder(4096);
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<d:multistatus xmlns:d=\"DAV:\">\n");
            response(xml, t, request);
            if (depth.equals("1") && Files.isDirectory(t.file)) {
                for (String name : children(t.file)) {
                    response(xml, t.child(name), request);
                }
            }
            xml.append("</d:multistatus>\n");
            reply.xml(207, xml.toString());
        }

        private void response(StringBuilder xml, Target t, PropRequest request) throws IOException {
            boolean dir = Files.isDirectory(t.file);
            xml.append("<d:response><d:href>").append(escapeXml(t.href(dir))).append("</d:href>");
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> names = request.mode == PropRequest.Mode.PROP
                    ? request.props : Arrays.asList(LIVE_PROPS);
            for (String qualified : names) {
                String value = request.mode == PropRequest.Mode.PROPNAME
                        ? "" : liveProp(qualified, t, dir);
                boolean ours = qualified.startsWith("DAV:|") && isLive(qualified.substring(5), dir);
                if (!ours) {
                    if (request.mode == PropRequest.Mode.PROP) {
                        missing.add(qualified);
                    }
                    continue;
                }
                found.add(request.mode == PropRequest.Mode.PROPNAME
                        ? element(qualified, "") : element(qualified, value));
            }
            if (!found.isEmpty() || missing.isEmpty()) {
                xml.append("<d:propstat><d:prop>");
                for (String f : found) {
                    xml.append(f);
                }
                xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>");
            }
            if (!missing.isEmpty()) {
                xml.append("<d:propstat><d:prop>");
                for (String m : missing) {
                    xml.append(element(m, ""));
                }
                xml.append("</d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>");
            }
            xml.append("</d:response>\n");
        }

        private static boolean isLive(String local, boolean dir) {
            switch (local) {
                case "displayname":
                case "resourcetype":
                case "getlastmodified":
                case "creationdate":
                    return true;
                case "getcontentlength":
                case "getcontenttype":
                case "getetag":
                    return !dir;
                default:
                    return false;
            }
        }

        /** The value XML of a live prop, or "" when it has none. */
        private String liveProp(String qualified, Target t, boolean dir) throws IOException {
            if (!qualified.startsWith("DAV:|")) {
                return "";
            }
            String local = qualified.substring(5);
            switch (local) {
                case "displayname":
                    return escapeXml(t.segments.isEmpty() ? "" : t.segments.get(t.segments.size() - 1));
                case "resourcetype":
                    return dir ? "<d:collection/>" : "";
                case "getlastmodified":
                    return HTTP_DATE.format(Files.getLastModifiedTime(t.file).toInstant());
                case "creationdate":
                    return DateTimeFormatter.ISO_INSTANT.format(
                            Files.getLastModifiedTime(t.file).toInstant().truncatedTo(ChronoUnit.SECONDS));
                case "getcontentlength":
                    return Long.toString(Files.size(t.file));
                case "getcontenttype":
                    return escapeXml(contentType(t.file));
                case "getetag":
                    return escapeXml(etag(t.file));
                default:
                    return "";
            }
        }

        /** {@code ns|local} → an element, prefixed d: for DAV:, else declared. */
        private static String element(String qualified, String value) {
            int bar = qualified.indexOf('|');
            String ns = qualified.substring(0, bar);
            String local = qualified.substring(bar + 1);
            if (ns.equals("DAV:")) {
                return value.isEmpty() ? "<d:" + local + "/>" : "<d:" + local + ">" + value + "</d:" + local + ">";
            }
            String open = "<x:" + local + " xmlns:x=\"" + escapeXml(ns) + "\"";
            return value.isEmpty() ? open + "/>" : open + ">" + value + "</x:" + local + ">";
        }

        private List<String> children(Path dir) throws IOException {
            List<String> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(TMP_PREFIX) || Files.isSymbolicLink(p)) {
                        continue;
                    }
                    out.add(name);
                }
            }
            Collections.sort(out);
            return out;
        }

        // ---- GET / HEAD ----

        private void get(HttpExchange ex, Target t, Reply reply, boolean head) throws IOException {
            drain(ex);
            if (!Files.exists(t.file)) {
                reply.empty(404);
                return;
            }
            if (Files.isDirectory(t.file)) {
                StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>")
                        .append(escapeXml(t.href(true))).append("</title><ul>");
                for (String name : children(t.file)) {
                    boolean d = Files.isDirectory(t.file.resolve(name));
                    String href = t.child(name).href(d);
                    html.append("<li><a href=\"").append(escapeXml(href)).append("\">")
                            .append(escapeXml(name)).append(d ? "/" : "").append("</a>");
                }
                html.append("</ul>");
                reply.text(200, "text/html; charset=utf-8", html.toString(), head);
                return;
            }
            long size = Files.size(t.file);
            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", contentType(t.file));
            h.add("ETag", etag(t.file));
            h.add("Last-Modified", HTTP_DATE.format(Files.getLastModifiedTime(t.file).toInstant()));
            h.add("Accept-Ranges", "bytes");
            long from = 0;
            long to = size - 1;
            int status = 200;
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
                String spec = range.substring(6).trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) {
                        long suffix = Long.parseLong(spec.substring(1));
                        from = Math.max(0, size - suffix);
                    } else if (dash > 0) {
                        from = Long.parseLong(spec.substring(0, dash));
                        if (dash + 1 < spec.length()) {
                            to = Math.min(size - 1, Long.parseLong(spec.substring(dash + 1)));
                        }
                    }
                    if (from >= size || from > to) {
                        h.add("Content-Range", "bytes */" + size);
                        reply.empty(416);
                        return;
                    }
                    status = 206;
                    h.add("Content-Range", "bytes " + from + "-" + to + "/" + size);
                } catch (NumberFormatException e) {
                    from = 0;
                    to = size - 1;
                }
            }
            long length = size == 0 ? 0 : to - from + 1;
            if (head) {
                h.add("Content-Length", Long.toString(length));
                reply.empty(status);
                return;
            }
            reply.status = status;
            ex.sendResponseHeaders(status, length == 0 ? -1 : length);
            if (length == 0) {
                return;
            }
            try (InputStream in = Files.newInputStream(t.file);
                 OutputStream out = ex.getResponseBody()) {
                skipFully(in, from);
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        break;
                    }
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new ClientGone();
                    }
                    left -= n;
                }
            }
        }

        // ---- PUT ----

        private void put(HttpExchange ex, Target t, Reply reply) throws Exception {
            if (o.readOnly) {
                drain(ex);
                reply.empty(403);
                return;
            }
            if (t.isMountRoot() || Files.isDirectory(t.file)) {
                drain(ex);
                reply.empty(405);
                return;
            }
            Path parent = t.file.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                drain(ex);
                reply.empty(409);
                return;
            }
            long declared = -1;
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            if (cl != null) {
                declared = Long.parseLong(cl.trim());
            }
            if (o.quota >= 0 && declared >= 0) {
                long existing = Files.exists(t.file) ? Files.size(t.file) : 0;
                if (used() - existing + declared > o.quota) {
                    // Answered at once, without reading the body, the way
                    // a real server checks a quota against Content-Length.
                    reply.empty(507);
                    return;
                }
            }
            boolean existed = Files.exists(t.file);
            Path tmp = parent.resolve(TMP_PREFIX + randomHex(8) + ".tmp");
            long got = 0;
            boolean placed = false;
            try {
                try (InputStream in = ex.getRequestBody();
                     OutputStream out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    while (true) {
                        int n;
                        try {
                            n = in.read(buf);
                        } catch (IOException e) {
                            throw new ClientGone();
                        }
                        if (n < 0) {
                            break;
                        }
                        out.write(buf, 0, n);
                        got += n;
                    }
                }
                if (declared >= 0 && got != declared) {
                    throw new ClientGone();
                }
                Files.move(tmp, t.file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                placed = true;
            } finally {
                if (!placed) {
                    Files.deleteIfExists(tmp);
                }
            }
            reply.note = got + " bytes";
            ex.getResponseHeaders().add("ETag", etag(t.file));
            reply.empty(existed ? 204 : 201);
        }

        private long used() throws IOException {
            try (Stream<Path> s = Files.walk(root)) {
                return s.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0;
                    }
                }).sum();
            }
        }

        // ---- MKCOL ----

        private void mkcol(HttpExchange ex, Target t, Reply reply) throws IOException {
            byte[] body = readBody(ex, MAX_XML_BYTES);
            if (body.length > 0) {
                reply.empty(415);
                return;
            }
            if (o.readOnly) {
                reply.empty(403);
                return;
            }
            if (Files.exists(t.file)) {
                reply.empty(405);
                return;
            }
            Path parent = t.file.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                reply.empty(409);
                return;
            }
            try {
                Files.createDirectory(t.file);
            } catch (FileAlreadyExistsException e) {
                reply.empty(405);
                return;
            }
            reply.empty(201);
        }

        // ---- DELETE ----

        private void delete(Target t, Reply reply) throws IOException {
            if (o.readOnly || t.isMountRoot()) {
                reply.empty(403);
                return;
            }
            if (!Files.exists(t.file)) {
                reply.empty(404);
                return;
            }
            deleteTree(t.file);
            reply.empty(204);
        }

        // ---- COPY / MOVE ----

        private void copyOrMove(HttpExchange ex, Target src, Reply reply, boolean move)
                throws IOException {
            String destination = ex.getRequestHeaders().getFirst("Destination");
            if (destination == null) {
                reply.empty(400);
                return;
            }
            Target dst;
            try {
                URI uri = URI.create(destination.trim());
                dst = resolve(uri.getRawPath());
            } catch (IllegalArgumentException | BadPath e) {
                reply.empty(400);
                return;
            }
            if (dst == null) {
                reply.empty(502);
                return;
            }
            if (o.readOnly || dst.isMountRoot() || (move && src.isMountRoot())) {
                reply.empty(403);
                return;
            }
            if (!Files.exists(src.file)) {
                reply.empty(404);
                return;
            }
            Path s = src.file.normalize();
            Path d = dst.file.normalize();
            if (s.equals(d) || d.startsWith(s)) {
                reply.empty(403);
                return;
            }
            Path parent = d.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                reply.empty(409);
                return;
            }
            boolean overwrite = !"F".equalsIgnoreCase(String.valueOf(
                    ex.getRequestHeaders().getFirst("Overwrite")).trim());
            boolean existed = Files.exists(d);
            if (existed && !overwrite) {
                reply.empty(412);
                return;
            }
            if (existed) {
                deleteTree(d);
            }
            if (move) {
                Files.move(s, d);
            } else {
                String depth = ex.getRequestHeaders().getFirst("Depth");
                boolean shallow = "0".equals(depth);
                copyTree(s, d, shallow);
            }
            reply.empty(existed ? 204 : 201);
        }

        private void copyTree(Path s, Path d, boolean shallow) throws IOException {
            if (Files.isDirectory(s)) {
                Files.createDirectory(d);
                if (shallow) {
                    return;
                }
                for (String name : children(s)) {
                    copyTree(s.resolve(name), d.resolve(name), false);
                }
            } else {
                Files.copy(s, d, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    // ---- request parsing ----

    /** What a PROPFIND body asks for. */
    static final class PropRequest {
        enum Mode { ALLPROP, PROPNAME, PROP }

        Mode mode = Mode.ALLPROP;
        final List<String> props = new ArrayList<>();

        static PropRequest parse(byte[] body) throws IOException {
            PropRequest r = new PropRequest();
            if (body.length == 0) {
                return r;
            }
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (head.contains("<!doctype")) {
                throw new IOException("DOCTYPE");
            }
            try {
                SAXParserFactory f = SAXParserFactory.newInstance();
                f.setNamespaceAware(true);
                SAXParser parser = f.newSAXParser();
                parser.getXMLReader().setEntityResolver((p, s) -> new InputSource(new StringReader("")));
                boolean[] sawPropfind = {false};
                parser.parse(new ByteArrayInputStream(body), new DefaultHandler() {
                    private int depth;
                    private boolean inProp;

                    @Override
                    public void startElement(String uri, String local, String q, Attributes a) {
                        depth++;
                        if (depth == 1) {
                            sawPropfind[0] = "DAV:".equals(uri) && "propfind".equals(local);
                            return;
                        }
                        if (depth == 2 && "DAV:".equals(uri)) {
                            if ("propname".equals(local)) {
                                r.mode = Mode.PROPNAME;
                            } else if ("prop".equals(local)) {
                                r.mode = Mode.PROP;
                                inProp = true;
                            }
                            return;
                        }
                        if (depth == 3 && inProp) {
                            r.props.add(uri + "|" + local);
                        }
                    }

                    @Override
                    public void endElement(String uri, String local, String q) {
                        if (depth == 2) {
                            inProp = false;
                        }
                        depth--;
                    }
                });
                if (!sawPropfind[0]) {
                    throw new IOException("not a DAV:propfind");
                }
            } catch (SAXException e) {
                throw new IOException(e.getMessage());
            } catch (ParserConfigurationException e) {
                throw new IOException(e);
            }
            return r;
        }
    }

    // ---- replies ----

    /** Sends a reply once and remembers its status for the log line. */
    private static final class Reply {
        private final HttpExchange ex;
        int status;
        String note;

        Reply(HttpExchange ex) {
            this.ex = ex;
        }

        void empty(int code) throws IOException {
            status = code;
            ex.sendResponseHeaders(code, -1);
        }

        void xml(int code, String body) throws IOException {
            text(code, "application/xml; charset=utf-8", body, false);
        }

        void text(int code, String type, String body, boolean head) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            status = code;
            ex.getResponseHeaders().add("Content-Type", type);
            if (head) {
                ex.getResponseHeaders().add("Content-Length", Integer.toString(bytes.length));
                ex.sendResponseHeaders(code, -1);
                return;
            }
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** The client hung up mid-request: nothing to answer. */
    private static final class ClientGone extends IOException {
    }

    // ---- helpers ----

    private static byte[] readBody(HttpExchange ex, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new IOException("request body too large");
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void drain(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[16 * 1024];
            while (in.read(buf) > 0) {
                // discard
            }
        } catch (IOException ignored) {
            // nothing to read from a client that left
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) {
                    return;
                }
                s = 1;
            }
            left -= s;
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p) && !Files.isSymbolicLink(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteTree(c);
                }
            }
        }
        try {
            Files.delete(p);
        } catch (NoSuchFileException ignored) {
            // gone already
        }
    }

    /** Size and modification time to the nanosecond: changes whenever the
     *  file does. */
    private static String etag(Path p) throws IOException {
        FileTime t = Files.getLastModifiedTime(p);
        return "\"" + Long.toHexString(Files.size(p)) + "-"
                + Long.toHexString(t.to(TimeUnit.NANOSECONDS)) + "\"";
    }

    private static String contentType(Path p) {
        try {
            String t = Files.probeContentType(p);
            return t != null ? t : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /** Percent-encodes everything but the unreserved characters. */
    static String encodeSegment(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (keep) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    /** %XX → bytes → UTF-8; null when malformed. ("+" stays "+": a path is
     *  not a form.) */
    static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    private static String display(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                sb.append(i % 32 == 0 ? "\n    " : ":");
            }
            sb.append(hex.substring(i, i + 2).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // ---- TLS ----

    private static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // no interfaces to list
        }
        return out;
    }

    /** The keystore at {@code path}, made with {@code keytool} on first use:
     *  an EC P-256 certificate for this machine's name and addresses. */
    private static KeyStore loadOrMakeKeystore(Path path, List<String> lan) throws Exception {
        if (!Files.exists(path)) {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                host = "localhost";
            }
            StringBuilder san = new StringBuilder("SAN=dns:localhost,ip:127.0.0.1");
            for (String ip : lan) {
                san.append(",ip:").append(ip);
            }
            String keytool = Paths.get(System.getProperty("java.home"), "bin",
                    File.separatorChar == '\\' ? "keytool.exe" : "keytool").toString();
            Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "davserver",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "825",
                    "-dname", "CN=" + host, "-ext", san.toString(),
                    "-keystore", path.toString(), "-storetype", "PKCS12",
                    "-storepass", KEYSTORE_PASSWORD, "-keypass", KEYSTORE_PASSWORD)
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) {
                throw new IOException("keytool failed: " + new String(out, StandardCharsets.UTF_8));
            }
            System.out.println("  made a self-signed certificate in " + path.toAbsolutePath());
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path.toFile())) {
            ks.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        return ks;
    }
}
