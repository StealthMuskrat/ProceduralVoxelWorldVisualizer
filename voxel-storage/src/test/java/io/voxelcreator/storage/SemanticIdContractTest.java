package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.VoxelType;
import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred project/storage identity agreement and independently encoded dictionary headers. */
class SemanticIdContractTest {
  @TempDir Path directory;

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

  @Test
  void standaloneRegistryRejectsInvalidNamesWithoutRepairingSourceDefinitions() {
    var invalid = new ArrayList<String>(INVALID);
    invalid.add(null);
    invalid.add("EMPTY");
    for (String id : invalid) {
      var type = new VoxelType();
      type.id = id;
      var before = ProjectIO.JSON.valueToTree(type);
      var failure =
          assertThrows(IllegalArgumentException.class, () -> new VoxelRegistry(List.of(type)));
      assertFalse(failure.getMessage().isBlank());
      assertEquals(before, ProjectIO.JSON.valueToTree(type));
    }
  }

  @Test
  void validRegistryRetainsExactTextCaseAndUuidLookupAsAnOwnedSnapshot() {
    var names = List.of("Stone", "stone", "empty", "é", "石", "🌋", "with space");
    var types = new ArrayList<VoxelType>();
    for (String name : names) {
      var type = new VoxelType();
      type.id = name;
      types.add(type);
    }
    var registry = new VoxelRegistry(types);
    for (int i = 0; i < names.size(); i++) {
      assertEquals(i + 1, registry.runtimeId(names.get(i)));
      assertEquals(i + 1, registry.runtimeId(types.get(i).uuid));
      assertEquals(names.get(i), registry.semanticId(i + 1));
    }
    assertEquals(0, registry.runtimeId("EMPTY"));
    assertThrows(IllegalArgumentException.class, () -> registry.runtimeId("STONE"));
    types.getFirst().id = "changed";
    types.clear();
    var expected = new ArrayList<>(List.of("EMPTY"));
    expected.addAll(names);
    assertEquals(expected, registry.semanticIds());
  }

  @Test
  void bothArchiveVersionsRejectInvalidDictionaryTextBeforeReturningAReader() throws Exception {
    var registry = VoxelRegistry.semantic("stone");
    var original = List.copyOf(registry.semanticIds());
    int sequence = 0;
    for (int version : new int[] {1, 2})
      for (String id : INVALID)
        for (int at : new int[] {0, 1}) {
          var path = directory.resolve("invalid-" + sequence++ + ".pvw");
          byte[] bytes = archive(version, at == 0 ? List.of(id, "stone") : List.of("EMPTY", id));
          Files.write(path, bytes);
          var failure =
              assertThrows(IOException.class, () -> VoxelArchive.openWorldReader(path, registry));
          assertTrue(failure.getMessage().contains("Invalid dictionary ID at index " + at));
          assertArrayEquals(bytes, Files.readAllBytes(path));
          assertEquals(original, registry.semanticIds());
          Files.delete(path);
        }
  }

  @Test
  void duplicateAndMisplacedEmptyDictionaryNamesRemainRejected() throws Exception {
    int sequence = 0;
    for (int version : new int[] {1, 2})
      for (var names :
          List.of(
              List.of("EMPTY", "stone", "stone"),
              List.of("EMPTY", "EMPTY"),
              List.of("stone", "EMPTY"))) {
        var path = directory.resolve("reserved-" + sequence++ + ".pvw");
        Files.write(path, archive(version, names));
        assertThrows(
            IOException.class,
            () -> VoxelArchive.openWorldReader(path, VoxelRegistry.semantic("stone")));
      }
  }

  @Test
  void unicodeAndCaseSensitiveTypesRoundTripAndRemapInBothFormats() throws Exception {
    var source = VoxelRegistry.semantic("Stone", "stone", "é", "石", "🌋", "with space", "empty");
    var target = VoxelRegistry.semantic("empty", "with space", "🌋", "石", "é", "stone", "Stone");
    var position = new ChunkPos(-2, 1, -1);
    var emptyPosition = new ChunkPos(0, -1, 0);
    var chunk = VoxelChunk.generate(position, 4, (x, y, z) -> (x + 2 * y + 3 * z) % 8);
    for (var format : VoxelArchive.Format.values()) {
      var path = directory.resolve("valid-" + format + ".pvw");
      try (var writer = VoxelArchive.openWorldWriter(path, source, format)) {
        writer.append(chunk);
        writer.append(VoxelChunk.uniform(emptyPosition, 2, 0));
        writer.finish();
      }
      byte[] before = Files.readAllBytes(path);
      try (var reader = VoxelArchive.openWorldReader(path, target)) {
        assertEquals(source.semanticIds(), reader.dictionary());
        var loaded = reader.read(position).orElseThrow();
        for (int z = 0; z < 4; z++)
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int value = (x + 2 * y + 3 * z) % 8;
              assertEquals(value == 0 ? 0 : 8 - value, loaded.getVoxelTypeId(x, y, z));
            }
        assertTrue(reader.read(emptyPosition).orElseThrow().isEmpty());
        assertFalse(reader.hasStoredPayload(emptyPosition));
        assertFalse(reader.contains(new ChunkPos(99, 0, 0)));
      }
      var missing = VoxelRegistry.semantic("stone");
      var missingBefore = List.copyOf(missing.semanticIds());
      try (var reader = VoxelArchive.openWorldReader(path, missing)) {
        assertTrue(reader.read(emptyPosition).orElseThrow().isEmpty());
        var failure = assertThrows(IOException.class, () -> reader.read(position));
        assertTrue(failure.getMessage().contains("Stone"));
        assertEquals(missingBefore, missing.semanticIds());
      }
      assertArrayEquals(before, Files.readAllBytes(path));
    }
  }

  private static byte[] archive(int version, List<String> names) throws IOException {
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
}
