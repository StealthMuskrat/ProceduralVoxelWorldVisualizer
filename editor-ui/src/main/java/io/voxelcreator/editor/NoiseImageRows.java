package io.voxelcreator.editor;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Bounded exact-coordinate row reuse. Source keys must identify an immutable sampling program. */
public final class NoiseImageRows implements AutoCloseable {
  public static final long MAX_BYTES = 4L << 20;

  public record Statistics(
      int sampledRows,
      int reusedRows,
      int retainedRows,
      long retainedBytes,
      long limitBytes,
      int sampledValues,
      int reusedValues) {
    public Statistics(
        int sampledRows, int reusedRows, int retainedRows, long retainedBytes, long limitBytes) {
      this(sampledRows, reusedRows, retainedRows, retainedBytes, limitBytes, 0, 0);
    }
  }

  public record Memory(String source, int rows, long bytes, long limitBytes) {}

  public static final class Image {
    private final int side;
    private final double[] values;
    private final Statistics statistics;

    private Image(int side, double[] values, Statistics statistics) {
      this.side = side;
      this.values = values;
      this.statistics = statistics;
    }

    public int side() {
      return side;
    }

    public double value(int x, int z) {
      if (x < 0 || z < 0 || x >= side || z >= side) throw new IndexOutOfBoundsException();
      return values[z * side + x];
    }

    public double[] copyValues() {
      return values.clone();
    }

    public Statistics statistics() {
      return statistics;
    }
  }

  private static final class Axis {
    final double[] values;
    final int hash;

    Axis(double[] values) {
      this.values = values;
      hash = Arrays.hashCode(values);
    }

    public int hashCode() {
      return hash;
    }

    public boolean equals(Object other) {
      return other instanceof Axis a && Arrays.equals(values, a.values);
    }
  }

  private record Key(Axis x, long z) {}

  private record Row(double[] values, long weight) {}

  private record Candidate(Key key, Row row) {}

  /**
   * Owned references to immutable rows; eviction/source changes cannot mutate these observations.
   */
  private synchronized List<Candidate> candidates(long lease, long z) {
    if (lease != generation || closed) return List.of();
    var result = new ArrayList<Candidate>();
    for (var entry : rows.entrySet())
      if (entry.getKey().z == z) result.add(new Candidate(entry.getKey(), entry.getValue()));
    return result;
  }

  private synchronized void touch(long lease, Key key) {
    if (lease == generation && !closed) rows.get(key);
  }

  private LinkedHashMap<Key, Row> rows = new LinkedHashMap<>(16, .75f, true);
  private String source;
  private long latest = Long.MIN_VALUE, generation, bytes, limit;
  private long ceiling = MAX_BYTES;
  private volatile boolean closed;

  public static long rowBytes(int side) {
    return 512L + 16L * side;
  }

  public synchronized Memory memory() {
    return new Memory(source, rows.size(), bytes, limit);
  }

  public synchronized void budget(long budget) {
    if (budget < 0) throw new IllegalArgumentException("Negative image cache budget");
    if (closed) return;
    ceiling = Math.min(MAX_BYTES, budget);
    limit = Math.min(limit, ceiling);
    trim();
  }

  private synchronized long begin(String key, long version, long budget, boolean force) {
    if (closed) throw new CancellationException("Image cache closed");
    if (version < latest) return -1;
    latest = Math.max(latest, version);
    if (!Objects.equals(source, key) || force) {
      rows = new LinkedHashMap<>(16, .75f, true);
      bytes = 0;
      source = key;
      generation++;
    }
    limit = Math.min(ceiling, budget);
    trim();
    return generation;
  }

  private synchronized Row get(long lease, Key key) {
    return lease == generation && !closed ? rows.get(key) : null;
  }

  private synchronized void put(long lease, Key key, Row row) {
    if (lease != generation || closed || row.weight > limit) return;
    var old = rows.put(key, row);
    if (old != null) bytes -= old.weight;
    bytes += row.weight;
    trim();
  }

