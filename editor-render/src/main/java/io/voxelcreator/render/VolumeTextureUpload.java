package io.voxelcreator.render;

import java.nio.FloatBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;

/** Render-thread-owned, bounded texture replacement; only a complete candidate becomes resident. */
public final class VolumeTextureUpload implements AutoCloseable {
  public static final int STAGING_BYTES = 65536;

  public interface Backend {
    int allocate(int size);

    /** Cube-only backends remain source compatible and reject unsupported boxes explicitly. */
    default int allocate(GridExtent shape) {
      if (shape.width() != shape.height() || shape.width() != shape.depth())
        throw new UnsupportedOperationException("Rectangular volume allocation unavailable");
      return allocate(shape.width());
    }

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

    /**
     * Retires an owned texture. A thrown failure may precede or follow backend release; detached
     * handles are attempted once rather than replayed against potentially reused texture names.
     */
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

    private void validate(GridExtent shape) {
      int[] origins = {x, y, z}, counts = {shape.width(), shape.height(), shape.depth()};
      for (int axis = 0; axis < 3; axis++)
        if ((long) origins[axis] + (long) counts[axis] * sampleStep > (long) Integer.MAX_VALUE + 1)
          throw new IllegalArgumentException("Volume region exceeds signed block coordinates");
    }
  }

  /** Immutable texture metadata; size/extent retain their legacy horizontal cube meaning. */
  public record Resident(
      int texture,
      int size,
      VolumeData.Encoding encoding,
      Region region,
      int sampleStep,
      int height,
      int depth) {
    public Resident(
        int texture, int size, VolumeData.Encoding encoding, Region region, int sampleStep) {
      this(texture, size, encoding, region, sampleStep, size, size);
    }

    public Resident(
        int texture,
        GridExtent shape,
        VolumeData.Encoding encoding,
        Region region,
        int sampleStep) {
      this(texture, shape.width(), encoding, region, sampleStep, shape.height(), shape.depth());
    }

    public Resident(int texture, int size, VolumeData.Encoding encoding, Region region) {
      this(texture, size, encoding, region, region == null ? 1 : region.sampleStep);
    }

    public Resident(int texture, int size, VolumeData.Encoding encoding) {
      this(texture, size, encoding, null, 1);
    }

    public Resident {
      new GridExtent(size, height, depth);
      VolumeData.validateSampleStep(sampleStep);
      if (region != null && region.sampleStep != sampleStep)
        throw new IllegalArgumentException("Volume resident spacing mismatch");
    }

    public int extent() {
      return size * sampleStep;
    }

    public GridExtent shape() {
      return new GridExtent(size, height, depth);
    }

    public int width() {
      return size;
    }

    public int extentX() {
      return size * sampleStep;
    }

    public int extentY() {
      return height * sampleStep;
    }

    public int extentZ() {
      return depth * sampleStep;
    }

    public long bytes() {
      return 4L * size * height * depth;
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
      region.validate(next.shape());
      if (region.sampleStep != next.sampleStep())
        throw new IllegalArgumentException("Volume source region spacing mismatch");
    }
    long bytes = 4L * next.shape().cells();
    if (bytes > gpuBudget - (active == null ? 0 : active.bytes())) return false;
    var regions = regions(next, region);
    cancel();
    int texture = backend.allocate(next.shape());
    if (texture <= 0) throw new IllegalStateException("Volume texture allocation failed");
    candidate = new Resident(texture, next.shape(), next.encoding(), region, next.sampleStep());
    data = next;
    plan = regions;
    return true;
  }

  private List<Box> regions(VolumeData next, Region region) {
    int w = next.width(), h = next.height(), d = next.depth();
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
      return List.of(new Box(0, 0, 0, w, h, d, false));
    var prior = active.region;
    long dx = (long) prior.x - region.x,
        dy = (long) prior.y - region.y,
        dz = (long) prior.z - region.z;
    int step = next.sampleStep();
    // Equal spacing alone is insufficient: centers must have the same grid phase.
    if (dx % step != 0 || dy % step != 0 || dz % step != 0)
      return List.of(new Box(0, 0, 0, w, h, d, false));
    long px = dx / step, py = dy / step, pz = dz / step;
    if (px >= w
        || px + active.size <= 0
        || py >= h
        || py + active.height <= 0
        || pz >= d
        || pz + active.depth <= 0) return List.of(new Box(0, 0, 0, w, h, d, false));
    int x0 = (int) Math.max(0L, px), y0 = (int) Math.max(0L, py), z0 = (int) Math.max(0L, pz);
    int x1 = (int) Math.min(w, px + active.size),
        y1 = (int) Math.min(h, py + active.height),
        z1 = (int) Math.min(d, pz + active.depth);
    var result = new ArrayList<Box>(7);
    add(result, x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, true);
    // Six disjoint slabs cover the complement; no destination texel is written twice.
    add(result, 0, 0, 0, x0, h, d, false);
    add(result, x1, 0, 0, w - x1, h, d, false);
    add(result, x0, 0, 0, x1 - x0, y0, d, false);
    add(result, x0, y1, 0, x1 - x0, h - y1, d, false);
    add(result, x0, y0, 0, x1 - x0, y1 - y0, z0, false);
    add(result, x0, y0, z1, x1 - x0, y1 - y0, d - z1, false);
    return List.copyOf(result);
  }

  private static void add(List<Box> boxes, int x, int y, int z, int w, int h, int d, boolean copy) {
    if (w > 0 && h > 0 && d > 0) boxes.add(new Box(x, y, z, w, h, d, copy));
  }

  /**
   * Host and GPU-copy destination writes share the supplied byte budget, including partial rows.
   * A previous-texture deletion fault occurs after publication; version/resident still describe the
   * complete replacement in that case.
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
    } catch (RuntimeException | Error failure) {
      int written = lastBytes, reused = lastCopied;
      try {
        cancel();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failure) failure.addSuppressed(cleanup);
      } finally {
        lastBytes = written;
        lastCopied = reused;
      }
      throw failure;
    }
    if (operation == plan.size()) {
      var previous = active;
      active = candidate;
      candidate = null;
      data = null;
      plan = List.of();
      cursor = operation = offset = 0;
      version++;
      // Retirement cannot roll back publication or cancel work created by its backend callback.
      delete(previous);
    }
    return lastBytes;
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

  private Resident detachCandidate() {
    var abandoned = candidate;
    candidate = null;
    data = null;
    plan = List.of();
    cursor = operation = offset = lastBytes = lastCopied = 0;
    return abandoned;
  }

  private void delete(Resident resident) {
    if (resident != null) backend.delete(resident.texture());
  }

  /** Detaches the pending candidate before backend deletion, preserving the resident. */
  public void cancel() {
    delete(detachCandidate());
  }

  /**
   * Detaches both captured textures before attempting either deletion. Both are attempted even on
   * failure; the first fault retains identity and a distinct second fault is suppressed.
   */
  public void clear() {
    var previous = active;
    active = null;
    var abandoned = detachCandidate();
    try {
      delete(abandoned);
    } catch (RuntimeException | Error failure) {
      try {
        delete(previous);
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failure) failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    delete(previous);
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Volume uploader closed");
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      clear();
    } finally {
      scratch = null;
    }
  }
}
