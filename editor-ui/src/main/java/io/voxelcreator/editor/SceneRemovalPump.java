package io.voxelcreator.editor;

import io.voxelcreator.render.ChunkScene;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Retained removal delivery; a normal accepted current backend return alone can acknowledge. */
final class SceneRemovalPump {
  interface Backend {
    boolean remove(ChunkScene.Removal removal);
    boolean reset(ChunkScene.RemovalReset reset);
  }

  private boolean busy;
  boolean busy() { return busy; }

  boolean run(ChunkScene scene, BooleanSupplier current, Backend backend) {
    Objects.requireNonNull(scene);
    Objects.requireNonNull(current);
    Objects.requireNonNull(backend);
    if (busy) throw new IllegalStateException("Scene removal pump already active");
    busy = true;
    try {
      if (!current.getAsBoolean()) return false;
      var reset = scene.pendingRemovalReset();
      if (reset != null) {
        boolean accepted = backend.reset(reset);
        if (!current.getAsBoolean() || !accepted || scene.pendingRemovalReset() != reset) return false;
        scene.invalidateUploads();
        if (!current.getAsBoolean() || !scene.acknowledgeRemovalReset(reset)) return false;
        return current.getAsBoolean() && !scene.hasPendingRemovals();
      }
      for (var removal : scene.pendingRemovals()) {
        if (!current.getAsBoolean() || scene.pendingRemovalReset() != null) return false;
        boolean accepted = backend.remove(removal);
        if (!current.getAsBoolean() || !accepted) return false;
        if (!scene.acknowledgeRemoval(removal)) return false;
      }
      return current.getAsBoolean() && !scene.hasPendingRemovals();
    } finally {
      busy = false;
    }
  }
}
