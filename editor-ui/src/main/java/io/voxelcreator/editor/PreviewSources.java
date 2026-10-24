package io.voxelcreator.editor;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;

/** Background compilation. Render threads receive a fully constructed immutable sampling source. */
public final class PreviewSources {
  private PreviewSources() {}

  public static ChunkScene.Source create(
      ProjectData snapshot,
      NoiseGeneratorEngine engine,
      int kind,
      UUID noise,
      double threshold,
      String mode,
      UUID stage) {
    if (kind == 1) {
      var field = new NoiseCompiler(snapshot, snapshot.project.seed).compile(noise);
      return (position, token) -> SceneChunk.sampled(position, 32, token, field::sample, threshold);
    }
    var compiled = new CompiledProject(snapshot);
    var world =
        engine.openWorld(
            compiled, new WorldOptions(null, 32, WorldOptions.CachePolicy.NO_CACHE, 0));
    return new ChunkScene.Source() {
      public SceneChunk load(ChunkPos position, CancellationToken token) {
        token.throwIfCancelled();
        if (mode.equals("Final") || stage == null)
          return SceneChunk.exact(world.generateChunkAsync(position, token).join());
        var pair = world.generateStageChunk(position, stage, token);
        return switch (mode) {
          case "Before" -> SceneChunk.exact(pair.before());
          case "After" -> SceneChunk.exact(pair.after());
          case "Only Changes" -> SceneChunk.changes(pair, snapshot.voxelTypes.size());
          default -> throw new IllegalArgumentException("Stage mode");
        };
      }

      public void close() {
        world.close();
      }
    };
  }
}
