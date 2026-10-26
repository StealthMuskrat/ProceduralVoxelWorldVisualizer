package io.voxelcreator.editor;

/** UI-thread-owned admission failure for one viewport; changed requests explicitly rearm it. */
final class PreviewAdmission {
  record Decision(boolean allowed, long workingBytes, String message) {}

  private String blocked = "";

  boolean blocked() {
    return !blocked.isEmpty();
  }

  void changed() {
    blocked = "";
  }

  void queueFull() {
    blocked = "Preview queue is full; Regenerate to retry";
  }

  Decision check(
      int kind, int side, String mode, long cellBudget, int maxDimension, long cpuBytes) {
    if (blocked()) return new Decision(false, 0, blocked);
    if (kind < 0 || kind > 2 || side < 1 || side > 256 || cellBudget < 0 || cpuBytes < 0)
      throw new IllegalArgumentException("Invalid fixed preview admission");
    long cells = (long) side * side * (kind == 0 ? 1 : side);
    if (cells > cellBudget || kind != 0 && side > maxDimension)
      return reject("Preview exceeds configured budget; reduce extent");
    long bytes =
        kind == 0
            ? 64L * side * side
            : (mode.equals("Volume") ? 12L : mode.equals("Point Cloud") ? 96L : 3024L)
                * side
                * side
                * side;
    if (bytes > cpuBytes)
      return reject("Preview exceeds working-memory budget; reduce extent or raise CPU MiB");
    return new Decision(true, bytes, "");
  }

  private Decision reject(String message) {
    blocked = message;
    return new Decision(false, 0, message);
  }
}
