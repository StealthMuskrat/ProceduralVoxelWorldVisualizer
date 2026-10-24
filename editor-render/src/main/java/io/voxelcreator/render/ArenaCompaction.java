package io.voxelcreator.render;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * Render-independent relocation cursor. Copy one bounded range, then acknowledge it. Drawing the
 * completed prefix at its destination and remaining suffix at its source preserves all primitives.
 */
public final class ArenaCompaction {
  public static final int MAX_COPY_BYTES = 64 * 1024;

  public record Copy(int sourceOffset, int targetOffset, int bytes) {}

  public record Segment(int offset, int bytes) {}

  private final ArenaAllocator.Layout layout;
  private final List<ArenaAllocator.Move> moves;
  private final Map<ArenaAllocator.Slice, Integer> indices = new HashMap<>();
  private final int[] progress, quantum;
  private final long total;
  private int index;
  private long copied;
  private Copy issued;

  public ArenaCompaction(
      ArenaAllocator.Layout layout, ToIntFunction<ArenaAllocator.Slice> primitiveBytes) {
    this.layout = Objects.requireNonNull(layout);
    moves = layout.moves();
    progress = new int[moves.size()];
    quantum = new int[moves.size()];
    long bytes = 0;
    for (int i = 0; i < moves.size(); i++) {
      var move = moves.get(i);
      indices.put(move.source(), i);
      int q = primitiveBytes.applyAsInt(move.source());
      if (q != 16 && q != 48)
        throw new IllegalArgumentException("Packed points or triangles required");
      quantum[i] = q;
      bytes += move.source().length();
    }
    total = bytes;
  }

  public ArenaAllocator.Layout layout() {
    return layout;
  }

  public boolean complete() {
    return index == moves.size();
  }

  public long copiedBytes() {
    return copied;
  }

  public long remainingBytes() {
    return total - copied;
  }

  /**
   * No progress when the budget cannot hold the next complete primitive (or final partial tail).
   */
  public Copy next(int byteBudget) {
    if (byteBudget < 0) throw new IllegalArgumentException("Negative copy budget");
    if (issued != null) throw new IllegalStateException("Acknowledge the issued copy first");
    if (complete()) return null;
    var move = moves.get(index);
    int remaining = move.source().length() - progress[index];
    int limit = Math.min(byteBudget, MAX_COPY_BYTES);
    int bytes = remaining <= limit ? remaining : limit / quantum[index] * quantum[index];
    if (bytes == 0) return null;
    issued =
        new Copy(
            move.source().offset() + progress[index],
            move.target().offset() + progress[index],
            bytes);
    return issued;
  }

  /** A failed read/copy must not acknowledge or change the draw ranges. */
  public void copied(Copy copy) {
    if (copy == null || issued != copy)
      throw new IllegalArgumentException("Unknown copy acknowledgement");
    progress[index] += copy.bytes();
    copied += copy.bytes();
    issued = null;
    if (progress[index] == moves.get(index).source().length()) index++;
  }

  public void retry() {
    issued = null;
  }

  public List<Segment> segments(ArenaAllocator.Slice source) {
    if (source.length() == 0) return List.of();
    Integer i = indices.get(source);
    if (i == null) return List.of(new Segment(source.offset(), source.length()));
    var move = moves.get(i);
    int prefix = progress[i];
    if (prefix == 0) return List.of(new Segment(source.offset(), source.length()));
    if (prefix == source.length()) return List.of(new Segment(move.target().offset(), prefix));
    return List.of(
        new Segment(move.target().offset(), prefix),
        new Segment(source.offset() + prefix, source.length() - prefix));
  }
}
