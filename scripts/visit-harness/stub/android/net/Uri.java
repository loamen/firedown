package android.net;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harness stub of the slice of android.net.Uri the visit code reads, with
 * Android's semantics for those calls: a lenient RFC 3986 split (appendix B),
 * the host without userinfo or port and NOT lower-cased, a decoded path,
 * query names in order of first appearance, and getQueryParameter returning
 * the first value decoded with '+' as a space. An opaque URI has no host.
 */
public final class Uri {
    private static final Pattern RFC3986 =
            Pattern.compile("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?");

    private final String authority;
    private final String path;
    private final String query;

    private Uri(String authority, String path, String query) {
        this.authority = authority;
        this.path = path;
        this.query = query;
    }

    public static Uri parse(String s) {
        Matcher m = RFC3986.matcher(s);
        if (!m.matches()) return new Uri(null, null, null);
        return new Uri(m.group(4), m.group(5), m.group(7));
    }

    public String getHost() {
        if (authority == null) return null;
        String h = authority;
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int colon = h.lastIndexOf(':');
        if (colon >= 0 && !h.endsWith("]")) h = h.substring(0, colon);
        return h.isEmpty() ? null : h;
    }

    public String getPath() {
        if (path == null) return null;
        return decode(path, false);
    }

    public Set<String> getQueryParameterNames() {
        if (query == null || query.isEmpty()) return Collections.emptySet();
        Set<String> names = new LinkedHashSet<>();
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            names.add(decode(name, false));
        }
        return names;
    }

    public String getQueryParameter(String key) {
        if (query == null) return null;
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq), false);
            if (name.equals(key)) {
                return eq < 0 ? "" : decode(pair.substring(eq + 1), true);
            }
        }
        return null;
    }

    private static String decode(String s, boolean plusIsSpace) {
        try {
            String in = plusIsSpace ? s : s.replace("+", "%2B");
            return URLDecoder.decode(in, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
