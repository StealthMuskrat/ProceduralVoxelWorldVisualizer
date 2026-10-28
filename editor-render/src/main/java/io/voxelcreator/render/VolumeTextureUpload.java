package io.voxelcreator.render;

import java.nio.FloatBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;

/** Render-thread-owned, bounded texture replacement; only a complete candidate becomes resident. */
public final class VolumeTextureUpload implements AutoCloseable {
  public static final int STAGING_BYTES = 65536;

  public interface Backend {
    int allocate(int size);

    void write(
        int texture, int x, int y, int z, int width, int height, int depth, FloatBuffer values);

    default boolean supportsCopies() {
      return false;
    }

    default void copy(
        int source,
        int sx,
        int sy,
        int sz,
        int destination,
        int dx,
        int dy,
        int dz,
        int width,
        int height) {
      throw new UnsupportedOperationException("Volume texture copies unavailable");
    }

    void delete(int texture);
  }

  /**
   * The source key identifies one immutable scalar program; spacing and grid phase also gate reuse.
   */
  public record Region(String source, int x, int y, int z, int sampleStep) {
    public Region(String source, int x, int y, int z) {
      this(source, x, y, z, 1);
    }

    public Region {
      VolumeData.validateSampleStep(sampleStep);
      if (source == null || source.isBlank())
        throw new IllegalArgumentException("Volume source identity");
    }

    private void validate(int side) {
      for (int origin : new int[] {x, y, z})
        if ((long) origin + (long) side * sampleStep > (long) Integer.MAX_VALUE + 1)
          throw new IllegalArgumentException("Volume region exceeds signed block coordinates");
    }
  }

  public record Resident(
      int texture, int size, VolumeData.Encoding encoding, Region region, int sampleStep) {
    public Resident(int texture, int size, VolumeData.Encoding encoding, Region region) {
      this(texture, size, encoding, region, region == null ? 1 : region.sampleStep);
    }

    public Resident(int texture, int size, VolumeData.Encoding encoding) {
      this(texture, size, encoding, null, 1);
    }

    public Resident {
      VolumeData.validateSampleStep(sampleStep);
      if (region != null && region.sampleStep != sampleStep)
        throw new IllegalArgumentException("Volume resident spacing mismatch");
    }

    public int extent() {
      return size * sampleStep;
    }

    public long bytes() {
      return 4L * size * size * size;
    }
  }

  /** Uploaded/last bytes count all destination writes, including resident GPU copies. */
  public record Statistics(
      long activeBytes,
      long pendingBytes,
      long remainingBytes,
      long cpuBytes,
      long uploadedBytes,
      int lastBytes,
      long version,
      boolean pending,
      long copiedBytes,
      int lastCopiedBytes) {
    public Statistics(
        long activeBytes,
        long pendingBytes,
        long remainingBytes,
        long cpuBytes,
        long uploadedBytes,
        int lastBytes,
        long version,
        boolean pending) {
      this(
          activeBytes,
          pendingBytes,
          remainingBytes,
          cpuBytes,
          uploadedBytes,
          lastBytes,
          version,
          pending,
          0,
          0);
    }

    public long hostBytes() {
      return uploadedBytes - copiedBytes;
    }

    public int lastHostBytes() {
      return lastBytes - lastCopiedBytes;
    }
  }

  private record Box(int x, int y, int z, int width, int height, int depth, boolean copy) {
    int count() {
      return width * height * depth;
    }
  }

  private final Backend backend;
  private FloatBuffer scratch = BufferUtils.createFloatBuffer(STAGING_BYTES / Float.BYTES);
  private Resident active, candidate;
  private VolumeData data;
  private List<Box> plan = List.of();
  private int cursor, operation, offset, lastBytes, lastCopied;
  private long uploaded, copied, version;
  private boolean closed;

  public VolumeTextureUpload(Backend backend) {
    this.backend = Objects.requireNonNull(backend);
  }

  public boolean stage(VolumeData next, long gpuBudget) {
    return stage(next, gpuBudget, null);
  }

  /**
   * Rejection leaves resident/candidate intact. Only compatible, explicitly identified overlap is
   * reused.
   */
  public boolean stage(VolumeData next, long gpuBudget, Region region) {
    ensureOpen();
    Objects.requireNonNull(next);
    if (gpuBudget < 0) throw new IllegalArgumentException("Negative volume GPU budget");
    if (region != null) {
      region.validate(next.size());
      if (region.sampleStep != next.sampleStep())
        throw new IllegalArgumentException("Volume source region spacing mismatch");
    }
    long bytes = 4L * next.size() * next.size() * next.size();
    if (bytes > gpuBudget - (active == null ? 0 : active.bytes())) return false;
    var regions = regions(next, region);
    cancel();
    int texture = backend.allocate(next.size());
    if (texture <= 0) throw new IllegalStateException("Volume texture allocation failed");
    candidate = new Resident(texture, next.size(), next.encoding(), region, next.sampleStep());
    data = next;
    plan = regions;
    return true;
  }

