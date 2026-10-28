package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.storage.ChunkPos;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class SurfaceSceneLodTest {
  private static RenderPressure reducedBackground() {
    var pressure = new RenderPressure();
    for (int i = 1; i <= 3; i++) pressure.observe(i * 1_000_000_000L, .99, .99, 10, true);
    return pressure;
  }

  private static ChunkScene.Source source(int size, AtomicInteger samples) {
    return new ChunkScene.Source() {
      public boolean surfaces() {
        return true;
      }

      public SceneChunk load(ChunkPos p, CancellationToken t) {
        return SceneChunk.isosurface(
            p,
            size,
            t,
            (x, y, z) -> {
              samples.incrementAndGet();
              return z - 3.257;
            },
            0);
      }
    };
  }

  private static ChunkScene.Config config(int n, long bytes, int quality) {
    return new ChunkScene.Config(n, 0, 0, 1, quality, 1, bytes, false, 27);
  }

  private static ChunkScene.Upload settle(
      ChunkScene scene, Vector3d center, Vector3d eye, int level) throws Exception {
    long end = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < end) {
      scene.tick(center, eye, 8);
      for (var upload : scene.uploads(4, 1 << 20)) {
        assertNull(upload.cells());
        if (upload.level() == level) {
          scene.uploaded(upload);
          return upload;
        }
      }
      Thread.sleep(1);
    }
    fail(scene.statistics().toString());
    return null;
  }

  @Test
  void cameraQualityAndPressureReuseOneCanonicalGridAndOneSourceEpoch() throws Exception {
    var samples = new AtomicInteger();
    var pressure = reducedBackground();
    var center = new Vector3d(4);
    try (var workers = Executors.newFixedThreadPool(1);
        var scene =
            new ChunkScene(source(8, samples), workers, config(8, 32L << 20, 1), pressure)) {
      long epoch = scene.epoch();
      var fine = settle(scene, center, new Vector3d(4), 0);
      var coarse = settle(scene, center, new Vector3d(4, 4, 18.4), 2);
      assertTrue(coarse.version() > fine.version());
      assertTrue(coarse.mesh().byteSize() < fine.mesh().byteSize());
      // Acknowledging an older upload cannot certify the newer target as uploaded.
      scene.uploaded(fine);
      scene.configure(config(8, 32L << 20, 2));
      var quality = settle(scene, center, new Vector3d(4, 4, 18.4), 1);
      assertTrue(quality.version() > coarse.version());
      var returned = settle(scene, center, new Vector3d(4), 0);
      assertEquals(fine.mesh().readOnly(), returned.mesh().readOnly());
      assertEquals(epoch, scene.epoch());
      assertEquals(9 * 9 * 9, samples.get());
      assertEquals(1, scene.statistics().generated());
      assertTrue(scene.statistics().cpuBytes() + scene.statistics().reserved() <= 32L << 20);
    }
  }

  @Test
  void distantScalarLodCanRecoverMeshingAdmissionWithoutRegeneratingExactCorners()
      throws Exception {
    var samples = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(1);
        var scene =
            new ChunkScene(
                source(16, samples), workers, config(16, 8L << 20, 1), reducedBackground())) {
      var center = new Vector3d(8);
      for (int i = 0; i < 100; i++) {
        scene.tick(center, center, 8);
        Thread.sleep(1);
      }
      assertEquals(0, scene.statistics().meshed());
      assertTrue(scene.statistics().status().contains("CPU budget"));
      var coarse = settle(scene, center, new Vector3d(8, 8, 56), 3);
      assertTrue(coarse.mesh().byteSize() > 0);
      assertEquals(17 * 17 * 17, samples.get());
      assertEquals(1, scene.statistics().generated());
      assertTrue(scene.statistics().cpuBytes() + scene.statistics().reserved() <= 8L << 20);
    }
  }
}
