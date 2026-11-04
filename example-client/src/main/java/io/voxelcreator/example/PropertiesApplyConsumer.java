package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Deferred engine-only saved Apply replay plus real public prepared-edit/history/save transitions.
 */
public final class PropertiesApplyConsumer {
  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]));
  }

  static void run(Path out) throws Exception {
    int fields = 0, cells = 0, snapshots = 0, transactions = 0;
    for (String mode : List.of("success", "cancel", "escape", "raw", "retry", "close")) {
      var directory = out.resolve(mode);
      var original = new ProjectLoader().load(directory.resolve("before.json"));
      require(original.success(), "Apply baseline rejected");
      boolean applied = mode.equals("success") || mode.equals("retry");
      for (String name :
          applied ? List.of("before", "after", "undone", "redone") : List.of("before", "after")) {
        var file = directory.resolve(name + ".json");
        byte[] bytes = Files.readAllBytes(file);
        var loaded = new ProjectLoader().load(file);
        require(loaded.success(), "Apply saved state rejected");
        var expected = ProjectIO.JSON.valueToTree(original.project());
        boolean changed = name.equals("redone") || name.equals("after") && applied;
        if (changed)
          ((ObjectNode) expected.at("/noise2D/0/algorithm/parameters")).put("value", .625);
        if (mode.equals("raw") && name.equals("after"))
          ((ObjectNode) expected.get("project")).put("seed", 18);
        require(
            expected.equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "Apply changed unrelated values or committed a private failed candidate");
        int[] counts = observe(loaded.project(), changed ? .625 : .125);
        fields += counts[0];
        cells += counts[1];
        snapshots++;
        require(Arrays.equals(bytes, Files.readAllBytes(file)), "Sampling changed saved bytes");
      }
      if (applied) {
        var document = new DocumentSession();
        document.setValidator(NoiseCompiler::validate);
        require(
            document.open(directory.resolve("before.json")).isEmpty(),
            "Public edit source rejected");
        var values = ProjectIO.JSON.valueToTree(document.data());
        var request =
            document.requestEdit(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .625));
        DocumentSession.PreparedEdit prepared;
        try (var worker = Executors.newSingleThreadExecutor()) {
          prepared = worker.submit(request::prepare).get(10, TimeUnit.SECONDS);
        }
        require(
            prepared.success()
                && document.currentEdit(prepared)
                && values.equals(ProjectIO.JSON.valueToTree(document.data())),
            "Preparation published a document");
        document.applyEdit(prepared);
        for (int phase = 0; phase < 4; phase++) {
          double expected = phase == 1 ? .125 : .625;
          ProjectData sample;
          if (phase == 1) document.undo();
          else if (phase == 2) document.redo();
          if (phase == 3) {
            var file = directory.resolve("api-applied.json");
            document.save(file);
            var loaded = new ProjectLoader().load(file);
            require(loaded.success(), "Prepared edit save/load rejected");
            sample = loaded.project();
          } else sample = document.data();
          var target = values.deepCopy();
          ((ObjectNode) target.at("/noise2D/0/algorithm/parameters")).put("value", expected);
          require(
              target.equals(ProjectIO.JSON.valueToTree(sample)),
              "Prepared edit/history/persistence changed unrelated state");
          int[] counts = observe(sample, expected);
          fields += counts[0];
          cells += counts[1];
        }
        transactions++;
      }
    }
    require(
        snapshots == 16 && transactions == 2 && fields == 504 && cells == 7056,
        "Apply replay dimensions");
    Files.writeString(
        out.resolve("engine-replay.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "snapshots",
                snapshots,
                "preparedTransactions",
                transactions,
                "apiStates",
                8,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
    System.out.println(
        "PROPERTIES_APPLY_ENGINE_ONLY_PASS 16 saved states / 2 prepared transactions / 504 fields /"
            + " 7056 EMPTY cells");
  }

  private static int[] observe(ProjectData p, double expected) {
    var values = ProjectIO.JSON.valueToTree(p);
    var compiled = new CompiledProject(p);
    int fields = 0, cells = 0;
    var field = compiled.noise2D("field", compiled.defaultSeed());
    for (int z = -1; z <= 1; z++)
      for (int x = -3; x <= 3; x++) {
        require(
            Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(field.sample(x, z)),
            "Exact independent Apply field");
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
                require(
                    world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                    "Apply created unauthored World output");
                cells++;
              }
        }
    }
    require(values.equals(ProjectIO.JSON.valueToTree(p)), "Runtime changed authoring values");
    return new int[] {fields, cells};
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
