package io.voxelcreator.editor;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** UI-owned bounded reuse; keys contain values, never mutable curve or point references. */
final class CurveCompilationCache {
  static final int MAX_ENTRIES = 8;
  static final long MAX_BYTES = 2L << 20;

  private record Knot(
      UUID id, double x, double y, double in, double out, String segment, String handles) {
    static Knot capture(Point point) {
      return new Knot(
          point.uuid,
          point.x,
          point.y,
          point.inTangent,
          point.outTangent,
          point.segmentInterpolation,
          point.handleMode);
    }
  }

  private record Key(
      UUID id,
      double min,
      double max,
      double displayMin,
      double displayMax,
      String outside,
      List<Knot> knots) {
    static Key capture(Curve curve) {
      return new Key(
          curve.uuid,
          curve.min,
          curve.max,
          curve.displayMin,
          curve.displayMax,
          curve.outsideDomain,
          curve.points.stream().map(Knot::capture).toList());
    }

    long bytes() {
      long size = 512L + 2L * outside.length();
      for (var knot : knots)
        size += 256L + 2L * (knot.segment().length() + knot.handles().length());
      return size;
    }
  }

  private final LinkedHashMap<Key, ScalarCurve> entries = new LinkedHashMap<>(16, .75f, true);
  private long bytes, builds;

  ScalarCurve get(Curve curve) {
    Key key;
    try {
      key = Key.capture(curve);
    } catch (RuntimeException malformed) {
      // Preserve the canonical constructor's validation order and diagnostics for malformed input.
      return compile(curve);
    }
    var cached = entries.get(key);
    if (cached != null) return cached;
    var compiled = compile(curve);
    long size = key.bytes();
    if (size <= MAX_BYTES) {
      entries.put(key, compiled);
      bytes += size;
      while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
        var first = entries.keySet().iterator().next();
        entries.remove(first);
        bytes -= first.bytes();
      }
    }
    return compiled;
  }

  private ScalarCurve compile(Curve curve) {
    builds++;
    return new ScalarCurve(curve);
  }

  long builds() {
    return builds;
  }

  int entries() {
    return entries.size();
  }

  long bytes() {
    return bytes;
  }

  void clear() {
    entries.clear();
    bytes = 0;
  }
}
