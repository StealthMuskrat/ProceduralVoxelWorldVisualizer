package io.voxelcreator.render;

/**
 * Effective display-window admission; configured extent and canonical field semantics never change.
 */
public final class VolumeStreamingLimits {
  private VolumeStreamingLimits() {}

  public record Limits(long cpuBytes, long gpuBytes, long cacheBytes, long cells, int dimension) {
    public Limits {
      if (cpuBytes < 0 || gpuBytes < 0 || cacheBytes < 0 || cells < 0 || dimension < 0)
        throw new IllegalArgumentException("Negative volume limit");
    }
  }

  public record Plan(
      int side, long workingBytes, long cacheBytes, String rejection, int sampleStep) {
    public Plan {
      VolumeData.validateSampleStep(sampleStep);
    }

    public Plan(int side, long workingBytes, long cacheBytes, String rejection) {
      this(side, workingBytes, cacheBytes, rejection, 1);
    }

    public int textureSide() {
      return side / sampleStep;
    }

    public long textureBytes() {
      return 4L * textureSide() * textureSide() * textureSide();
    }

    public boolean allowed() {
      return rejection == null;
    }
  }

  public static Plan plan(
      int requestedSide, int pressureLevel, Limits limits, long activeGpuBytes) {
    if (requestedSide < 2
        || requestedSide > 256
        || pressureLevel < 0
        || pressureLevel > 5
        || activeGpuBytes < 0) throw new IllegalArgumentException("Invalid volume request");
    java.util.Objects.requireNonNull(limits);
    int side =
        (requestedSide + VolumeWindows.CHUNK - 1) / VolumeWindows.CHUNK * VolumeWindows.CHUNK;
    // Display-only coarse centers reduce sampling/storage before reducing visible world extent.
    int sampleStep = pressureLevel >= 1 ? 2 : 1;
    if (pressureLevel >= 2) side = Math.max(VolumeWindows.CHUNK, side - VolumeWindows.CHUNK);
    int textureSide = side / sampleStep;
    long cells = (long) textureSide * textureSide * textureSide;
    long working = VolumeWindows.workingBytes(side, sampleStep);
    long cache =
        pressureLevel >= 3
            ? 0
            : Math.min(
                VolumeWindows.MAX_CACHE_BYTES,
                Math.min(limits.cacheBytes, Math.max(0, limits.cpuBytes - working)));
    String rejection = null;
    if (textureSide > limits.dimension || cells > limits.cells)
      rejection = "Streaming volume exceeds extent/cell budget";
    else if (working > limits.cpuBytes) rejection = "Streaming volume exceeds CPU MiB";
    else if (4 * cells > limits.gpuBytes - activeGpuBytes)
      rejection = "Streaming volume replacement exceeds GPU MiB";
    return new Plan(side, working, cache, rejection, sampleStep);
  }

  /**
   * Count one serialized worker/window reservation, including a queued successor and GPU staging.
   */
  public static long cpuBytes(long cached, long worker, long reserved, long upload) {
    if (cached < 0 || worker < 0 || reserved < 0 || upload < 0)
      throw new IllegalArgumentException("Negative volume memory");
    return Math.addExact(cached, Math.max(upload, Math.max(worker, reserved)));
  }
}
