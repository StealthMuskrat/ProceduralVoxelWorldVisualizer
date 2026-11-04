package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-only complete saved browser states and exact constant/EMPTY expectations. */
public final class BackgroundBrowserSelectionConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int snapshots = 0, fields = 0, cells = 0;
    for (String mode :
        List.of(
            "single", "range", "empty", "manager", "cancel", "escape", "retry", "raw", "close")) {
      var directory = out.resolve(mode);
      var before = new ProjectLoader().load(directory.resolve("before.json"));
      require(before.success(), "Browser baseline rejected");
      boolean applied = List.of("single", "range", "empty", "manager", "retry").contains(mode);
      for (String name :
          applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
        var file = directory.resolve(name + ".json");
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "Saved browser state rejected");
        double a = applied && List.of("after", "redone").contains(name) ? .625 : .125;
        double c = mode.equals("raw") && name.equals("after") ? .75 : .875;
        var expected = ProjectIO.JSON.valueToTree(before.project());
        ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", a);
        ((ObjectNode) expected.at("/noise2D/2/algorithm/parameters")).put("value", c);
        require(
            expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "Browser selection changed unrelated authoring values");
        require(
            loaded.project().noise2D.stream()
                .map(n -> n.id)
                .toList()
                .equals(List.of("a", "b", "c")),
            "Saved browser fixture identities");
        int[] count = observe(loaded.project(), new double[] {a, .375, c});
        fields += count[0];
        cells += count[1];
        snapshots++;
        require(Arrays.equals(bytes, Files.readAllBytes(file)), "Browser replay wrote saved bytes");
      }
    }
    require(snapshots == 28 && fields == 1764 && cells == 8232, "Browser replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "snapshots",
                snapshots,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "BACKGROUND_BROWSER_SELECTION_ENGINE_ONLY_PASS 28 saved states / 1764 fields / 8232 EMPTY"
            + " cells");
  }

  private static int[] observe(ProjectData project, double[] expected) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    int fields = 0, cells = 0;
    for (int i = 0; i < 3; i++) {
      var sampler = compiled.noise2D(String.valueOf((char) ('a' + i)), compiled.defaultSeed());
      for (int z = -1; z <= 1; z++)
        for (int x = -3; x <= 3; x++) {
          require(
              Double.doubleToRawLongBits(sampler.sample(x, z))
                  == Double.doubleToRawLongBits(expected[i]),
              "Independent browser field");
          fields++;
        }
    }
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int z = -1; z <= 1; z++)
            for (int y = -3; y <= 3; y++)
              for (int x = -3; x <= 3; x++) {
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                    "Browser selection created unauthored World voxels");
                cells++;
              }
        }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(project)),
        "Browser replay mutated authoring model");
    return new int[] {fields, cells};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
