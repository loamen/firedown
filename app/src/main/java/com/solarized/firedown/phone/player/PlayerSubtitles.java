package com.solarized.firedown.phone.player;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's subtitle decisions, kept free of Android and media3 so a JDK
 * harness can run them (scripts/player-subtitles-harness): which format a
 * subtitle file is, what language its name declares, and which of a video's
 * downloaded subtitles shows on its own.
 *
 * <p>Why the last one needs care: subtitles are matched to a video by the
 * PAGE they were downloaded from (the YouTube watch URL both carry), and a
 * page can hold several videos (a news article, a feed). Every subtitle of
 * the page is offered in the Subtitles sheet, but one shows by itself only
 * when it surely belongs to THIS video: the user linked it (added it from
 * this player), its name is the video's name plus a language tag (how
 * YouTube captions are saved: "Title [en].srt" beside "Title.mp4"), or no
 * other video came from that page.
 */
public final class PlayerSubtitles {

    /** media3's MimeTypes.APPLICATION_SUBRIP. */
    public static final String MIME_SUBRIP = "application/x-subrip";
    /** media3's MimeTypes.TEXT_VTT. */
    public static final String MIME_VTT = "text/vtt";
    /** media3's MimeTypes.TEXT_SSA. */
    public static final String MIME_SSA = "text/x-ssa";
    /** media3's MimeTypes.APPLICATION_TTML. */
    public static final String MIME_TTML = "application/ttml+xml";

    /** A trailing "[en]", "[pt-BR]" or "[en-auto]" before the extension. */
    private static final Pattern LANGUAGE_TAG =
            Pattern.compile("\\[([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)]\\s*$");

    /** A trailing " (2)" the download pipeline adds to a taken name. */
    private static final Pattern COUNTER = Pattern.compile("\\s*\\(\\d{1,4}\\)\\s*$");

