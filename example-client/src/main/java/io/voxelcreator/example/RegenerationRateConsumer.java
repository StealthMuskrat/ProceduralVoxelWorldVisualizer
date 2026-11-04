package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only proof that rate editing leaves saved authoring and exact samples alone. */
public final class RegenerationRateConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]);
    var beforeFile = out.resolve("project-before.json");
    var afterFile = out.resolve("project-after.json");
    byte[] beforeBytes = Files.readAllBytes(beforeFile), afterBytes = Files.readAllBytes(afterFile);
    var before = new ProjectLoader().load(beforeFile);
    var after = new ProjectLoader().load(afterFile);
    require(before.success() && after.success(), "Rate authoring snapshots rejected");
    require(
        ProjectIO.JSON
            .valueToTree(before.project())
            .equals(ProjectIO.JSON.valueToTree(after.project())),
        "Rate editing changed project data");
    int fields = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (var project : List.of(before.project(), after.project())) {
        require(
            project.noise2D.size() == 1
                && project.noise2D.getFirst().id.equals("field")
                && project.worldGenerator.layers.isEmpty(),
            "Rate editing published private data");
        var values = ProjectIO.JSON.valueToTree(project);
        var compiled = new CompiledProject(project);
        var sampler = compiled.noise2D("field", compiled.defaultSeed());
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(sampler.sample(x, z))
                    == Double.doubleToRawLongBits(.125),
                "Rate editing changed scalar values");
            fields++;
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                      "Rate editing changed EMPTY world");
                  cells++;
                }
          }
        require(
            values.equals(ProjectIO.JSON.valueToTree(project)),
            "Runtime sampling changed model values");
      }
    }
    require(fields == 42 && cells == 588, "Unexpected rate replay observations");
    require(
        Arrays.equals(beforeBytes, Files.readAllBytes(beforeFile))
            && Arrays.equals(afterBytes, Files.readAllBytes(afterFile)),
        "Runtime replay changed saved bytes");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", fields, "cells", cells, "roots", List.of(2, 4))));
    System.out.println("REGENERATION_RATE_ENGINE_ONLY_PASS 42 exact fields / 588 EMPTY cells");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
