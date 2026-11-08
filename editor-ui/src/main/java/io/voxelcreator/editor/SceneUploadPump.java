package io.voxelcreator.editor;

import io.voxelcreator.render.ChunkScene;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Captured CPU/GPU frame upload ownership; only current, exact resident receipts acknowledge. */
final class SceneUploadPump {
  interface Backend {
    long uploadedBytes();
    int compact(int budget);
    boolean compacting();
    ChunkScene.Upload pending();
    boolean contains(ChunkScene.Upload upload);
    boolean stage(ChunkScene.Upload upload);
    ChunkScene.Upload advance(int budget);
  }

  private boolean busy;
  boolean busy() { return busy; }

  boolean run(ChunkScene scene, BooleanSupplier current, Backend backend, int byteBudget) {
    Objects.requireNonNull(scene);
    Objects.requireNonNull(current);
    Objects.requireNonNull(backend);
    if (busy) throw new IllegalStateException("Scene upload pump already active");
    if (byteBudget < 0) throw new IllegalArgumentException("Negative scene upload budget");
    busy = true;
    try {
      if (!current.getAsBoolean()) return false;
      long before = backend.uploadedBytes();
      if (!current.getAsBoolean()) return false;
      int maintenance = backend.compact(byteBudget);
      if (!current.getAsBoolean()) return false;
      if (maintenance < 0 || maintenance > byteBudget)
        throw new IllegalStateException("Invalid scene maintenance byte count");
      int budget = byteBudget - maintenance;
      for (int batch = 0; batch < 4; batch++) {
        boolean compacting = backend.compacting();
        if (!current.getAsBoolean()) return false;
        if (compacting) break;
        long uploaded = backend.uploadedBytes();
        if (!current.getAsBoolean()) return false;
        int remaining = remaining(before, uploaded, budget);
        if (remaining == 0) break;
        var attempted = backend.pending();
        if (!current.getAsBoolean()) return false;
        if (attempted == null) {
          var uploads = scene.uploads(1, remaining);
          if (!current.getAsBoolean()) return false;
          if (uploads.isEmpty()) break;
          attempted = uploads.getFirst();
          boolean resident = backend.contains(attempted);
          if (!current.getAsBoolean()) return false;
          if (resident) {
            scene.uploaded(attempted);
            if (!current.getAsBoolean()) return false;
            continue;
          }
          boolean staged = backend.stage(attempted);
          if (!current.getAsBoolean()) return false;
          if (!staged) {
            compacting = backend.compacting();
            if (!current.getAsBoolean()) return false;
            if (!compacting) scene.reject();
            break;
          }
          var pending = backend.pending();
          if (!current.getAsBoolean()) return false;
          if (pending != attempted)
            throw new IllegalStateException("Scene stage did not retain its upload receipt");
        }
        if (attempted.epoch() != scene.epoch())
          throw new IllegalStateException("Foreign pending scene upload");
        var completed = backend.advance(remaining);
        if (!current.getAsBoolean()) return false;
        uploaded = backend.uploadedBytes();
        if (!current.getAsBoolean()) return false;
        remaining(before, uploaded, budget);
        if (completed == null) break;
        if (completed != attempted)
          throw new IllegalStateException("Mismatched scene upload receipt");
        boolean resident = backend.contains(completed);
        if (!current.getAsBoolean()) return false;
        if (!resident) throw new IllegalStateException("Completed scene upload is not resident");
        scene.uploaded(completed);
        if (!current.getAsBoolean()) return false;
      }
      return current.getAsBoolean();
    } finally {
      busy = false;
    }
  }

  private static int remaining(long before, long uploaded, int budget) {
    long bytes = uploaded - before;
    if (before < 0 || uploaded < before || bytes > budget)
      throw new IllegalStateException("Invalid scene upload byte count");
    return budget - (int) bytes;
  }
}
