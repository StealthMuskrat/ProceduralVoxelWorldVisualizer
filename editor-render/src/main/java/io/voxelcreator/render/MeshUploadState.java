package io.voxelcreator.render;

import java.util.Objects;

/** Caller vertex-array and global array-buffer bindings around writes to the private mesh VAO. */
final class MeshUploadState {
  enum Item { VERTEX_ARRAY, ARRAY_BUFFER }
  interface Backend {
    int read(Item item);
    void write(Item item, int value);
  }
  private final Backend backend;
  private final int vertexArray, arrayBuffer;
  private MeshUploadState(Backend backend, int vertexArray, int arrayBuffer) {
    this.backend = backend;
    this.vertexArray = vertexArray;
    this.arrayBuffer = arrayBuffer;
  }
  static MeshUploadState capture(Backend backend) {
    Objects.requireNonNull(backend);
    int vertexArray = backend.read(Item.VERTEX_ARRAY);
    int arrayBuffer = backend.read(Item.ARRAY_BUFFER);
    return new MeshUploadState(backend, vertexArray, arrayBuffer);
  }
  void run(Runnable validate, Runnable body) {
    RenderTransaction.run(() -> {
      validate.run(); body.run(); validate.run(); return null;
    }, () -> {},
        () -> backend.write(Item.VERTEX_ARRAY, vertexArray),
        () -> backend.write(Item.ARRAY_BUFFER, arrayBuffer));
    validate.run();
  }
}
