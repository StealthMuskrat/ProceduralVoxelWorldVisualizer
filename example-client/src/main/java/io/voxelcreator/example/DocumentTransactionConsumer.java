package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Deferred engine-JAR-only rejection of stale edit/history candidates after host reentrancy. */
final class DocumentTransactionConsumer {
  private record Owned(
      ProjectData active,
      Object token,
      Path path,
      long history,
      boolean undo,
      boolean redo,
      boolean dirty,
      JsonNode values) {}

  private static Owned capture(DocumentSession d) {
    return new Owned(
        d.data(),
        d.sessionToken(),
        d.path(),
        d.historyBytes(),
        d.canUndo(),
        d.canRedo(),
        d.dirty(),
        ProjectIO.JSON.valueToTree(d.data()));
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var cases = new ArrayList<Map<String, Object>>();
    long fields = 0, cells = 0;
    for (String action : List.of("EDIT", "UNDO", "REDO"))
      for (String mutation : List.of("NEW", "EDIT", "SAVE", "POLICY")) {
        String id = action + "-" + mutation;
        var directory = out.resolve(id);
        Files.createDirectories(directory);
        var document = new DocumentSession();
        document.setValidator(NoiseCompiler::validate);
        require(
            document.edit(DocumentTransactionConsumer::fixture).isEmpty(),
            "Transaction fixture rejected");
        var original = directory.resolve("original.pvproj.json");
        document.save(original);
        byte[] originalBytes = Files.readAllBytes(original);
        require(
            document
                .edit(
                    p -> {
                      p.project.seed = 29;
                      p.noise2D.getFirst().algorithm.parameters.put("value", .5);
                    })
                .isEmpty(),
            "Initial edit rejected");
        if (action.equals("REDO")) document.undo();
        var once = new AtomicBoolean();
        var owned = new AtomicReference<Owned>();
        document.setValidator(
            candidate -> {
              if (once.compareAndSet(false, true)) {
                switch (mutation) {
                  case "NEW" -> {
                    document.newProject();
                    require(
                        document.edit(DocumentTransactionConsumer::fixture).isEmpty(),
                        "Successor fixture rejected");
                  }
                  case "EDIT" ->
                      require(
                          document
                              .edit(
                                  p -> {
                                    p.project.seed = 31;
                                    p.noise2D.getFirst().algorithm.parameters.put("value", .75);
                                  })
                              .isEmpty(),
                          "Nested edit rejected");
                  case "SAVE" -> {
                    try {
                      document.save(directory.resolve("nested-save.pvproj.json"));
                    } catch (java.io.IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  }
                  case "POLICY" -> document.setValidator(NoiseCompiler::validate);
                  default -> throw new AssertionError(mutation);
                }
                owned.set(capture(document));
              }
              return NoiseCompiler.validate(candidate);
            });
        try {
          switch (action) {
            case "EDIT" ->
                document.edit(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .875));
            case "UNDO" -> document.undo();
            case "REDO" -> document.redo();
            default -> throw new AssertionError(action);
          }
          throw new AssertionError("Stale transaction published after " + id);
        } catch (IllegalStateException expected) {
          require(
              expected.getMessage().contains("ownership changed"),
              "Unexpected transaction rejection: " + expected);
        }
        var after = owned.get();
        require(
            after != null
                && document.data() == after.active()
                && document.sessionToken() == after.token()
                && Objects.equals(document.path(), after.path())
                && document.historyBytes() == after.history()
                && document.canUndo() == after.undo()
                && document.canRedo() == after.redo()
                && document.dirty() == after.dirty()
                && after.values().equals(ProjectIO.JSON.valueToTree(document.data()))
                && Arrays.equals(originalBytes, Files.readAllBytes(original)),
            "Stale transaction changed the newer document/history/checkpoint: " + id);
        double expected =
            mutation.equals("NEW")
                ? .25
                : mutation.equals("EDIT") ? .75 : action.equals("REDO") ? .25 : .5;
        document.setValidator(NoiseCompiler::validate);
        var saved = directory.resolve("retained.pvproj.json");
        document.save(saved);
        var loaded = new ProjectLoader().load(saved);
        require(
            loaded.success() && after.values().equals(ProjectIO.JSON.valueToTree(loaded.project())),
            "Retained transaction values changed during public save/load");
        try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
          for (var data : List.of(document.data(), loaded.project())) {
            var compiled = new CompiledProject(data);
            var sampler = compiled.noise2D("field", compiled.defaultSeed());
            for (int z = -1; z <= 1; z++)
              for (int x = -3; x <= 3; x++) {
                require(
                    Double.doubleToRawLongBits(sampler.sample(x, z))
                        == Double.doubleToRawLongBits(expected),
                    "Stale transaction changed independent retained scalar expectation");
                fields++;
              }
            for (int root : new int[] {2, 4})
              try (var world =
                  engine.openWorld(
                      compiled,
                      new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
                for (int z = -1; z <= 1; z++)
                  for (int y = -3; y <= 3; y++)
                    for (int x = -3; x <= 3; x++) {
                      require(
                          world.getVoxelTypeId(new BlockPos(x, y, z)) == 0,
                          "Transaction introduced unselected World output");
                      cells++;
                    }
              }
          }
        }
        require(
            after.values().equals(ProjectIO.JSON.valueToTree(document.data()))
                && Arrays.equals(originalBytes, Files.readAllBytes(original)),
            "Runtime changed retained authoring/file values");
        cases.add(
            Map.of(
                "action",
                action,
                "mutation",
                mutation,
                "expected",
                expected,
                "newerStatePreserved",
                true));
      }
    require(
        cases.size() == 12 && fields == 504 && cells == 7056,
        "Unexpected transaction replay dimensions");
    Files.writeString(
        out.resolve("transaction-ownership.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                cases,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
  }

  private static void fixture(ProjectData p) {
    p.project.seed = 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .25);
    p.noise2D.add(n);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
