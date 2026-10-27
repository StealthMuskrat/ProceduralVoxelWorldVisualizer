package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import java.nio.*;

/**
 * Immutable 16-byte voxel vertex stream: half-cell ushort XYZ, snorm8 normal, unorm8 AO, uint type.
 */
public final class PackedMesh {
  public static final int STRIDE = 16;
  private final byte[] bytes;
  private final boolean points;
  private final int length;
  private final int unitsPerBlock;

  public PackedMesh(byte[] bytes, boolean points) {
    if (bytes.length % STRIDE != 0) throw new IllegalArgumentException("Packed vertex alignment");
    this.bytes = bytes.clone();
    this.points = points;
    this.length = bytes.length;
    this.unitsPerBlock = 2;
  }

  private PackedMesh(byte[] bytes, int length, boolean points, int unitsPerBlock) {
    this.bytes = bytes;
    this.length = length;
    this.unitsPerBlock = unitsPerBlock;
    this.points = points;
  }

  static PackedMesh owned(byte[] bytes, int length, boolean points) {
    return new PackedMesh(bytes, length, points, 2);
  }

  /** Surface vertices use 1/128-block positions in the same immutable 16-byte arena layout. */
  public static PackedMesh surface(Mesh mesh, int size) {
    return surface(mesh, size, new CancellationToken());
  }

  public static PackedMesh surface(Mesh mesh, int size, CancellationToken token) {
    if (size < 1 || size > 256 || mesh.points() || mesh.vertices().length % 21 != 0)
      throw new IllegalArgumentException("Surface mesh layout");
    token.throwIfCancelled();
    float[] vertices = mesh.vertices();
    byte[] result = new byte[Math.multiplyExact(mesh.vertexCount(), STRIDE)];
    var out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
    int[][] xyz = new int[3][3];
    for (int i = 0; i < vertices.length; i += 21) {
      if (i % (21 * 1024) == 0) token.throwIfCancelled();
      for (int v = 0; v < 3; v++)
        for (int axis = 0; axis < 3; axis++) {
          float value = vertices[i + v * 7 + axis];
          if (!Float.isFinite(value) || value < 0 || value > size)
            throw new IllegalArgumentException("Surface position outside chunk");
          xyz[v][axis] = Math.round(value * 128);
        }
      long ux = xyz[1][0] - xyz[0][0], uy = xyz[1][1] - xyz[0][1], uz = xyz[1][2] - xyz[0][2];
      long vx = xyz[2][0] - xyz[0][0], vy = xyz[2][1] - xyz[0][1], vz = xyz[2][2] - xyz[0][2];
      long nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      if (nx == 0 && ny == 0 && nz == 0) continue;
      double direction = nx * vertices[i + 3] + ny * vertices[i + 4] + nz * vertices[i + 5];
      for (int order = 0; order < 3; order++) {
        int v = direction < 0 && order != 0 ? 3 - order : order;
        for (int axis = 0; axis < 3; axis++) out.putShort((short) xyz[v][axis]);
        for (int axis = 3; axis < 6; axis++) {
          float normal = vertices[i + v * 7 + axis];
          if (!Float.isFinite(normal) || Math.abs(normal) > 1.000001)
            throw new IllegalArgumentException("Finite unit surface normal required");
          out.put((byte) Math.round(Math.max(-1, Math.min(1, normal)) * 127));
        }
        out.put((byte) 255).putShort((short) 0).putInt(128);
      }
    }
    token.throwIfCancelled();
    return new PackedMesh(result, out.position(), false, 128);
  }

  public int unitsPerBlock() {
    return unitsPerBlock;
  }

  public long retainedBytes() {
    return bytes.length;
  }

  public int vertexCount() {
    return length / STRIDE;
  }

  public int byteSize() {
    return length;
  }

  public boolean points() {
    return points;
  }

  public void writeTo(ByteBuffer target) {
    target.put(bytes, 0, length);
  }

  public void writeRangeTo(ByteBuffer target, int offset, int count) {
    if (offset < 0 || count < 0 || offset + count > length) throw new IndexOutOfBoundsException();
    target.put(bytes, offset, count);
  }

  public ByteBuffer readOnly() {
    return ByteBuffer.wrap(bytes, 0, length)
        .slice()
        .asReadOnlyBuffer()
        .order(ByteOrder.LITTLE_ENDIAN);
  }
}
