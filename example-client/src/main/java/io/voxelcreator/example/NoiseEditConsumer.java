package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only full-model and exact-arithmetic oracle for background structural edits. */
final class NoiseEditConsumer {
  static void run(Path out) throws Exception {
    var receipt = ProjectIO.JSON.readTree(out.resolve("noise-edit-native.json").toFile());
    require(
        receipt.path("formatVersion").asInt() == 1 && receipt.path("cases").size() == 7,
        "Structural receipt format");
    var modes = new LinkedHashSet<String>();
    for (var entry : receipt.path("cases")) {
      var mode = entry.path("mode").asText();
      require(modes.add(mode), "Duplicate structural case");
      require(
          entry.path("staged").asBoolean()
              == List.of("automatic", "resolve", "retry").contains(mode),
          "Structural outcome");
      require(
          entry.path("queuedFrames").asInt() >= 12 && entry.path("heldFrames").asInt() >= 12,
          "Structural held frames");
      for (String key :
          List.of("privateRetained", "rawRetained", "preferencesRetained", "originalBytesRetained"))
        require(entry.path(key).asBoolean(), "Structural input retention");
    }
    require(
        modes.equals(Set.of("automatic", "resolve", "cancel", "escape", "retry", "stale", "close")),
        "Missing structural case");
    int fields = 0, cells = 0;
    for (String mode : modes) {
      var directory = out.resolve(mode);
      var beforeFile = directory.resolve("before.json");
      var afterFile = directory.resolve("after.json");
      var beforeBytes = Files.readAllBytes(beforeFile);
      var afterBytes = Files.readAllBytes(afterFile);
      var before = new ProjectLoader().load(beforeFile);
      var after = new ProjectLoader().load(afterFile);
      require(before.success() && after.success(), "Structural snapshots rejected");
      var expected = ProjectIO.JSON.valueToTree(before.project());
      var actual = ProjectIO.JSON.valueToTree(after.project());
      var n = before.project().noise2D.getFirst();
      require(n.id.equals("field") && n.sharedParameters.size() == 2, "Structural source shape");
      boolean staged = List.of("automatic", "resolve", "retry").contains(mode);
      if (staged) {
        var authored = (ObjectNode) expected.path("noise2D").get(0);
        authored.put("id", "private");
        var parameters = (ArrayNode) authored.path("sharedParameters");
        parameters.remove(mode.equals("resolve") ? 0 : 1);
        if (mode.equals("resolve")) {
          var binding = (ObjectNode) authored.path("processing").get(0).path("inputs").path("b");
          binding.put("sourceKind", "Constant");
          binding.put("value", 2.0);
          binding.remove("parameterUuid");
          binding.remove("itemUuid");
          binding.remove("noiseGeneratorUuid");
          binding.remove("ambientKey");
        }
      }
      require(
          expected.equals(actual),
          "Structural stage changed unrelated values or failed to apply its exact resolution");
      try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
        for (var p : List.of(before.project(), after.project())) {
          var values = ProjectIO.JSON.valueToTree(p);
          var compiled = new CompiledProject(p);
          var sampler = compiled.noise2D(p.noise2D.getFirst().uuid, compiled.defaultSeed());
          for (int z = -1; z <= 1; z++)
            for (int x = -3; x <= 3; x++) {
              require(sampler.sample(x, z) == 12, "Independent (1+2)*4 structural arithmetic");
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
                        "Structural edit changed EMPTY World");
                    cells++;
                  }
            }
          require(
              values.equals(ProjectIO.JSON.valueToTree(p)), "Structural replay mutated project");
        }
      }
      require(
          Arrays.equals(beforeBytes, Files.readAllBytes(beforeFile))
              && Arrays.equals(afterBytes, Files.readAllBytes(afterFile)),
          "Structural replay changed saved bytes");
    }
    require(fields == 294 && cells == 4116, "Structural replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "fields", fields, "cells", cells, "roots", List.of(2, 4))));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
