package io.voxelcreator.api;

import java.util.*;

/** Queue-only counters: inline work and executor startup tasks do not pass through the queue. */
public record SchedulerStats(
    int queued,
    int capacity,
    int queuedScopes,
    Map<GenerationPriority, Integer> byPriority,
    long dequeued,
    long removed,
    long rejected,
    long promotions) {
  public SchedulerStats {
    var copy = new EnumMap<GenerationPriority, Integer>(GenerationPriority.class);
    copy.putAll(byPriority);
    byPriority = Collections.unmodifiableMap(copy);
  }
}
