package io.voxelcreator.editor;

import java.util.*;

/** Exact footer values and consecutive native submissions, independently for each trace host. */
final class TraceActionScope {
  record Context(
      Object document,
      long revision,
      TraceSnapshot snapshot,
      int step,
      int row,
      String query,
      int page,
      boolean voxel,
      boolean control,
      boolean pending) {}

  record Ticket(int host, long version, Context context) {}

  private record Presentation(long version, Context context, int frame) {}

  private final Map<Integer, Presentation> hosts = new HashMap<>();
  private long version;

  Ticket synchronize(int host, int frame, Context context) {
    Objects.requireNonNull(context);
    // A skipped native frame retires that host without another host keeping it alive.
    hosts.values().removeIf(p -> frame < p.frame() || frame - p.frame() > 1);
    var prior = hosts.get(host);
    long current = prior != null && prior.context().equals(context) ? prior.version() : ++version;
    hosts.put(host, new Presentation(current, context, frame));
    return new Ticket(host, current, context);
  }

  boolean current(Ticket ticket, Context context) {
    var presentation = hosts.get(ticket.host());
    return presentation != null
        && presentation.version() == ticket.version()
        && presentation.context().equals(context)
        && ticket.context().equals(context);
  }
}
