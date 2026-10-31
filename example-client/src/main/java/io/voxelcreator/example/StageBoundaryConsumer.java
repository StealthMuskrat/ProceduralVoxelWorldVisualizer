package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only verification of selected UUIDs, disabled stages and exact stage pairs. */
public final class StageBoundaryConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var file = out.resolve("stage-project.json");
    var before = ProjectIO.JSON.readTree(file.toFile());
    if (!before.equals(ProjectIO.JSON.readTree(out.resolve("after.json").toFile()))
        || !ProjectIO.JSON
            .readTree(out.resolve("preferences-before.json").toFile())
            .equals(ProjectIO.JSON.readTree(out.resolve("preferences.json").toFile())))
      throw new AssertionError("Stage selection changed persisted project/preferences");
    var loaded = new ProjectLoader().load(file);
    if (!loaded.success()) throw new AssertionError(loaded.diagnostics());
    var p = loaded.project();
    var report = ProjectIO.JSON.readTree(out.resolve("stage-boundaries-native.json").toFile());
    var clear = UUID.fromString(report.path("clear").asText());
    var fill = UUID.fromString(report.path("fill").asText());
    var disabled = UUID.fromString(report.path("disabled").asText());
    var layer = p.worldGenerator.layers.getFirst().uuid;
    int checked = 0;
    try (var engine = new NoiseGeneratorEngine(1, 8, 64L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var stages = new HashMap<UUID, GenerationStage>();
          world.stages().forEach(stage -> stages.put(stage.uuid(), stage));
          if (stages.size() != 240 || report.path("catalog").size() != stages.size())
            throw new AssertionError("Missing authored/disabled stage");
          for (var row : report.path("catalog")) {
            var stage = stages.get(UUID.fromString(row.path("uuid").asText()));
            String label =
                row.path("kind").asText().equals("layer")
                    ? row.path("id").asText()
                    : row.path("layerId").asText() + " / " + row.path("id").asText();
            if (stage == null
                || !stage.layerUuid().toString().equals(row.path("layerUuid").asText())
                || !stage.kind().equals(row.path("kind").asText())
                || !stage.label().equals(label)
                || stage.enabled() != row.path("enabled").asBoolean())
              throw new AssertionError("UI catalog differs from committed engine stage " + row);
          }
          requirePair(stages.get(fill), 0, 1, true);
          requirePair(stages.get(clear), 1, 2, true);
          requirePair(stages.get(layer), 0, 3, true);
          requirePair(stages.get(disabled), 3, 3, false);
          for (var selection : report.path("selections"))
            if (!stages.containsKey(UUID.fromString(selection.path("uuid").asText())))
              throw new AssertionError("Selection is not an engine stage UUID");
          for (int cx : new int[] {-1, 0}) {
            var pos = new ChunkPos(cx, 0, 0);
            var cleared = world.generateStageChunk(pos, clear);
            var whole = world.generateStageChunk(pos, layer);
            var inactive = world.generateStageChunk(pos, disabled);
            var finalChunk = world.generateChunk(pos);
            for (int z = 0; z < root; z++)
              for (int y = 0; y < root; y++)
                for (int x = 0; x < root; x++) {
                  if (!world
                          .registry()
                          .semanticId(cleared.before().getVoxelTypeId(x, y, z))
                          .equals("a")
                      || cleared.after().getVoxelTypeId(x, y, z) != 0
                      || !cleared.changed(x, y, z)
                      || whole.before().getVoxelTypeId(x, y, z) != 0
                      || !world
                          .registry()
                          .semanticId(whole.after().getVoxelTypeId(x, y, z))
                          .equals("b")
                      || !world
                          .registry()
                          .semanticId(inactive.before().getVoxelTypeId(x, y, z))
                          .equals("b")
                      || inactive.before().getVoxelTypeId(x, y, z)
                          != inactive.after().getVoxelTypeId(x, y, z)
                      || inactive.changed(x, y, z)
                      || !world
                          .registry()
                          .semanticId(finalChunk.getVoxelTypeId(x, y, z))
                          .equals("b"))
                    throw new AssertionError("Before/After/Only Changes/Final exact cell mismatch");
                  checked++;
                }
          }
        }
    }
    if (checked != 144) throw new AssertionError("Unexpected signed chunk coverage");
    System.out.println(
        "Stage boundaries engine-only PASS; all 240 catalog UUIDs, disabled stages and 144 exact"
            + " stage/final cells");
  }

  private static void requirePair(GenerationStage stage, int before, int after, boolean enabled) {
    if (stage == null
        || stage.before() != before
        || stage.after() != after
        || stage.enabled() != enabled)
      throw new AssertionError("Unexpected committed stage interval " + stage);
  }
}
