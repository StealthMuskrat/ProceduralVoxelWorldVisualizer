package io.voxelcreator.storage;

import java.io.*;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Exact adaptive snapshot. EMPTY=0, UNIFORM=-type, BRANCH/PACKED use a compact primitive arena. */
public final class VoxelChunk {
  @FunctionalInterface
  public interface Sampler {
    int sample(int x, int y, int z);
  }

  @FunctionalInterface
  public interface OccupiedConsumer {
    void accept(int x, int y, int z, int type);
  }

  private final ChunkPos position;
  private final int size, root;
  private final int[] arena;

  private VoxelChunk(ChunkPos p, int size, int root, int[] arena) {
    this.position = p;
    this.size = size;
    this.root = root;
    this.arena = arena;
  }

  public static void validateSize(int size) {
    if (size < 2 || size > 256 || (size & (size - 1)) != 0)
      throw new IllegalArgumentException("Root size must be 2,4,8,16,32,64,128,256");
  }

  public static VoxelChunk uniform(ChunkPos p, int size, int type) {
    validateSize(size);
    if (type < 0) throw new IllegalArgumentException("Negative type");
    return new VoxelChunk(p, size, -type, new int[0]);
  }

  public static VoxelChunk generate(ChunkPos p, int size, Sampler sampler) {
    validateSize(size);
    var b = new Builder(sampler);
    int root = b.build(0, 0, 0, size);
    return new VoxelChunk(p, size, root, Arrays.copyOf(b.arena, b.used));
  }

  public ChunkPos position() {
    return position;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return root == 0;
  }

  public long estimatedBytes() {
    return 64L + 4L * arena.length;
  }

  public String representation() {
    return root == 0 ? "EMPTY" : root < 0 ? "UNIFORM" : arena[root] == 1 ? "BRANCH" : "PACKED";
  }

  public int getVoxelTypeId(int x, int y, int z) {
    if ((x | y | z) < 0 || x >= size || y >= size || z >= size)
      throw new IndexOutOfBoundsException("Chunk-local coordinates");
    int ptr = root, s = size;
    while (ptr > 0) {
      if (arena[ptr] == 2) return arena[ptr + 1 + (y * s + z) * s + x];
      int h = s / 2, child = (x >= h ? 1 : 0) | (y >= h ? 2 : 0) | (z >= h ? 4 : 0);
      ptr = arena[ptr + 1 + child];
      x %= h;
      y %= h;
      z %= h;
      s = h;
    }
    return -ptr;
  }

  public void forEachOccupied(OccupiedConsumer consumer) {
    visit(root, 0, 0, 0, size, consumer);
  }

  private void visit(int ptr, int x, int y, int z, int s, OccupiedConsumer consumer) {
    if (ptr == 0) return;
    if (ptr < 0) {
      for (int yy = y; yy < y + s; yy++)
        for (int zz = z; zz < z + s; zz++)
          for (int xx = x; xx < x + s; xx++) consumer.accept(xx, yy, zz, -ptr);
      return;
    }
    if (arena[ptr] == 2) {
      for (int yy = 0; yy < s; yy++)
        for (int zz = 0; zz < s; zz++)
          for (int xx = 0; xx < s; xx++) {
            int t = arena[ptr + 1 + (yy * s + zz) * s + xx];
            if (t != 0) consumer.accept(x + xx, y + yy, z + zz, t);
          }
      return;
    }
    int h = s / 2;
    for (int child = 0; child < 8; child++)
      visit(
          arena[ptr + 1 + child],
          x + ((child & 1) != 0 ? h : 0),
          y + ((child & 2) != 0 ? h : 0),
          z + ((child & 4) != 0 ? h : 0),
          h,
          consumer);
  }

  private static final class Builder {
    final Sampler sampler;
    int[] arena = new int[256];
    int used = 1;

    Builder(Sampler s) {
      sampler = s;
    }

