package io.voxelcreator.core;

import java.util.*;

/** Owned, bounded observations of the same compiled evaluation used by ordinary sampling. */
public record NoiseTrace(
    UUID resourceUuid,
    double x,
    double y,
    double z,
    double value,
    List<Event> events,
    long omitted) {
  public NoiseTrace {
    events = List.copyOf(events);
  }

  public record Event(
      UUID resourceUuid,
      UUID itemUuid,
      String id,
      String phase,
      String kind,
      String type,
      double x,
      double y,
      double z,
      List<Double> values) {
    public Event {
      values = List.copyOf(values);
    }
  }

  public static final int DEFAULT_LIMIT = 256, MAX_LIMIT = 4096;

  static final class Capture {
    final int limit;
    final List<Event> events = new ArrayList<>();
    long omitted;

    Capture(int limit) {
      this.limit = limit;
    }

    void add(
        UUID resource,
        UUID item,
        String id,
        String phase,
        String kind,
        String type,
        double x,
        double y,
        double z,
        double... values) {
      if (events.size() >= limit) {
        if (omitted < Long.MAX_VALUE) omitted++;
        return;
      }
      var copy = new ArrayList<Double>(values.length);
      for (double v : values) copy.add(v);
      events.add(new Event(resource, item, id, phase, kind, type, x, y, z, copy));
    }
  }
}
