package io.voxelcreator.editor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A reset request outlives periodic INI capture and is consumed only after its builder succeeds.
 */
final class WorkspaceLayout {
  private boolean pending;
  private long request;

  WorkspaceLayout(UserConfig.Workspace workspace) {
    pending = workspace.docking.isBlank();
  }

  boolean pending() {
    return pending;
  }

  void reset(UserConfig.Workspace workspace) {
    workspace.docking = "";
    pending = true;
    request++;
  }

  boolean build(Runnable builder) {
    if (!pending) return false;
    long owner = request;
    builder.run();
    if (owner == request) pending = false;
    return true;
  }

  boolean capture(UserConfig.Workspace workspace, Supplier<String> ini) {
    if (pending) return false;
    long owner = request;
    String value = Objects.requireNonNull(ini.get());
    // A reset requested by the capture callback must not be overwritten by older INI data.
    if (pending || owner != request) return false;
    workspace.docking = value;
    return true;
  }
}
