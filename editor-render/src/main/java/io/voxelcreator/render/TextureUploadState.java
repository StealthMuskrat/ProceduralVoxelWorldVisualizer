package io.voxelcreator.render;

import java.util.Objects;
import java.util.function.Supplier;

/** Fixed unpack/current-unit binding transaction for a host texture allocation or write. */
final class TextureUploadState {
  enum Item {
    ACTIVE_TEXTURE, BINDING, PIXEL_UNPACK_BUFFER, ALIGNMENT, SWAP_BYTES,
    ROW_LENGTH, IMAGE_HEIGHT, SKIP_PIXELS, SKIP_ROWS, SKIP_IMAGES
  }
  interface Backend {
    int read(Item item);
    void write(Item item, int value);
  }
  private final Backend backend;
  private final int[] captured;

  private TextureUploadState(Backend backend, int[] captured) {
    this.backend = backend;
    this.captured = captured;
  }
  static TextureUploadState capture(Backend backend) {
    Objects.requireNonNull(backend);
    var values = new int[Item.values().length];
    for (var item : Item.values()) values[item.ordinal()] = backend.read(item);
    return new TextureUploadState(backend, values);
  }
  <T> T run(Runnable validate, Supplier<T> body, Runnable rollback) {
    var items = Item.values();
    var restores = new Runnable[items.length];
    for (var item : items) {
      int value = captured[item.ordinal()];
      restores[item.ordinal()] = () -> backend.write(item, value);
    }
    T result = RenderTransaction.run(() -> {
      validate.run();
      host(Item.ACTIVE_TEXTURE, captured[Item.ACTIVE_TEXTURE.ordinal()], validate);
      host(Item.PIXEL_UNPACK_BUFFER, 0, validate);
      host(Item.ALIGNMENT, 4, validate);
      host(Item.SWAP_BYTES, 0, validate);
      host(Item.ROW_LENGTH, 0, validate);
      host(Item.IMAGE_HEIGHT, 0, validate);
      host(Item.SKIP_PIXELS, 0, validate);
      host(Item.SKIP_ROWS, 0, validate);
      host(Item.SKIP_IMAGES, 0, validate);
      T prepared = body.get();
      validate.run();
      return prepared;
    }, rollback, restores);
    try { validate.run(); }
    catch (RuntimeException | Error primary) {
      try { rollback.run(); }
      catch (RuntimeException | Error ignored) { /* Keep the obsolete operation primary. */ }
      throw primary;
    }
    return result;
  }
  private void host(Item item, int value, Runnable validate) {
    backend.write(item, value);
    validate.run();
  }
}
