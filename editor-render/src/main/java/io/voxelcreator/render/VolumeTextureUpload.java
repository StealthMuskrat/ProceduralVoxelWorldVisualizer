package io.voxelcreator.render;

import java.nio.FloatBuffer;
import java.util.Objects;
import org.lwjgl.BufferUtils;

/** Render-thread-owned, bounded texture replacement; only a complete candidate becomes resident. */
public final class VolumeTextureUpload implements AutoCloseable {
  public static final int STAGING_BYTES = 65536;

  public interface Backend {
    int allocate(int size);

    void write(
        int texture, int x, int y, int z, int width, int height, int depth, FloatBuffer values);

    void delete(int texture);
  }

  public record Resident(int texture, int size, VolumeData.Encoding encoding) {
    public long bytes() {
      return 4L * size * size * size;
    }
  }

  public record Statistics(
      long activeBytes,
      long pendingBytes,
      long remainingBytes,
      long cpuBytes,
      long uploadedBytes,
      int lastBytes,
      long version,
      boolean pending) {}

  private final Backend backend;
  private FloatBuffer scratch = BufferUtils.createFloatBuffer(STAGING_BYTES / Float.BYTES);
  private Resident active, candidate;
  private VolumeData data;
  private int cursor, lastBytes;
  private long uploaded, version;
  private boolean closed;

  public VolumeTextureUpload(Backend backend) {
    this.backend = Objects.requireNonNull(backend);
  }

  /**
   * Rejection leaves the current resident and pending candidate intact. No allocation on rejection.
   */
  public boolean stage(VolumeData next, long gpuBudget) {
    ensureOpen();
    Objects.requireNonNull(next);
    if (gpuBudget < 0) throw new IllegalArgumentException("Negative volume GPU budget");
    long bytes = 4L * next.size() * next.size() * next.size();
    if (bytes > gpuBudget - (active == null ? 0 : active.bytes())) return false;
    cancel();
    int texture = backend.allocate(next.size());
    if (texture <= 0) throw new IllegalStateException("Volume texture allocation failed");
    candidate = new Resident(texture, next.size(), next.encoding());
    data = next;
    return true;
  }

  /** Destination writes, including partial rows, never exceed the supplied byte budget. */
  public int advance(int byteBudget) {
    ensureOpen();
    if (byteBudget < 0) throw new IllegalArgumentException("Negative volume upload budget");
    lastBytes = 0;
    if (candidate == null) return 0;
    try {
      int n = candidate.size(), plane = n * n, total = plane * n;
      while (cursor < total && byteBudget - lastBytes >= Float.BYTES) {
        int available = Math.min(scratch.capacity(), (byteBudget - lastBytes) / Float.BYTES);
        int x = cursor % n, y = cursor / n % n, z = cursor / plane;
        int w, h = 1, d = 1;
        if (x == 0 && y == 0 && available >= plane) {
          w = n;
          h = n;
          d = Math.min(n - z, available / plane);
        } else if (x == 0 && available >= n) {
          w = n;
          h = Math.min(n - y, available / n);
        } else w = Math.min(n - x, available);
        int count = w * h * d;
        scratch.clear().limit(count);
        data.copySamples(cursor, scratch);
        scratch.flip();
        backend.write(candidate.texture(), x, y, z, w, h, d, scratch);
        cursor += count;
        lastBytes += count * Float.BYTES;
        uploaded += count * Float.BYTES;
      }
      if (cursor == total) {
        var previous = active;
        active = candidate;
        candidate = null;
        data = null;
        cursor = 0;
        version++;
        if (previous != null) backend.delete(previous.texture());
      }
      return lastBytes;
    } catch (RuntimeException | Error failure) {
      int written = lastBytes;
      cancel();
      lastBytes = written;
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
        candidate != null);
  }

  /** Abandon only the incomplete replacement; the accepted texture remains usable. */
  public void cancel() {
    var abandoned = candidate;
    candidate = null;
    data = null;
    cursor = 0;
    lastBytes = 0;
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
