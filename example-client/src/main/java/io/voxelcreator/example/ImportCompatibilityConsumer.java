package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR-only Reuse rejection, owned Copy publication and exact-output replay. */
public final class ImportCompatibilityConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(document.edit(p -> fixture(p, false)).isEmpty(), "Destination rejected");
    var beforePath = out.resolve("before.pvproj.json");
    document.save(beforePath);
    String before = Files.readString(beforePath);
    var baseline = ProjectIO.copy(document.data());
    var compiledBefore = new CompiledProject(baseline);
    var incoming = new ProjectData();
    fixture(incoming, true);
    String incomingBefore = ProjectIO.JSON.writeValueAsString(incoming);
    var source = incoming.noise2D.getFirst();
    var type = incoming.voxelTypes.getFirst();
    var session = new ResourceImport.Session(document.data(), incoming);
    session.choose(type.uuid, ResourceImport.Choice.REUSE);
    UUID copiedIdentity = row(session.preview(), source.uuid).targetUuid();
    session.choose(source.uuid, ResourceImport.Choice.REUSE);
    var rejected = session.preview();
    require(
        !rejected.valid() && !row(rejected, source.uuid).compatible(),
        "Different identity-named binding incorrectly offered Reuse");
    require(
        rejected.diagnostics().stream().anyMatch(d -> d.code().equals("INCOMPATIBLE_REUSE")),
        "Missing Reuse conflict diagnostic");
    var active = document.data();
    var token = document.sessionToken();
    long history = document.historyBytes();
    try {
      document.edit(session::applyTo);
      throw new AssertionError("Incompatible Reuse published");
    } catch (IllegalStateException expected) {
    }
    require(
        document.data() == active
            && document.sessionToken() == token
            && document.path().equals(beforePath)
            && document.historyBytes() == history
            && !document.dirty()
            && before.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Rejected Reuse changed document/checkpoint/history");
    session.choose(source.uuid, ResourceImport.Choice.COPY);
    var preview = session.preview();
    var copiedRow = row(preview, source.uuid);
    require(
        preview.valid()
            && copiedIdentity.equals(copiedRow.targetUuid())
            && copiedRow.targetId().equals("field-copy"),
        "Copy choice lost stable identity or collision-safe name");
    session.choose(source.uuid, ResourceImport.Choice.SKIP);
    require(session.preview().valid(), "Skip dependency closure rejected");
    session.choose(source.uuid, ResourceImport.Choice.COPY);
    require(
        copiedIdentity.equals(row(session.preview(), source.uuid).targetUuid()),
        "Choice round trip changed copied identity");
    String expected = ProjectIO.JSON.writeValueAsString(session.preview().candidate());
    require(document.edit(session::applyTo).isEmpty(), "Valid Copy rejected");
    require(
        expected.equals(ProjectIO.JSON.writeValueAsString(document.data()))
            && document.sessionToken() == token
            && document.dirty(),
        "Publication differed from the complete preview");
    var after = document.data();
    require(
        after.project.uuid.equals(baseline.project.uuid)
            && after.project.seed == 17
            && after.project.minY == -2
            && after.project.maxY == 2,
        "Import replaced destination settings");
    require(
        ProjectIO.JSON
                .valueToTree(baseline.noise2D.getFirst())
                .equals(ProjectIO.JSON.valueToTree(after.noise2D.getFirst()))
            && ProjectIO.JSON
                .valueToTree(baseline.worldGenerator.layers.getFirst())
                .equals(ProjectIO.JSON.valueToTree(after.worldGenerator.layers.getFirst()))
            && ProjectIO.JSON
                .valueToTree(baseline.voxelTypes)
                .equals(ProjectIO.JSON.valueToTree(after.voxelTypes)),
        "Import changed existing definitions");
    var copied = after.noise2D.getLast();
    require(
        copied.uuid.equals(copiedIdentity)
            && ProjectIO.JSON
                .valueToTree(source.processing.getFirst().inputs)
                .equals(ProjectIO.JSON.valueToTree(copied.processing.getFirst().inputs)),
        "Copy lost an input binding");
    var addedLayer = after.worldGenerator.layers.getLast();
    require(
        addedLayer.defaults.voxelTypeUuid.equals(after.voxelTypes.getFirst().uuid)
            && addedLayer.operations.getFirst().noiseGeneratorUuid.equals(copied.uuid)
            && addedLayer.operations.getFirst().overrides.isEmpty(),
        "Import lost reused type/internal references or sparse inheritance");
    var afterPath = out.resolve("after.pvproj.json");
    document.save(afterPath);
    long savedHistory = document.historyBytes();
    document.undo();
    require(
        before.equals(ProjectIO.JSON.writeValueAsString(document.data())) && document.dirty(),
        "Import was not one complete Undo");
    document.redo();
    require(
        expected.equals(ProjectIO.JSON.writeValueAsString(document.data()))
            && !document.dirty()
            && document.historyBytes() == savedHistory,
        "Redo lost the saved import checkpoint");
    var loaded = new ProjectLoader().load(afterPath);
    require(
        loaded.success() && expected.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
        "Saved import did not reload exactly");
    var compiledAfter = new CompiledProject(loaded.project());
    long fields = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (int state = 0; state < 2; state++) {
        var compiled = state == 0 ? compiledBefore : compiledAfter;
        var existing = compiled.noise2D("field", compiled.defaultSeed());
        var imported = state == 0 ? null : compiled.noise2D(copiedIdentity, compiled.defaultSeed());
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(existing.sample(x, z))
                    == Double.doubleToRawLongBits(x + 15.0),
                "Existing field changed");
            fields++;
            if (imported != null) {
              require(
                  Double.doubleToRawLongBits(imported.sample(x, z))
                      == Double.doubleToRawLongBits(x + 25.0),
                  "Copied identity-named input changed output");
              fields++;
            }
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int value = state == 1 && y >= -2 && y <= 2 && x >= 1 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == value,
                      "Reuse/Copy policy changed exact world semantics");
                  cells++;
                }
          }
      }
    }
    require(fields == 63 && cells == 588, "Unexpected replay dimensions");
    require(
        incomingBefore.equals(ProjectIO.JSON.writeValueAsString(incoming))
            && before.equals(Files.readString(beforePath))
            && expected.equals(Files.readString(afterPath)),
        "Import/runtime changed source or saved bytes");
    Files.writeString(
        out.resolve("import-compatibility.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "fields",
                fields,
                "cells",
                cells,
                "rejectedReuse",
                1,
                "reusedTypes",
                1,
                "copiedNoises",
                1,
                "roots",
                List.of(2, 4))));
    dependencyReplay(out.resolve("dependencies"));
    System.out.println("IMPORT_COMPATIBILITY_CONSUMER_PASS");
  }

  /** Analytical expectations: old leaf/middle/field = 2; copied closure = 5. */
  private static void dependencyReplay(Path out) throws Exception {
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(
        document.edit(p -> ResourceGraph.assign(p, dependencyFixture())).isEmpty(),
        "Dependency destination rejected");
    var beforePath = out.resolve("before.pvproj.json");
    document.save(beforePath);
    String before = Files.readString(beforePath);
    var baseline = ProjectIO.copy(document.data());
    var incoming = ProjectIO.copy(baseline);
    incoming.noise2D.getFirst().algorithm.parameters.put("value", 5.0);
    var layer = incoming.worldGenerator.layers.getFirst();
    layer.uuid = UUID.randomUUID();
    layer.id = "incoming-layer";
    layer.operations.getFirst().uuid = UUID.randomUUID();
    layer.defaults.action = "SET";
    String sourceBefore = ProjectIO.JSON.writeValueAsString(incoming);
    var session = new ResourceImport.Session(document.data(), incoming);
    session.choose(incoming.voxelTypes.getFirst().uuid, ResourceImport.Choice.REUSE);
    var originalPreview = session.preview();
    require(originalPreview.valid(), "Dependency Copy preview rejected");
    var copied =
        incoming.noise2D.stream().map(n -> row(originalPreview, n.uuid).targetUuid()).toList();
    UUID root = incoming.noise2D.getLast().uuid;
    require(!row(originalPreview, root).compatible(), "Transitive mismatch offered Reuse");
    session.choose(root, ResourceImport.Choice.REUSE);
    var active = document.data();
    var token = document.sessionToken();
    long history = document.historyBytes();
    require(!session.preview().valid(), "Transitive Reuse preview accepted");
    try {
      document.edit(session::applyTo);
      throw new AssertionError("Changed dependency published through Reuse");
    } catch (IllegalStateException expected) {
    }
    require(
        document.data() == active
            && document.sessionToken() == token
            && history == document.historyBytes()
            && !document.dirty()
            && before.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Transitive rejection changed document/history");
    session.choose(root, ResourceImport.Choice.COPY);
    require(
        copied.getLast().equals(row(session.preview(), root).targetUuid()),
        "Transitive conflict changed stable Copy identity");
    String expected = ProjectIO.JSON.writeValueAsString(session.preview().candidate());
    require(document.edit(session::applyTo).isEmpty(), "Dependency Copy rejected");
    var after = document.data();
    require(
        expected.equals(ProjectIO.JSON.writeValueAsString(after))
            && after.noise2D.size() == 6
            && after.voxelTypes.size() == 1
            && after.worldGenerator.layers.size() == 2,
        "Dependency Copy differs from preview");
    require(
        after
                .noise2D
                .get(4)
                .processing
                .getFirst()
                .inputs
                .get("a")
                .noiseGeneratorUuid
                .equals(copied.getFirst())
            && after
                .noise2D
                .get(5)
                .processing
                .getFirst()
                .inputs
                .get("a")
                .noiseGeneratorUuid
                .equals(copied.get(1))
            && after
                .worldGenerator
                .layers
                .getLast()
                .operations
                .getFirst()
                .noiseGeneratorUuid
                .equals(copied.getLast())
            && after.worldGenerator.layers.getLast().operations.getFirst().overrides.isEmpty(),
        "Dependency Copy lost UUID closure or sparse inheritance");
    require(
        ProjectIO.JSON
                .valueToTree(baseline.noise2D)
                .equals(ProjectIO.JSON.valueToTree(after.noise2D.subList(0, 3)))
            && ProjectIO.JSON
                .valueToTree(baseline.project)
                .equals(ProjectIO.JSON.valueToTree(after.project))
            && ProjectIO.JSON
                .valueToTree(baseline.worldGenerator.layers.getFirst())
                .equals(ProjectIO.JSON.valueToTree(after.worldGenerator.layers.getFirst())),
        "Dependency import replaced destination definitions/settings");
    var afterPath = out.resolve("after.pvproj.json");
    document.save(afterPath);
    document.undo();
    require(
        before.equals(ProjectIO.JSON.writeValueAsString(document.data())) && document.dirty(),
        "Dependency import did not Undo as one transaction");
    document.redo();
    require(
        expected.equals(ProjectIO.JSON.writeValueAsString(document.data())) && !document.dirty(),
        "Dependency Redo lost the saved checkpoint");
    var loaded = new ProjectLoader().load(afterPath);
    require(
        loaded.success() && expected.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
        "Dependency save/reload changed the document");
    long fields = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (int state = 0; state < 2; state++) {
        var compiled = new CompiledProject(state == 0 ? baseline : loaded.project());
        for (int index = 0; index < (state == 0 ? 3 : 6); index++) {
          var id = index < 3 ? baseline.noise2D.get(index).uuid : copied.get(index - 3);
          var noise = compiled.noise2D(id, compiled.defaultSeed());
          for (int z = -1; z <= 1; z++)
            for (int x = -3; x <= 3; x++) {
              require(
                  Double.doubleToRawLongBits(noise.sample(x, z))
                      == Double.doubleToRawLongBits(index < 3 ? 2.0 : 5.0),
                  "Dependency identity changed exact field output");
              fields++;
            }
        }
        for (int rootSize : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled,
                  new WorldOptions(null, rootSize, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int expectedType = state == 1 && y >= -2 && y <= 2 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expectedType,
                      "Dependency Copy world differs from independent threshold expectation");
                  cells++;
                }
          }
      }
    }
    require(fields == 189 && cells == 588, "Unexpected dependency replay dimensions");
    require(
        sourceBefore.equals(ProjectIO.JSON.writeValueAsString(incoming))
            && before.equals(Files.readString(beforePath))
            && expected.equals(Files.readString(afterPath)),
        "Dependency import changed inputs/bytes");
    Files.writeString(
        out.resolve("dependency-reuse.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "fields",
                fields,
                "cells",
                cells,
                "rejectedReuse",
                1,
                "copiedNoises",
                3,
                "reusedTypes",
                1,
                "roots",
                List.of(2, 4))));
  }

  private static ProjectData dependencyFixture() {
    var project = new ProjectData();
    project.project.seed = 17;
    project.project.minY = -2;
    project.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    project.voxelTypes.add(type);
    UUID previous = null;
    for (String id : List.of("leaf", "middle", "field")) {
      var noise = new Noise();
      noise.id = id;
      noise.algorithm.key = "CONSTANT";
      noise.algorithm.parameters.clear();
      noise.algorithm.parameters.put("value", previous == null ? 2.0 : 0.0);
      if (previous != null) {
        var alias = ProcessingRegistry.create("ADD", "alias", 2);
        var binding = new Binding();
        binding.sourceKind = "Noise Generator Reference";
        binding.noiseGeneratorUuid = previous;
        alias.inputs.put("a", binding);
        alias.inputs.put("b", Binding.constant(0));
        noise.processing.add(alias);
        noise.outputBinding = Binding.earlier(alias.uuid);
      }
      project.noise2D.add(noise);
      previous = noise.uuid;
    }
    var layer = new Layer();
    layer.id = "existing-layer";
    layer.defaults.threshold = 3;
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = previous;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
    return project;
  }

  private static ResourceImport.Row row(ResourceImport.Plan plan, UUID id) {
    return plan.rows().stream().filter(r -> r.uuid().equals(id)).findFirst().orElseThrow();
  }

  private static void fixture(ProjectData p, boolean incoming) {
    p.project.seed = incoming ? 47 : 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 0.0);
    var expression = ProcessingRegistry.create("EXPRESSION", "formula", 2);
    var inputs = List.of("uuid", "id", "parentFolderUuid", "targetUuid", "reference");
    for (int i = 0; i < inputs.size(); i++)
      expression.inputs.put(inputs.get(i), Binding.constant(incoming && i == 0 ? 11 : i + 1));
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("x + " + String.join(" + ", inputs)));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = incoming ? "incoming-layer" : "existing-layer";
    layer.defaults.action = incoming ? "SET" : "NO_CHANGE";
    layer.defaults.threshold = incoming ? 25.25 : 15.25;
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
