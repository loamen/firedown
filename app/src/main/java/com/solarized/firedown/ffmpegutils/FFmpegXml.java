package com.solarized.firedown.ffmpegutils;

import android.util.Log;
import android.util.Xml;

import com.solarized.firedown.BuildConfig;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * The XML parser behind FFmpeg's DASH demuxer, in place of libxml2.
 *
 * dashdec.c reads an MPD with a small slice of libxml2's TREE API (first
 * element child, next element sibling, an attribute by name, a node's text).
 * The fork's libavformat/fdxml.c provides that slice under libxml2's names
 * and gets the tree from here: ONE static call per manifest. Native hands the
 * whole manifest (already downloaded through FFmpegOkhttp) as a direct
 * ByteBuffer over its own memory, this parses it with the platform's
 * XmlPullParser and returns the tree as a flat byte[]; fdxml.c rebuilds it.
 * No Java object outlives the call and no node handle ever crosses JNI, so
 * there is nothing to leak and nothing shared between threads — the parser
 * is per call, unlike libxml2's process-global state (patch 0006's crash).
 *
 * Wire format — must match fdxml.c, byte for byte:
 * <pre>
 *   "FDX1"                                   magic + version
 *   then records, document order:
 *   ELEMENT  name  count  (name value)*      opens an element (attrs in order)
 *   END                                      closes the innermost element
 *   TEXT     value                           character data (entities resolved)
 *   CDATA    value                           a CDATA section
 *   OTHER                                    a comment or processing instruction
 *   string = varint byte length + UTF-8 bytes; count = varint
 * </pre>
 * Only the root element's subtree is written. TEXT, CDATA and OTHER stay
 * separate nodes because libxml2 keeps them separate and dashdec.c can tell:
 * get_content_url reads a BaseURL only when its FIRST child is a text node,
 * so a CDATA BaseURL (or one opening with a comment) is skipped there.
 *
 * Names follow libxml2's rule, which this class applies ITSELF with the
 * parser's namespace processing OFF: a prefix declared in scope (and "xml")
 * is stripped — {@code <mpd:MPD>} is "MPD", {@code xml:lang} is "lang" — while
 * an UNDECLARED prefix stays part of the name ({@code <mspr:pro>} is
 * "mspr:pro"). libxml2 only warns about an undeclared prefix; KXmlParser in
 * namespace mode rejects the whole document over it, and real manifests
 * carry them (PlayReady's {@code mspr:pro}, SCTE-35 events, YouTube's
 * {@code yt:} attributes), so leaving it to the parser lost those
 * manifests outright. Namespace declarations are not attributes, as in
 * libxml2.
 *
 * A DOCTYPE is refused (the WebDAV PROPFIND rule): no MPD carries one, and
 * it is the door to entity expansion. libxml2 with the options dashdec used
 * would have accepted it; refusing is the one deliberate difference.
 */
public final class FFmpegXml {

    private static final String TAG = FFmpegXml.class.getSimpleName();

    static final byte[] MAGIC = { 'F', 'D', 'X', '1' };
    static final int TAG_ELEMENT = 1;
    static final int TAG_END = 2;
    static final int TAG_TEXT = 3;
    static final int TAG_CDATA = 4;
    static final int TAG_OTHER = 5;

    /** libxml2's own nesting limit without XML_PARSE_HUGE; fdxml.c enforces it too. */
    static final int MAX_DEPTH = 256;

    private static final String XMLNS = "xmlns";
    private static final String XMLNS_PREFIX = "xmlns:";
    /** Bound by definition in every document, never declared. */
    private static final String XML_PREFIX = "xml";

    private FFmpegXml() {
    }

    /**
     * libxml2's name for a qualified name: the local part when the prefix is
     * declared in scope (or is "xml"), the whole name when it is not.
     */
    static String localName(String qualified, ArrayList<String> prefixes) {
        int colon = qualified.indexOf(':');
        if (colon < 0) {
            return qualified;
        }
        String prefix = qualified.substring(0, colon);
        if (prefix.equals(XML_PREFIX) || prefixes.lastIndexOf(prefix) >= 0) {
            return qualified.substring(colon + 1);
        }
        return qualified;
    }