  private void trim() {
    boolean removed = false;
    var iterator = rows.entrySet().iterator();
    while (bytes > limit && iterator.hasNext()) {
      bytes -= iterator.next().getValue().weight;
      iterator.remove();
      removed = true;
    }
    if (removed) {
      var compact = new LinkedHashMap<Key, Row>(Math.max(16, rows.size() * 2), .75f, true);
      compact.putAll(rows);
      rows = compact;
    }
  }

  private void check(CancellationToken token) {
    token.throwIfCancelled();
    if (closed || Thread.currentThread().isInterrupted())
      throw new CancellationException("Image sampling cancelled");
  }

  public Image sample(
      String key,
      long version,
      NoiseAlgorithms.Field field,
      int side,
      double panX,
      double panZ,
      double step,
      long budget,
      boolean force,
      CancellationToken token) {
    if (closed) throw new IllegalStateException("Image cache closed");
    Objects.requireNonNull(key);
    Objects.requireNonNull(field);
    Objects.requireNonNull(token);
    if (side < 1
        || side > 256
        || !Double.isFinite(panX)
        || !Double.isFinite(panZ)
        || !Double.isFinite(step)
        || step <= 0
        || budget < 0) throw new IllegalArgumentException("Invalid 2D image grid or cache budget");
    check(token);
    double[] xs = new double[side], zs = new double[side];
    for (int i = 0; i < side; i++) {
      xs[i] = panX + (i - side / 2.) * step;
      zs[i] = panZ + (i - side / 2.) * step;
      if (!Double.isFinite(xs[i]) || !Double.isFinite(zs[i]))
        throw new IllegalArgumentException("Nonfinite 2D image coordinate");
    }
    var axis = new Axis(xs);
    long lease = begin(key, version, budget, force);
    double[] values = new double[side * side];
    int sampled = 0, reused = 0, sampledValues = 0, reusedValues = 0;
    for (int z = 0; z < side; z++) {
      check(token);
      var rowKey = new Key(axis, Double.doubleToLongBits(zs[z]));
      var row = get(lease, rowKey);
      if (row == null) {
        double[] data = new double[side];
        boolean[] found = new boolean[side];
        int copied = 0;
        // Positive steps produce a sorted X axis, including repeated representable coordinates.
        // Search exact doubles only: nearby coordinates must still execute the scalar program.
        for (var candidate : candidates(lease, rowKey.z)) {
          check(token);
          boolean used = false;
          for (int x = 0; x < side; x++) {
            if ((x & 31) == 0) check(token);
            if (found[x]) continue;
            int index = Arrays.binarySearch(candidate.key.x.values, xs[x]);
            if (index >= 0) {
              data[x] = candidate.row.values[index];
              found[x] = true;
              copied++;
              used = true;
            }
          }
          if (used) touch(lease, candidate.key);
          if (copied == side) break;
        }
        for (int x = 0; x < side; x++) {
          if ((x & 31) == 0) check(token);
          if (found[x]) continue;
          double value = field.sample(xs[x], 0, zs[z]);
          if (!Double.isFinite(value))
            throw new ArithmeticException("Nonfinite 2D preview sample at " + xs[x] + "," + zs[z]);
          data[x] = value;
        }
        check(token);
        row = new Row(data, rowBytes(side));
        put(lease, rowKey, row);
        sampledValues += side - copied;
        reusedValues += copied;
        if (copied == side) reused++;
        else sampled++;
      } else {
        reused++;
        reusedValues += side;
      }
      System.arraycopy(row.values, 0, values, z * side, side);
    }
    check(token);
    var memory = memory();
    return new Image(
        side,
        values,
        new Statistics(
            sampled,
            reused,
            memory.rows,
            memory.bytes,
            memory.limitBytes,
            sampledValues,
            reusedValues));
  }

  @Override
  public synchronized void close() {
    closed = true;
    generation++;
    rows = new LinkedHashMap<>(16, .75f, true);
    bytes = 0;
    source = null;
  }
}
