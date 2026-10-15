package com.solarized.firedown.utils;
/** Test stand-in: the mime predicates, by prefix. */
public class FileUriHelper {
  public static boolean isImage(String m) { return m != null && m.startsWith("image/"); }
  public static boolean isGIF(String m) { return "image/gif".equals(m); }
  public static boolean isVideo(String m) { return m != null && (m.startsWith("video/") || m.equalsIgnoreCase("application/x-mpegurl") || m.equalsIgnoreCase("audio/mpegurl")); }
  public static boolean isAudio(String m) { return m != null && m.contains("audio"); }
  public static boolean isSubtitle(String m) { return "text/vtt".equals(m); }
}
