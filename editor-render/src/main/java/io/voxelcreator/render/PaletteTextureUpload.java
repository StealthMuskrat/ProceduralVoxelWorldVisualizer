package io.voxelcreator.render;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.lwjgl.BufferUtils;

/** Render-thread palette preparation/cache; a failed backend write remains explicitly retryable. */
final class PaletteTextureUpload {
  interface Backend {
    void write(int width, int height, IntBuffer pixels);
  }

  record Layout(int width, int height, int cells) {}

  private final Backend backend;
  private int[] accepted;
  private int width = 256;

  PaletteTextureUpload(Backend backend) {
    this.backend = Objects.requireNonNull(backend);
  }

  static Layout layout(int count, int maximum) {
    if (count < 1 || maximum < 1) throw new IllegalArgumentException("Palette count/device limit");
    int width = Math.min(count, maximum);
    long height = ((long) count + width - 1) / width, cells = width * height;
    if (height > maximum || cells > Integer.MAX_VALUE / Integer.BYTES)
      throw new IllegalArgumentException("Palette exceeds device or CPU buffer capacity");
    return new Layout(width, (int) height, (int) cells);
  }

  boolean set(int[] colors, int maximum) {
    Objects.requireNonNull(colors);
    var shape = layout(colors.length, maximum);
    if (width == shape.width && Arrays.equals(accepted, colors)) return false;
    var owned = colors.clone();
    var pixels = BufferUtils.createIntBuffer(shape.cells);
    pixels.put(owned);
    while (pixels.hasRemaining()) pixels.put(0);
    pixels.flip();
    try {
      backend.write(shape.width, shape.height, pixels.asReadOnlyBuffer());
    } catch (RuntimeException | Error failure) {
      // A throwing write may already have changed the texture, so even the old colors need retry.
      accepted = null;
      throw failure;
    }
    accepted = owned;
    width = shape.width;
    return true;
  }

  int width() {
    return width;
  }
}
