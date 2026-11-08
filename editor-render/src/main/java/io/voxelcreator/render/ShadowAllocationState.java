package io.voxelcreator.render;

import java.util.Objects;
import java.util.function.Supplier;

/** One snapshot and one independent restoration tail for depth storage and split framebuffers. */
final class ShadowAllocationState {
  interface Backend extends TextureUploadState.Backend {
    int readFramebuffer();
    int drawFramebuffer();
    void readFramebuffer(int value);
    void drawFramebuffer(int value);
  }
  private final Backend backend;
  private final TextureUploadState texture;
  private final int read, draw;
  private ShadowAllocationState(Backend backend, TextureUploadState texture, int read, int draw) {
    this.backend = backend; this.texture = texture; this.read = read; this.draw = draw;
  }
  static ShadowAllocationState capture(Backend backend) {
    Objects.requireNonNull(backend);
    var texture = TextureUploadState.capture(backend);
    int read = backend.readFramebuffer(), draw = backend.drawFramebuffer();
    return new ShadowAllocationState(backend, texture, read, draw);
  }
  ShadowAllocationState retiring(int oldTexture, int oldFramebuffer) {
    return new ShadowAllocationState(backend, texture.withoutBinding(oldTexture),
        oldFramebuffer != 0 && read == oldFramebuffer ? 0 : read,
        oldFramebuffer != 0 && draw == oldFramebuffer ? 0 : draw);
  }
  <T> T run(Runnable current, Supplier<T> body, Runnable rollback) {
    return texture.run(current, body, rollback,
        () -> backend.readFramebuffer(read), () -> backend.drawFramebuffer(draw));
  }
}
