package io.voxelcreator.render;

import java.nio.*;

/**
 * Immutable 16-byte voxel vertex stream: half-cell ushort XYZ, snorm8 normal, unorm8 AO, uint type.
 */
public final class PackedMesh {
  public static final int STRIDE = 16;
  private final byte[] bytes;
  private final boolean points;
  private final int length;

  public PackedMesh(byte[] bytes, boolean points) {
    if (bytes.length % STRIDE != 0) throw new IllegalArgumentException("Packed vertex alignment");
    this.bytes = bytes.clone();
    this.points = points;
    this.length = bytes.length;
  }

  private PackedMesh(byte[] bytes, int length, boolean points) {
    this.bytes = bytes;
    this.length = length;
    this.points = points;
  }

  static PackedMesh owned(byte[] bytes, int length, boolean points) {
    return new PackedMesh(bytes, length, points);
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
