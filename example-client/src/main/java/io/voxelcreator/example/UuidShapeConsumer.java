package io.voxelcreator.example;

import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.api.*;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.BlockPos;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Deferred engine-JAR-only replay of expression map names and canonical UUID reference arrays. */
public final class UuidShapeConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var document = new DocumentSession();
    require(document.edit(UuidShapeConsumer::fixture).isEmpty(), "Fixture rejected");
    var path = out.resolve("uuid-shapes.pvproj.json");
    document.save(path);
    String saved = Files.readString(path);
    var load = new ProjectLoader().load(path);
    require(load.success(), "Legal identity-like input names rejected: " + load.diagnostics());
    require(
        saved.equals(ProjectIO.JSON.writeValueAsString(load.project())),
        "Reload changed bindings or identity references");
    require(
        load.project()
            .noise2D
            .getFirst()
            .processing
            .getFirst()
            .inputs
            .keySet()
            .equals(Set.of("uuid", "reference", "targetUuid")),
        "Input map keys changed");
    var external = load.project().noise2D.getLast().uuid;
    require(
        ResourceGraph.references(load.project().noise2D.getFirst()).contains(external),
        "Identity-named binding disappeared from dependencies");
    require(
        ResourceGraph.selection(
                    load.project(), List.of(load.project().noise2D.getFirst().uuid), true)
                .noise2D
                .size()
            == 2,
        "Dependency-aware selection omitted the external field");
    require(document.edit(p -> p.project.seed = 29).isEmpty(), "Current edit rejected");
    var active = document.data();
    var token = document.sessionToken();
    long history = document.historyBytes();
    String current = ProjectIO.JSON.writeValueAsString(active);
    var compiledCurrent = new CompiledProject(active);
    var compiledLoaded = new CompiledProject(load.project());
    int rejected = 0;
    for (String pointer :
        List.of(
            "/voxelGroups/0/voxelTypeUuids/0",
            "/voxelSets/0/voxelTypeUuids/0",
            "/folders/0/childOrder/0",
            "/project/rootOrder/noise2D/0")) {
      ObjectNode baseline = ProjectIO.JSON.valueToTree(active);
      var id = UUID.fromString(baseline.at(pointer).textValue());
      String base64 =
          Base64.getEncoder()
              .encodeToString(
                  ByteBuffer.allocate(16)
                      .putLong(id.getMostSignificantBits())
                      .putLong(id.getLeastSignificantBits())
                      .array());
      String shortened =
          Arrays.stream(id.toString().split("-"))
              .map(part -> new BigInteger(part, 16).toString(16))
              .collect(Collectors.joining("-"));
      for (String invalid : List.of(base64, shortened)) {
        require(!invalid.equals(id.toString()), "Fixture did not shorten its UUID");
        ObjectNode tree = baseline.deepCopy();
        ((ArrayNode) tree.at(pointer.substring(0, pointer.lastIndexOf('/'))))
            .set(0, TextNode.valueOf(invalid));
        var candidate = out.resolve("rejected-" + rejected + ".json");
        String text = ProjectIO.JSON.writeValueAsString(tree);
        Files.writeString(candidate, text);
        var failure = new ProjectLoader().load(candidate);
        require(
            !failure.success() && failure.project() == null,
            "Malformed reference exposed a project");
        check(failure.diagnostics(), pointer);
        check(document.open(candidate), pointer);
        require(
            document.data() == active
                && document.sessionToken() == token
                && document.path().equals(path)
                && document.historyBytes() == history
                && document.dirty()
                && current.equals(ProjectIO.JSON.writeValueAsString(document.data())),
            "Rejected UUID array changed the current session");
        require(
            saved.equals(Files.readString(path)) && text.equals(Files.readString(candidate)),
            "Read changed file bytes");
        rejected++;
      }
    }
    document.undo();
    require(
        !document.dirty() && saved.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Rejected open lost saved checkpoint or undo");
    document.redo();
    require(
        document.dirty() && current.equals(ProjectIO.JSON.writeValueAsString(document.data())),
        "Rejected open lost complete redo");
    require(
        document.open(path).isEmpty()
            && !document.dirty()
            && document.sessionToken() != token
            && document.historyBytes() == 0,
        "Valid retry did not replace the session");
    long fields = 0, cells = 0;
    try (var engine = new NoiseGeneratorEngine(2, 32, 16L << 20)) {
      for (var compiled : List.of(compiledCurrent, compiledLoaded)) {
        var field = compiled.noise2D("field", compiled.defaultSeed());
        for (int z = -1; z <= 1; z++)
          for (int x = -3; x <= 3; x++) {
            require(
                Double.doubleToRawLongBits(field.sample(x, z))
                    == Double.doubleToRawLongBits(x + 6.0),
                "Expression input names changed exact evaluation");
            fields++;
          }
        for (int root : new int[] {2, 4})
          try (var world =
              engine.openWorld(
                  compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0))) {
            for (int z = -1; z <= 1; z++)
              for (int y = -3; y <= 3; y++)
                for (int x = -3; x <= 3; x++) {
                  int expected = y >= -2 && y <= 2 && x >= 1 ? 1 : 0;
                  require(
                      world.getVoxelTypeId(new BlockPos(x, y, z)) == expected,
                      "Typed UUID validation changed exact world output");
                  cells++;
                }
          }
      }
    }
    require(rejected == 8 && fields == 42 && cells == 588, "Unexpected replay dimensions");
    require(
        saved.equals(ProjectIO.JSON.writeValueAsString(document.data()))
            && saved.equals(Files.readString(path)),
        "Runtime changed authoring state");
    Files.writeString(
        out.resolve("uuid-shapes.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "rejected",
                rejected,
                "referenceArrays",
                4,
                "fields",
                fields,
                "cells",
                cells,
                "roots",
                List.of(2, 4))));
    System.out.println("UUID_SHAPE_CONSUMER_PASS");
  }

  private static void check(List<Diagnostic> issues, String pointer) {
    require(
        issues.size() == 1
            && issues.getFirst().code().equals("INVALID_STRUCTURE")
            && issues.getFirst().jsonPointer().equals(pointer),
        "Unexpected rejection: " + issues);
  }

  private static void fixture(ProjectData p) {
    p.project.seed = 17;
    p.project.minY = -2;
    p.project.maxY = 2;
    var type = new VoxelType();
    type.id = "stone";
    type.uuid = UUID.fromString("00000001-0001-0001-0001-000000000001");
    p.voxelTypes.add(type);
    for (var list : List.of(p.voxelGroups, p.voxelSets)) {
      var membership = new Membership();
      membership.id = "members";
      membership.voxelTypeUuids.add(type.uuid);
      list.add(membership);
    }
    var folder = new Folder();
    folder.id = "folder";
    folder.uuid = UUID.fromString("00000003-0003-0003-0003-000000000003");
    p.folders.add(folder);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(folder.uuid)));
    var noise = new Noise();
    noise.id = "field";
    noise.uuid = UUID.fromString("00000002-0002-0002-0002-000000000002");
    noise.parentFolderUuid = folder.uuid;
    folder.childOrder.add(noise.uuid);
    var expression = ProcessingRegistry.create("EXPRESSION", "formula", 2);
    var external = new Noise();
    external.id = "one";
    external.uuid = UUID.fromString("00000004-0004-0004-0004-000000000004");
    external.algorithm.key = "CONSTANT";
    external.algorithm.parameters.clear();
    external.algorithm.parameters.put("value", 1.0);
    var referenced = new Binding();
    referenced.sourceKind = "Noise Generator Reference";
    referenced.noiseGeneratorUuid = external.uuid;
    expression.inputs.put("uuid", referenced);
    expression.inputs.put("reference", Binding.constant(2));
    expression.inputs.put("targetUuid", Binding.constant(3));
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("x + uuid + reference + targetUuid"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    p.noise2D.add(noise);
    p.noise2D.add(external);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    layer.defaults.threshold = 6.25;
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
