package io.voxelcreator.render;

/**
 * Measured effective fallback state. Configured preferences are never mutated. Tick time is
 * monotonic.
 */
public final class RenderPressure {
  private int level;
  private long lastChange, goodSince;
  private boolean manuallyPaused, changed;
  private long pauseVersion;

  /**
   * Identity of the current/most recent pause; ordinary pressure measurements do not replace it.
   */
  public long pauseVersion() {
    return pauseVersion;
  }

  public int level() {
    return level;
  }

  public int lodBias() {
    return level >= 1 ? 1 : 0;
  }

  public int radius(int configured) {
    return Math.max(0, configured - (level >= 2 ? 1 : 0));
  }

  public boolean preload() {
    return level < 3;
  }

  public boolean acceptExpensive() {
    return level < 4;
  }

  public boolean paused() {
    return level >= 5 || manuallyPaused;
  }

  public String message() {
    return switch (level) {
      case 0 -> "Full configured quality";
      case 1 -> "Pressure: distant LOD increased";
      case 2 -> "Pressure: effective render distance reduced";
      case 3 -> "Pressure: background preload reduced";
      case 4 -> "Pressure: new expensive previews deferred";
      default -> "Generation paused under critical pressure; Resume required";
    };
  }

  public boolean observe(
      long now, double cpuRatio, double gpuRatio, double frameMillis, boolean rejected) {
    boolean critical = rejected || cpuRatio > .96 || gpuRatio > .96,
        pressure = critical || cpuRatio > .86 || gpuRatio > .86 || frameMillis > 45;
    if (pressure) {
      goodSince = 0;
      if (level < 5 && (!changed || now - lastChange >= 1_000_000_000L)) {
        boolean wasPaused = paused();
        level++;
        if (!wasPaused && paused()) pauseVersion++;
        lastChange = now;
        changed = true;
        return true;
      }
    } else if (!paused() && cpuRatio < .65 && gpuRatio < .65 && frameMillis < 28) {
      if (goodSince == 0) goodSince = now;
      if (level > 0 && now - goodSince >= 5_000_000_000L && now - lastChange >= 2_000_000_000L) {
        level--;
        lastChange = now;
        goodSince = now;
        return true;
      }
    } else goodSince = 0;
    return false;
  }

  public void resume(long now) {
    level = Math.min(level, 3);
    manuallyPaused = false;
    lastChange = now;
    goodSince = 0;
  }

  public void pause() {
    if (!paused()) pauseVersion++;
    manuallyPaused = true;
  }

  /** Admit only an explicit decision for this still-active pause. */
  public boolean resume(long expectedPause, long now) {
    if (!paused() || pauseVersion != expectedPause) return false;
    resume(now);
    return true;
  }
}
