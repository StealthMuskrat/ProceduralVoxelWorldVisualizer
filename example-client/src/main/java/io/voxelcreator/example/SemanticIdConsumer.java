package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.VoxelType;
import io.voxelcreator.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR-only project/registry/archive semantic identity replay. */
public final class SemanticIdConsumer {
  private static final List<String> NAMES =
      List.of("Stone", "stone", "é", "石", "🌋", "with space", "empty");
  private static final List<String> INVALID =
      List.of(
          "",
          " ",
          " leading",
          "trailing ",
          "a\nb",
          "a\tb",
          "a\0b",
          "a\u007fb",
          "a\u0085b",
          "\u2003id",
          "id\u2003",
          "e\u0301");

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var project = new ProjectData();
    for (String name : NAMES) {
      var type = new VoxelType();
      type.id = name;
      project.voxelTypes.add(type);
    }
    var projectPath = out.resolve("semantic-ids.pvproj.json");
    new ProjectIO().save(projectPath, project);
    String original = Files.readString(projectPath);
    var loaded = new ProjectLoader().load(projectPath);
    require(loaded.success(), "Canonical project rejected");
    require(
        original.equals(ProjectIO.JSON.writeValueAsString(loaded.project())),
        "Project IDs were rewritten");
    var compiled = new CompiledProject(loaded.project());
    var source = compiled.registry();
    var dictionary = new ArrayList<>(List.of("EMPTY"));
    dictionary.addAll(NAMES);
    require(dictionary.equals(source.semanticIds()), "Compilation changed canonical identities");
    for (int i = 0; i < NAMES.size(); i++) {
      require(source.runtimeId(NAMES.get(i)) == i + 1, "Semantic lookup changed case or text");
      require(
          source.runtimeId(project.voxelTypes.get(i).uuid) == i + 1,
          "Compilation changed identity references");
    }
    loaded.project().voxelTypes.getFirst().id = "changed after compilation";
    require(
        dictionary.equals(source.semanticIds()), "Compiled registry retained mutable definitions");
    var target = VoxelRegistry.semantic("empty", "with space", "🌋", "石", "é", "stone", "Stone");
    var targetBefore = List.copyOf(target.semanticIds());
    var position = new ChunkPos(-2, 1, -1);
    var emptyPosition = new ChunkPos(0, -1, 0);
    var pattern = VoxelChunk.generate(position, 4, (x, y, z) -> (x + 2 * y + 3 * z) % 8);
    long cells = 0;
    for (var format : VoxelArchive.Format.values()) {
      var path = out.resolve("semantic-ids-" + format + ".pvw");
      try (var writer = VoxelArchive.openWorldWriter(path, source, format)) {
        writer.append(pattern);
        writer.append(VoxelChunk.uniform(emptyPosition, 2, 0));
        writer.finish();
      }
      byte[] before = Files.readAllBytes(path);
      try (var reader = VoxelArchive.openWorldReader(path, target)) {
        require(dictionary.equals(reader.dictionary()), "Archive dictionary changed text or order");
        var chunk = reader.read(position).orElseThrow();
        for (int z = 0; z < 4; z++)
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int value = (x + 2 * y + 3 * z) % 8;
              require(
                  chunk.getVoxelTypeId(x, y, z) == (value == 0 ? 0 : 8 - value),
                  "Semantic remapping merged distinct names");
              cells++;
            }
        var empty = reader.read(emptyPosition).orElseThrow();
        require(
            !reader.hasStoredPayload(emptyPosition) && !reader.contains(new ChunkPos(99, 0, 0)),
            "EMPTY and absent coverage merged");
        for (int z = 0; z < 2; z++)
          for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++) {
              require(empty.getVoxelTypeId(x, y, z) == 0, "EMPTY coverage changed");
              cells++;
            }
      }
      var missing = VoxelRegistry.semantic("stone");
      var missingBefore = List.copyOf(missing.semanticIds());
      try (var reader = VoxelArchive.openWorldReader(path, missing)) {
        require(
            reader.read(emptyPosition).orElseThrow().isEmpty(),
            "Missing unrelated names blocked EMPTY");
        try {
          reader.read(position);
          throw new AssertionError("Unresolved case-sensitive names were exposed");
        } catch (IOException expected) {
          require(
              expected.getMessage().contains("Stone"),
              "Missing exact case-sensitive name not reported");
        }
      }
      require(
          missingBefore.equals(missing.semanticIds()) && targetBefore.equals(target.semanticIds()),
          "Archive read mutated a registry");
      require(Arrays.equals(before, Files.readAllBytes(path)), "Archive read changed bytes");
    }
    var invalidRegistry = new ArrayList<String>(INVALID);
    invalidRegistry.add(null);
    invalidRegistry.add("EMPTY");
    int rejectedRegistries = 0;
    for (String id : invalidRegistry) {
      try {
        VoxelRegistry.semantic(id);
        throw new AssertionError("Invalid registry ID accepted");
      } catch (IllegalArgumentException expected) {
        rejectedRegistries++;
      }
    }
    int rejectedArchives = 0;
    for (int version : new int[] {1, 2}) {
      for (int i = 0; i < INVALID.size(); i++) {
        var path = out.resolve("invalid-" + version + "-" + i + ".pvw");
        byte[] bytes = header(version, List.of("EMPTY", INVALID.get(i)));
        Files.write(path, bytes);
        try (var ignored = VoxelArchive.openWorldReader(path, target)) {
          throw new AssertionError("Invalid dictionary ID accepted");
        } catch (IOException expected) {
          require(
              expected.getMessage().contains("Invalid dictionary ID at index 1"),
              "Failure did not identify the dictionary index");
          rejectedArchives++;
        }
        require(Arrays.equals(bytes, Files.readAllBytes(path)), "Rejected archive changed bytes");
      }
      var path = out.resolve("independent-header-" + version + ".pvw");
      Files.write(path, header(version, dictionary));
      try (var reader = VoxelArchive.openWorldReader(path, VoxelRegistry.semantic())) {
        require(
            dictionary.equals(reader.dictionary()) && reader.coverage().isEmpty(),
            "Unused canonical dictionary names required resolution");
      }
    }
    require(
        cells == 144 && rejectedRegistries == 14 && rejectedArchives == 24,
        "Unexpected replay dimensions");
    require(
        original.equals(Files.readString(projectPath))
            && original.equals(ProjectIO.JSON.writeValueAsString(project)),
        "Storage replay modified the authoring project");
    require(
        dictionary.equals(source.semanticIds()) && targetBefore.equals(target.semanticIds()),
        "Failure paths modified registries");
    Files.writeString(
        out.resolve("semantic-ids.json"),
        ProjectIO.JSON.writeValueAsString(
            Map.of(
                "names",
                NAMES,
                "cells",
                cells,
                "rejectedRegistries",
                rejectedRegistries,
                "rejectedArchives",
                rejectedArchives,
                "versions",
                List.of(1, 2))));
    System.out.println("SEMANTIC_ID_CONSUMER_PASS");
  }

  private static byte[] header(int version, List<String> names) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(bytes)) {
      output.writeLong(0x505643574f524c44L);
      output.writeInt(version);
      output.writeInt(1);
      output.writeInt(names.size());
      for (String name : names) output.writeUTF(name);
      long index = bytes.size();
      output.writeInt(0);
      output.writeLong(index);
      output.writeLong(0x505643494e444558L);
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
