package io.voxelcreator.editor;

import imgui.*;

/** Fixed higher-density default atlas, built once before renderer upload, with logical size 13. */
final class UiFontAtlas {
  static final int LOGICAL_SIZE = 13, RASTER_DENSITY = 4;
  static final long MAXIMUM_BYTES = 16L << 20;

  record Metrics(int width, int height, long rgbaBytes, float rasterSize, float fontScale) {}

  private UiFontAtlas() {}

  static Metrics install() {
    var io = ImGui.getIO();
    var atlas = io.getFonts();
    atlas.clear();
    atlas.setTexDesiredWidth(1024);
    var config = new ImFontConfig();
    try {
      config.setSizePixels(LOGICAL_SIZE * RASTER_DENSITY);
      config.setOversampleH(1);
      config.setOversampleV(1);
      config.setPixelSnapH(true);
      var font = atlas.addFontDefault(config);
      font.setScale(1f / RASTER_DENSITY);
      io.setFontDefault(font);
      if (!atlas.build()) throw new IllegalStateException("Unable to build the UI font atlas");
      int width = atlas.getTexWidth(), height = atlas.getTexHeight();
      long bytes = (long) width * height * 4;
      if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || bytes > MAXIMUM_BYTES)
        throw new IllegalStateException("UI font atlas exceeds its fixed allocation budget");
      return new Metrics(width, height, bytes, font.getFontSize(), font.getScale());
    } finally {
      config.destroy();
    }
  }
}
