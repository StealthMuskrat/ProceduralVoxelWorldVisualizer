package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Owned, bounded canonical scalar chunks assembled into complete camera-relative texture windows.
 */
public final class VolumeWindows implements AutoCloseable {
  public static final int CHUNK = 32;
  public static final long TILE_BYTES = 8L * CHUNK * CHUNK * CHUNK + 128;
  public static final long MAX_CACHE_BYTES = 64L << 20;

  public record Bounds(int side, int x, int y, int z) {
    public Bounds {
      if (side < CHUNK || side > 256 || side % CHUNK != 0)
        throw new IllegalArgumentException(
            "Streaming volume extent must be 32..256 in whole chunks");
      for (int origin : new int[] {x, y, z})
        if (origin % CHUNK != 0 || (long) origin + side > (long) Integer.MAX_VALUE + 1)
          throw new IllegalArgumentException(
              "Streaming volume bounds exceed signed block coordinates");
    }

    public static Bounds around(double x, double y, double z, int requestedSide) {
      if (requestedSide < 2 || requestedSide > 256)
        throw new IllegalArgumentException("Volume extent 2..256");
      int side = (requestedSide + CHUNK - 1) / CHUNK * CHUNK;
      return new Bounds(side, origin(x, side), origin(y, side), origin(z, side));
    }

    private static int origin(double center, int side) {
      if (!Double.isFinite(center)
          || center < Integer.MIN_VALUE
          || center >= (double) Integer.MAX_VALUE + 1)
        throw new IllegalArgumentException("Volume camera outside signed block coordinates");
      long start = ((long) Math.floor(center / CHUNK) - side / CHUNK / 2) * CHUNK;
      return (int)
          Math.max(Integer.MIN_VALUE, Math.min((long) Integer.MAX_VALUE + 1 - side, start));
    }
  }

  public record Lease(long generation, long request) {}

  public record Statistics(
      int sampledChunks, int reusedChunks, long sampledValues, long generation) {}

  public record Window(Bounds bounds, VolumeData volume, Field field, Statistics statistics) {}

  public record Memory(
      long generation, long bytes, long limit, int chunks, long workingBytes, long compilations) {}

  private record Key(int x, int y, int z) {}

  private record Tile(double[] values, double min, double max) {}

  private final LinkedHashMap<Key, Tile> tiles = new LinkedHashMap<>(16, .75f, true);
  // Cancellation of a running field is cooperative. Successors wait before allocating another
  // window.
  private final Semaphore worker = new Semaphore(1);
  private String source;
  private Field program;
  private long generation, latest = Long.MIN_VALUE, bytes, limit, working, compilations;
  private boolean closed;

  public static long workingBytes(int side) {
    if (side < CHUNK || side > 256 || side % CHUNK != 0)
      throw new IllegalArgumentException("Volume extent");
    return 12L * side * side * side + TILE_BYTES + VolumeTextureUpload.STAGING_BYTES;
  }

  /** UI-thread admission precedes queuing; old workers can never replace this source or lease. */
  public synchronized Lease begin(String key, long request, long budget) {
    Objects.requireNonNull(key);
    if (budget < 0) throw new IllegalArgumentException("Negative volume cache budget");
    if (closed || request < latest) throw new CancellationException("Obsolete volume request");
    latest = request;
    if (!key.equals(source)) {
      tiles.clear();
      bytes = 0;
      program = null;
      source = key;
      generation++;
    }
    limit = Math.min(MAX_CACHE_BYTES, budget);
    trim();
    return new Lease(generation, request);
  }

  public synchronized void budget(long budget) {
    if (budget < 0) throw new IllegalArgumentException("Negative volume cache budget");
    limit = Math.min(MAX_CACHE_BYTES, budget);
    trim();
  }

  public synchronized Memory memory() {
    return new Memory(generation, bytes, limit, tiles.size(), working, compilations);
  }

  public synchronized void clear() {
    if (source == null && tiles.isEmpty() && program == null) return;
    generation++;
    source = null;
    program = null;
    tiles.clear();
    bytes = 0;
  }

  private synchronized boolean current(Lease lease) {
    return !closed && lease.generation == generation && lease.request == latest;
  }

