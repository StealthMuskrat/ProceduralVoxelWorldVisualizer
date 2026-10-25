package io.voxelcreator.editor;

import java.util.*;

/** Small owned trace payload store, separate from bounded textual event retention. */
public final class TraceHistory {
  private final int capacity;
  private final long budget;
  private final LinkedHashMap<Long, TraceSnapshot> entries = new LinkedHashMap<>();
  private long bytes, evicted;

  public TraceHistory() {
    this(4, 8L << 20);
  }

  public TraceHistory(int capacity, long budget) {
    if (capacity < 1 || budget < 1)
      throw new IllegalArgumentException("Positive trace bounds required");
    this.capacity = capacity;
    this.budget = budget;
  }

  public boolean put(long id, TraceSnapshot trace) {
    Objects.requireNonNull(trace);
    if (trace.retainedBytes() > budget) return false;
    var old = entries.remove(id);
    if (old != null) bytes -= old.retainedBytes();
    entries.put(id, trace);
    bytes += trace.retainedBytes();
    while (entries.size() > capacity || bytes > budget) {
      var key = entries.keySet().iterator().next();
      bytes -= entries.remove(key).retainedBytes();
      evicted++;
    }
    return true;
  }

  public Optional<TraceSnapshot> get(long id) {
    return Optional.ofNullable(entries.get(id));
  }

  public int size() {
    return entries.size();
  }

  public long bytes() {
    return bytes;
  }

  public long evicted() {
    return evicted;
  }

  public void clear() {
    entries.clear();
    bytes = 0;
  }
}
