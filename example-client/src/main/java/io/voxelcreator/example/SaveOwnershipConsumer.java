package io.voxelcreator.example;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.api.*;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Deferred public Save isolation, same-value checkpoint supersession and exact retained runtime.
 */
final class SaveOwnershipConsumer {
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

  private static void retained(DocumentSession d, Owned owned) {
    require(
        d.data() == owned.active()
            && d.sessionToken() == owned.token()
            && Objects.equals(d.path(), owned.path())
            && d.historyBytes() == owned.history()
            && d.canUndo() == owned.undo()
            && d.canRedo() == owned.redo()
            && d.dirty() == owned.dirty()
            && owned.values().equals(ProjectIO.JSON.valueToTree(d.data())),
        "Rejected Save/request changed committed model, history or checkpoint");
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var reports = new ArrayList<Map<String, Object>>();
    long fields = 0, cells = 0;
    for (String mode :
        List.of(
            "REJECT",
            "THROW",
            "MUTATE",
            "PREPARED_SAVE",
            "PREPARED_OPEN",
            "EDIT_RECEIPT",
            "UNDO_RECEIPT")) {
      for (boolean prepared :
          mode.equals("REJECT") || mode.equals("THROW") || mode.equals("MUTATE")
              ? List.of(false, true)
              : List.of(false)) {
        var directory = out.resolve(mode + "-" + prepared);
        Files.createDirectories(directory);
        var d = new DocumentSession();
        d.setValidator(NoiseCompiler::validate);
        require(
            d.edit(SaveOwnershipConsumer::fixture).isEmpty(), "Save isolation fixture rejected");
        var original = directory.resolve("original.pvproj.json");
        d.save(original);
        var target = directory.resolve("candidate.pvproj.json");
        Files.writeString(target, "prior destination");
        double expected;
        if (Set.of("REJECT", "THROW", "MUTATE").contains(mode)) {
          require(
              d.edit(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .5)).isEmpty(),
              "Initial edit rejected");
          var before = capture(d);
          var thrown = new AssertionError("validator failed");
          d.setValidator(
              p -> {
                p.noise2D.getFirst().algorithm.parameters.put("value", .75);
                if (mode.equals("THROW")) throw thrown;
                if (mode.equals("REJECT"))
                  return List.of(Diagnostic.error("REJECTED", "/noise2D/0", "rejected"));
                return List.of();
              });
          try {
            save(d, target, prepared);
            throw new AssertionError("Mutating validator published");
          } catch (IOException failure) {
            require(!mode.equals("THROW"), "Wrong validator failure type");
          } catch (AssertionError failure) {
            require(mode.equals("THROW") && failure == thrown, "Original validator failure lost");
          }
          retained(d, before);
          require(
              Files.readString(target).equals("prior destination"),
              "Rejected validation touched destination");
          var held = new AtomicReference<ProjectData>();
          d.setValidator(
              p -> {
                held.set(p);
                return NoiseCompiler.validate(p);
              });
          save(d, target, prepared);
          require(held.get() != d.data(), "Validation aliased active data");
          held.get().noise2D.getFirst().algorithm.parameters.put("value", .75);
          require(
              d.data().noise2D.getFirst().algorithm.parameters.get("value") == .5 && !d.dirty(),
              "Retained validation candidate changed saved data");
          d.setValidator(NoiseCompiler::validate);
          d.undo();
          d.redo();
          expected = .5;
        } else if (mode.equals("PREPARED_SAVE")) {
          try (var older = d.requestSave(target).prepare()) {
            d.save(original);
            var current = capture(d);
            try {
              d.applySave(older);
              throw new AssertionError("Old Save survived newer checkpoint");
            } catch (IllegalStateException failure) {
              retained(d, current);
            }
          }
          require(
              Files.readString(target).equals("prior destination"),
              "Stale Save replaced destination");
          expected = .25;
        } else if (mode.equals("PREPARED_OPEN")) {
          var incoming = ProjectIO.copy(d.data());
          incoming.noise2D.getFirst().algorithm.parameters.put("value", .75);
          var file = directory.resolve("incoming.pvproj.json");
          new ProjectIO().save(file, incoming);
          var older = d.requestOpen(file).prepare();
          d.save(original);
          var current = capture(d);
          try {
            d.applyOpen(older);
            throw new AssertionError("Old Open survived newer checkpoint");
          } catch (IllegalStateException failure) {
            retained(d, current);
          }
          d.applyOpen(d.requestOpen(file).prepare());
          expected = .75;
        } else if (mode.equals("EDIT_RECEIPT")) {
          var after = new AtomicReference<Owned>();
          try {
            d.edit(
                p -> {
                  p.noise2D.getFirst().algorithm.parameters.put("value", .875);
                  try {
                    d.save(original);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                  after.set(capture(d));
                });
            throw new AssertionError("Old edit survived newer checkpoint");
          } catch (IllegalStateException failure) {
            retained(d, after.get());
          }
          expected = .25;
        } else {
          require(
              d.edit(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .5)).isEmpty(),
              "History fixture rejected");
          d.save(original);
          var once = new AtomicBoolean();
          var after = new AtomicReference<Owned>();
          d.setValidator(
              p -> {
                if (once.compareAndSet(false, true)) {
                  try {
                    d.save(original);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                  after.set(capture(d));
                }
                return NoiseCompiler.validate(p);
              });
          try {
            d.undo();
            throw new AssertionError("Old Undo survived newer checkpoint");
          } catch (IllegalStateException failure) {
            retained(d, after.get());
          }
          d.setValidator(NoiseCompiler::validate);
          d.undo();
          d.redo();
          expected = .5;
        }
        byte[] originalBytes = Files.readAllBytes(original);
        var values = ProjectIO.JSON.valueToTree(d.data());
        d.setValidator(NoiseCompiler::validate);
        var file = directory.resolve("retained.pvproj.json");
        d.save(file);
        var load = new ProjectLoader().load(file);
        require(
            load.success() && values.equals(ProjectIO.JSON.valueToTree(load.project())),
            "Save ownership persistence changed model");
        try (var engine = new NoiseGeneratorEngine(2, 16, 16L << 20)) {
          for (var data : List.of(d.data(), load.project())) {
            var compiled = new CompiledProject(data);
            var sampler = compiled.noise2D("field", compiled.defaultSeed());
            for (int z = -1; z <= 1; z++)
              for (int x = -3; x <= 3; x++) {
                require(
                    Double.doubleToRawLongBits(sampler.sample(x, z))
                        == Double.doubleToRawLongBits(expected),
                    "Save isolation changed exact field output");
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
                          "Save isolation introduced World output");
                      cells++;
                    }
              }
          }
        }
        require(
            values.equals(ProjectIO.JSON.valueToTree(d.data()))
                && Arrays.equals(originalBytes, Files.readAllBytes(original)),
            "Sampling changed authoring/file values");
        try (var files = Files.list(directory)) {
          require(
              files.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-save-")),
              "Save staging sibling retained");
        }
        reports.add(
            Map.of(
                "mode", mode, "prepared", prepared, "expected", expected, "modelRetained", true));
      }
    }
    require(
        reports.size() == 10 && fields == 420 && cells == 5880,
        "Unexpected Save ownership replay dimensions");
    Files.writeString(
        out.resolve("save-ownership.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "formatVersion",
                1,
                "cases",
                reports,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
  }

  private static void save(DocumentSession d, Path file, boolean prepared) throws IOException {
    if (prepared) {
      try (var candidate = d.requestSave(file).prepare()) {
        d.applySave(candidate);
      }
    } else d.save(file);
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