  private List<Box> regions(VolumeData next, Region region) {
    int n = next.size();
    if (active == null
        || region == null
        || active.region() == null
        || !backend.supportsCopies()
        || !region.source.equals(active.region.source)
        || region.sampleStep != active.sampleStep
        || Double.doubleToLongBits(next.encoding().offset())
            != Double.doubleToLongBits(active.encoding.offset())
        || Double.doubleToLongBits(next.encoding().scale())
            != Double.doubleToLongBits(active.encoding.scale()))
      return List.of(new Box(0, 0, 0, n, n, n, false));
    var prior = active.region;
    long dx = (long) prior.x - region.x,
        dy = (long) prior.y - region.y,
        dz = (long) prior.z - region.z;
    int step = next.sampleStep();
    // Equal spacing alone is insufficient: centers must have the same grid phase.
    if (dx % step != 0 || dy % step != 0 || dz % step != 0)
      return List.of(new Box(0, 0, 0, n, n, n, false));
    long px = dx / step, py = dy / step, pz = dz / step;
    if (px >= n
        || px + active.size <= 0
        || py >= n
        || py + active.size <= 0
        || pz >= n
        || pz + active.size <= 0) return List.of(new Box(0, 0, 0, n, n, n, false));
    int x0 = (int) Math.max(0L, px), y0 = (int) Math.max(0L, py), z0 = (int) Math.max(0L, pz);
    int x1 = (int) Math.min(n, px + active.size),
        y1 = (int) Math.min(n, py + active.size),
        z1 = (int) Math.min(n, pz + active.size);
    var result = new ArrayList<Box>(7);
    add(result, x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, true);
    // Six disjoint slabs cover the complement; no destination texel is written twice.
    add(result, 0, 0, 0, x0, n, n, false);
    add(result, x1, 0, 0, n - x1, n, n, false);
    add(result, x0, 0, 0, x1 - x0, y0, n, false);
    add(result, x0, y1, 0, x1 - x0, n - y1, n, false);
    add(result, x0, y0, 0, x1 - x0, y1 - y0, z0, false);
    add(result, x0, y0, z1, x1 - x0, y1 - y0, n - z1, false);
    return List.copyOf(result);
  }

  private static void add(List<Box> boxes, int x, int y, int z, int w, int h, int d, boolean copy) {
    if (w > 0 && h > 0 && d > 0) boxes.add(new Box(x, y, z, w, h, d, copy));
  }

  /**
   * Host and GPU-copy destination writes share the supplied byte budget, including partial rows.
   */
  public int advance(int byteBudget) {
    ensureOpen();
    if (byteBudget < 0) throw new IllegalArgumentException("Negative volume upload budget");
    lastBytes = lastCopied = 0;
    if (candidate == null) return 0;
    try {
      while (operation < plan.size() && byteBudget - lastBytes >= Float.BYTES) {
        var box = plan.get(operation);
        int available = Math.min(scratch.capacity(), (byteBudget - lastBytes) / Float.BYTES);
        int plane = box.width * box.height;
        int lx = offset % box.width, ly = offset / box.width % box.height, lz = offset / plane;
        int w, h = 1, d = 1;
        if (!box.copy && lx == 0 && ly == 0 && available >= plane) {
          w = box.width;
          h = box.height;
          d = Math.min(box.depth - lz, available / plane);
        } else if (lx == 0 && available >= box.width) {
          w = box.width;
          h = Math.min(box.height - ly, available / box.width);
        } else w = Math.min(box.width - lx, available);
        int count = w * h * d, x = box.x + lx, y = box.y + ly, z = box.z + lz;
        if (box.copy) {
          var from = active.region;
          var to = candidate.region;
          backend.copy(
              active.texture,
              (int) (((long) to.x - from.x) / to.sampleStep + x),
              (int) (((long) to.y - from.y) / to.sampleStep + y),
              (int) (((long) to.z - from.z) / to.sampleStep + z),
              candidate.texture,
              x,
              y,
              z,
              w,
              h);
          copied += count * Float.BYTES;
          lastCopied += count * Float.BYTES;
        } else {
          scratch.clear().limit(count);
          data.copyRegion(x, y, z, w, h, d, scratch);
          scratch.flip();
          backend.write(candidate.texture, x, y, z, w, h, d, scratch);
        }
        cursor += count;
        offset += count;
        lastBytes += count * Float.BYTES;
        uploaded += count * Float.BYTES;
        if (offset == box.count()) {
          offset = 0;
          operation++;
        }
      }
      if (operation == plan.size()) {
        var previous = active;
        active = candidate;
        candidate = null;
        data = null;
        plan = List.of();
        cursor = operation = offset = 0;
        version++;
        if (previous != null) backend.delete(previous.texture());
      }
      return lastBytes;
    } catch (RuntimeException | Error failure) {
      int written = lastBytes, reused = lastCopied;
      cancel();
      lastBytes = written;
      lastCopied = reused;
      throw failure;
    }
  }

  public Resident resident() {
    return active;
  }

  public Statistics statistics() {
    long bytes = candidate == null ? 0 : candidate.bytes();
    return new Statistics(
        active == null ? 0 : active.bytes(),
        bytes,
        bytes - 4L * cursor,
        closed ? 0 : STAGING_BYTES + bytes,
        uploaded,
        lastBytes,
        version,
        candidate != null,
        copied,
        lastCopied);
  }

  public void cancel() {
    var abandoned = candidate;
    candidate = null;
    data = null;
    plan = List.of();
    cursor = operation = offset = lastBytes = lastCopied = 0;
    if (abandoned != null) backend.delete(abandoned.texture());
  }

  public void clear() {
    cancel();
    var previous = active;
    active = null;
    if (previous != null) backend.delete(previous.texture());
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Volume uploader closed");
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    clear();
    scratch = null;
  }
}
