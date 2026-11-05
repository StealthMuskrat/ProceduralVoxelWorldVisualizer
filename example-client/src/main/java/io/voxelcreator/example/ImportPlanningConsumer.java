package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred independent saved-state replay of background choice/reload/retry/cancellation. */
final class ImportPlanningConsumer {
  static void run(Path out) throws Exception {
    int samples = 0, cells = 0;
    for (String mode : List.of("choice", "reload", "cancel", "escape", "retry", "close", "ready-cancel", "ready-escape", "ready-close", "ready-reload-cancel")) {
      var directory = out.resolve(mode);
      var beforeFile = directory.resolve("before.json");
      var afterFile = directory.resolve("after.json");
      byte[] beforeBytes = Files.readAllBytes(beforeFile),
          afterBytes = Files.readAllBytes(afterFile);
      var before = new ProjectLoader().load(beforeFile);
      var after = new ProjectLoader().load(afterFile);
      var incoming = new ProjectLoader().load(directory.resolve("incoming.json"));
      require(
          before.success() && after.success() && incoming.success(), "Planning snapshots rejected");
      boolean imported = List.of("choice", "reload", "retry").contains(mode);
      require(
          before.project().noise2D.size() == 2
              && after.project().noise2D.size() == (imported ? 3 : 2),
          "Planning resource counts");
      var expected = ProjectIO.JSON.valueToTree(before.project());
      var actual = ProjectIO.JSON.valueToTree(after.project());
      require(before.project().project.seed == 17, "Planning source seed");
      if (mode.equals("reload") || mode.equals("ready-reload-cancel")) ((ObjectNode) expected.get("project")).put("seed", 18);
      if (imported) {
        var added = after.project().noise2D.getLast();
        require(
            added.id.equals("added")
                && !added.uuid.equals(incoming.project().noise2D.getLast().uuid),
            "COPY identity");
        var authored = ProjectIO.JSON.valueToTree(incoming.project().noise2D.getLast());
        ((ObjectNode) authored).put("uuid", added.uuid.toString());
        require(authored.equals(ProjectIO.JSON.valueToTree(added)), "Copied resource values");
        require(
            after
                .project()
                .project
                .rootOrder
                .equals(
                    Map.of(
                        "noise2D",
                        List.of(
                            before.project().noise2D.get(0).uuid,
                            before.project().noise2D.get(1).uuid,
                            added.uuid))),
            "Exact appended order");
        ((ArrayNode) actual.get("noise2D")).remove(2);
        ((ObjectNode) actual.get("project")).remove("rootOrder");
        ((ObjectNode) expected.get("project")).remove("rootOrder");
      }
      require(
          expected.equals(actual),
          "Preview work changed unrelated committed values or REUSE definition");
      try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
        for (var p : List.of(before.project(), after.project())) {
          var values = ProjectIO.JSON.valueToTree(p);
          var compiled = new CompiledProject(p);
          for (var noise : p.noise2D) {
            double value =
                switch (noise.id) {
                  case "existing" -> .125;
                  case "shared" -> .25;
                  case "added" -> .75;
                  default -> Double.NaN;
                };
            var sampler = compiled.noise2D(noise.uuid, compiled.defaultSeed());
            for (int z = -1; z <= 1; z++)
              for (int x = -3; x <= 3; x++) {
                require(
                    Double.doubleToRawLongBits(value)
                        == Double.doubleToRawLongBits(sampler.sample(x, z)),
                    "Planning scalar expectation");
                samples++;
              }
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
                        "Import planning introduced World output");
                    cells++;
                  }
            }
          require(values.equals(ProjectIO.JSON.valueToTree(p)), "Replay mutated authoring values");
        }
      }
      require(
          Arrays.equals(beforeBytes, Files.readAllBytes(beforeFile))
              && Arrays.equals(afterBytes, Files.readAllBytes(afterFile)),
          "Replay changed saved bytes");
    }
    require(samples == 903 && cells == 5880, "Planning replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", samples, "cells", cells, "roots", List.of(2, 4))));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
