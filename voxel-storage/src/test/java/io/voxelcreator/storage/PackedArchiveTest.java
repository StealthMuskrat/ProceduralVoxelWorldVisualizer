package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectData.VoxelType;
import io.voxelcreator.model.ProjectIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackedArchiveTest {
  @TempDir Path temp;
  private static final ChunkPos POS = new ChunkPos(-3, 2, 4);

  private static JsonNode vectors() throws IOException {
    try (var in = PackedArchiveTest.class.getResourceAsStream("tree-vectors.json")) {
      return ProjectIO.JSON.readTree(Objects.requireNonNull(in));
    }
  }

  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static VoxelChunk decode(byte[] data, int size) throws IOException {
    try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
      var c = VoxelChunk.readTree(POS, size, in, i -> i);
      assertEquals(-1, in.read());
      return c;
    }
  }

  private static byte[] encode(VoxelChunk chunk, boolean packed) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      if (packed) chunk.writePackedTree(out, i -> i);
      else chunk.writeTree(out, i -> i);
    }
    return bytes.toByteArray();
  }

  @Test
  void independentIntegerBitStreamVectorsMatchReaderAndWriter() throws Exception {
    for (var v : vectors().path("leafVectors")) {
      int size = v.path("size").intValue();
      var data = hex(v.path("treeHex").textValue());
      var c = decode(data, size);
      for (int i = 0; i < size * size * size; i++)
        assertEquals(
            v.path("values").get(i).intValue(),
            c.getVoxelTypeId(i % size, i / (size * size), i / size % size));
      assertArrayEquals(data, encode(c, true));
      try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
        var remapped =
            VoxelChunk.readTree(POS, size, in, id -> id == 0 ? 0 : Integer.MAX_VALUE - id);
        for (int i = 0; i < size * size * size; i++) {
          int raw = v.path("values").get(i).intValue();
          assertEquals(
              raw == 0 ? 0 : Integer.MAX_VALUE - raw,
              remapped.getVoxelTypeId(i % size, i / (size * size), i / size % size));
        }
      }
    }
  }

  @Test
  void independentLegacyTreeIsReadAndReemittedExactly() throws Exception {
    byte[] raw = hex(vectors().path("legacyTreeHex").textValue());
    var c = decode(raw, 4);
    assertArrayEquals(raw, encode(c, false));
    assertTrue(encode(c, true).length < raw.length / 4);
    try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
      var empty = VoxelChunk.readTree(POS, 4, in, id -> 0);
      assertTrue(empty.isEmpty());
      assertArrayEquals(new int[] {0}, empty.paletteRuntimeIds());
    }
  }

  @Test
  void independentArchiveVersionsRemapAndKeepExplicitEmptyCoverage() throws Exception {
    var fixture = vectors();
    for (int version : new int[] {1, 2}) {
      Path path = temp.resolve("independent-" + version + ".pvw");
      Files.write(path, hex(fixture.path("archiveV" + version + "Hex").textValue()));
      var registry = VoxelRegistry.semantic("d", "c", "b", "a", "unused");
      try (var reader = VoxelArchive.openWorldReader(path, registry)) {
        assertEquals(version, reader.version());
        assertEquals(List.of("EMPTY", "a", "b", "c", "d"), reader.dictionary());
        assertEquals(Set.of(POS, new ChunkPos(1, 1, 1)), reader.coverage());
        assertTrue(reader.read(new ChunkPos(1, 1, 1)).orElseThrow().isEmpty());
        assertFalse(reader.hasStoredPayload(new ChunkPos(1, 1, 1)));
        assertTrue(reader.read(new ChunkPos(99, 0, 0)).isEmpty());
        var c = reader.read(POS).orElseThrow();
        for (int i = 0; i < 64; i++)
          assertEquals(i % 5 == 0 ? 0 : 5 - i % 5, c.getVoxelTypeId(i % 4, i / 16, i / 4 % 4));
        assertThrows(UnsupportedOperationException.class, () -> reader.coverage().clear());
      }
      assertEquals(6, registry.size());
    }
  }

  @Test
  void defaultV2AndExplicitV1AreDeterministicAndInteroperable() throws Exception {
    var registry = VoxelRegistry.semantic("a", "b", "c", "d");
    var c = VoxelChunk.generate(POS, 16, (x, y, z) -> (x + 2 * y + 3 * z) % 5);
    byte[] previous = null;
    for (var format : VoxelArchive.Format.values()) {
      Path path = temp.resolve(format + ".pvw");
      for (int pass = 0; pass < 2; pass++) {
        try (var w = VoxelArchive.openWorldWriter(path, registry, format)) {
          w.append(c);
          w.append(VoxelChunk.uniform(new ChunkPos(0, 0, 0), 8, 0));
          w.finish();
          w.finish();
        }
        if (pass == 0) previous = Files.readAllBytes(path);
        else assertArrayEquals(previous, Files.readAllBytes(path));
      }
      try (var r = VoxelArchive.openWorldReader(path, registry)) {
        assertEquals(format.version(), r.version());
        var actual = r.read(POS).orElseThrow();
        for (int y = 0; y < 16; y++)
          for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
              assertEquals(c.getVoxelTypeId(x, y, z), actual.getVoxelTypeId(x, y, z));
        r.close();
        assertThrows(IOException.class, () -> r.read(POS));
      }
    }
    Path single = temp.resolve("default.pvw");
    VoxelArchive.writeSingleChunk(single, c, registry);
    try (var r = VoxelArchive.openWorldReader(single, registry)) {
      assertEquals(2, r.version());
    }
  }

  @Test
  void missingIdsAreListedBeforeAnySnapshotEscapesAndRegistryStaysUnchanged() throws Exception {
    Path file = temp.resolve("missing.pvw");
    Files.write(file, hex(vectors().path("archiveV2Hex").textValue()));
    var registry = VoxelRegistry.semantic("a");
    try (var r = VoxelArchive.openWorldReader(file, registry)) {
      var e = assertThrows(IOException.class, () -> r.read(POS));
      assertTrue(e.getMessage().contains("b"));
      assertTrue(e.getMessage().contains("c"));
      assertTrue(e.getMessage().contains("d"));
      assertEquals(List.of("EMPTY", "a"), registry.semanticIds());
      assertTrue(r.read(new ChunkPos(1, 1, 1)).orElseThrow().isEmpty());
    }
  }

  @Test
  void invalidPackedHeadersIndicesUnusedEntriesAndPaddingReject() throws Exception {
    var fixture = vectors();
    byte[] two = hex(fixture.path("leafVectors").get(0).path("treeHex").textValue());
    // Three-entry 2^3 leaf: 2-bit indices occupy the low 16 bits of its final word.
    var cases = new ArrayList<byte[]>();
    byte[] bad = two.clone();
    bad[1] = 1;
    cases.add(bad);
    bad = two.clone();
    bad[1] = 9;
    cases.add(bad);
    bad = two.clone();
    bad[9] = 0;
    cases.add(bad); // duplicate palette value
    bad = two.clone();
    bad[bad.length - 1] |= 3;
    cases.add(bad); // index 3 outside count 3
    bad = two.clone();
    Arrays.fill(bad, bad.length - 4, bad.length, (byte) 0);
    cases.add(bad); // unused entries
    bad = two.clone();
    bad[bad.length - 4] = 1;
    cases.add(bad); // nonzero high padding
    bad = two.clone();
    bad[2] = (byte) 0x80;
    cases.add(bad); // negative dictionary ID
    for (byte[] bytes : cases) assertThrows(IOException.class, () -> decode(bytes, 2));
    for (int length = 0; length < two.length; length++) {
      byte[] truncated = Arrays.copyOf(two, length);
      assertThrows(IOException.class, () -> decode(truncated, 2));
    }
    assertThrows(IOException.class, () -> decode(two, 8));
    assertThrows(IOException.class, () -> decode(new byte[] {2}, 2));
    assertThrows(IOException.class, () -> decode(new byte[] {99}, 4));
    assertThrows(IOException.class, () -> decode(new byte[] {1, -1, -1, -1, -1}, 4));
    assertThrows(
        IOException.class,
        () ->
            VoxelChunk.readTree(
                POS, 2, new DataInputStream(new ByteArrayInputStream(two)), i -> -1));
  }

  @Test
  void compressedJunkTruncationChecksumAndLegacyTagMismatchReject() throws Exception {
    byte[] tree = hex(vectors().path("leafVectors").get(3).path("treeHex").textValue());
    var compressed = new ByteArrayOutputStream();
    try (var zip = new DeflaterOutputStream(compressed)) {
      zip.write(tree);
    }
    byte[] good = compressed.toByteArray();
    var payloads = new ArrayList<byte[]>();
    payloads.add(Arrays.copyOf(good, good.length - 2));
    byte[] checksum = good.clone();
    checksum[checksum.length - 1] ^= 1;
    payloads.add(checksum);
    payloads.add(Arrays.copyOf(good, good.length + 2048));
    for (int i = 0; i < payloads.size(); i++) {
      Path file = temp.resolve("broken-" + i + ".pvw");
      archive(file, 2, payloads.get(i));
      try (var r = VoxelArchive.openWorldReader(file, VoxelRegistry.semantic("a", "b", "c", "d"))) {
        assertThrows(IOException.class, () -> r.read(POS));
      }
    }
    Path legacy = temp.resolve("illegal-tag.pvw");
    archive(legacy, 1, good);
    try (var r = VoxelArchive.openWorldReader(legacy, VoxelRegistry.semantic("a", "b", "c", "d"))) {
      assertThrows(IOException.class, () -> r.read(POS));
    }
  }

  private static void archive(Path file, int version, byte[] payload) throws IOException {
    try (var out = new DataOutputStream(Files.newOutputStream(file))) {
      out.writeLong(0x505643574f524c44L);
      out.writeInt(version);
      out.writeInt(1);
      out.writeInt(5);
      for (String id : List.of("EMPTY", "a", "b", "c", "d")) out.writeUTF(id);
      int offset = out.size();
      out.write(payload);
      int index = out.size();
      out.writeInt(1);
      out.writeInt(POS.x());
      out.writeInt(POS.y());
      out.writeInt(POS.z());
      out.writeInt(4);
      out.writeLong(offset);
      out.writeInt(payload.length);
      out.writeLong(index);
      out.writeLong(0x505643494e444558L);
    }
  }

  @Test
  void failedAppendAndConstructorDoNotReplaceDestinationOrLeakTemporaryFiles() throws Exception {
    var registry = VoxelRegistry.semantic("a");
    Path file = temp.resolve("safe.pvw");
    var c = VoxelChunk.uniform(POS, 4, 1);
    VoxelArchive.writeSingleChunk(file, c, registry);
    byte[] before = Files.readAllBytes(file);
    try (var writer = VoxelArchive.openWorldWriter(file, registry)) {
      assertThrows(
          IllegalArgumentException.class, () -> writer.append(VoxelChunk.uniform(POS, 4, 2)));
      writer.append(c);
      assertThrows(IOException.class, () -> writer.append(c));
      writer.finish();
    }
    try (var writer = VoxelArchive.openWorldWriter(file, registry)) {
      writer.append(c);
    }
    assertArrayEquals(before, Files.readAllBytes(file));
    assertThrows(
        UTFDataFormatException.class,
        () -> VoxelArchive.openWorldWriter(file, VoxelRegistry.semantic("a".repeat(65536))));
    try (var children = Files.list(temp)) {
      assertEquals(List.of(file), children.toList());
    }
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void malformedHeadersDictionaryAndIndexRangesRejectBeforeReadingChunks() throws Exception {
    byte[] valid = hex(vectors().path("archiveV2Hex").textValue());
    var cases = new ArrayList<byte[]>();
    for (int offset : new int[] {0, 8, 12, 16, valid.length - 1}) {
      byte[] copy = valid.clone();
      copy[offset] ^= 0x40;
      cases.add(copy);
    }
    byte[] duplicate = valid.clone();
    duplicate[32] = 'a';
    cases.add(duplicate);
    // The first nonempty index entry's offset moves one byte into its payload.
    var input = new DataInputStream(new ByteArrayInputStream(valid, valid.length - 16, 8));
    int index = (int) input.readLong();
    byte[] shifted = valid.clone();
    shifted[index + 4 + 23]++;
    cases.add(shifted);
    for (int i = 0; i < cases.size(); i++) {
      Path file = temp.resolve("header-" + i + ".pvw");
      Files.write(file, cases.get(i));
      assertThrows(
          IOException.class,
          () -> VoxelArchive.openWorldReader(file, VoxelRegistry.semantic("a", "b", "c", "d")));
    }
  }

  @Test
  void registryRejectsNoncanonicalIdsAndDuplicateUuids() {
    assertThrows(IllegalArgumentException.class, () -> VoxelRegistry.semantic("e\u0301"));
    assertThrows(IllegalArgumentException.class, () -> VoxelRegistry.semantic(""));
    assertThrows(IllegalArgumentException.class, () -> VoxelRegistry.semantic("EMPTY"));
    var a = new VoxelType();
    a.id = "a";
    var b = new VoxelType();
    b.id = "b";
    b.uuid = a.uuid;
    assertThrows(IllegalArgumentException.class, () -> new VoxelRegistry(List.of(a, b)));
    assertEquals(1, VoxelRegistry.semantic("é").runtimeId("é"));
  }
}
