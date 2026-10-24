package io.voxelcreator.storage;

import java.io.*;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Exact immutable adaptive snapshot with a chunk palette and primitive node arena. */
public final class VoxelChunk {
  @FunctionalInterface
  public interface Sampler {
    int sample(int x, int y, int z);
  }

  @FunctionalInterface
  public interface OccupiedConsumer {
    void accept(int x, int y, int z, int type);
  }

  /**
   * Counts describe reachable storage only. Byte estimates exclude caller and registry ownership.
   */
  public record StorageStatistics(
      long emptyRegions,
      long uniformRegions,
      long branchNodes,
      long packedLeaves,
      long denseLeaves,
      long occupiedVoxels,
      int paletteEntries,
      int arenaInts,
      long packedWords) {}

  private static final int BRANCH = 1, PACKED = 2, DENSE = 3;
  private final ChunkPos position;
  private final int size, root;
  private final int[] arena, palette;

  private VoxelChunk(ChunkPos p, int size, int root, int[] arena, int[] palette) {
    this.position = Objects.requireNonNull(p);
    this.size = size;
    this.root = root;
    this.arena = arena;
    this.palette = palette;
  }

  public static void validateSize(int size) {
    if (size < 2 || size > 256 || (size & (size - 1)) != 0)
      throw new IllegalArgumentException("Root size must be 2,4,8,16,32,64,128,256");
  }

  public static VoxelChunk uniform(ChunkPos p, int size, int type) {
    validateSize(size);
    if (type < 0) throw new IllegalArgumentException("Negative type");
    return new VoxelChunk(
        p, size, type == 0 ? 0 : -1, new int[0], type == 0 ? new int[] {0} : new int[] {0, type});
  }

  public static VoxelChunk generate(ChunkPos p, int size, Sampler sampler) {
    validateSize(size);
    Objects.requireNonNull(p);
    Objects.requireNonNull(sampler);
    var b = new Builder();
    int root = b.build(0, 0, 0, size, sampler);
    return b.finish(p, size, root);
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
    return 80L + 4L * arena.length + 4L * palette.length;
  }

  public String representation() {
    return root == 0 ? "EMPTY" : root < 0 ? "UNIFORM" : arena[root] == BRANCH ? "BRANCH" : "PACKED";
  }

  /** A defensive copy of the reachable chunk dictionary; index zero is always EMPTY. */
  public int[] paletteRuntimeIds() {
    return palette.clone();
  }

  public int getVoxelTypeId(int x, int y, int z) {
    checkCell(x, y, z);
    int ptr = root, s = size;
    while (ptr > 0) {
      if (arena[ptr] != BRANCH) return leafValue(ptr, (y * s + z) * s + x);
      int h = s / 2, child = (x >= h ? 1 : 0) | (y >= h ? 2 : 0) | (z >= h ? 4 : 0);
      ptr = arena[ptr + 1 + child];
      x %= h;
      y %= h;
      z %= h;
      s = h;
    }
    return palette[-ptr];
  }

  private void checkCell(int x, int y, int z) {
    if ((x | y | z) < 0 || x >= size || y >= size || z >= size)
      throw new IndexOutOfBoundsException("Chunk-local coordinates");
  }

  private int leafValue(int ptr, int index) {
    if (arena[ptr] == DENSE) return palette[arena[ptr + 1 + index]];
    int count = arena[ptr + 1], bits = bits(count);
    int local = unpack(arena, ptr + 2 + count, index, bits);
    return palette[arena[ptr + 2 + local]];
  }

  public void forEachOccupied(OccupiedConsumer consumer) {
    Objects.requireNonNull(consumer);
    visit(root, 0, 0, 0, size, consumer);
  }

