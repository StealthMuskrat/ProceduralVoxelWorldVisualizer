package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

public class StorageContractTest {
  @TempDir Path temp;

  @Test
  void floorCoordinatesAndInclusiveClip() {
    assertEquals(new ChunkPos(-1, -1, -1), ChunkPos.fromBlock(new BlockPos(-1, -1, -1), 64));
    assertEquals(63, Math.floorMod(-1, 64));
    var r = new RegionBounds(new BlockPos(-1, -10, -1), new BlockPos(1, 10, 1));
    assertEquals(-2, r.clipY(-2, 3).orElseThrow().min().y());
    assertEquals(8, r.chunkCount(64));
    assertTrue(r.clipY(11, 20).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> VoxelChunk.validateSize(3));
  }

  @Test
  void adaptiveStorageAgainstDenseOracle() {
    var rng = new Random(412);
    for (int size : new int[] {2, 4, 8, 16, 32}) {
      int[] oracle = new int[size * size * size];
      for (int i = 0; i < oracle.length; i++) oracle[i] = rng.nextInt(4);
      var c =
          VoxelChunk.generate(
              new ChunkPos(-1, 0, 1), size, (x, y, z) -> oracle[(y * size + z) * size + x]);
      for (int y = 0; y < size; y++)
        for (int z = 0; z < size; z++)
          for (int x = 0; x < size; x++)
            assertEquals(oracle[(y * size + z) * size + x], c.getVoxelTypeId(x, y, z));
      long[] occupied = {0};
      c.forEachOccupied(
          (x, y, z, t) -> {
            assertEquals(oracle[(y * size + z) * size + x], t);
            occupied[0]++;
          });
      assertEquals(Arrays.stream(oracle).filter(v -> v > 0).count(), occupied[0]);
    }
  }

  @Test
  void allRootsCoalesceAndSparse256UsesNoDenseArray() {
    for (int size : new int[] {2, 4, 8, 16, 32, 64, 128, 256}) {
      var u = VoxelChunk.uniform(new ChunkPos(0, 0, 0), size, 2);
      assertEquals(2, u.getVoxelTypeId(size - 1, size - 1, size - 1));
      assertEquals("UNIFORM", u.representation());
      assertTrue(u.estimatedBytes() < 100);
    }
    var sparse =
        VoxelChunk.generate(
            new ChunkPos(0, 0, 0), 256, (x, y, z) -> x == 255 && y == 255 && z == 255 ? 1 : 0);
    assertEquals(1, sparse.getVoxelTypeId(255, 255, 255));
    assertEquals(0, sparse.getVoxelTypeId(254, 255, 255));
    assertTrue(sparse.estimatedBytes() < 10000);
    assertEquals("BRANCH", sparse.representation());
  }

  @Test
  void archiveSemanticRemapCoverageAndRandomAccess() throws Exception {
    var reg = VoxelRegistry.semantic("stone", "dirt");
    var pos = new ChunkPos(-3, 2, 4);
    var chunk = VoxelChunk.generate(pos, 16, (x, y, z) -> (x + y + z) % 3);
    Path archive = temp.resolve("world.pvw");
    try (var w = VoxelArchive.openWorldWriter(archive, reg)) {
      w.append(chunk);
      w.append(VoxelChunk.uniform(new ChunkPos(1, 1, 1), 16, 0));
      w.finish();
    }
    try (var r = VoxelArchive.openWorldReader(archive, VoxelRegistry.semantic("dirt", "stone"))) {
      assertTrue(r.contains(pos));
      assertFalse(r.contains(new ChunkPos(5, 5, 5)));
      assertFalse(r.hasStoredPayload(new ChunkPos(1, 1, 1)));
      assertTrue(r.read(new ChunkPos(1, 1, 1)).orElseThrow().isEmpty());
      var loaded = r.read(pos).orElseThrow();
      for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
          for (int x = 0; x < 16; x++) {
            int id = chunk.getVoxelTypeId(x, y, z);
            assertEquals(id == 0 ? 0 : id == 1 ? 2 : 1, loaded.getVoxelTypeId(x, y, z));
          }
    }
    try (var r = VoxelArchive.openWorldReader(archive, VoxelRegistry.semantic("stone"))) {
      assertThrows(IOException.class, () -> r.read(pos));
    }
  }

  @Test
  void rejectsCorruptIndexAndPreservesPriorArchiveWhenWriterAborted() throws Exception {
    Path f = temp.resolve("one.pvw");
    var reg = VoxelRegistry.semantic("s");
    var c = VoxelChunk.uniform(new ChunkPos(0, 0, 0), 8, 1);
    VoxelArchive.writeSingleChunk(f, c, reg);
    byte[] old = Files.readAllBytes(f);
    try (var w = VoxelArchive.openWorldWriter(f, reg)) {
      w.append(c);
    }
    assertArrayEquals(old, Files.readAllBytes(f));
    try (var io = new RandomAccessFile(f.toFile(), "rw")) {
      io.seek(io.length() - 16);
      io.writeLong(Long.MAX_VALUE);
    }
    assertThrows(IOException.class, () -> VoxelArchive.openWorldReader(f, reg));
  }
}
