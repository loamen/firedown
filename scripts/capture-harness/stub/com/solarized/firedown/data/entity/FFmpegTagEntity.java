package com.solarized.firedown.data.entity;
public class FFmpegTagEntity {
  public static final int TYPE_UNKNOWN = 0, TYPE_DURATION = 1, TYPE_QUALITY = 2, TYPE_RESOLUTION = 3, TYPE_LANGUAGE = 4;
  private final int type; private final String text;
  public FFmpegTagEntity(int type, String text) { this.type = type; this.text = text; }
  public int getType() { return type; } public String getText() { return text; }
}