  private void visit(int ptr, int x, int y, int z, int s, OccupiedConsumer consumer) {
    if (ptr == 0) return;
    if (ptr <= 0 || arena[ptr] != BRANCH) {
      for (int yy = 0; yy < s; yy++)
        for (int zz = 0; zz < s; zz++)
          for (int xx = 0; xx < s; xx++) {
            int t = ptr < 0 ? palette[-ptr] : leafValue(ptr, (yy * s + zz) * s + xx);
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

  public StorageStatistics storageStatistics() {
    long[] n = new long[7];
    count(root, size, n);
    return new StorageStatistics(
        n[0], n[1], n[2], n[3], n[4], n[5], palette.length, arena.length, n[6]);
  }

  private void count(int ptr, int s, long[] n) {
    if (ptr == 0) {
      n[0]++;
      return;
    }
    if (ptr < 0) {
      n[1]++;
      n[5] += (long) s * s * s;
      return;
    }
    if (arena[ptr] == BRANCH) {
      n[2]++;
      for (int i = 0; i < 8; i++) count(arena[ptr + 1 + i], s / 2, n);
    } else {
      n[arena[ptr] == PACKED ? 3 : 4]++;
      int cells = s * s * s;
      if (arena[ptr] == PACKED) n[6] += words(cells, bits(arena[ptr + 1]));
      for (int i = 0; i < cells; i++) if (leafValue(ptr, i) != 0) n[5]++;
    }
  }

  /**
   * Returns an exact new snapshot with one local cell replaced; this snapshot remains unchanged.
   */
  public VoxelChunk withVoxel(int x, int y, int z, int type) {
    return withFilledBox(x, y, z, x, y, z, type);
  }

  /**
   * Replaces an inclusive local box. Fully covered subtrees become immediate EMPTY/UNIFORM nodes.
   * Only intersecting leaves are expanded (at most 64 cells each). Retained nodes are copied into
   * compact new storage; no nodes or palette values are shared mutably with this snapshot. The cost
   * includes copying retained storage, not just the changed region.
   */
  public VoxelChunk withFilledBox(
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int type) {
    checkCell(minX, minY, minZ);
    checkCell(maxX, maxY, maxZ);
    if (minX > maxX || minY > maxY || minZ > maxZ)
      throw new IllegalArgumentException("Reversed local box");
    if (type < 0) throw new IllegalArgumentException("Negative type");
    if (root <= 0 && palette[-root] == type) return this;
    if (minX == 0
        && minY == 0
        && minZ == 0
        && maxX == size - 1
        && maxY == size - 1
        && maxZ == size - 1) return uniform(position, size, type);
    var b = new Builder();
    int next = replace(root, 0, 0, 0, size, minX, minY, minZ, maxX, maxY, maxZ, type, b);
    return b.finish(position, size, next);
  }

  private int replace(
      int ptr,
      int x,
      int y,
      int z,
      int s,
      int ax,
      int ay,
      int az,
      int bx,
      int by,
      int bz,
      int type,
      Builder b) {
    checkpoint();
    if (x > bx || y > by || z > bz || x + s <= ax || y + s <= ay || z + s <= az)
      return copy(ptr, s, b);
    if (x >= ax && y >= ay && z >= az && x + s - 1 <= bx && y + s - 1 <= by && z + s - 1 <= bz)
      return -b.intern(type);
    if (ptr <= 0 && palette[-ptr] == type) return -b.intern(type);
    if (s <= 4) {
      int[] values = new int[s * s * s];
      for (int yy = 0; yy < s; yy++)
        for (int zz = 0; zz < s; zz++)
          for (int xx = 0; xx < s; xx++) {
            int i = (yy * s + zz) * s + xx;
            values[i] =
                x + xx >= ax
                        && x + xx <= bx
                        && y + yy >= ay
                        && y + yy <= by
                        && z + zz >= az
                        && z + zz <= bz
                    ? type
                    : ptr <= 0 ? palette[-ptr] : leafValue(ptr, i);
          }
      return b.leaf(values);
    }
    int h = s / 2;
    int[] children = new int[8];
    for (int i = 0; i < 8; i++)
      children[i] =
          replace(
              ptr <= 0 ? ptr : arena[ptr + 1 + i],
              x + ((i & 1) != 0 ? h : 0),
              y + ((i & 2) != 0 ? h : 0),
              z + ((i & 4) != 0 ? h : 0),
              h,
              ax,
              ay,
              az,
              bx,
              by,
              bz,
              type,
              b);
    return b.branch(children);
  }

  private int copy(int ptr, int s, Builder b) {
    checkpoint();
    if (ptr <= 0) return -b.intern(palette[-ptr]);
    if (arena[ptr] == BRANCH) {
      int[] children = new int[8];
      for (int i = 0; i < 8; i++) children[i] = copy(arena[ptr + 1 + i], s / 2, b);
      return b.branch(children);
    }
    int[] values = new int[s * s * s];
    for (int i = 0; i < values.length; i++) values[i] = leafValue(ptr, i);
    return b.leaf(values);
  }

  private static void checkpoint() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }

  private static int bits(int count) {
    return 32 - Integer.numberOfLeadingZeros(count - 1);
  }

  private static int words(int cells, int bits) {
    return (cells * bits + 31) / 32;
  }

  private static int unpack(int[] data, int start, int index, int bits) {
    int bit = index * bits, word = start + (bit >>> 5), shift = bit & 31;
    int value = data[word] >>> shift;
    if (shift + bits > 32) value |= data[word + 1] << (32 - shift);
    return value & ((1 << bits) - 1);
  }

  private static void pack(int[] data, int start, int index, int bits, int value) {
    int bit = index * bits, word = start + (bit >>> 5), shift = bit & 31;
    data[word] |= value << shift;
    if (shift + bits > 32) data[word + 1] |= value >>> (32 - shift);
  }

  private static final class Builder {
    int[] arena = new int[256], palette = new int[16];
    int[] table = new int[32];
    int used = 1, paletteSize = 1;

    int intern(int value) {
      if (value == 0) return 0;
      int slot = slot(value, table);
      if (table[slot] != 0) return table[slot];
      if (paletteSize * 2 >= table.length) {
        table = new int[table.length * 2];
        for (int i = 1; i < paletteSize; i++) table[slot(palette[i], table)] = i;
        slot = slot(value, table);
      }
      if (paletteSize == palette.length) palette = Arrays.copyOf(palette, palette.length * 2);
      int id = paletteSize++;
      palette[id] = value;
      table[slot] = id;
      return id;
    }

    private int slot(int value, int[] table) {
      int hash = value * 0x9e3779b9;
      int slot = (hash ^ hash >>> 16) & (table.length - 1);
      while (table[slot] != 0 && palette[table[slot]] != value)
        slot = (slot + 1) & (table.length - 1);
      return slot;
    }

    int alloc(int count) {
      int ptr = used;
      used = Math.addExact(used, count);
      if (used > arena.length) arena = Arrays.copyOf(arena, Math.max(used, arena.length * 2));
      return ptr;
    }

    int build(int x, int y, int z, int s, Sampler sampler) {
      checkpoint();
      if (s <= 4) {
        int[] values = new int[s * s * s];
        for (int yy = 0; yy < s; yy++)
          for (int zz = 0; zz < s; zz++)
            for (int xx = 0; xx < s; xx++) {
              int v = sampler.sample(x + xx, y + yy, z + zz);
              if (v < 0) throw new IllegalArgumentException("Negative runtime type");
              values[(yy * s + zz) * s + xx] = v;
            }
        return leaf(values);
      }
      int h = s / 2;
      int[] children = new int[8];
      for (int i = 0; i < 8; i++)
        children[i] =
            build(
                x + ((i & 1) != 0 ? h : 0),
                y + ((i & 2) != 0 ? h : 0),
                z + ((i & 4) != 0 ? h : 0),
                h,
                sampler);
      return branch(children);
    }

    int branch(int[] children) {
      boolean uniform = children[0] <= 0;
      for (int i = 1; i < 8; i++) uniform &= children[i] == children[0];
      if (uniform) return children[0];
      int ptr = alloc(9);
      arena[ptr] = BRANCH;
      System.arraycopy(children, 0, arena, ptr + 1, 8);
      return ptr;
    }

    int leaf(int[] values) {
      boolean same = true;
      for (int value : values) same &= value == values[0];
      if (same) return -intern(values[0]);
      int[] local = values.clone();
      Arrays.sort(local);
      int count = 0;
      for (int v : local) if (count == 0 || local[count - 1] != v) local[count++] = v;
      if (count == 1) return -intern(local[0]);
      int bits = bits(count), words = words(values.length, bits);
      if (2 + count + words >= 1 + values.length) {
        int ptr = alloc(1 + values.length);
        arena[ptr] = DENSE;
        // Intern once per distinct type, not once per voxel (important for large runtime IDs).
        int[] mapping = new int[count];
        for (int i = 0; i < count; i++) mapping[i] = intern(local[i]);
        for (int i = 0; i < values.length; i++)
          arena[ptr + 1 + i] = mapping[Arrays.binarySearch(local, 0, count, values[i])];
        return ptr;
      }
      int ptr = alloc(2 + count + words);
      arena[ptr] = PACKED;
      arena[ptr + 1] = count;
      for (int i = 0; i < count; i++) arena[ptr + 2 + i] = intern(local[i]);
      for (int i = 0; i < values.length; i++)
        pack(arena, ptr + 2 + count, i, bits, Arrays.binarySearch(local, 0, count, values[i]));
      return ptr;
    }

    VoxelChunk finish(ChunkPos p, int size, int root) {
      // All nonuniform children remain reachable; uniform coalescence allocates no child nodes.
      return new VoxelChunk(
          p,
          size,
          root,
          root <= 0 ? new int[0] : Arrays.copyOf(arena, used),
          Arrays.copyOf(palette, paletteSize));
    }
  }

  /** Writes the legacy v1 exact tree encoding. Mapping results must be stable and nonnegative. */
  public void writeTree(DataOutput out, IntUnaryOperator map) throws IOException {
    writeTree(out, map, false);
  }

  /** Writes the v2 exact tree encoding, choosing compact per-leaf palettes when smaller. */
  public void writePackedTree(DataOutput out, IntUnaryOperator map) throws IOException {
    writeTree(out, map, true);
  }

  private void writeTree(DataOutput out, IntUnaryOperator map, boolean packed) throws IOException {
    Objects.requireNonNull(out);
    Objects.requireNonNull(map);
    if (map.applyAsInt(0) != 0) throw new IOException("EMPTY must map to zero");
    write(root, size, out, map, packed);
  }

  private void write(int ptr, int s, DataOutput out, IntUnaryOperator map, boolean packed)
      throws IOException {
    checkpoint();
    if (ptr <= 0) {
      int value = ptr == 0 ? 0 : map.applyAsInt(palette[-ptr]);
      if (value < 0) throw new IOException("Negative mapped type");
      out.writeByte(value == 0 ? 0 : 1);
      if (value != 0) out.writeInt(value);
    } else if (arena[ptr] == BRANCH) {
      out.writeByte(2);
      for (int i = 0; i < 8; i++) write(arena[ptr + 1 + i], s / 2, out, map, packed);
    } else {
      int[] values = new int[s * s * s];
      for (int i = 0; i < values.length; i++) {
        values[i] = map.applyAsInt(leafValue(ptr, i));
        if (values[i] < 0) throw new IOException("Negative mapped type");
      }
      int[] local = values.clone();
      Arrays.sort(local);
      int count = 0;
      for (int value : local) if (count == 0 || local[count - 1] != value) local[count++] = value;
      if (count == 1) {
        out.writeByte(local[0] == 0 ? 0 : 1);
        if (local[0] != 0) out.writeInt(local[0]);
      } else if (packed
          && 2 + 4 * count + 4 * words(values.length, bits(count)) < 1 + 4 * values.length) {
        out.writeByte(4);
        out.writeByte(count);
        for (int i = 0; i < count; i++) out.writeInt(local[i]);
        int[] data = new int[words(values.length, bits(count))];
        for (int i = 0; i < values.length; i++)
          pack(data, 0, i, bits(count), Arrays.binarySearch(local, 0, count, values[i]));
        for (int value : data) out.writeInt(value);
      } else {
        out.writeByte(3);
        for (int value : values) out.writeInt(value);
      }
    }
  }

  /**
   * Reads legacy and packed trees; all decoded values are repacked into private compact storage.
   */
  public static VoxelChunk readTree(ChunkPos p, int size, DataInput in, IntUnaryOperator map)
      throws IOException {
    return readTree(p, size, in, map, true);
  }

  static VoxelChunk readTree(
      ChunkPos p, int size, DataInput in, IntUnaryOperator map, boolean packed) throws IOException {
    validateSize(size);
    Objects.requireNonNull(p);
    Objects.requireNonNull(in);
    Objects.requireNonNull(map);
    if (map.applyAsInt(0) != 0) throw new IOException("EMPTY must map to zero");
    var b = new Builder();
    int root = read(size, in, map, b, packed);
    return b.finish(p, size, root);
  }

  private static int read(int s, DataInput in, IntUnaryOperator map, Builder b, boolean packed)
      throws IOException {
    checkpoint();
    int kind = in.readUnsignedByte();
    if (kind == 0) return 0;
    if (kind == 1) {
      int raw = in.readInt();
      if (raw <= 0) throw new IOException("Uniform dictionary type must be occupied");
      int value = map.applyAsInt(raw);
      if (value < 0) throw new IOException("Negative mapped type");
      return -b.intern(value);
    }
    if (kind == 2) {
      if (s <= 4) throw new IOException("Branch below packed leaf");
      int[] children = new int[8];
      for (int i = 0; i < 8; i++) children[i] = read(s / 2, in, map, b, packed);
      return b.branch(children);
    }
    if (kind != 3 && (kind != 4 || !packed)) throw new IOException("Unknown tree node " + kind);
    if (s > 4) throw new IOException("Oversized packed leaf");
    int cells = s * s * s;
    int[] values = new int[cells];
    if (kind == 3) {
      for (int i = 0; i < cells; i++) {
        int raw = in.readInt();
        if (raw < 0) throw new IOException("Negative dictionary type");
        values[i] = map.applyAsInt(raw);
      }
    } else {
      int count = in.readUnsignedByte();
      if (count < 2 || count > cells) throw new IOException("Invalid leaf palette size");
      int[] local = new int[count];
      for (int i = 0; i < count; i++) {
        local[i] = in.readInt();
        if (local[i] < 0 || i > 0 && local[i] <= local[i - 1])
          throw new IOException("Invalid leaf palette order");
      }
      int bits = bits(count);
      int[] data = new int[words(cells, bits)];
      for (int i = 0; i < data.length; i++) data[i] = in.readInt();
      int usedBits = cells * bits & 31;
      if (usedBits != 0 && data[data.length - 1] >>> usedBits != 0)
        throw new IOException("Nonzero packed padding");
      long seen = 0;
      for (int i = 0; i < cells; i++) {
        int index = unpack(data, 0, i, bits);
        if (index >= count) throw new IOException("Leaf palette index out of bounds");
        seen |= 1L << index;
        values[i] = index;
      }
      if (Long.bitCount(seen) != count) throw new IOException("Unused leaf palette entry");
      for (int i = 0; i < count; i++) local[i] = map.applyAsInt(local[i]);
      for (int i = 0; i < cells; i++) values[i] = local[values[i]];
    }
    for (int value : values) if (value < 0) throw new IOException("Negative mapped type");
    return b.leaf(values);
  }
}
