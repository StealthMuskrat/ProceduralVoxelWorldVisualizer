package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred exact saved member-only edits and independent set/group World behavior. */
public final class BackgroundMembershipConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int snapshots = 0, fields = 0, cells = 0, solid = 0;
    for (String kind : List.of("set", "group")) {
      for (String mode :
          kind.equals("set")
              ? List.of("success", "cancel", "escape", "retry", "edit", "raw", "close", "empty", "ready-cancel", "ready-escape", "ready-edit")
              : List.of("success", "empty")) {
        var directory = out.resolve(kind + "-" + mode);
        var before = new ProjectLoader().load(directory.resolve("before.json"));
        require(before.success(), "Membership baseline rejected");
        var types = before.project().voxelTypes;
        require(
            types.stream().map(t -> t.id).toList().equals(List.of("solid", "spare", "third")),
            "Membership type identities");
        boolean applied = List.of("success", "retry", "edit", "empty").contains(mode);
        for (String name :
            applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
          var file = directory.resolve(name + ".json");
          byte[] bytes = Files.readAllBytes(file);
          var loaded = new ProjectLoader().load(file);
          require(loaded.success(), "Saved membership rejected");
          boolean target = applied && List.of("after", "redone").contains(name);
          var expected = ProjectIO.JSON.valueToTree(before.project());
          var values =
              !target
                  ? List.of(types.get(1).uuid)
                  : mode.equals("empty")
                      ? List.<UUID>of()
                      : List.of(types.get(1).uuid, types.get(mode.equals("edit") ? 2 : 0).uuid);
          var owner =
              (ObjectNode) expected.at(kind.equals("set") ? "/voxelSets/0" : "/voxelGroups/0");
          owner.set("voxelTypeUuids", ProjectIO.JSON.valueToTree(values));
          ((ObjectNode) expected.get("project"))
              .put("seed", mode.equals("raw") && name.equals("after") ? 6 : 5);
          require(
              expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
              "Membership changed unrelated/private authoring values");
          boolean cleared =
              kind.equals("set") && target && List.of("success", "retry").contains(mode);
          int[] counts = observe(loaded.project(), cleared);
          fields += counts[0];
          cells += counts[1];
          solid += counts[2];
          snapshots++;
          require(
              Arrays.equals(bytes, Files.readAllBytes(file)),
              "Membership replay wrote saved bytes");
        }
      }
    }
    require(
        snapshots == 38 && fields == 798 && cells == 11172 && solid == 7140,
        "Membership replay dimensions");
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
                "solidCells",
                solid,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "BACKGROUND_MEMBERSHIP_ENGINE_ONLY_PASS 38 saved states / 798 fields / 11172 cells / sets"
            + " affect predicates, groups organize");
  }

  private static int[] observe(ProjectData project, boolean cleared) {
    var values = ProjectIO.JSON.valueToTree(project);
    var compiled = new CompiledProject(project);
    var sampler = compiled.noise2D("field", compiled.defaultSeed());
    int fields = 0, cells = 0, solid = 0;
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(sampler.sample(x, z)) == Double.doubleToRawLongBits(.125),
            "Independent membership field");
        fields++;
      }
    try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
      for (int root : new int[] {2, 4})
        try (var world =
            engine.openWorld(
                compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          for (int z = -1; z <= 1; z++)
            for (int y = -3; y <= 3; y++)
              for (int x = -3; x <= 3; x++) {
                int type = !cleared && y >= -2 && y <= 2 ? 1 : 0;
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == type,
                    "Independent IN_SET/CLEAR membership World");
                cells++;
                if (type == 1) solid++;
              }
        }
    }
    require(
        values.equals(ProjectIO.JSON.valueToTree(project)),
        "Membership runtime changed authoring model");
    return new int[] {fields, cells, solid};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
