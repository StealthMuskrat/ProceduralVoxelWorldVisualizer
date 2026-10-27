package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ChunkScenePickingTest {
  private static final Vector3d FOCUS = new Vector3d(2.5, 2.5, 2.5);
  private static final Vector3d NEAR = new Vector3d(-.5, 2.5, 2.5);
  private static final Vector3d FAR = new Vector3d(-12, 2.5, 2.5);

  private static ChunkScene.Config config() {
    return new ChunkScene.Config(8, 0, 0, 1, 1, 2, 64L << 20, false, 27);
  }

  private static Camera.Ray ray(double x, double y, double z, double dx) {
    return new Camera.Ray(new Vector3d(x, y, z), new Vector3d(dx, 0, 0));
  }

  private static Map<ChunkPos, Long> drawn(ChunkScene.Upload upload) {
    return Map.of(upload.position(), upload.version());
  }

  private static ChunkScene.Upload awaitUpload(
      ChunkScene scene, Vector3d focus, Vector3d eye, long previous) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < end) {
      scene.tick(focus, eye, 8);
      for (var upload : scene.uploads(8, 1 << 20)) if (upload.version() > previous) return upload;
      Thread.sleep(1);
    }
    throw new AssertionError(scene.statistics());
  }

  private static ChunkScene.Source solid() {
    return (p, token) -> SceneChunk.exact(VoxelChunk.uniform(p, 8, 1));
  }

  private static ChunkScene.Source plane() {
    return (p, token) ->
        SceneChunk.sampled(p, 8, token, (x, y, z) -> Math.floorMod((int) x, 8) == 3 ? 1 : -1, 0);
  }

  @Test
  void onlyAcknowledgedAndActuallyDrawnVersionsCanBePicked() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(solid(), workers, config())) {
      var upload = awaitUpload(scene, FOCUS, NEAR, 0);
      var ray = ray(-10, 2.5, 2.5, 1);
      assertTrue(scene.pick(ray, 30, drawn(upload)).isEmpty());
      scene.uploaded(upload);
      assertTrue(scene.pick(ray, 30, Map.of()).isEmpty());
      assertTrue(scene.pick(ray, 30, Map.of(upload.position(), upload.version() + 1)).isEmpty());
      assertEquals(
          new ChunkScene.Pick(new BlockPos(0, 2, 2), false),
          scene.pick(ray, 30, drawn(upload)).orElseThrow());
    }
  }

  @Test
  void loadedHaloAndOccupiedCameraCellsDoNotExtendDrawnCoverage() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(solid(), workers, config())) {
      var focus = new Vector3d(-.5, 2.5, 2.5);
      var upload = awaitUpload(scene, focus, new Vector3d(20, 2.5, 2.5), 0);
      scene.uploaded(upload);
      long generated = scene.statistics().generated();
      var picked = scene.pick(ray(20, 2.5, 2.5, -1), 40, drawn(upload)).orElseThrow();
      assertEquals(new BlockPos(-1, 2, 2), picked.position());
      assertEquals(generated, scene.statistics().generated());
      assertTrue(scene.pick(ray(20, 10, 2.5, -1), 40, drawn(upload)).isEmpty());
    }
  }

  @Test
  void pendingLodReplacementKeepsTheDisplayedCellInputUntilGpuCommit() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(plane(), workers, config())) {
      var fine = awaitUpload(scene, FOCUS, NEAR, 0);
      assertEquals(0, fine.cells().level());
      scene.uploaded(fine);
      var coarse = awaitUpload(scene, FOCUS, FAR, fine.version());
      assertEquals(1, coarse.cells().level());
      var ray = ray(-.5, 2.5, 2.5, 1);
      assertEquals(
          new ChunkScene.Pick(new BlockPos(3, 2, 2), false),
          scene.pick(ray, 10, drawn(fine)).orElseThrow());
      assertTrue(scene.pick(ray, 10, drawn(coarse)).isEmpty());
      scene.uploaded(coarse);
      assertEquals(
          new ChunkScene.Pick(new BlockPos(2, 2, 2), true),
          scene.pick(ray, 10, drawn(coarse)).orElseThrow());
      assertTrue(scene.pick(ray, 10, drawn(fine)).isEmpty());
    }
  }

  @Test
  void retainedCoarseCellsAreAccountedAndReleasedAfterFineCommit() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(plane(), workers, config())) {
      var coarse = awaitUpload(scene, FOCUS, FAR, 0);
      scene.uploaded(coarse);
      var fine = awaitUpload(scene, FOCUS, NEAR, coarse.version());
      assertEquals(0, fine.cells().level());
      long before = scene.statistics().cpuBytes();
      assertTrue(scene.pick(ray(-.5, 2.5, 2.5, 1), 10, drawn(coarse)).orElseThrow().displayProxy());
      scene.uploaded(fine);
      assertEquals(coarse.cells().bytes(), before - scene.statistics().cpuBytes());
      assertFalse(scene.pick(ray(-.5, 2.5, 2.5, 1), 10, drawn(fine)).orElseThrow().displayProxy());
      scene.uploaded(coarse);
      assertTrue(scene.pick(ray(-.5, 2.5, 2.5, 1), 10, drawn(coarse)).isEmpty());
    }
  }

  @Test
  void invalidationForeignEpochAndClosureCannotRestorePickingCells() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2);
        var scene = new ChunkScene(solid(), workers, config())) {
      var upload = awaitUpload(scene, FOCUS, NEAR, 0);
      scene.uploaded(upload);
      scene.invalidateUploads();
      var ray = ray(-.5, 2.5, 2.5, 1);
      assertTrue(scene.pick(ray, 20, drawn(upload)).isEmpty());
      scene.uploaded(
          new ChunkScene.Upload(
              upload.epoch() + 1,
              upload.position(),
              upload.version(),
              upload.mesh(),
              upload.cells()));
      assertTrue(scene.pick(ray, 20, drawn(upload)).isEmpty());
      scene.close();
      scene.uploaded(upload);
      assertTrue(scene.pick(ray, 20, drawn(upload)).isEmpty());
      assertEquals(0, scene.statistics().cpuBytes());
    }
  }

  @Test
  void bothSignedIntegerEdgesMapToTheirCorrectChunkAndLocalCell() throws Exception {
    for (int edge : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      try (var workers = Executors.newFixedThreadPool(2);
          var scene = new ChunkScene(solid(), workers, config())) {
        var focus = new Vector3d(edge + .5, 2.5, 2.5);
        var upload = awaitUpload(scene, focus, focus, 0);
        scene.uploaded(upload);
        assertEquals(
            new BlockPos(edge, 2, 2),
            scene.pick(ray(edge + .5, 2.5, 2.5, 1), 1, drawn(upload)).orElseThrow().position());
      }
    }
  }
}