    /**
     * Called from fdxml.c on the demuxer's thread. Returns the serialized
     * tree, or null when the manifest is not well-formed XML (or carries a
     * DOCTYPE) — dashdec.c then reports AVERROR_INVALIDDATA, exactly as it
     * did when xmlReadMemory returned NULL.
     *
     * Never throws: an exception pending on return would be a CheckJNI abort
     * on the native side, so even an OutOfMemoryError is answered with null.
     */
    public static byte[] parse(ByteBuffer manifest) {
        try {
            return serialize(new BufferInputStream(manifest));
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "parse: not a usable manifest: " + e.getMessage());
            }
            return null;
        } catch (OutOfMemoryError e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "parse: out of memory");
            }
            return null;
        }
    }

    private static byte[] serialize(InputStream input) throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        // OFF on purpose: prefixes are resolved below, libxml2's way.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        // null encoding: the parser detects it (BOM, then the XML declaration),
        // as xmlReadMemory did with a NULL encoding.
        parser.setInput(input, null);

        Writer out = new Writer();
        StringBuilder text = new StringBuilder();
        // Prefixes declared in scope, innermost last; scopeMarks[d] is the
        // stack size before the element at depth d declared its own.
        ArrayList<String> prefixes = new ArrayList<>();
        int[] scopeMarks = new int[MAX_DEPTH + 1];
        boolean pendingText = false;
        boolean rootSeen = false;
        int depth = 0;

        out.bytes(MAGIC);
        int token = parser.nextToken();
        while (token != XmlPullParser.END_DOCUMENT) {
            switch (token) {
                case XmlPullParser.START_TAG:
                    if (depth == 0 && rootSeen) {
                        // libxml2: "Extra content at the end of the document".
                        throw new XmlPullParserException("second root element");
                    }
                    if (pendingText) {
                        out.text(TAG_TEXT, text);
                        pendingText = false;
                    }
                    depth++;
                    if (depth > MAX_DEPTH) {
                        throw new XmlPullParserException("nesting deeper than " + MAX_DEPTH);
                    }
                    rootSeen = true;
                    // An element's declarations are in scope for its own
                    // name and attributes, so collect them first.
                    scopeMarks[depth] = prefixes.size();
                    int attributes = 0;
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String name = parser.getAttributeName(i);
                        if (name.startsWith(XMLNS_PREFIX)) {
                            prefixes.add(name.substring(XMLNS_PREFIX.length()));
                        } else if (!name.equals(XMLNS)) {
                            attributes++;
                        }
                    }
                    out.tag(TAG_ELEMENT);
                    out.string(localName(parser.getName(), prefixes));
                    out.varint(attributes);
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String name = parser.getAttributeName(i);
                        if (name.equals(XMLNS) || name.startsWith(XMLNS_PREFIX)) {
                            continue;
                        }
                        out.string(localName(name, prefixes));
                        // XML attribute-value normalization turns a literal tab
                        // into a space, as libxml2 does; KXmlParser already
                        // does it for CR and LF but leaves tabs.
                        out.string(parser.getAttributeValue(i).replace('\t', ' '));
                    }
                    break;
                case XmlPullParser.END_TAG:
                    if (pendingText) {
                        out.text(TAG_TEXT, text);
                        pendingText = false;
                    }
                    prefixes.subList(scopeMarks[depth], prefixes.size()).clear();
                    depth--;
                    out.tag(TAG_END);
                    break;
                case XmlPullParser.TEXT:
                case XmlPullParser.ENTITY_REF:
                case XmlPullParser.IGNORABLE_WHITESPACE:
                    // libxml2 folds entity references into the surrounding
                    // text node ("a&amp;b" is ONE node "a&b"), so they merge.
                    // getText() is null for an entity the parser could not
                    // resolve, which libxml2 treats as a fatal error.
                    String chunk = parser.getText();
                    if (chunk == null) {
                        throw new XmlPullParserException("undefined entity: " + parser.getName());
                    }
                    if (depth > 0) {
                        text.append(chunk);
                        pendingText = true;
                    }
                    break;
                case XmlPullParser.CDSECT:
                    if (depth > 0) {
                        if (pendingText) {
                            out.text(TAG_TEXT, text);
                            pendingText = false;
                        }
                        text.append(parser.getText());
                        out.text(TAG_CDATA, text);
                    }
                    break;
                case XmlPullParser.COMMENT:
                case XmlPullParser.PROCESSING_INSTRUCTION:
                    if (depth > 0) {
                        if (pendingText) {
                            out.text(TAG_TEXT, text);
                            pendingText = false;
                        }
                        out.tag(TAG_OTHER);
                    }
                    break;
                case XmlPullParser.DOCDECL:
                    throw new XmlPullParserException("DOCTYPE refused");
                default:
                    break;
            }
            token = parser.nextToken();
        }
        if (!rootSeen) {
            throw new XmlPullParserException("no root element");
        }
        return out.toByteArray();
    }

    /** Reads the native manifest buffer in place — no copy into the Java heap. */
    private static final class BufferInputStream extends InputStream {

        private final ByteBuffer mBuffer;

        BufferInputStream(ByteBuffer buffer) {
            mBuffer = buffer;
        }

        @Override
        public int read() {
            if (!mBuffer.hasRemaining()) {
                return -1;
            }
            return mBuffer.get() & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (!mBuffer.hasRemaining()) {
                return -1;
            }
            int n = Math.min(len, mBuffer.remaining());
            mBuffer.get(b, off, n);
            return n;
        }

        @Override
        public int available() {
            return mBuffer.remaining();
        }
    }

    /** The wire format's writer. Strings are standard UTF-8, never JNI's modified UTF-8. */
    private static final class Writer extends ByteArrayOutputStream {

        Writer() {
            super(16 * 1024);
        }

        void tag(int tag) {
            write(tag);
        }

        void bytes(byte[] b) {
            write(b, 0, b.length);
        }

        void varint(int value) {
            int v = value;
            while ((v & ~0x7f) != 0) {
                write((v & 0x7f) | 0x80);
                v >>>= 7;
            }
            write(v);
        }

        void string(String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            varint(utf8.length);
            bytes(utf8);
        }

        /** Writes a TEXT/CDATA record from the accumulated text and clears it. */
        void text(int tag, StringBuilder text) {
            tag(tag);
            string(text.toString());
            text.setLength(0);
        }
    }
}