    /** "00:00:01,000 --> 00:00:02,000" (SubRip) or "00:01.000 --> …" (VTT). */
    private static final Pattern CUE_TIMING =
            Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2})?([,.])\\d{1,3}\\s*-->");

    private PlayerSubtitles() {
    }

    /**
     * The media3 sample mime for a subtitle file, from its stored mime and
     * then its extension; null when it is not a subtitle format the player
     * can parse. YouTube captions are stored as "text/srt", which no parser
     * knows by that name, so it maps to SubRip.
     */
    public static String mimeFor(String fileName, String storedMime) {
        if (storedMime != null) {
            String mime = storedMime.toLowerCase(Locale.ROOT);
            int semicolon = mime.indexOf(';');
            if (semicolon >= 0) {
                mime = mime.substring(0, semicolon).trim();
            }
            switch (mime) {
                case "text/srt":
                case "application/srt":
                case "application/x-subrip":
                    return MIME_SUBRIP;
                case "text/vtt":
                case "application/vtt":
                    return MIME_VTT;
                case "text/x-ssa":
                case "text/x-ass":
                    return MIME_SSA;
                case "application/ttml+xml":
                    return MIME_TTML;
                default:
                    break;
            }
        }
        String extension = extensionOf(fileName);
        switch (extension) {
            case "srt":
                return MIME_SUBRIP;
            case "vtt":
            case "webvtt":
                return MIME_VTT;
            case "ass":
            case "ssa":
                return MIME_SSA;
            case "ttml":
            case "dfxp":
                return MIME_TTML;
            default:
                return null;
        }
    }

    /**
     * The format from the first bytes of a file, for a picked file whose name
     * and provider mime say nothing ("subs.txt", application/octet-stream).
     * Null when the bytes are none of the four.
     */
    public static String sniffMime(byte[] head, int length) {
        if (head == null || length <= 0) {
            return null;
        }
        String text = new String(head, 0, Math.min(length, head.length), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("WEBVTT")) {
            return MIME_VTT;
        }
        if (trimmed.startsWith("[Script Info]")) {
            return MIME_SSA;
        }
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<tt")) {
            return trimmed.contains("<tt") ? MIME_TTML : null;
        }
        Matcher timing = CUE_TIMING.matcher(trimmed);
        if (timing.find()) {
            // A headerless file with SubRip's comma milliseconds is SubRip; with
            // a dot it is a WebVTT body missing its header, which the parser
            // refuses, so it is not offered.
            return ",".equals(timing.group(1)) ? MIME_SUBRIP : null;
        }
        return null;
    }

    /** The extension a subtitle of this media3 mime is saved with. */
    public static String extensionFor(String sampleMime) {
        if (MIME_VTT.equals(sampleMime)) {
            return "vtt";
        }
        if (MIME_SSA.equals(sampleMime)) {
            return "ass";
        }
        if (MIME_TTML.equals(sampleMime)) {
            return "ttml";
        }
        return "srt";
    }

    /**
     * The mime a subtitle of this media3 mime is stored with in Downloads:
     * SubRip as "text/srt", the spelling YouTube captions already carry and
     * the Downloads list files under Subtitle.
     */
    public static String storedMimeFor(String sampleMime) {
        return MIME_SUBRIP.equals(sampleMime) ? "text/srt" : sampleMime;
    }

    /**
     * A track's name for the Subtitles sheet: "English", "Español", and
     * "English (auto)" for a speech-recognition caption — the same wording
     * the capture side stores. Null when the tag names no language.
     */
    public static String displayLanguage(String tag, Locale displayLocale) {
        String language = languageOf(tag);
        if (language == null || language.isEmpty() || "und".equalsIgnoreCase(language)) {
            return null;
        }
        String name = Locale.forLanguageTag(language).getDisplayName(displayLocale);
        if (name.isEmpty() || name.equalsIgnoreCase(language)) {
            name = language;
        } else {
            int first = name.codePointAt(0);
            int width = Character.charCount(first);
            name = name.substring(0, width).toUpperCase(displayLocale) + name.substring(width);
        }
        return isAutoTag(tag) ? name + " (auto)" : name;
    }

    /** The extension of a file name, lowercase, or "" when it has none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The language tag a subtitle's name ends with, "en" or "en-auto" for
     * "Title [en-auto].srt"; null when the name carries none.
     */
    public static String languageTag(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = COUNTER.matcher(withoutExtension(fileName)).replaceFirst("");
        Matcher matcher = LANGUAGE_TAG.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    /** True for a tag YouTube gives its speech-recognition captions. */
    public static boolean isAutoTag(String tag) {
        return tag != null && tag.toLowerCase(Locale.ROOT).endsWith("-auto");
    }

    /** The BCP 47 language of a tag, without the "-auto" suffix. */
    public static String languageOf(String tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        return isAutoTag(tag) ? tag.substring(0, tag.length() - "-auto".length()) : tag;
    }

    /**
     * A name reduced to what two files of one video share: no extension, no
     * language tag, no " (2)" counter, case folded. "Title [en].srt" and
     * "Title (1).mp4" both become "title".
     */
    public static String stem(String fileName) {
        if (fileName == null) {
            return "";
        }
        // Counter, tag, counter: "Title (2) [en]" (how the player names a
        // second copy) and "Title [en] (2)" (how a download is renamed) both
        // reduce to "title".
        String name = COUNTER.matcher(withoutExtension(fileName)).replaceFirst("");
        name = LANGUAGE_TAG.matcher(name).replaceFirst("");
        name = COUNTER.matcher(name).replaceFirst("");
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String withoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** One subtitle the player could attach. */
    public static final class Candidate {
        public final String name;
        /** The language tag from the name, or null. */
        public final String tag;
        /** Added from this video's player, or otherwise linked to it. */
        public final boolean linked;

        public Candidate(String name, String tag, boolean linked) {
            this.name = name;
            this.tag = tag;
            this.linked = linked;
        }
    }

    /**
     * True when the subtitle surely belongs to this video and may show by
     * itself (see the class comment). {@code otherVideos} counts the OTHER
     * finished videos downloaded from the same page.
     */
    public static boolean belongs(Candidate candidate, String videoName, int otherVideos) {
        if (candidate.linked || otherVideos == 0) {
            return true;
        }
        String subtitleStem = stem(candidate.name);
        return !subtitleStem.isEmpty() && subtitleStem.equals(stem(videoName));
    }

    /**
     * The subtitle that shows by itself, as an index into {@code candidates},
     * or -1 for none. Only subtitles that {@link #belongs belong} compete. The
     * order, the YouTube picker's: the phone's language written by a person,
     * the phone's language from speech recognition, any language written by a
     * person, then the first. Downloading a subtitle is asking to see it, so a
     * subtitle in another language still shows when it is all there is.
     */
    public static int pickDefault(List<Candidate> candidates, String videoName,
                                  int otherVideos, String deviceLanguage) {
        String device = deviceLanguage == null ? "" : mainLanguage(deviceLanguage);
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!belongs(candidate, videoName, otherVideos)) {
                continue;
            }
            boolean auto = isAutoTag(candidate.tag);
            String language = languageOf(candidate.tag);
            boolean deviceMatch = language != null && !device.isEmpty()
                    && mainLanguage(language).equals(device);
            int rank;
            if (deviceMatch && !auto) {
                rank = 0;
            } else if (deviceMatch) {
                rank = 1;
            } else if (!auto) {
                rank = 2;
            } else {
                rank = 3;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    private static String mainLanguage(String language) {
        String lower = language.toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = lower.indexOf('-');
        return dash > 0 ? lower.substring(0, dash) : lower;
    }
}
