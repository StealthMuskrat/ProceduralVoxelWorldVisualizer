package io.voxelcreator.render;

import io.voxelcreator.storage.ChunkPos;
import java.util.*;

/** Render-thread retained tombstones bounded by the configured CPU scene chunk count. */
final class SceneRemovals implements AutoCloseable {
  private final long epoch;
  private final LinkedHashMap<ChunkPos, ChunkScene.Removal> pending = new LinkedHashMap<>();
  private int limit;
  private ChunkScene.RemovalReset reset, invalidated;
  private boolean closed;

  SceneRemovals(long epoch, int limit) {
    if (limit < 1) throw new IllegalArgumentException("Removal limit");
    this.epoch = epoch;
    this.limit = limit;
  }

  void offer(ChunkPos position, long version) {
    if (closed) return;
    var removal = new ChunkScene.Removal(epoch, position, version);
    if (reset != null || !pending.containsKey(position) && pending.size() >= limit) {
      requestReset(version);
      return;
    }
    var old = pending.get(position);
    if (old == null || old.version() < version) pending.put(position, removal);
  }

  void limit(int value, long version) {
    if (value < 1) throw new IllegalArgumentException("Removal limit");
    if (closed) return;
    limit = value;
    if (pending.size() > limit) requestReset(version);
  }

  private void requestReset(long version) {
    if (reset != null && reset.version() >= version) return;
    pending.clear();
    reset = new ChunkScene.RemovalReset(epoch, version);
    invalidated = null;
  }

  List<ChunkScene.Removal> pending() { return List.copyOf(pending.values()); }
  ChunkScene.RemovalReset reset() { return reset; }
  boolean hasPending() { return reset != null || !pending.isEmpty(); }

  boolean acknowledge(ChunkScene.Removal removal) {
    if (closed || removal == null || removal.epoch() != epoch
        || pending.get(removal.position()) != removal) return false;
    pending.remove(removal.position());
    return true;
  }

  void uploadsInvalidated() { invalidated = reset; }

  boolean acknowledge(ChunkScene.RemovalReset value) {
    if (closed || value == null || value != reset || value != invalidated) return false;
    reset = invalidated = null;
    return true;
  }

  List<ChunkPos> drainPositions() {
    if (reset != null) throw new IllegalStateException("Scene removal reset pending");
    var positions = new ArrayList<>(pending.keySet());
    pending.clear();
    return positions;
  }

  public void close() {
    closed = true;
    pending.clear();
    reset = invalidated = null;
  }
}
