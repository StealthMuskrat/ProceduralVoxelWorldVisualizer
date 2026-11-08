package io.voxelcreator.render;

/** One comparison policy for pending, resident and drawn versions; GLuint/arena identity is separate. */
final class SceneRemovalPolicy {
  private SceneRemovalPolicy() {}

  static boolean removes(long epoch, ChunkScene.Removal removal, long version) {
    return removal.epoch() == epoch && version <= removal.version();
  }
}
