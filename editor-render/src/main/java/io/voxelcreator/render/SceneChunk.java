package io.voxelcreator.render;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.util.Objects;

/**
 * Immutable display input. Stage ghost IDs remain in this renderer wrapper, never in exact chunks.
 */
public final class SceneChunk {
  private final ChunkPos position;
  private final int size;
  private final long bytes;
  private final MeshBuilder.Cells cells;
  private final int uniform;
  private final double[] surface;
  private final double threshold;

  private SceneChunk(ChunkPos pos, int size, long bytes, MeshBuilder.Cells cells) {
    this(pos, size, bytes, cells, -1);
  }

  private SceneChunk(ChunkPos pos, int size, long bytes, MeshBuilder.Cells cells, int uniform) {
    this(pos, size, bytes, cells, uniform, null, 0);
  }

  private SceneChunk(
      ChunkPos pos,
      int size,
      long bytes,
      MeshBuilder.Cells cells,
      int uniform,
      double[] surface,
      double threshold) {
    this.surface = surface;
    this.threshold = threshold;
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

  /** Private complete corner grid; neighboring chunks evaluate identical canonical face nodes. */
  public static SceneChunk isosurface(
      ChunkPos pos, int size, CancellationToken token, MeshBuilder.Scalar field, double threshold) {
    if (size < 2 || size > 256 || Integer.bitCount(size) != 1 || !Double.isFinite(threshold))
      throw new IllegalArgumentException("Surface chunk size/threshold");
    Objects.requireNonNull(pos);
    Objects.requireNonNull(token);
    Objects.requireNonNull(field);
    token.throwIfCancelled();
    int side = size + 1;
    long ox = (long) pos.x() * size, oy = (long) pos.y() * size, oz = (long) pos.z() * size;
    if (ox < Integer.MIN_VALUE
        || oy < Integer.MIN_VALUE
        || oz < Integer.MIN_VALUE
        || ox + size > 1L + Integer.MAX_VALUE
        || oy + size > 1L + Integer.MAX_VALUE
        || oz + size > 1L + Integer.MAX_VALUE)
      throw new IllegalArgumentException("Surface chunk outside block coordinate domain");
    double[] corners = new double[side * side * side];
    for (int z = 0; z <= size; z++)
      for (int y = 0; y <= size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x <= size; x++) {
          double value = field.sample(ox + (double) x, oy + (double) y, oz + (double) z);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException("Nonfinite surface corner");
          corners[(z * side + y) * side + x] = value;
        }
      }
    token.throwIfCancelled();
    return new SceneChunk(
        pos,
        size,
        128L + 8L * corners.length,
        (x, y, z) -> corners[(z * side + y) * side + x] >= threshold ? 128 : 0,
        -1,
        corners,
        threshold);
  }

  public boolean surface() {
    return surface != null;
  }

  public PackedMesh surfaceMesh(CancellationToken token) {
    return surfaceMesh(0, token);
  }

  public PackedMesh surfaceMesh(int level, CancellationToken token) {
    if (surface == null) throw new IllegalStateException("No continuous surface grid");
    int side = size + 1;
    var mesh =
        SurfaceLodMesh.build(
            size,
            level,
            (x, y, z) -> surface[((int) z * side + (int) y) * side + (int) x],
            threshold,
            token,
            SurfaceLodMesh.maximumMeshBytes(size, level));
    token.throwIfCancelled();
    var packed = PackedMesh.surface(mesh, size, token);
    token.throwIfCancelled();
    return packed;
  }

  public static long surfaceWorkingBytes(int size) {
    return IsosurfaceMesh.workingBytes(size) + 2 * 576L * size * size * size + 65536;
  }

  public static long surfaceWorkingBytes(int size, int level) {
    if (level == 0) return surfaceWorkingBytes(size);
    return SurfaceLodMesh.workingBytes(size, level)
        + 2 * (SurfaceLodMesh.maximumMeshBytes(size, level) / 7 * 4)
        + 65536;
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
