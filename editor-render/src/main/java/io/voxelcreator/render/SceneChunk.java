package io.voxelcreator.render;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;

/**
 * Immutable display input. Stage ghost IDs remain in this renderer wrapper, never in exact chunks.
 */
public final class SceneChunk {
  private final ChunkPos position;
  private final int size;
  private final long bytes;
  private final MeshBuilder.Cells cells;
  private final int uniform;

  private SceneChunk(ChunkPos pos, int size, long bytes, MeshBuilder.Cells cells) {
    this(pos, size, bytes, cells, -1);
  }

  private SceneChunk(ChunkPos pos, int size, long bytes, MeshBuilder.Cells cells, int uniform) {
    this.position = pos;
    this.size = size;
    this.bytes = bytes;
    this.cells = cells;
    this.uniform = uniform;
  }

  public static SceneChunk exact(VoxelChunk chunk) {
    return new SceneChunk(
        chunk.position(),
        chunk.size(),
        chunk.estimatedBytes(),
        chunk::getVoxelTypeId,
        SetUniform(chunk));
  }

  public static SceneChunk changes(StageChunk pair, int typeCount) {
    var before = pair.before();
    var after = pair.after();
    return new SceneChunk(
        before.position(),
        before.size(),
        before.estimatedBytes() + after.estimatedBytes(),
        (x, y, z) -> {
          int a = before.getVoxelTypeId(x, y, z), b = after.getVoxelTypeId(x, y, z);
          return a == b ? 0 : b > 0 ? b : typeCount + a;
        });
  }

  public static SceneChunk sampled(
      ChunkPos pos, int size, CancellationToken token, MeshBuilder.Scalar field, double threshold) {
    int[] values = new int[size * size * size];
    long ox = (long) pos.x() * size, oy = (long) pos.y() * size, oz = (long) pos.z() * size;
    for (int z = 0; z < size; z++) {
      token.throwIfCancelled();
      for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++) {
          double v = field.sample(ox + x, oy + y, oz + z);
          if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite preview field");
          values[(z * size + y) * size + x] =
              v >= threshold ? Math.max(1, Math.min(255, (int) ((v + 1) * 127.5))) : 0;
        }
    }
    return new SceneChunk(
        pos, size, 128L + 4L * values.length, (x, y, z) -> values[(z * size + y) * size + x]);
  }

  private static int SetUniform(VoxelChunk chunk) {
    return chunk.representation().equals("UNIFORM") || chunk.isEmpty()
        ? chunk.getVoxelTypeId(0, 0, 0)
        : -1;
  }

  public int uniformType() {
    return uniform;
  }

  public ChunkPos position() {
    return position;
  }

  public int size() {
    return size;
  }

  public long bytes() {
    return bytes;
  }

  public int get(int x, int y, int z) {
    return cells.get(x, y, z);
  }
}
