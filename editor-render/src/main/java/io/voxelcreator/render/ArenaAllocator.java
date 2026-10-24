package io.voxelcreator.render;

import java.util.*;

/** First-fit, aligned fixed-budget arena with exact free-range coalescing. No OpenGL dependency. */
public final class ArenaAllocator {
  public record Slice(int offset, int length) {}

  private final int capacity;
  private Layout active;
  private final TreeMap<Integer, Integer> free = new TreeMap<>();
  private final Map<Integer, Integer> used = new HashMap<>();

  public ArenaAllocator(int capacity) {
    if (capacity < 16 || capacity % 16 != 0)
      throw new IllegalArgumentException("16-byte arena alignment");
    this.capacity = capacity;
    free.put(0, capacity);
  }

  public Slice allocate(int bytes) {
    if (active != null) throw new IllegalStateException("Arena layout is being compacted");
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
    if (active != null) throw new IllegalStateException("Arena layout is being compacted");
    if (slice.length == 0) return;
    Integer length = used.get(slice.offset);
    if (length == null || length != slice.length)
      throw new IllegalArgumentException("Unknown arena slice");
    used.remove(slice.offset);
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

  /** Frozen relocation layout. Destinations preserve allocation order and only move downward. */
  public static final class Layout {
    private final Map<Slice, Slice> targets;
    private final List<Move> moves;

    private Layout(Map<Slice, Slice> targets, List<Move> moves) {
      this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
      this.moves = List.copyOf(moves);
    }

    public List<Move> moves() {
      return moves;
    }

    public Slice target(Slice source) {
      if (source.length() == 0) return source;
      return Objects.requireNonNull(targets.get(source), "Unknown relocation source");
    }
  }

  public record Move(Slice source, Slice target) {}

  /**
   * Returns null when the used prefix is already packed. Allocations/releases freeze until
   * commit/reset.
   */
  public Layout beginCompaction() {
    if (active != null) throw new IllegalStateException("Compaction already active");
    var targets = new LinkedHashMap<Slice, Slice>();
    var moves = new ArrayList<Move>();
    int next = 0;
    for (var entry : new TreeMap<>(used).entrySet()) {
      var source = new Slice(entry.getKey(), entry.getValue());
      var target = new Slice(next, entry.getValue());
      targets.put(source, target);
      if (source.offset() != next) moves.add(new Move(source, target));
      next += entry.getValue();
    }
    if (moves.isEmpty()) return null;
    active = new Layout(targets, moves);
    return active;
  }

  /**
   * Caller commits only after all planned copies completed. Old slice addresses become obsolete.
   */
  public void commitCompaction(Layout layout) {
    if (layout == null || active != layout)
      throw new IllegalArgumentException("Obsolete compaction layout");
    used.clear();
    free.clear();
    int end = 0;
    for (var target : layout.targets.values()) {
      used.put(target.offset(), target.length());
      end = target.offset() + target.length();
    }
    if (end < capacity) free.put(end, capacity - end);
    active = null;
  }

  /** Epoch replacement discards every allocation, including an unfinished relocation. */
  public void reset() {
    active = null;
    used.clear();
    free.clear();
    free.put(0, capacity);
  }

  public long freeBytes() {
    return capacity - usedBytes();
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