    int alloc(int count) {
      int ptr = used;
      used = Math.addExact(used, count);
      if (used > arena.length) arena = Arrays.copyOf(arena, Math.max(used, arena.length * 2));
      return ptr;
    }

    int build(int x, int y, int z, int s) {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      if (s <= 4) {
        int count = s * s * s;
        int[] values = new int[count];
        int first = -1;
        boolean uniform = true;
        for (int yy = 0; yy < s; yy++)
          for (int zz = 0; zz < s; zz++)
            for (int xx = 0; xx < s; xx++) {
              int v = sampler.sample(x + xx, y + yy, z + zz);
              if (v < 0) throw new IllegalArgumentException("Negative runtime type");
              values[(yy * s + zz) * s + xx] = v;
              if (first == -1) first = v;
              else if (first != v) uniform = false;
            }
        if (uniform) return -first;
        int ptr = alloc(count + 1);
        arena[ptr] = 2;
        System.arraycopy(values, 0, arena, ptr + 1, count);
        return ptr;
      }
      int h = s / 2;
      int[] children = new int[8];
      boolean uniform = true;
      int checkpoint = used;
      for (int i = 0; i < 8; i++) {
        children[i] =
            build(
                x + ((i & 1) != 0 ? h : 0),
                y + ((i & 2) != 0 ? h : 0),
                z + ((i & 4) != 0 ? h : 0),
                h);
        if (i > 0 && children[i] != children[0]) uniform = false;
      }
      if (uniform && children[0] <= 0) {
        used = checkpoint;
        return children[0];
      }
      int ptr = alloc(9);
      arena[ptr] = 1;
      System.arraycopy(children, 0, arena, ptr + 1, 8);
      return ptr;
    }
  }

  public void writeTree(DataOutput out, IntUnaryOperator map) throws IOException {
    write(root, size, out, map);
  }

  private void write(int ptr, int s, DataOutput out, IntUnaryOperator map) throws IOException {
    if (ptr == 0) {
      out.writeByte(0);
      return;
    }
    if (ptr < 0) {
      out.writeByte(1);
      out.writeInt(map.applyAsInt(-ptr));
      return;
    }
    if (arena[ptr] == 1) {
      out.writeByte(2);
      for (int i = 0; i < 8; i++) write(arena[ptr + 1 + i], s / 2, out, map);
    } else {
      out.writeByte(3);
      for (int i = 0; i < s * s * s; i++) out.writeInt(map.applyAsInt(arena[ptr + 1 + i]));
    }
  }

  public static VoxelChunk readTree(ChunkPos p, int size, DataInput in, IntUnaryOperator map)
      throws IOException {
    validateSize(size);
    var b = new Builder(null);
    int root = read(size, in, map, b);
    return new VoxelChunk(p, size, root, Arrays.copyOf(b.arena, b.used));
  }

  private static int read(int s, DataInput in, IntUnaryOperator map, Builder b) throws IOException {
    int kind = in.readUnsignedByte();
    if (kind == 0) return 0;
    if (kind == 1) {
      int v = map.applyAsInt(in.readInt());
      if (v <= 0) throw new IOException("Uniform type must be occupied");
      return -v;
    }
    if (kind == 2) {
      if (s <= 4) throw new IOException("Branch below packed leaf");
      int p = b.alloc(9);
      b.arena[p] = 1;
      for (int i = 0; i < 8; i++) {
        int child = read(s / 2, in, map, b);
        b.arena[p + 1 + i] = child;
      }
      return p;
    }
    if (kind == 3) {
      if (s > 4) throw new IOException("Oversized packed leaf");
      int p = b.alloc(1 + s * s * s);
      b.arena[p] = 2;
      for (int i = 0; i < s * s * s; i++) {
        int v = map.applyAsInt(in.readInt());
        if (v < 0) throw new IOException("Invalid packed type");
        b.arena[p + 1 + i] = v;
      }
      return p;
    }
    throw new IOException("Unknown tree node " + kind);
  }
}