  private void check(Lease lease, CancellationToken token) {
    // Cancellation parents are external callbacks; never invoke them while owning the cache lock.
    token.throwIfCancelled();
    if (!current(lease) || Thread.currentThread().isInterrupted())
      throw new CancellationException("Obsolete volume window");
  }

  private void requireCurrent(Lease lease) {
    if (!current(lease)) throw new CancellationException("Obsolete volume window");
  }

  private void trim() {
    var it = tiles.entrySet().iterator();
    while (bytes > limit && it.hasNext()) {
      it.next();
      it.remove();
      bytes -= TILE_BYTES;
    }
  }

  private Tile get(Lease lease, Key key, CancellationToken token) {
    check(lease, token);
    synchronized (this) {
      requireCurrent(lease);
      return tiles.get(key);
    }
  }

  private void put(Lease lease, Key key, Tile tile, CancellationToken token) {
    check(lease, token);
    synchronized (this) {
      requireCurrent(lease);
      if (TILE_BYTES > limit) return;
      if (tiles.put(key, tile) == null) bytes += TILE_BYTES;
      trim();
    }
  }

  public Window sample(
      Lease lease, Bounds bounds, Supplier<Field> compiler, CancellationToken token) {
    Objects.requireNonNull(lease);
    Objects.requireNonNull(bounds);
    Objects.requireNonNull(compiler);
    Objects.requireNonNull(token);
    check(lease, token);
    try {
      worker.acquire();
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Volume worker interrupted");
    }
    try {
      check(lease, token);
      Field field;
      synchronized (this) {
        working = workingBytes(bounds.side);
        field = program;
      }
      if (field == null) {
        field = Objects.requireNonNull(compiler.get());
        check(lease, token);
        synchronized (this) {
          requireCurrent(lease);
          program = field;
          compilations++;
        }
      }
      int n = bounds.side, sampled = 0, reused = 0;
      double[] raw = new double[n * n * n];
      double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
      // Reuse a complete immutable tile before considering generation; old windows never own cache
      // slots.
      for (int z = 0; z < n; z += CHUNK)
        for (int y = 0; y < n; y += CHUNK)
          for (int x = 0; x < n; x += CHUNK) {
            check(lease, token);
            var key = new Key(bounds.x + x, bounds.y + y, bounds.z + z);
            var tile = get(lease, key, token);
            if (tile == null) {
              double[] values = new double[CHUNK * CHUNK * CHUNK];
              double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
              for (int tz = 0; tz < CHUNK; tz++)
                for (int ty = 0; ty < CHUNK; ty++) {
                  check(lease, token);
                  for (int tx = 0; tx < CHUNK; tx++) {
                    double value =
                        field.sample(
                            (double) key.x + tx + .5,
                            (double) key.y + ty + .5,
                            (double) key.z + tz + .5);
                    if (!Double.isFinite(value))
                      throw new IllegalArgumentException("Nonfinite canonical volume chunk sample");
                    values[(tz * CHUNK + ty) * CHUNK + tx] = value;
                    lo = Math.min(lo, value);
                    hi = Math.max(hi, value);
                  }
                }
              tile = new Tile(values, lo, hi);
              put(lease, key, tile, token);
              sampled++;
            } else reused++;
            min = Math.min(min, tile.min);
            max = Math.max(max, tile.max);
            for (int tz = 0; tz < CHUNK; tz++)
              for (int ty = 0; ty < CHUNK; ty++) {
                check(lease, token);
                System.arraycopy(
                    tile.values,
                    (tz * CHUNK + ty) * CHUNK,
                    raw,
                    ((z + tz) * n + y + ty) * n + x,
                    CHUNK);
              }
          }
      var volume =
          VolumeData.encodeOwned(
              n,
              raw,
              min,
              max,
              new CancellationToken(
                  () ->
                      !current(lease)
                          || token.isCancelled()
                          || Thread.currentThread().isInterrupted()));
      check(lease, token);
      return new Window(
          bounds,
          volume,
          field,
          new Statistics(
              sampled, reused, (long) sampled * CHUNK * CHUNK * CHUNK, lease.generation));
    } finally {
      synchronized (this) {
        working = 0;
      }
      worker.release();
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    clear();
  }
}
