package io.voxelcreator.render;

import java.util.*;

/** First-fit, aligned fixed-budget arena with exact free-range coalescing. No OpenGL dependency. */
public final class ArenaAllocator {
  public record Slice(int offset, int length) {}

  private final int capacity;
  private final TreeMap<Integer, Integer> free = new TreeMap<>();
  private final Map<Integer, Integer> used = new HashMap<>();

  public ArenaAllocator(int capacity) {
    if (capacity < 16 || capacity % 16 != 0)
      throw new IllegalArgumentException("16-byte arena alignment");
    this.capacity = capacity;
    free.put(0, capacity);
  }

  public Slice allocate(int bytes) {
    if (bytes < 0 || bytes % 16 != 0) throw new IllegalArgumentException("Vertex alignment");
    if (bytes == 0) return new Slice(0, 0);
    for (var entry : free.entrySet())
      if (entry.getValue() >= bytes) {
        int start = entry.getKey(), length = entry.getValue();
        free.remove(start);
        if (length > bytes) free.put(start + bytes, length - bytes);
        used.put(start, bytes);
        return new Slice(start, bytes);
      }
    return null;
  }

  public void release(Slice slice) {
    if (slice.length == 0) return;
    Integer length = used.remove(slice.offset);
    if (length == null || length != slice.length)
      throw new IllegalArgumentException("Unknown arena slice");
    int start = slice.offset;
    var lo = free.lowerEntry(start);
    if (lo != null && lo.getKey() + lo.getValue() == start) {
      start = lo.getKey();
      length += lo.getValue();
      free.remove(lo.getKey());
    }
    var hi = free.ceilingEntry(start);
    if (hi != null && start + length == hi.getKey()) {
      length += hi.getValue();
      free.remove(hi.getKey());
    }
    free.put(start, length);
  }

  public long usedBytes() {
    return used.values().stream().mapToLong(Integer::longValue).sum();
  }

  public int capacity() {
    return capacity;
  }

  public int largestFreeRange() {
    return free.values().stream().mapToInt(Integer::intValue).max().orElse(0);
  }
}
